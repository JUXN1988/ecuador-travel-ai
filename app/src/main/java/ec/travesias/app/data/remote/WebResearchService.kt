package ec.travesias.app.data.remote

import ec.travesias.app.data.model.PlaceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Herramienta de investigación en internet, potente y con fuentes.
 *
 * Es la "búsqueda de verdad" de la app: usa Gemini con **google_search** (busca en la web en
 * vivo y devuelve de dónde sacó cada cosa) y **url_context** (lee páginas concretas). No es un
 * modelo respondiendo de memoria: cada respuesta viene respaldada por enlaces reales.
 *
 * Tres modos, de menos a más profundo:
 *  - [buscar]      → una pasada. Rápido, para responder algo puntual con fuentes.
 *  - [investigar]  → varias pasadas en paralelo (una por subpregunta) y una síntesis final.
 *                    Es la que hay que usar cuando la respuesta importa.
 *  - [ficha]       → devuelve un JSON con la ficha completa de un lugar (coordenadas, costo,
 *                    horario, cómo llegar, contactos), verificando en internet.
 *
 * Todo se apoya en el mismo GeminiClient que ya usan el Radar y la Guía IA.
 */
class WebResearchService(private val gemini: GeminiClient) {

    val isAvailable: Boolean get() = gemini.isAvailable

    data class Hallazgo(
        val texto: String,
        val fuentes: List<PlaceSource> = emptyList(),
        val consultas: List<String> = emptyList(),
        /** Vacío si todo fue bien; si no, explica en cristiano qué pasó. */
        val problema: String = ""
    ) {
        val ok: Boolean get() = problema.isBlank() && texto.isNotBlank()

        /** Las fuentes en una línea, para meterlas en el prompt de otro paso. */
        fun fuentesEnTexto(max: Int = 6): String =
            fuentes.take(max).joinToString("\n") { "  - " + it.title.ifBlank { it.platform.displayName } + ": " + it.url }
    }

    companion object {
        private const val HOY_ES = "Hoy es el día en que se hace la consulta; prioriza información de los últimos 12 meses."

        private val SISTEMA = """
            Eres el investigador de la app "Travesías en Ecuador". Tu trabajo es averiguar cosas en internet
            y responder SOLO con lo que puedas respaldar con fuentes reales que hayas consultado.

            Reglas de oficio:
            - Busca en la web SIEMPRE antes de responder. No respondas de memoria.
            - Prioriza fuentes ecuatorianas y recientes: ministerios y GADs, medios locales, páginas oficiales
              del lugar, TripAdvisor, Google Maps, y publicaciones de TikTok/Instagram/Facebook/YouTube.
            - Los precios van en dólares estadounidenses (Ecuador usa USD) y con la fecha de la fuente.
            - Los horarios, costos de entrada y estados (abierto/cerrado/en mantenimiento) cambian: si la fuente
              es vieja o no la encuentras, DILO en vez de inventarla.
            - Distancias y tiempos, en carretera ecuatoriana real, no en línea recta.
            - Si algo no lo encuentras, escribe "no verificado" en ese punto. Nunca rellenes con suposiciones.
            - Escribe en español ecuatoriano, claro y directo.
        """.trimIndent()
    }

    /**
     * Una pasada de búsqueda con fuentes. [urls] son páginas concretas que quieres que lea
     * (el enlace de un TikTok, la web del lugar…).
     */
    suspend fun buscar(
        pregunta: String,
        contexto: String = "",
        urls: List<String> = emptyList(),
        maxPalabras: Int = 300
    ): Hallazgo = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext Hallazgo("", problema = "Falta la clave de Gemini: la búsqueda en internet está desactivada.")

        val prompt = buildString {
            appendLine(HOY_ES)
            if (contexto.isNotBlank()) { appendLine("CONTEXTO:"); appendLine(contexto.take(4000)); appendLine() }
            if (urls.isNotEmpty()) {
                appendLine("LEE ESTAS PÁGINAS antes de responder:")
                urls.take(6).forEach { appendLine("  " + it) }
                appendLine()
            }
            appendLine("PREGUNTA: " + pregunta)
            appendLine()
            appendLine("Busca en internet y responde en máximo " + maxPalabras + " palabras. Sé concreto: nombres,")
            appendLine("cifras, horarios y direcciones. Marca como \"no verificado\" lo que no hayas podido confirmar.")
        }

        val r = runCatching {
            gemini.generate(
                prompt = prompt,
                systemInstruction = SISTEMA,
                useSearch = true,
                useUrlContext = urls.isNotEmpty(),
                temperature = 0.25,
                maxOutputTokens = 24576
            )
        }.getOrElse { e ->
            return@withContext Hallazgo("", problema = mensajeDeError(e))
        }

        if (r.text.isBlank()) {
            // Segundo intento sin herramientas: con grounding el modelo a veces gasta todo el
            // presupuesto "pensando" y devuelve vacío (pasa en gemini-2.5-flash).
            val plan_b = runCatching {
                gemini.generate(
                    prompt = prompt + "\n\nResponde directamente con lo que sepas, sin buscar.",
                    systemInstruction = SISTEMA,
                    temperature = 0.3,
                    maxOutputTokens = 8192
                )
            }.getOrNull()
            if (plan_b != null && plan_b.text.isNotBlank()) {
                return@withContext Hallazgo(plan_b.text, r.sources, r.searchQueries,
                    problema = "")
            }
            return@withContext Hallazgo("", r.sources, r.searchQueries, problema = when {
                r.blocked -> "Los filtros de seguridad bloquearon esa consulta."
                r.truncated -> "La respuesta se cortó por longitud."
                else -> "La búsqueda no devolvió nada. Revisa la conexión."
            })
        }
        Hallazgo(r.text.trim(), r.sources, r.searchQueries)
    }

    /**
     * Investigación profunda: descompone la pregunta en subpreguntas, las busca EN PARALELO
     * (cada una con su propia ronda de google_search) y luego junta todo en una respuesta con
     * fuentes. Es más lento y gasta más, pero es lo que hay que usar cuando la respuesta importa.
     */
    suspend fun investigar(
        pregunta: String,
        contexto: String = "",
        urls: List<String> = emptyList(),
        maxSubpreguntas: Int = 4
    ): Hallazgo = coroutineScope {
        if (!isAvailable) return@coroutineScope Hallazgo("", problema = "Falta la clave de Gemini: la búsqueda en internet está desactivada.")

        // 1) Plan: en qué se divide la pregunta.
        val subpreguntas = planificar(pregunta, contexto, maxSubpreguntas)

        // 2) Cada subpregunta, buscada por su cuenta y a la vez.
        val parciales = subpreguntas.map { sub ->
            async { sub to buscar(sub, contexto, if (sub == subpreguntas.first()) urls else emptyList(), maxPalabras = 200) }
        }.map { it.await() }

        val utiles = parciales.filter { it.second.ok }
        if (utiles.isEmpty()) {
            val primerProblema = parciales.firstOrNull()?.second?.problema.orEmpty()
            return@coroutineScope Hallazgo("", problema = primerProblema.ifBlank { "No se pudo investigar en internet." })
        }

        val todasLasFuentes = utiles.flatMap { it.second.fuentes }.distinctBy { it.url }
        val todasLasConsultas = utiles.flatMap { it.second.consultas }.distinct()

        // 3) Síntesis: una sola respuesta coherente a partir de lo hallado.
        val material = utiles.joinToString("\n\n") { (sub, h) ->
            "### " + sub + "\n" + h.texto + (if (h.fuentes.isEmpty()) "" else "\nFuentes:\n" + h.fuentesEnTexto(4))
        }
        val sintesis = runCatching {
            gemini.generate(
                prompt = """
                    PREGUNTA ORIGINAL: $pregunta
                    ${if (contexto.isNotBlank()) "CONTEXTO DEL VIAJERO:\n" + contexto.take(2000) else ""}

                    Esto es lo que se encontró investigando en internet, por partes:

                    $material

                    Redacta UNA respuesta unificada para el viajero. Reglas:
                    - Junta lo que se repite, y si dos fuentes se contradicen dilo y quédate con la más reciente u oficial.
                    - No añadas nada que no esté arriba.
                    - Deja claro qué está verificado y qué conviene reconfirmar antes de ir.
                    - Español ecuatoriano, concreto, máximo 400 palabras.
                """.trimIndent(),
                systemInstruction = SISTEMA,
                temperature = 0.3,
                maxOutputTokens = 12288
            )
        }.getOrNull()

        val texto = sintesis?.text?.takeIf { it.isNotBlank() }
            ?: utiles.joinToString("\n\n") { it.second.texto }

        Hallazgo(texto.trim(), todasLasFuentes, todasLasConsultas)
    }

    /**
     * Ficha completa y verificada de un lugar, en JSON listo para [PlaceJsonParser].
     * [pistas] es todo lo que ya sepamos (texto de un TikTok, ciudad, lo que escribió el viajero).
     */
    suspend fun ficha(
        nombreOPistas: String,
        pistas: String = "",
        urls: List<String> = emptyList()
    ): Pair<JSONObject?, Hallazgo> = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null to Hallazgo("", problema = "Falta la clave de Gemini.")

        val prompt = buildString {
            appendLine(HOY_ES)
            appendLine("Investiga en internet este lugar de Ecuador y arma su ficha completa.")
            appendLine()
            appendLine("LUGAR / PISTAS: " + nombreOPistas)
            if (pistas.isNotBlank()) { appendLine(); appendLine("INFORMACIÓN ADICIONAL:"); appendLine(pistas.take(3000)) }
            if (urls.isNotEmpty()) { appendLine(); appendLine("PÁGINAS A LEER:"); urls.take(5).forEach { appendLine("  " + it) } }
            appendLine()
            appendLine("Busca en internet: la página oficial o del GAD, Google Maps, TripAdvisor, medios ecuatorianos")
            appendLine("y publicaciones recientes en redes. Confirma coordenadas, costo de entrada en USD, horario,")
            appendLine("cómo llegar desde la ciudad más cercana, teléfono/WhatsApp y redes del lugar.")
            appendLine()
            appendLine("Devuelve ÚNICAMENTE este JSON, sin markdown ni texto alrededor:")
            appendLine(PlaceJsonParser.SCHEMA)
            appendLine()
            appendLine("Añade además: \"confidence\" (0-1, qué tan seguro estás de que es este el lugar) y")
            appendLine("\"sin_verificar\" (lista de los campos que NO pudiste confirmar en internet).")
        }

        val r = runCatching {
            gemini.generate(
                prompt = prompt,
                systemInstruction = SISTEMA + "\nCuando te pidan JSON, respondes solo JSON.",
                useSearch = true,
                useUrlContext = urls.isNotEmpty(),
                temperature = 0.2,
                maxOutputTokens = 24576
            )
        }.getOrElse { e -> return@withContext null to Hallazgo("", problema = mensajeDeError(e)) }

        var obj = JsonExtract.firstObject(r.text)
        val fuentes = r.sources

        // Reintento en JSON estricto: sin herramientas el modelo no se queda sin presupuesto.
        if (obj == null) {
            val b = runCatching {
                gemini.generate(
                    prompt = prompt + "\n\nResponde el JSON directamente, sin buscar en la web.",
                    systemInstruction = SISTEMA + "\nSolo JSON.",
                    jsonOnly = true,
                    temperature = 0.3,
                    maxOutputTokens = 24576
                )
            }.getOrNull()
            if (b != null) obj = JsonExtract.firstObject(b.text)
        }

        val problema = when {
            obj != null -> ""
            r.blocked -> "Los filtros de seguridad bloquearon esa consulta."
            r.truncated -> "La respuesta de la IA se cortó."
            r.isEmpty -> "La IA no respondió. Revisa la conexión."
            else -> "No se pudo armar la ficha del lugar."
        }
        obj to Hallazgo(r.text.take(2000), fuentes, r.searchQueries, problema)
    }

    /** Descompone la pregunta en subpreguntas buscables. Si falla, busca la pregunta tal cual. */
    private suspend fun planificar(pregunta: String, contexto: String, maximo: Int): List<String> {
        val r = runCatching {
            gemini.generate(
                prompt = """
                    Un viajero en Ecuador pregunta: "$pregunta"
                    ${if (contexto.isNotBlank()) "Contexto: " + contexto.take(1200) else ""}

                    Divide esa pregunta en hasta $maximo búsquedas web independientes que, juntas, la respondan bien.
                    Cada una debe ser una consulta concreta y buscable (no "más información sobre X").
                    Si la pregunta ya es simple, devuelve una sola.
                    Responde solo: {"busquedas":["...","..."]}
                """.trimIndent(),
                systemInstruction = "Divides preguntas en búsquedas web. Solo JSON.",
                jsonOnly = true,
                temperature = 0.4,
                maxOutputTokens = 2048
            )
        }.getOrNull()

        val arr = r?.text?.let { JsonExtract.firstObject(it) }?.optJSONArray("busquedas")
        val lista = (0 until (arr?.length() ?: 0))
            .mapNotNull { arr?.optString(it, "")?.trim()?.takeIf { s -> s.length > 3 } }
            .take(maximo)
        return lista.ifEmpty { listOf(pregunta) }
    }

    private fun mensajeDeError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Unable to resolve host") || m.contains("timeout", true) ->
                "Sin conexión a internet: no pude buscar."
            m.contains("API_KEY") || m.contains("API key", true) || m.contains("403") ->
                "La clave de Gemini no es válida o no tiene permisos."
            m.contains("429") -> "Se agotó la cuota de búsquedas por ahora. Prueba en unos minutos."
            else -> "Falló la búsqueda en internet: " + m.take(140)
        }
    }
}

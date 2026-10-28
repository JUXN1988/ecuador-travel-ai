package ec.travesias.app.data.remote

import ec.travesias.app.data.model.PlaceSource
import ec.travesias.app.data.model.SourcePlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente REST para Google Gemini (API v1beta).
 *
 * - Modelo por defecto: gemini-2.5-flash (rápido, económico, con grounding).
 * - Herramientas: google_search (búsqueda web con fuentes) y url_context (leer un enlace).
 * - Devuelve texto + fuentes de grounding para mostrarlas al usuario.
 */
class GeminiClient(
    private val apiKeyProvider: () -> String = { Secrets.geminiApiKey },
    private val model: String = DEFAULT_MODEL
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    data class Result(
        val text: String,
        val sources: List<PlaceSource>,
        val searchQueries: List<String> = emptyList(),
        /** STOP, MAX_TOKENS, SAFETY, RECITATION… tal como lo devuelve la API. */
        val finishReason: String = "",
        /** Motivo de bloqueo del prompt, si lo hubo. */
        val blockReason: String = ""
    ) {
        val isEmpty: Boolean get() = text.isBlank()

        /** La respuesta se cortó: en gemini-2.5-flash el "pensamiento" consume el presupuesto. */
        val truncated: Boolean get() = finishReason == "MAX_TOKENS"

        val blocked: Boolean get() = blockReason.isNotBlank() || finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT"
    }

    val isAvailable: Boolean get() = apiKeyProvider().isNotBlank()

    /**
     * Genera contenido.
     * @param useSearch activa Google Search grounding.
     * @param useUrlContext permite que el modelo lea las URLs incluidas en el prompt.
     * @param jsonOnly pide salida JSON (sin herramientas; incompatible con grounding).
     */
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        useSearch: Boolean = false,
        useUrlContext: Boolean = false,
        jsonOnly: Boolean = false,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 24576
    ): Result {
        val key = apiKeyProvider()
        require(key.isNotBlank()) { "GEMINI_API_KEY no configurada" }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            systemInstruction?.let {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it))))
            }
            val tools = JSONArray()
            if (useSearch && !jsonOnly) tools.put(JSONObject().put("google_search", JSONObject()))
            if (useUrlContext && !jsonOnly) tools.put(JSONObject().put("url_context", JSONObject()))
            if (tools.length() > 0) put("tools", tools)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", maxOutputTokens)
                if (jsonOnly) put("responseMimeType", "application/json")
            })
            put("safetySettings", JSONArray().apply {
                listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").forEach {
                    put(JSONObject().put("category", it).put("threshold", "BLOCK_ONLY_HIGH"))
                }
            })
        }

        val url = "$BASE$model:generateContent"
        // 180 s: con google_search el modelo tarda de sobra más de 60 s. Un timeout aquí
        // se confundía con "sin internet" y tumbaba el Radar entero.
        val raw = Http.postJson(url, body.toString(), mapOf("x-goog-api-key" to key), readTimeoutSeconds = 180)
        return parse(raw)
    }

    /**
     * Prueba la conexión y la clave con una petición mínima y devuelve un veredicto legible.
     * Sirve para saber POR QUÉ no funciona el Radar sin tener que adivinar.
     */
    suspend fun diagnose(): String {
        val key = apiKeyProvider()
        if (key.isBlank()) {
            return "No hay clave de Gemini configurada. Pega tu GEMINI_API_KEY en el archivo .env y vuelve a compilar."
        }
        val pinta = when {
            key.startsWith("AIza") -> "formato correcto de Google AI Studio"
            else -> "FORMATO RARO: las claves de Google AI Studio empiezan por \"AIza\". " +
                "La tuya no tiene ese prefijo (suele ser de otro producto de Google) " +
                "y la API de Gemini la rechaza. Genera una nueva en aistudio.google.com/apikey"
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Responde solo: ok")))
            ))
            put("generationConfig", JSONObject().put("maxOutputTokens", 2048).put("temperature", 0.0))
        }

        return try {
            val raw = Http.postJson("$BASE$model:generateContent", body.toString(), mapOf("x-goog-api-key" to key))
            val r = parse(raw)
            val basico = when {
                r.text.isNotBlank() -> "1) Conexión básica con el modelo $model: OK. Clave con $pinta."
                r.truncated -> "1) El modelo respondió pero se quedó sin espacio (finishReason MAX_TOKENS). Clave con $pinta."
                r.blocked -> "1) La petición fue bloqueada por los filtros de seguridad (" + r.blockReason + ")."
                else -> "1) El modelo respondió vacío (finishReason: " + r.finishReason.ifBlank { "desconocido" } + "). Clave con $pinta."
            }
            // La prueba que de verdad importa para el Radar: la llamada CON búsqueda web.
            // Es la lenta y la que se caía por timeout haciendo creer que no había internet.
            basico + "\n\n" + diagnosticarBusqueda()
        } catch (e: Http.HttpException) {
            val detalle = e.body.take(320).replace("\n", " ")
            val causa = when (e.code) {
                400 -> if (detalle.contains("API_KEY_INVALID", true)) "La clave NO es válida para la API de Gemini." else "Petición rechazada (400)."
                403 -> "Acceso denegado (403): la clave existe pero no tiene habilitada la API Generative Language, o está restringida por app/IP."
                404 -> "El modelo \"" + model + "\" no existe o no está disponible para esta clave."
                429 -> "Cuota agotada (429). Espera unos minutos o revisa los límites de tu proyecto."
                else -> "Error HTTP " + e.code + "."
            }
            causa + "\n\nClave: " + pinta + "\n\nRespuesta del servidor: " + detalle
        } catch (e: Exception) {
            "No se pudo contactar con la API: " + (e.message ?: "sin detalle") +
                "\n\nRevisa que el teléfono tenga internet. Clave con " + pinta
        }
    }

    /**
     * Segunda prueba: una consulta CON búsqueda en Google, que es exactamente lo que hace el
     * Radar. Se mide cuánto tarda, porque el fallo típico no es de conexión sino de tiempo:
     * el modelo busca, lee y piensa, y si se pasa del timeout OkHttp lanza un error que la app
     * confundía con "el teléfono no tiene internet".
     */
    private suspend fun diagnosticarBusqueda(): String {
        val key = apiKeyProvider()
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(
                    JSONObject().put("text", "Busca en la web y responde en una sola frase: ¿qué se puede visitar en Baños de Agua Santa, Ecuador?")
                ))
            ))
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("maxOutputTokens", 8192).put("temperature", 0.2))
        }
        val inicio = System.currentTimeMillis()
        return try {
            val raw = Http.postJson("$BASE$model:generateContent", body.toString(), mapOf("x-goog-api-key" to key), readTimeoutSeconds = 180)
            val segundos = (System.currentTimeMillis() - inicio) / 1000
            val r = parse(raw)
            val fuentes = if (r.sources.isEmpty()) "sin fuentes" else r.sources.size.toString() + " fuentes"
            when {
                r.text.isNotBlank() ->
                    "2) Búsqueda web (lo que usa el Radar): OK en " + segundos + " s, " + fuentes + ".\n" +
                        "El Radar debería funcionar. Si aun así no trae paradas, prueba con menos intereses o un tramo más corto."
                r.blocked -> "2) Búsqueda web: bloqueada por los filtros de seguridad (" + r.blockReason + ")."
                r.truncated -> "2) Búsqueda web: la respuesta se cortó por longitud tras " + segundos + " s."
                else -> "2) Búsqueda web: el modelo respondió vacío tras " + segundos + " s (finishReason: " +
                    r.finishReason.ifBlank { "desconocido" } + "). No es tu conexión: el servidor sí contestó."
            }
        } catch (e: Exception) {
            val segundos = (System.currentTimeMillis() - inicio) / 1000
            val m = e.message.orEmpty()
            if (m.contains("timeout", true))
                "2) Búsqueda web: se agotó el tiempo tras " + segundos + " s. Tu internet funciona, pero la búsqueda " +
                    "de Gemini está tardando demasiado. Vuelve a intentarlo o usa un tramo más corto."
            else
                "2) Búsqueda web: falló tras " + segundos + " s — " + m.take(160)
        }
    }

    private fun parse(raw: String): Result {
        val json = JSONObject(raw)
        val block = json.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        val candidates = json.optJSONArray("candidates") ?: return Result("", emptyList(), blockReason = block)
        if (candidates.length() == 0) return Result("", emptyList(), blockReason = block)
        val cand = candidates.getJSONObject(0)
        val finish = cand.optString("finishReason", "")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val t = p.optString("text", "")
                if (t.isNotBlank()) sb.append(t)
            }
        }
        val sources = mutableListOf<PlaceSource>()
        val queries = mutableListOf<String>()
        cand.optJSONObject("groundingMetadata")?.let { gm ->
            gm.optJSONArray("groundingChunks")?.let { chunks ->
                for (i in 0 until chunks.length()) {
                    val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                    val uri = web.optString("uri", "")
                    if (uri.isBlank()) continue
                    sources.add(
                        PlaceSource(
                            platform = SourcePlatform.fromUrl(web.optString("title", "")).takeIf { it != SourcePlatform.OTHER && it != SourcePlatform.WEB }
                                ?: SourcePlatform.fromUrl(uri),
                            url = uri,
                            title = web.optString("title", "")
                        )
                    )
                }
            }
            gm.optJSONArray("webSearchQueries")?.let { q ->
                for (i in 0 until q.length()) queries.add(q.optString(i))
            }
        }
        return Result(sb.toString(), sources.distinctBy { it.url }, queries, finish, block)
    }
}

/**
 * Utilidades para extraer JSON de respuestas de modelos (que a veces envuelven en ```json ... ```).
 */
object JsonExtract {
    fun firstObject(text: String): JSONObject? {
        val cleaned = stripFences(text)
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(cleaned.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    fun firstArray(text: String): JSONArray? {
        val cleaned = stripFences(text)
        val start = cleaned.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONArray(cleaned.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /**
     * Rescata los objetos JSON completos de un array, AUNQUE la respuesta esté cortada.
     *
     * gemini-2.5-flash se queda a veces sin presupuesto de tokens a mitad de la lista: el JSON
     * llega sin cerrar y [firstObject] devuelve null, o sea que se perdían los 9 lugares que sí
     * habían llegado enteros. Esto recorre el array y devuelve todos los objetos bien formados.
     *
     * @param arrayKey nombre del array ("places", "lugares"...). Si es null se usa el primer "[".
     */
    fun objectsInArray(text: String, arrayKey: String?): List<JSONObject> {
        val cleaned = stripFences(text)
        var inicio = if (arrayKey.isNullOrBlank()) cleaned.indexOf('[') else {
            val marca = cleaned.indexOf("\"" + arrayKey + "\"")
            if (marca < 0) -1 else cleaned.indexOf('[', marca)
        }
        if (inicio < 0) return emptyList()

        val salida = ArrayList<JSONObject>()
        var i = inicio + 1
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (c == ']') break
            if (c != '{') { i++; continue }
            // Recorremos este objeto hasta su llave de cierre, respetando cadenas y anidados.
            var profundidad = 0
            var enCadena = false
            var escape = false
            var fin = -1
            var j = i
            while (j < cleaned.length) {
                val ch = cleaned[j]
                if (enCadena) {
                    when {
                        escape -> escape = false
                        ch == '\\' -> escape = true
                        ch == '"' -> enCadena = false
                    }
                } else when (ch) {
                    '"' -> enCadena = true
                    '{' -> profundidad++
                    '}' -> { profundidad--; if (profundidad == 0) { fin = j; } }
                }
                if (fin >= 0) break
                j++
            }
            if (fin < 0) break  // el último objeto venía cortado: se descarta y ya
            runCatching { JSONObject(cleaned.substring(i, fin + 1)) }.getOrNull()?.let { salida.add(it) }
            i = fin + 1
        }
        return salida
    }

    private fun stripFences(text: String): String =
        text.replace("```json", "").replace("```JSON", "").replace("```", "").trim()

    fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: run {
            val s = optString(key, "")
            return if (s.isBlank()) emptyList() else s.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
        }
        return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
    }

    fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getDouble(key) }.getOrElse {
            optString(key).replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
        }
    }
}

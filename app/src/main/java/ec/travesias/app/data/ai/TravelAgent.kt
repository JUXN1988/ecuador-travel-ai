package ec.travesias.app.data.ai

import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.RadarRequest
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.VerificationLevel
import ec.travesias.app.data.remote.GeminiClient
import ec.travesias.app.data.remote.GeocodingService
import ec.travesias.app.data.remote.JsonExtract
import ec.travesias.app.data.remote.RadarService
import ec.travesias.app.data.remote.RoutingService
import ec.travesias.app.data.remote.WebResearchService
import ec.travesias.app.data.remote.LinkExtractorService
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.repository.PlaceIndexRepository
import ec.travesias.app.data.repository.RouteRepository
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.data.repository.RouteCoherence
import ec.travesias.app.data.repository.TripCostEstimator
import ec.travesias.app.data.local.VehicleSettings
import ec.travesias.app.data.repository.normalizeSearch
import ec.travesias.app.ui.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Guía IA con herramientas: además de conversar, **hace cosas**.
 *
 * Puede consultar el catálogo que llevamos en el teléfono, rastrear menciones en redes
 * sociales y web, geocodificar direcciones, y sobre todo **construir y editar la ruta**
 * del viajero: crearla, añadir y quitar paradas, moverlas de día, ponerles hora y notas,
 * y optimizar el orden.
 *
 * Trabaja en dos rondas: primero decide qué necesita buscar, ejecutamos esas búsquedas y
 * le devolvemos los resultados; en la segunda ronda ya emite las acciones concretas.
 * Sin internet cambia a un planificador local que arma la ruta con el catálogo offline.
 */
class TravelAgent(
    private val client: GeminiClient,
    private val routes: RouteRepository,
    private val index: PlaceIndexRepository,
    private val radar: RadarService,
    private val geocoding: GeocodingService,
    /** Buscador de internet con fuentes (Gemini + google_search + url_context). */
    private val research: WebResearchService = WebResearchService(client),
    /** Lector de enlaces de TikTok/Instagram/Facebook/YouTube. */
    private val links: LinkExtractorService? = null
) {

    data class Result(
        val reply: String,
        /** Lo que el agente hizo de verdad, para mostrarlo en la conversación. */
        val steps: List<String> = emptyList(),
        val routeChanged: Boolean = false,
        val suggestions: List<String> = emptyList(),
        val usedOffline: Boolean = false
    )

    private val online: Boolean get() = client.isAvailable

    // ======================================================================
    //  Entrada principal
    // ======================================================================

    suspend fun handle(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        contextRegion: String = "Ecuador"
    ): Result = withContext(Dispatchers.IO) {
        if (!online) return@withContext planLocal(userMessage, "sin clave de Gemini")

        val steps = ArrayList<String>()
        try {
            val contexto = buildContext(contextRegion)

            // ---- Ronda 1: el agente decide qué necesita ----
            var raw = ask(userMessage, history, contexto, hallazgos = null)
            var plan = JsonExtract.firstObject(raw)
                ?: return@withContext planLocal(userMessage, "la IA no devolvió un plan legible")

            // ---- Herramientas ----
            val peticiones = plan.optJSONArray("buscar")
            if (peticiones != null && peticiones.length() > 0) {
                val hallazgos = ejecutarBusquedas(peticiones, steps)
                if (hallazgos.isNotBlank()) {
                    raw = ask(userMessage, history, contexto, hallazgos)
                    plan = JsonExtract.firstObject(raw) ?: plan
                }
            }

            // ---- Ronda 2: ejecutar las acciones sobre la ruta ----
            val acciones = plan.optJSONArray("acciones")
            var cambio = false
            if (acciones != null) {
                for (i in 0 until acciones.length()) {
                    val a = acciones.optJSONObject(i) ?: continue
                    if (ejecutarAccion(a, steps)) cambio = true
                }
            }

            // ---- Ronda 3: revisión de coherencia (una sola vez) ----
            // El modelo a veces deja días vacíos, jornadas de 14 h o paradas en zigzag. Antes de
            // contestar se revisa la ruta con reglas fijas y, si algo no cuadra, se le pide que lo
            // corrija con acciones concretas.
            var notaRevision = ""
            if (cambio) {
                val problemas = runCatching { problemasDeLaRuta() }.getOrDefault(emptyList())
                    .filter { it.level != RouteCoherence.Level.INFO }
                if (problemas.isNotEmpty()) {
                    val revision = buildString {
                        appendLine("REVISIÓN AUTOMÁTICA de la ruta que acabas de armar/editar. Problemas detectados:")
                        problemas.forEach { appendLine("- " + it.text) }
                        appendLine("Corrígelos AHORA con acciones (agregar_parada, mover_parada, quitar_parada, editar_parada,")
                        appendLine("o actualizar_ruta con menos días si sobran). No vuelvas a crear la ruta ni repitas paradas que ya están.")
                        appendLine("En \"respuesta\" di en una o dos frases qué corregiste.")
                    }
                    val raw2 = runCatching { ask(userMessage, history, buildContext(contextRegion), hallazgos = null, revision = revision) }.getOrNull()
                    val plan2 = raw2?.let { JsonExtract.firstObject(it) }
                    val acciones2 = plan2?.optJSONArray("acciones")
                    var corrigio = false
                    if (acciones2 != null) {
                        for (i in 0 until acciones2.length()) {
                            val a = acciones2.optJSONObject(i) ?: continue
                            if (ejecutarAccion(a, steps)) corrigio = true
                        }
                    }
                    if (corrigio) {
                        steps.add(t(AppSettings.language.value, "Revisé la ruta y corregí lo que no cuadraba", "Reviewed the route and fixed what did not fit"))
                        notaRevision = plan2?.optString("respuesta", "")?.trim().orEmpty()
                    }
                    val quedan = runCatching { problemasDeLaRuta() }.getOrDefault(emptyList())
                        .filter { it.level == RouteCoherence.Level.SERIOUS }
                    if (quedan.isNotEmpty()) {
                        notaRevision = (notaRevision + "\n\n" + t(AppSettings.language.value, "Ojo: ", "Note: ") + quedan.first().text).trim()
                    }
                }
            }

            val respuesta = listOf(plan.optString("respuesta", "").trim(), notaRevision)
                .filter { it.isNotBlank() }.joinToString("\n\n")
            val lang = AppSettings.language.value
            Result(
                reply = respuesta.ifBlank {
                    if (steps.isEmpty()) t(lang, "No estoy seguro de haber entendido. ¿Me lo dices de otra forma?", "I am not sure I understood. Could you rephrase that?")
                    else t(lang, "Listo.", "Done.")
                },
                steps = steps,
                routeChanged = cambio,
                suggestions = plan.optJSONArray("sugerencias").toStringList().take(3)
            )
        } catch (e: Exception) {
            planLocal(userMessage, "no se pudo conectar con la IA")
        }
    }

    // ======================================================================
    //  Conversación con el modelo
    // ======================================================================

    /** Si el viajero puso la app en inglés, la IA debe responder y escribir todo en inglés. */
    private fun idiomaDirectiva(): String =
        if (AppSettings.language.value == AppLanguage.EN)
            "\n\nIMPORTANT: The traveler set the app to English. Reply ONLY in English — the \"respuesta\" field and any text inside \"acciones\" (notes, names you write) must be in English too."
        else ""

    /** Problemas de la ruta activa según las reglas fijas de [RouteCoherence]. */
    private suspend fun problemasDeLaRuta(): List<RouteCoherence.Issue> {
        val ruta = routes.activeRouteOnce() ?: return emptyList()
        val ends = routes.endpointsFor(ruta)
        return RouteCoherence.check(ruta, RouteSchedule.build(ruta, null, ends), ends)
    }

    private suspend fun ask(
        userMessage: String,
        history: List<Pair<String, String>>,
        contexto: String,
        hallazgos: String?,
        revision: String? = null
    ): String {
        val historial = history.takeLast(6).joinToString("\n") { (quien, texto) ->
            (if (quien == "user") "Viajero" else "Guía") + ": " + texto.take(300)
        }
        val prompt = buildString {
            appendLine(contexto)
            if (historial.isNotBlank()) {
                appendLine()
                appendLine("CONVERSACIÓN RECIENTE:")
                appendLine(historial)
            }
            if (!hallazgos.isNullOrBlank()) {
                appendLine()
                appendLine("RESULTADOS DE LAS BÚSQUEDAS QUE PEDISTE:")
                appendLine(hallazgos)
                appendLine("Ya no pidas más búsquedas: usa esto y devuelve las acciones.")
            }
            if (!revision.isNullOrBlank()) {
                appendLine()
                appendLine(revision)
                appendLine("No pidas búsquedas en esta ronda: deja \"buscar\" vacío.")
            }
            appendLine()
            appendLine("MENSAJE DEL VIAJERO: " + userMessage)
        }
        return client.generate(
            prompt = prompt,
            systemInstruction = SYSTEM + idiomaDirectiva(),
            jsonOnly = true,
            temperature = 0.35,
            maxOutputTokens = 24576
        ).text
    }

    /** Foto del estado actual: qué ruta hay, qué paradas y qué lugares tenemos guardados. */
    private suspend fun buildContext(contextRegion: String): String {
        val ruta = routes.activeRouteOnce()
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        return buildString {
            appendLine("CONTEXTO ACTUAL")
            appendLine("Hoy es " + RouteCoherence.today() + ".")
            appendLine("Región de interés del viajero: " + contextRegion)
            if (ruta == null) {
                appendLine("Ruta activa: NINGUNA. Si el viajero quiere armar un viaje, créala tú.")
            } else {
                appendLine(
                    "Ruta activa: \"" + ruta.name + "\" — de " + ruta.originName.ifBlank { "(sin origen)" } +
                        " a " + ruta.destinationName.ifBlank { "(sin destino)" } +
                        ", " + ruta.totalDays + " día(s), " + ruta.travelers + " viajero(s), estilo " + ruta.travelStyle +
                        (if (ruta.startDate.isNotBlank()) ", inicia " + ruta.startDate else "")
                )
                appendLine(
                    "Transporte del viaje: " + ruta.transportMode.displayName +
                        ". Arranca cada día a las " + ruta.dailyStartTime + "."
                )
                if (ruta.stops.isEmpty()) {
                    appendLine("Paradas: ninguna todavía.")
                } else {
                    val ends = routes.endpointsFor(ruta)
                    if (ends.roundTrip) appendLine("Es un viaje de IDA Y VUELTA: el último día se regresa a " + ruta.originName + ".")
                    appendLine("Horario calculado (incluye la salida desde el origen y la llegada final; tiempos estimados):")
                    val planes = RouteSchedule.build(ruta, null, ends)
                    planes.forEach { dia ->
                        appendLine("  Día " + dia.day + " (" + dia.start + " → " + dia.end + ", " +
                            RouteSchedule.durationLabel(dia.travelMinutes) + " en camino, " +
                            dia.distanceKm.toInt() + " km), sale de " + dia.startPlace + ":")
                        dia.stops.forEach { p ->
                            appendLine("    · " + p.arrival + "–" + p.departure + "  " + p.stop.placeName +
                                "  [viaje " + p.travelLabel + " en " + p.transport.shortName +
                                ", visita " + p.visitLabel + "]" +
                                (if (p.stop.notes.isNotBlank()) " — " + p.stop.notes.take(50) else ""))
                        }
                        dia.finishLeg?.let { l ->
                            appendLine("    · " + l.arrival + "  llegada a " + l.toName + " [viaje " + l.travelLabel + ", " + l.distanceKm.toInt() + " km]")
                        }
                        dia.overnight?.let { appendLine("    (duerme en " + it + ")") }
                        if (dia.isLongDay) appendLine("    (ojo: este día pasa de 12 horas de jornada)")
                    }
                    val vacios = (1..ruta.totalDays).filter { d -> ruta.stops.none { it.dayNumber == d } }
                    if (vacios.isNotEmpty()) appendLine("  Días SIN paradas: " + vacios.joinToString(", "))
                    val v = VehicleSettings.profile.value
                    val costo = TripCostEstimator.estimate(ruta, planes, v)
                    appendLine(
                        "Costo estimado por persona: $" + TripCostEstimator.fmtUsd(costo.perPersonUsd) +
                            " (entradas $" + TripCostEstimator.fmtUsd(costo.entriesPerPersonUsd) +
                            " + transporte $" + TripCostEstimator.fmtUsd(costo.transportPerPersonUsd) + "). Recorrido " +
                            costo.totalKm.toInt() + " km."
                    )
                    if (costo.fuelUsd > 0) {
                        appendLine(
                            "Vehículo del viajero: " + v.type.displayName + ", " + TripCostEstimator.fmt1(v.kmPerUnit) + " km/" + v.fuel.unit +
                                ", " + v.fuel.displayName + " a $" + TripCostEstimator.fmtUsd(v.pricePerUnit) + "/" + v.fuel.unit +
                                ". Gasto: " + TripCostEstimator.fmt1(costo.fuelUnits) + " " + v.fuel.unit + " ≈ $" + TripCostEstimator.fmtUsd(costo.fuelUsd) +
                                ", peajes ≈ $" + TripCostEstimator.fmtUsd(costo.tollsUsd) + "."
                        )
                    }
                }
            }
            appendLine()
            appendLine("LUGARES QUE LA APP YA TIENE GUARDADOS (funcionan sin internet; úsalos primero):")
            catalogo.sortedBy { it.province }.forEach { p ->
                appendLine("  · " + p.name + " [" + p.category.displayName + ", " + p.province + "]")
            }
        }
    }

    // ======================================================================
    //  Herramientas
    // ======================================================================

    private suspend fun ejecutarBusquedas(peticiones: JSONArray, steps: MutableList<String>): String {
        val salida = StringBuilder()
        var usadas = 0
        for (i in 0 until peticiones.length()) {
            if (usadas >= 3) break
            val q = peticiones.optJSONObject(i) ?: continue
            val herramienta = q.optString("herramienta", "catalogo").lowercase()
            val consulta = q.optString("consulta", "").trim()
            if (consulta.isBlank()) continue
            usadas++
            when {
                herramienta.startsWith("web") || herramienta.contains("intern") || herramienta.contains("buscar") -> {
                    steps.add(t(AppSettings.language.value, "Busqué en internet: \"" + consulta + "\"", "Searched the web: \"" + consulta + "\""))
                    salida.appendLine(buscarEnInternet(consulta, q.optString("profundo", "") == "true" || q.optBoolean("profundo", false)))
                }
                herramienta.startsWith("enlace") || herramienta.contains("link") || herramienta.contains("url") -> {
                    steps.add(t(AppSettings.language.value, "Abrí el enlace y saqué sus datos", "Opened the link and pulled its data"))
                    salida.appendLine(leerEnlace(consulta))
                }
                herramienta.startsWith("red") || herramienta.contains("social") -> {
                    steps.add(t(AppSettings.language.value, "Rastreé redes sociales y web: \"" + consulta + "\"", "Scanned social media and the web: \"" + consulta + "\""))
                    salida.appendLine(buscarEnRedes(consulta, q.optString("origen", ""), q.optString("destino", "")))
                }
                herramienta.startsWith("map") || herramienta.contains("direc") -> {
                    steps.add(t(AppSettings.language.value, "Busqué la dirección de \"" + consulta + "\"", "Looked up the address for \"" + consulta + "\""))
                    salida.appendLine(buscarDireccion(consulta))
                }
                else -> {
                    steps.add(t(AppSettings.language.value, "Revisé el catálogo del teléfono: \"" + consulta + "\"", "Checked the offline catalog: \"" + consulta + "\""))
                    salida.appendLine(buscarEnCatalogo(consulta))
                }
            }
        }
        return salida.toString()
    }

    /** Catálogo local + nomenclátor. Funciona sin internet. */
    private suspend fun buscarEnCatalogo(consulta: String): String {
        val q = normalizeSearch(consulta)
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        val coincidencias = catalogo.filter { p ->
            val texto = normalizeSearch(p.name + " " + p.city + " " + p.province + " " + p.shortDescription + " " + p.tags.joinToString(" "))
            q.split(" ").any { it.length > 2 && texto.contains(it) }
        }.take(10)
        val sugerencias = runCatching { index.suggest(consulta, 6) }.getOrDefault(emptyList())
        return buildString {
            appendLine("[catálogo] " + consulta)
            if (coincidencias.isEmpty() && sugerencias.isEmpty()) appendLine("  (sin coincidencias)")
            coincidencias.forEach { p ->
                appendLine("  · " + p.name + " — " + p.category.displayName + ", " + p.city + " (" + p.province + "). " +
                    p.shortDescription.take(120) + " Cómo llegar: " + p.howToGetThere.take(160))
            }
            sugerencias.filter { s -> coincidencias.none { it.name.equals(s.name, true) } }.forEach { s ->
                appendLine("  · " + s.name + " — " + s.subtitle)
            }
        }
    }

    /** Radar social: menciones recientes en TikTok, Instagram, Facebook, YouTube y web. */
    private suspend fun buscarEnRedes(consulta: String, origen: String, destino: String): String {
        val ruta = routes.activeRouteOnce()
        val desde = origen.ifBlank { ruta?.originName ?: "" }.ifBlank { "Quito" }
        val hasta = destino.ifBlank { ruta?.destinationName ?: "" }.ifBlank { consulta }
        val peticion = RadarRequest(
            originName = desde,
            destinationName = hasta,
            waypoints = emptyList(),
            interests = listOf(consulta),
            maxDetourKm = 40,
            travelStyle = ruta?.travelStyle ?: "Moderado",
            freeText = consulta
        )
        val res = runCatching { radar.discover(peticion, maxPlaces = 8) }.getOrNull()
            ?: return "[redes] " + consulta + "\n  (la búsqueda social no respondió; usa el catálogo)"
        return buildString {
            appendLine("[redes sociales y web] " + consulta)
            appendLine("  Resumen: " + res.summary.take(300))
            if (res.places.isEmpty()) appendLine("  (sin lugares nuevos)")
            res.places.take(8).forEach { p ->
                appendLine("  · " + p.name + " — " + p.category.displayName + ", " + p.city.ifBlank { p.province } +
                    ". " + p.shortDescription.take(120))
            }
            if (res.groundingSources.isNotEmpty()) {
                appendLine("  Fuentes: " + res.groundingSources.take(4).joinToString(", ") { it.url })
            }
        }
    }

    /**
     * Búsqueda en internet de verdad, con fuentes. Es la herramienta más potente del agente:
     * usa Gemini con google_search (y url_context si le pasan enlaces). En modo [profundo]
     * parte la pregunta en varias búsquedas paralelas y luego las sintetiza.
     */
    private suspend fun buscarEnInternet(consulta: String, profundo: Boolean): String {
        if (!research.isAvailable) return "[internet] " + consulta + "\n  (sin clave de Gemini: no puedo buscar en internet)"
        val ruta = runCatching { routes.activeRouteOnce() }.getOrNull()
        val contexto = buildString {
            if (ruta != null) {
                appendLine("Viaje actual: " + ruta.originName + " → " + ruta.destinationName +
                    ", " + ruta.totalDays + " días, en " + ruta.transportMode.displayName + ".")
            }
            appendLine("El viajero está en Ecuador.")
        }
        val h = runCatching {
            if (profundo) research.investigar(consulta, contexto)
            else research.buscar(consulta, contexto, maxPalabras = 280)
        }.getOrNull()

        if (h == null || !h.ok) {
            return "[internet] " + consulta + "\n  (" + (h?.problema ?: "la búsqueda falló") + ")"
        }
        return buildString {
            appendLine("[internet — información verificada hoy] " + consulta)
            appendLine(h.texto.take(2500).prependIndent("  "))
            if (h.fuentes.isNotEmpty()) {
                appendLine("  Fuentes consultadas:")
                appendLine(h.fuentesEnTexto(6))
            }
        }
    }

    /**
     * El viajero pegó un enlace (TikTok, Reel, video de Facebook, YouTube o una web):
     * se abre, se le saca el texto real y se identifica el lugar.
     */
    private suspend fun leerEnlace(url: String): String {
        val limpio = Regex("https?://[^\\s]+").find(url)?.value ?: url
        val extractor = links ?: return "[enlace] " + limpio + "\n  (el lector de enlaces no está disponible)"
        val lugar = runCatching { extractor.extractPlace(limpio).first }.getOrNull()
        if (lugar != null) {
            return buildString {
                appendLine("[enlace] " + limpio)
                appendLine("  Lugar identificado: " + lugar.name + " — " + lugar.category.displayName +
                    ", " + lugar.city.ifBlank { lugar.province } + " (" + lugar.province + ")")
                appendLine("  " + lugar.shortDescription.take(200))
                appendLine("  Coordenadas: " + lugar.latitude + ", " + lugar.longitude)
                if (lugar.openingHours.isNotBlank()) appendLine("  Horario: " + lugar.openingHours)
                if (lugar.howToGetThere.isNotBlank()) appendLine("  Cómo llegar: " + lugar.howToGetThere.take(220))
                appendLine("  (ya quedó guardado en el catálogo; puedes añadirlo como parada por su nombre exacto)")
            }
        }
        // Si no se pudo identificar el lugar, al menos devolvemos lo que se leyó de la publicación.
        val meta = runCatching { extractor.fetchMeta(limpio) }.getOrNull()
        return buildString {
            appendLine("[enlace] " + limpio)
            if (meta != null && meta.tieneContenido) {
                appendLine("  No se identificó un lugar concreto, pero la publicación dice:")
                if (meta.title.isNotBlank()) appendLine("  Título: " + meta.title.take(200))
                if (meta.description.isNotBlank()) appendLine("  Texto: " + meta.description.take(300))
                if (meta.author.isNotBlank()) appendLine("  Cuenta: @" + meta.author + " (" + meta.platform.displayName + ")")
                appendLine("  Pídele al viajero el nombre del lugar o busca en internet con estas pistas.")
            } else {
                appendLine("  " + (meta?.platform?.displayName ?: "Esa red") +
                    " no dejó leer el contenido. Pregúntale al viajero qué lugar sale en el video.")
            }
        }
    }

    private suspend fun buscarDireccion(consulta: String): String {
        val hits = runCatching { geocoding.search(consulta, limit = 3) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return "[mapa] " + consulta + "\n  (no se encontró la dirección)"
        return buildString {
            appendLine("[mapa] " + consulta)
            hits.forEach { h ->
                appendLine("  · " + h.displayName.take(140) + " (" + h.latitude + ", " + h.longitude + ")")
            }
        }
    }

    // ======================================================================
    //  Acciones sobre la ruta
    // ======================================================================

    private suspend fun ejecutarAccion(a: JSONObject, steps: MutableList<String>): Boolean {
        return when (a.optString("tipo", "").lowercase().replace(" ", "_")) {
            "crear_ruta" -> {
                val nombre = a.optString("nombre", "").ifBlank { "Mi travesía" }
                val ruta = TravelRoute(
                    name = nombre,
                    originName = a.optString("origen", ""),
                    destinationName = a.optString("destino", ""),
                    // La IA a veces copia el formato literal ("AAAA-MM-DD"): solo entra una fecha real.
                    startDate = RouteCoherence.cleanDate(a.optString("fecha_inicio", "")),
                    totalDays = a.optInt("dias", 3).coerceIn(1, 30),
                    travelers = a.optInt("viajeros", 2).coerceIn(1, 20),
                    travelStyle = a.optString("estilo", "Moderado").ifBlank { "Moderado" },
                    transportMode = TransportMode.from(a.optString("transporte", "")),
                    dailyStartTime = a.optString("hora_inicio", "").trim().takeIf { RouteSchedule.isValidHhmm(it) } ?: "08:00"
                )
                routes.createRoute(ruta)
                steps.add(t(AppSettings.language.value, "Creé la ruta \"" + nombre + "\" de " + ruta.totalDays + " día(s)", "Created the route \"" + nombre + "\" for " + ruta.totalDays + " day(s)"))
                true
            }

            "actualizar_ruta" -> {
                val actual = routes.activeRouteOnce() ?: return false
                val nueva = actual.copy(
                    name = a.optString("nombre", "").ifBlank { actual.name },
                    originName = a.optString("origen", "").ifBlank { actual.originName },
                    destinationName = a.optString("destino", "").ifBlank { actual.destinationName },
                    startDate = a.optString("fecha_inicio", "").takeIf { RouteCoherence.isValidDate(it) } ?: RouteCoherence.cleanDate(actual.startDate),
                    totalDays = (if (a.has("dias")) a.optInt("dias", actual.totalDays) else actual.totalDays).coerceIn(1, 30),
                    travelers = (if (a.has("viajeros")) a.optInt("viajeros", actual.travelers) else actual.travelers).coerceIn(1, 20),
                    travelStyle = a.optString("estilo", "").ifBlank { actual.travelStyle },
                    transportMode = if (a.has("transporte")) TransportMode.from(a.optString("transporte", "")) else actual.transportMode,
                    dailyStartTime = a.optString("hora_inicio", "").trim().takeIf { RouteSchedule.isValidHhmm(it) } ?: actual.dailyStartTime
                )
                routes.updateRoute(nueva)
                // Si bajó el número de días, las paradas que quedaron fuera pasan al último día.
                actual.stops.filter { it.dayNumber > nueva.totalDays }.forEach { routes.moveStop(it, nueva.totalDays, 999) }
                steps.add(t(AppSettings.language.value, "Actualicé los datos de la ruta", "Updated the route details"))
                true
            }

            "agregar_parada", "anadir_parada", "añadir_parada" -> {
                val nombre = a.optString("lugar", "").ifBlank { a.optString("nombre", "") }
                if (nombre.isBlank()) return false
                val lugar = resolverLugar(nombre)
                if (lugar == null) {
                    steps.add(t(AppSettings.language.value, "No encontré \"" + nombre + "\" en el mapa", "Could not find \"" + nombre + "\" on the map"))
                    return false
                }
                val dia = a.optInt("dia", 1).coerceAtLeast(1)
                val parada = routes.addStop(lugar, dia)
                if (parada == null) {
                    steps.add(t(AppSettings.language.value, "\"" + lugar.name + "\" ya estaba en la ruta", "\"" + lugar.name + "\" was already in the route"))
                    return false
                }
                val hora = a.optString("hora", "").trim()
                val nota = a.optString("nota", "")
                // "hora_fija" solo cuando hay una razón real (ferry, tour, horario de apertura).
                // Si no, la hora es una sugerencia y la app la recalcula sola según el transporte.
                val horaFija = a.optBoolean("hora_fija", false) && RouteSchedule.isValidHhmm(hora)
                // Antes se ignoraban la duración, el costo y el transporte que proponía la IA.
                val duracion = if (a.has("duracion_min")) a.optInt("duracion_min", parada.durationMinutes).coerceIn(10, 600) else parada.durationMinutes
                val costo = if (a.has("costo")) a.optDouble("costo", parada.estimatedCostUsd).takeIf { !it.isNaN() && it >= 0 } ?: parada.estimatedCostUsd else parada.estimatedCostUsd
                val medio = a.optString("transporte", "").takeIf { it.isNotBlank() }?.let { TransportMode.from(it) }
                if (hora.isNotBlank() || nota.isNotBlank() || duracion != parada.durationMinutes || costo != parada.estimatedCostUsd || medio != null) {
                    routes.updateStop(
                        parada.copy(
                            plannedTime = hora, timeLocked = horaFija, notes = nota,
                            durationMinutes = duracion, estimatedCostUsd = costo, transportMode = medio
                        )
                    )
                }
                steps.add(t(AppSettings.language.value, "Añadí \"" + lugar.name + "\" al día " + dia, "Added \"" + lugar.name + "\" to day " + dia))
                true
            }

            "quitar_parada", "eliminar_parada" -> {
                val nombre = a.optString("lugar", "").ifBlank { a.optString("nombre", "") }
                val parada = buscarParada(nombre) ?: run {
                    steps.add(t(AppSettings.language.value, "No hallé la parada \"" + nombre + "\" en tu ruta", "Could not find the stop \"" + nombre + "\" in your route"))
                    return false
                }
                routes.removeStop(parada.id)
                steps.add(t(AppSettings.language.value, "Quité \"" + parada.placeName + "\" de la ruta", "Removed \"" + parada.placeName + "\" from the route"))
                true
            }

            "mover_parada" -> {
                val nombre = a.optString("lugar", "").ifBlank { a.optString("nombre", "") }
                val parada = buscarParada(nombre) ?: return false
                val dia = a.optInt("dia", parada.dayNumber).coerceAtLeast(1)
                val orden = a.optInt("orden", parada.orderInDay).coerceAtLeast(0)
                routes.moveStop(parada, dia, orden)
                steps.add(t(AppSettings.language.value, "Moví \"" + parada.placeName + "\" al día " + dia, "Moved \"" + parada.placeName + "\" to day " + dia))
                true
            }

            "editar_parada", "nota_parada" -> {
                val nombre = a.optString("lugar", "").ifBlank { a.optString("nombre", "") }
                val parada = buscarParada(nombre) ?: return false
                val nuevaHora = a.optString("hora", "").ifBlank { parada.plannedTime }
                routes.updateStop(
                    parada.copy(
                        plannedTime = nuevaHora,
                        // Solo queda fija si esta acción trae "hora_fija":true (una razón real de horario).
                        timeLocked = if (a.has("hora")) (a.optBoolean("hora_fija", false) && nuevaHora.isNotBlank()) else parada.timeLocked,
                        notes = a.optString("nota", "").ifBlank { parada.notes },
                        durationMinutes = if (a.has("duracion_min")) a.optInt("duracion_min", parada.durationMinutes).coerceIn(10, 600) else parada.durationMinutes,
                        estimatedCostUsd = if (a.has("costo")) a.optDouble("costo", parada.estimatedCostUsd) else parada.estimatedCostUsd,
                        transportMode = if (a.has("transporte")) TransportMode.from(a.optString("transporte", "")) else parada.transportMode
                    )
                )
                steps.add(t(AppSettings.language.value, "Actualicé la parada \"" + parada.placeName + "\"", "Updated the stop \"" + parada.placeName + "\""))
                true
            }

            "transporte" -> {
                val actual = routes.activeRouteOnce() ?: return false
                val modo = TransportMode.from(a.optString("modo", "").ifBlank { a.optString("transporte", "") })
                routes.updateRoute(actual.copy(transportMode = modo))
                steps.add(t(AppSettings.language.value, "Puse el transporte del viaje en " + modo.displayName, "Set the trip transport to " + modo.displayNameEn))
                true
            }

            "hora_inicio" -> {
                val actual = routes.activeRouteOnce() ?: return false
                val hora = a.optString("hora", "").ifBlank { a.optString("hora_inicio", "") }
                if (!RouteSchedule.isValidHhmm(hora)) return false
                routes.updateRoute(actual.copy(dailyStartTime = hora.trim()))
                steps.add(t(AppSettings.language.value, "El día empieza ahora a las " + hora.trim(), "The day now starts at " + hora.trim()))
                true
            }

            "optimizar_ruta" -> {
                val ok = routes.optimizeActiveRoute()
                if (ok) steps.add(t(AppSettings.language.value, "Reordené las paradas para acortar el recorrido", "Reordered the stops to shorten the trip"))
                ok
            }

            else -> false
        }
    }

    /** Encuentra una parada de la ruta activa por nombre aproximado. */
    private suspend fun buscarParada(nombre: String): RouteStop? {
        if (nombre.isBlank()) return null
        val ruta = routes.activeRouteOnce() ?: return null
        val q = normalizeSearch(nombre)
        return ruta.stops.firstOrNull { normalizeSearch(it.placeName) == q }
            ?: ruta.stops.firstOrNull { normalizeSearch(it.placeName).contains(q) }
            ?: ruta.stops.firstOrNull { q.contains(normalizeSearch(it.placeName)) }
    }

    /**
     * Nombre → lugar real. Busca en el catálogo, luego en el nomenclátor del Ecuador
     * y, como último recurso, geocodifica y guarda el lugar nuevo.
     */
    suspend fun resolverLugar(nombre: String): Place? {
        val q = normalizeSearch(nombre)
        if (q.isBlank()) return null

        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        catalogo.firstOrNull { normalizeSearch(it.name) == q }?.let { return it }
        catalogo.firstOrNull { normalizeSearch(it.name).contains(q) || q.contains(normalizeSearch(it.name)) }?.let { return it }

        val sugerencia = runCatching { index.suggest(nombre, 3) }.getOrDefault(emptyList()).firstOrNull()
        if (sugerencia != null) {
            if (sugerencia.placeId.isNotBlank()) {
                routes.getPlace(sugerencia.placeId)?.let { return it }
            }
            val coords = runCatching { index.resolveCoordinates(sugerencia) }.getOrNull()
            if (coords != null) return crearLugarLigero(sugerencia.name, sugerencia.parent, coords.first, coords.second)
        }

        val hit = runCatching { geocoding.search(nombre, limit = 1).firstOrNull() }.getOrNull() ?: return null
        if (!PlaceJsonParser.isInEcuador(hit.latitude, hit.longitude)) return null
        return crearLugarLigero(nombre.trim(), hit.province.ifBlank { hit.city }, hit.latitude, hit.longitude)
    }

    private suspend fun crearLugarLigero(nombre: String, zona: String, lat: Double, lng: Double): Place? {
        if (!PlaceJsonParser.isInEcuador(lat, lng)) return null
        val lugar = Place(
            id = "ia_" + UUID.randomUUID().toString().replace("-", "").take(14),
            name = nombre,
            category = PlaceCategory.NATURE,
            region = EcuadorRegion.fromCoordinates(lat, lng),
            province = zona,
            city = zona,
            latitude = lat,
            longitude = lng,
            shortDescription = "Parada añadida por la Guía IA.",
            verification = VerificationLevel.AI_SUGGESTED
        )
        routes.upsertPlace(lugar)
        return lugar
    }

    // ======================================================================
    //  Planificador local (sin internet)
    // ======================================================================

    /**
     * Cuando no hay IA en línea, el agente sigue sirviendo: entiende las peticiones
     * habituales y arma la ruta con los lugares que la app lleva descargados.
     */
    private suspend fun planLocal(mensaje: String, motivo: String): Result {
        val texto = normalizeSearch(mensaje)
        val steps = ArrayList<String>()

        val quiereRuta = listOf("ruta", "itinerario", "viaje", "plan", "arma", "armame", "hazme", "planifica")
            .any { texto.contains(it) }
        val quiereAgregar = listOf("agrega", "anade", "añade", "sumar", "incluye").any { texto.contains(it) }
        val quiereQuitar = listOf("quita", "elimina", "borra", "saca").any { texto.contains(it) }
        val quiereOptimizar = listOf("optimiza", "ordena", "reordena", "acorta").any { texto.contains(it) }

        if (quiereOptimizar) {
            val ok = routes.optimizeActiveRoute()
            return Result(
                reply = if (ok) "Reordené las paradas de tu ruta para que el recorrido sea más corto."
                else "Necesitas al menos dos paradas en la ruta para poder reordenarlas.",
                steps = if (ok) listOf("Reordené las paradas") else emptyList(),
                routeChanged = ok, usedOffline = true
            )
        }

        if (quiereQuitar) {
            val ruta = routes.activeRouteOnce()
            val parada = ruta?.stops?.firstOrNull { normalizeSearch(mensaje).contains(normalizeSearch(it.placeName)) }
            if (parada != null) {
                routes.removeStop(parada.id)
                return Result("Quité \"" + parada.placeName + "\" de tu ruta.", listOf("Quité " + parada.placeName), true, usedOffline = true)
            }
        }

        if (quiereAgregar) {
            val lugar = extraerLugarMencionado(mensaje)
            if (lugar != null) {
                val dia = Regex("d[ií]a\\s*(\\d+)").find(texto)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val parada = routes.addStop(lugar, dia)
                return if (parada != null) Result(
                    "Añadí \"" + lugar.name + "\" al día " + dia + " de tu ruta.",
                    listOf("Añadí " + lugar.name + " al día " + dia), true, usedOffline = true
                ) else Result("\"" + lugar.name + "\" ya estaba en tu ruta.", emptyList(), false, usedOffline = true)
            }
        }

        if (quiereRuta) {
            val dias = Regex("(\\d+)\\s*d[ií]as?").find(texto)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 15) ?: 3
            val (origen, destino) = extraerOrigenDestino(mensaje)
            return armarRutaOffline(origen, destino, dias, steps)
        }

        return Result(
            reply = "Ahora mismo estoy sin conexión a la IA (" + motivo + "), pero igual puedo armarte la ruta con los " +
                "lugares que la app lleva descargados. Dime por ejemplo: «arma una ruta de 4 días de Quito a Baños», " +
                "«agrega el Quilotoa al día 2» o «optimiza mi ruta».",
            suggestions = listOf("Arma una ruta de 4 días de Quito a Baños", "Optimiza mi ruta", "¿Qué llevo para el páramo?"),
            usedOffline = true
        )
    }

    private suspend fun armarRutaOffline(origen: String, destino: String, dias: Int, steps: MutableList<String>): Result {
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        if (catalogo.isEmpty()) {
            return Result("Todavía no tengo lugares descargados para armar la ruta.", usedOffline = true)
        }

        val pOrigen = if (origen.isBlank()) null else resolverLugarOffline(origen, catalogo)
        val pDestino = if (destino.isBlank()) null else resolverLugarOffline(destino, catalogo)

        // Elegimos los lugares más cercanos a la línea origen–destino; si no hay, los mejor valorados.
        val candidatos = if (pOrigen != null && pDestino != null) {
            catalogo.sortedBy { p ->
                RoutingService.haversineKm(pOrigen.latitude, pOrigen.longitude, p.latitude, p.longitude) +
                    RoutingService.haversineKm(p.latitude, p.longitude, pDestino.latitude, pDestino.longitude)
            }
        } else {
            catalogo.sortedByDescending { it.socialMentions }
        }

        val porDia = 3
        val elegidos = LinkedHashSet<Place>()
        pOrigen?.let { elegidos.add(it) }
        candidatos.forEach { if (elegidos.size < dias * porDia) elegidos.add(it) }
        pDestino?.let { elegidos.add(it) }

        val nombre = if (origen.isNotBlank() && destino.isNotBlank()) "$origen → $destino" else "Mi travesía por Ecuador"
        val ruta = routes.createRoute(
            TravelRoute(
                name = nombre,
                originName = origen,
                destinationName = destino,
                totalDays = dias,
                travelers = 2
            )
        )
        steps.add(t(AppSettings.language.value, "Creé la ruta \"" + nombre + "\" de " + dias + " día(s)", "Created the route \"" + nombre + "\" for " + dias + " day(s)"))

        var i = 0
        elegidos.take(dias * porDia).forEach { p ->
            val dia = (i / porDia) + 1
            if (routes.addStop(p, dia.coerceIn(1, dias), ruta.id) != null) i++
        }
        steps.add(t(AppSettings.language.value, "Añadí " + i + " paradas del catálogo offline", "Added " + i + " stops from the offline catalog"))

        return Result(
            reply = "Listo: armé \"" + nombre + "\" con " + i + " paradas repartidas en " + dias + " día(s), usando los " +
                "lugares que ya tienes descargados. Ábrela en «Mi Ruta» para ajustar días, horas y notas. " +
                "Cuando tengas internet dime «busca lugares virales en esta ruta» y la completo con el Radar.",
            steps = steps,
            routeChanged = true,
            suggestions = listOf("Optimiza mi ruta", "Agrega una parada de comida típica", "¿Cuánto me cuesta este viaje?"),
            usedOffline = true
        )
    }

    private fun resolverLugarOffline(nombre: String, catalogo: List<Place>): Place? {
        val q = normalizeSearch(nombre)
        if (q.isBlank()) return null
        return catalogo.firstOrNull { normalizeSearch(it.name) == q }
            ?: catalogo.firstOrNull { normalizeSearch(it.city) == q }
            ?: catalogo.firstOrNull { normalizeSearch(it.name).contains(q) || normalizeSearch(it.city).contains(q) }
    }

    private suspend fun extraerLugarMencionado(mensaje: String): Place? {
        val limpio = mensaje.replace(Regex("(?i)\\b(agrega|añade|anade|incluye|suma|a mi ruta|al d[ií]a \\d+|por favor)\\b"), " ").trim()
        return resolverLugar(limpio)
    }

    private fun extraerOrigenDestino(mensaje: String): Pair<String, String> {
        val m = Regex("(?i)\\bde\\s+(.+?)\\s+(?:a|hasta|hacia)\\s+([^,.;]+)").find(mensaje)
            ?: Regex("(?i)\\bdesde\\s+(.+?)\\s+(?:a|hasta|hacia)\\s+([^,.;]+)").find(mensaje)
        if (m != null) {
            val o = m.groupValues[1].trim().removePrefix("la ").removePrefix("el ")
            val d = m.groupValues[2].trim().removeSuffix(".").trim()
            if (o.length in 3..40 && d.length in 3..40) return Pair(o, d)
        }
        return Pair("", "")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        private val SYSTEM = """
            Eres la "Guía IA" de la app Travesías en Ecuador. No eres un chatbot que solo conversa: eres un
            agente con herramientas que CONSTRUYE Y EDITA la ruta del viajero dentro de la app.

            Respondes SIEMPRE con un único objeto JSON válido, sin markdown y sin texto fuera del JSON:
            {
              "respuesta": "lo que le dices al viajero, en español, cálido y concreto (máx. 150 palabras)",
              "buscar": [ {"herramienta":"catalogo|internet|enlace|redes|mapa", "consulta":"...", "profundo":false, "origen":"", "destino":""} ],
              "acciones": [ ... ],
              "sugerencias": ["hasta 3 preguntas cortas de seguimiento"]
            }

            HERRAMIENTAS (campo "buscar", máximo 3 por respuesta):
            - "catalogo": los lugares que la app lleva descargados. Funciona sin internet. Empieza siempre por aquí.
            - "internet": BÚSQUEDA WEB REAL con fuentes. Es tu herramienta más potente y la que debes usar
              SIEMPRE que la respuesta dependa de algo que cambia o que tú no puedas saber de memoria:
              precios y costos de entrada actuales, horarios, si un lugar está abierto o cerrado, estado de
              las vías, cierres por clima o mantenimiento, feriados, festivales y fechas, requisitos de acceso,
              hoteles y restaurantes concretos, teléfonos, clima, noticias, y CUALQUIER dato posterior a tu
              entrenamiento. Pon "profundo":true cuando la pregunta tenga varias partes o la respuesta importe
              de verdad (se parte en varias búsquedas y se sintetiza; tarda más pero es mucho mejor).
              NO respondas de memoria un dato que esta herramienta puede verificar: búscalo.
            - "enlace": el viajero pegó una URL (TikTok, Reel, video de Facebook, YouTube, o una web).
              La abre, saca el texto real de la publicación e identifica el lugar. Úsala SIEMPRE que el
              mensaje del viajero traiga un enlace, y pon la URL completa en "consulta".
            - "redes": rastrea menciones recientes en TikTok, Instagram, Facebook, YouTube y blogs, y devuelve
              LUGARES listos para añadir. Úsala para "qué está de moda", "lugares virales", "algo nuevo".
            - "mapa": geocodifica una dirección concreta.
            Si el CONTEXTO ya te da lo necesario, deja "buscar" vacío y pasa directo a las acciones.

            USO DE LO QUE ENCUENTRES: cuando una búsqueda te devuelva información marcada como verificada en
            internet, úsala tal cual y menciona el dato concreto (el precio, la hora, el nombre). Si la búsqueda
            dijo "no verificado" o falló, dilo con honestidad ("no pude confirmar el precio actual") en vez de
            inventar una cifra.

            ACCIONES (campo "acciones", se ejecutan de verdad sobre la ruta del viajero):
            - {"tipo":"crear_ruta","nombre":"","origen":"","destino":"","dias":5,"viajeros":2,"estilo":"Moderado","fecha_inicio":"","transporte":"bus|carro|taxi|moto|tour|bici|a pie|mixto","hora_inicio":"07:30"}
              ("fecha_inicio": una fecha REAL tipo 2026-11-15 solo si el viajero la dijo o se deduce de "el próximo sábado"; si no, "")
            - {"tipo":"actualizar_ruta","nombre":"","dias":6,"viajeros":3,"estilo":"","fecha_inicio":"","transporte":"","hora_inicio":""}
            - {"tipo":"transporte","modo":"bus"}          (cambia solo el transporte del viaje)
            - {"tipo":"hora_inicio","hora":"06:30"}       (a qué hora arranca cada día)
            - {"tipo":"agregar_parada","lugar":"Quilotoa","dia":2,"hora":"","hora_fija":false,"duracion_min":180,"costo":2,"nota":"llevar cortavientos"}
            - {"tipo":"quitar_parada","lugar":"Casa del Árbol"}
            - {"tipo":"mover_parada","lugar":"Pailón del Diablo","dia":3,"orden":0}
            - {"tipo":"editar_parada","lugar":"Cuicocha","hora":"07:30","hora_fija":false,"nota":"","duracion_min":180,"costo":5,"transporte":"taxi"}
            - {"tipo":"optimizar_ruta"}

            CÓMO ARMAR UNA RUTA COHERENTE (lo más importante):
            - El ORIGEN no es una parada: la app suma sola el viaje desde el origen hasta la primera parada del día 1
              y, el último día, el viaje hasta el destino (o el regreso a casa si origen = destino). No añadas el origen.
            - Recorrido CONTINUO: cada parada sigue a la anterior por la misma vía; nada de ir al oriente, volver a la
              sierra y regresar al oriente. En ida y vuelta, ve por una vía y regresa por otra (o por la misma) sin zigzag.
            - LLENA TODOS LOS DÍAS: cada día debe tener al menos una parada. Si no hay suficientes lugares que valgan
              la pena, crea la ruta con MENOS días. Nunca dejes días vacíos al final.
            - Máximo en camino por día: carro propio ~6 h, bus ~7 h. Si un tramo es más largo (p. ej. Santo Domingo →
              Lago Agrio), parte el viaje con una parada intermedia para comer y otra para dormir.
            - La ÚLTIMA parada de cada día es donde se duerme: debe ser un pueblo o ciudad con hospedaje (o un lodge).
            - Pon "duracion_min" realistas (mirador 30, almuerzo 60, cascada/caminata 120-180, reserva/tour 240+)
              y "costo" por persona en USD cuando lo sepas.
            - Si viaja en carro propio, el contexto trae el gasto de combustible y peajes ya calculado: menciónalo.

            REGLAS DE OFICIO:
            - Si el viajero pide un viaje y no hay ruta activa, créala y añade las paradas en la misma respuesta.
            - Reparte las paradas con criterio: 2 o 3 por día, agrupadas por cercanía, sin cruzar el país ida y vuelta.
            - Usa el nombre EXACTO de los lugares del catálogo cuando existan; así se añaden con sus datos, fotos y costos.
            - Ten en cuenta la altitud: no pongas Chimborazo o Quilotoa el primer día si vienen de la costa.
            - Da precios en dólares y tiempos reales de carretera ecuatoriana.
            - EL TIEMPO MANDA. En el contexto tienes el horario calculado con horas de llegada y salida.
              Si un día pasa de 10-12 horas de jornada, reparte paradas a otro día o quita alguna, y dilo.
              En bus suma esperas de terminal; en carro propio los tiempos son más cortos pero hay que parar a comer.
            - Cuando propongas paradas nuevas, prioriza las que caen de camino y no alargan el día;
              di cuánto se tarda en llegar ("son 45 min más en bus") en vez de solo el nombre.
            - Si el viajero no ha dicho cómo viaja, pregúntaselo o asume bus y avísale que puede cambiarlo.
            - "hora" en agregar_parada/editar_parada es solo una SUGERENCIA: la app recalcula la llegada real
              según el transporte, así que NO le pongas "hora_fija":true salvo que exista una razón real de horario
              (sale un ferry o bus a esa hora exacta, un tour reservado, el lugar abre/cierra a cierta hora). Si
              pones "hora_fija":true a lo tonto, esa parada deja de moverse aunque el viajero cambie de transporte.
            - No inventes que hiciste algo: si no emites la acción, no lo digas en la respuesta.
            - Si el viajero solo pregunta algo (clima, soroche, qué llevar, seguridad), responde y deja "acciones" vacío.
        """.trimIndent()
    }
}

package ec.travesias.app.data.remote

import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Radar IA: descubre paradas a lo largo de un corredor (origen → destino) combinando:
 *  1. Gemini con Google Search grounding (menciones indexadas de TikTok, Instagram,
 *     Facebook, YouTube, blogs y prensa) → lista corta de candidatos.
 *  2. Una segunda pasada SIN herramientas y en JSON estricto que convierte esa lista en
 *     fichas completas. Se hace en lotes pequeños y en paralelo.
 *  3. Nominatim para geocodificar lugares sin coordenadas fiables.
 *  4. Google Places (opcional) para teléfono, horarios, reseñas y fotos.
 *  5. YouTube Data API (opcional) para videos reales de cada parada.
 *  6. Filtro geográfico: solo lugares dentro del desvío máximo respecto a la ruta.
 *
 * POR QUÉ DOS PASADAS (esto era el bug de "no hay internet"):
 * gemini-2.5-flash reparte el presupuesto de tokens entre lo que "piensa" y lo que escribe.
 * Pedirle de una sola vez 14 fichas enormes CON búsqueda web hacía que se quedara sin
 * presupuesto: devolvía texto vacío o un JSON cortado a la mitad, no se podía parsear nada
 * y la app culpaba a la conexión aunque la clave y el internet estuvieran perfectos.
 * Ahora la pasada con búsqueda escribe poquito (nombres) y las fichas largas se piden aparte,
 * sin herramientas, en lotes. Además se rescatan los objetos completos de un JSON cortado.
 */
class RadarService(
    private val gemini: GeminiClient,
    private val geocoding: GeocodingService,
    private val routing: RoutingService,
    private val places: PlacesService,
    private val youtube: YouTubeService
) {

    val isOnlineCapable: Boolean get() = gemini.isAvailable

    /** Candidato que devolvió la pasada con búsqueda web: solo lo mínimo para ubicarlo. */
    data class Pista(
        val nombre: String,
        val zona: String,
        val categoria: String,
        val porque: String,
        val menciones: Int,
        val lat: Double?,
        val lng: Double?,
        val fuentes: List<PlaceSource>
    )

    private val idiomaDirectiva: String
        get() = if (AppSettings.language.value == AppLanguage.EN)
            "\n- IMPORTANT: The traveler set the app to English. Write \"summary\", place names/descriptions and every text field in English."
        else ""

    private val basePrompt = """
        Eres el motor "Radar Social" de la app Travesías en Ecuador. Tu trabajo es rastrear, con la herramienta de
        búsqueda, lo que viajeros reales han publicado recientemente (AÑO 2024 EN ADELANTE) en TikTok, Instagram Reels, Facebook, YouTube,
        blogs y prensa ecuatoriana sobre paradas a lo largo de una ruta por Ecuador, y consolidarlo en datos verificables. Reglas:
        - Prioriza EXTREMADAMENTE las "huecas" de comida local y sitios turísticos virales muy nombrados desde el 2024.
        - Solo lugares reales de Ecuador, con coordenadas GPS decimales lo más exactas posible (WGS84).
        - Incluye variedad: comida típica, naturaleza, miradores para fotos, aventura, cultura, playa, paradas de servicio útiles.
        - Costos en dólares estadounidenses (USD), realistas para el año actual. Si es gratis usa 0.
        - "how_to_get_there" debe ser concreto: vía, kilómetro, desvío, bus que lo deja, tiempo aproximado.
        - Contactos y redes solo si los encontraste realmente; si no, deja "".
        - En "sources" incluye las URLs reales que consultaste (videos, publicaciones, artículos). No inventes URLs.
        - Responde ÚNICAMENTE con JSON válido, sin texto adicional ni markdown.
    """.trimIndent()

    private val systemPrompt: String get() = basePrompt + idiomaDirectiva

    // ======================================================================
    //  Paso 1: rastreo en redes y web (respuesta CORTA, con fuentes)
    // ======================================================================

    /**
     * Busca en redes y web las paradas del trayecto y devuelve solo nombres y ubicación
     * aproximada. Al pedir poco texto la respuesta cabe siempre, aunque el modelo "piense" mucho.
     */
    suspend fun rastrear(request: RadarRequest, maxPlaces: Int = 14): Rastreo {
        val interests = request.interests.ifEmpty { listOf("comida típica", "naturaleza", "miradores", "cultura", "aventura") }
        val waypoints = if (request.waypoints.isEmpty()) "" else "pasando por " + request.waypoints.joinToString(", ")

        val prompt = buildString {
            if (request.onlyDestination) {
                // -------- Modo "solo destino" --------
                // El viajero no dijo de dónde sale. No hay carretera que recorrer: lo útil es
                // saber qué hay para hacer con base en el destino, en un radio caminable o de
                // una hora de carro. Antes esto se mandaba como una ruta con origen vacío y la
                // IA devolvía lugares de medio país.
                appendLine("Zona del viajero: " + request.destinationName + " y sus alrededores (Ecuador).")
                appendLine("El viajero NO indicó de dónde sale: no busques una ruta ni lugares de paso.")
                appendLine("Busca paradas y planes con base en " + request.destinationName.trim() +
                    ", dentro de un radio de " + request.maxDetourKm + " km.")
                appendLine("Prioriza lo que se puede hacer saliendo y volviendo el mismo día desde " +
                    request.destinationName.trim() + ".")
            } else {
                appendLine("Ruta del viajero: de " + request.originName + " a " + request.destinationName + " " + waypoints + " (Ecuador).")
                appendLine("Estilo de viaje: " + request.travelStyle + ". Desvío máximo aceptado respecto a la vía: " + request.maxDetourKm + " km.")
            }
            appendLine("Intereses: " + interests.joinToString(", ") + ".")
            if (request.freeText.isNotBlank()) appendLine("Petición adicional del viajero: " + request.freeText)
            appendLine()
            if (request.onlyDestination) {
                appendLine("Busca en la web menciones desde el año 2024 (TikTok, Instagram, Facebook, YouTube, blogs, prensa) de")
                appendLine("planes y sitios imperdibles alrededor de " + request.destinationName.trim() + ". Devuelve entre 8 y " + maxPlaces + " lugares.")
                appendLine("INSTRUCCIÓN VITAL: todos los lugares deben estar a menos de " + request.maxDetourKm +
                    " km del centro de " + request.destinationName.trim() + ". Nada de otras provincias.")
                appendLine("Incluye SÍ O SÍ plazas, iglesias, miradores, parques, museos, mercados y comida típica del destino;")
                appendLine("no devuelvas solo restaurantes ni solo lugares de paso.")
            } else {
                appendLine("Busca en la web menciones desde el año 2024 (TikTok, Instagram, Facebook, YouTube, blogs, prensa) de paradas")
                appendLine("en este trayecto y devuelve entre 8 y " + maxPlaces + " lugares.")
                appendLine("INSTRUCCIÓN VITAL: Debes hacer la búsqueda de forma ORDENADA, PUEBLO POR PUEBLO a lo largo de la carretera.")
                appendLine("Ve avanzando por cada poblado del camino buscando qué hay alrededor (ej. a 15km), hasta llegar a la ciudad de destino y buscar sus sitios más nombrados.")
                appendLine("En el DESTINO FINAL incluye SÍ O SÍ 4-6 lugares turísticos imperdibles (plaza, catedral, mirador, parque,")
                appendLine("museo, mercado, barrio histórico, comida típica). El viajero no conoce el destino: no lo dejes vacío.")
            }
            appendLine("IMPORTANTE: esta respuesta debe ser CORTA. Nada de descripciones largas: solo la lista.")
            appendLine()
            appendLine("Formato exacto:")
            appendLine("{")
            appendLine("  \"resumen\": \"2 frases sobre qué está de moda en esta ruta\",")
            appendLine("  \"lugares\": [")
            appendLine("    {\"nombre\":\"\", \"zona\":\"cantón o provincia\", \"categoria\":\"nature|food|culture|adventure|beach|viewpoint|lodging|services\",")
            appendLine("     \"porque\":\"media frase: por qué vale la pena parar\", \"menciones\":0, \"lat\":0.0, \"lng\":0.0,")
            appendLine("     \"fuentes\":[\"https://...\"]}")
            appendLine("  ]")
            appendLine("}")
        }

        // 1er intento: CON búsqueda en Google (trae fuentes reales de redes y blogs).
        //
        // OJO: aquí NO se puede abortar si esto falla. La búsqueda web es la parte lenta y frágil
        // (el modelo busca, lee páginas y piensa; pasar de un minuto es normal). Antes un timeout
        // en esta llamada hacía `return` inmediato con "sin conexión a internet", aunque el
        // teléfono tuviera señal perfecta y la Guía IA funcionara: por eso el Radar parecía
        // no tener internet mientras el resto de la app sí. Ahora se guarda el fallo y se sigue.
        val primero = runCatching {
            gemini.generate(
                prompt = prompt,
                systemInstruction = systemPrompt,
                useSearch = true,
                temperature = 0.35,
                maxOutputTokens = 24576
            )
        }
        val res = primero.getOrNull()
        val falloPrimero = primero.exceptionOrNull()

        var pistas = res?.let { extraerPistas(it.text) }.orEmpty()
        var resumen = res?.let { JsonExtract.firstObject(it.text)?.optString("resumen", "") }.orEmpty()
        var conBusqueda = pistas.isNotEmpty()

        // 2º intento: sin herramientas y en JSON estricto. Es rápido y casi nunca falla, así que
        // sirve tanto si la búsqueda web devolvió vacío como si reventó por timeout.
        var falloSegundo: Throwable? = null
        if (pistas.isEmpty()) {
            conBusqueda = false
            val segundo = runCatching {
                gemini.generate(
                    prompt = prompt + if (request.onlyDestination)
                        "\n\nDevuelve el JSON directamente, sin buscar en la web, usando lo que ya sabes de " +
                            request.destinationName.trim() + " y sus alrededores (Ecuador)."
                    else
                        "\n\nDevuelve el JSON directamente, sin buscar en la web, usando lo que ya sabes de esta ruta del Ecuador.",
                    systemInstruction = systemPrompt,
                    jsonOnly = true,
                    temperature = 0.5,
                    maxOutputTokens = 16384
                )
            }
            falloSegundo = segundo.exceptionOrNull()
            segundo.getOrNull()?.let { r2 ->
                val otras = extraerPistas(r2.text)
                if (otras.isNotEmpty()) {
                    pistas = otras
                    resumen = JsonExtract.firstObject(r2.text)?.optString("resumen", "").orEmpty()
                }
            }
        }

        val fuentesGrounding = res?.sources.orEmpty()
        if (pistas.isEmpty()) {
            // Solo se culpa a la conexión si NINGUNA de las dos llamadas llegó al servidor.
            val ambasFallaron = falloPrimero != null && falloSegundo != null
            return when {
                ambasFallaron -> Rastreo(
                    emptyList(), "", fuentesGrounding,
                    problema = errorLegible(falloSegundo ?: falloPrimero!!),
                    fueRed = esErrorDeRed(falloSegundo ?: falloPrimero!!)
                )
                res != null -> Rastreo(emptyList(), "", fuentesGrounding, problema = sinResultados(res), fueRed = false)
                falloSegundo != null -> Rastreo(emptyList(), "", fuentesGrounding, problema = errorLegible(falloSegundo), fueRed = esErrorDeRed(falloSegundo))
                else -> Rastreo(emptyList(), "", fuentesGrounding, problema = "No se encontraron paradas para este tramo.", fueRed = false)
            }
        }
        val texto = resumen.ifBlank {
            if (conBusqueda) "Paradas sintetizadas a partir de menciones recientes en redes sociales y web."
            else "Sugerencias de la IA para este tramo (sin enlaces: la búsqueda web no devolvió resultados)."
        }
        return Rastreo(pistas.take(maxPlaces), texto, fuentesGrounding)
    }

    /** Lo que devuelve [rastrear]: candidatos + resumen + fuentes, o el motivo real del fallo. */
    data class Rastreo(
        val pistas: List<Pista>,
        val resumen: String,
        val fuentes: List<PlaceSource>,
        val problema: String = "",
        /** true solo si el fallo fue de conexión de verdad (no hubo respuesta del servidor). */
        val fueRed: Boolean = false
    )

    private fun extraerPistas(text: String): List<Pista> {
        if (text.isBlank()) return emptyList()
        val root = JsonExtract.firstObject(text)
        val arr = root?.optJSONArray("lugares") ?: root?.optJSONArray("places")
        val objetos = if (arr != null) (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        else JsonExtract.objectsInArray(text, "lugares").ifEmpty { JsonExtract.objectsInArray(text, null) }
        return objetos.mapNotNull { o ->
            val nombre = o.optString("nombre", o.optString("name", "")).trim()
            if (nombre.isBlank()) return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN).takeIf { !it.isNaN() && it != 0.0 }
            val lng = o.optDouble("lng", o.optDouble("lon", Double.NaN)).takeIf { !it.isNaN() && it != 0.0 }
            val fuentes = o.optJSONArray("fuentes")?.let { f ->
                (0 until f.length()).map { f.optString(it, "") }.filter { it.startsWith("http") }
                    .map { PlaceSource(SourcePlatform.fromUrl(it), it) }
            } ?: emptyList()
            Pista(
                nombre = nombre,
                zona = o.optString("zona", o.optString("city", "")).trim(),
                categoria = o.optString("categoria", o.optString("category", "")).trim(),
                porque = o.optString("porque", o.optString("why", "")).trim(),
                menciones = o.optInt("menciones", o.optInt("social_mentions", 0)),
                lat = lat, lng = lng, fuentes = fuentes
            )
        }.distinctBy { it.nombre.lowercase() }
    }

    // ======================================================================
    //  Paso 2: fichas completas (sin herramientas, en lotes y en paralelo)
    // ======================================================================

    /**
     * Convierte los candidatos en fichas completas. Va en lotes de 5 para que ninguna
     * respuesta se corte, y cada lote es una llamada JSON estricta (sin búsqueda web),
     * que es la forma más fiable de que el modelo devuelva un JSON entero.
     */
    suspend fun fichas(pistas: List<Pista>, request: RadarRequest): List<Place> = coroutineScope {
        if (pistas.isEmpty()) return@coroutineScope emptyList()
        pistas.chunked(5).map { lote ->
            async { fichasDeLote(lote, request) }
        }.flatMap { it.await() }
    }

    private suspend fun fichasDeLote(lote: List<Pista>, request: RadarRequest): List<Place> {
        val lista = lote.joinToString("\n") { p ->
            "- " + p.nombre +
                (if (p.zona.isNotBlank()) " (" + p.zona + ")" else "") +
                (if (p.lat != null && p.lng != null) " [lat " + p.lat + ", lng " + p.lng + "]" else "") +
                (if (p.porque.isNotBlank()) " — " + p.porque else "")
        }
        val prompt = buildString {
            if (request.onlyDestination) {
                appendLine("Arma la ficha completa de estos planes que están alrededor de " +
                    request.destinationName.trim() + " (Ecuador):")
            } else {
                appendLine("Arma la ficha completa de estas paradas del trayecto " + request.originName + " → " + request.destinationName + " (Ecuador):")
            }
            appendLine(lista)
            appendLine()
            appendLine("Respeta EXACTAMENTE estos nombres. Si ya te doy coordenadas, úsalas salvo que sepas que están mal.")
            appendLine("Rellena costos en USD, horario, cómo llegar (vía, kilómetro, desvío, bus que deja ahí) y contactos que conozcas.")
            appendLine("Lo que no sepas va vacío o en 0: no inventes teléfonos ni URLs.")
            appendLine()
            appendLine("Devuelve ÚNICAMENTE este JSON:")
            appendLine("{ \"places\": [ " + PlaceJsonParser.SCHEMA + " ] }")
        }
        val r = runCatching {
            gemini.generate(
                prompt = prompt,
                systemInstruction = systemPrompt,
                jsonOnly = true,
                temperature = 0.3,
                maxOutputTokens = 24576
            )
        }.getOrNull() ?: return lote.map { placeDePista(it) }

        val fichas = extractPlaces(r.text)
        if (fichas.isEmpty()) return lote.map { placeDePista(it) }

        // Cada ficha se cruza con su pista para no perder fuentes, menciones ni coordenadas.
        return lote.map { pista ->
            val ficha = fichas.firstOrNull { it.name.equals(pista.nombre, ignoreCase = true) }
                ?: fichas.firstOrNull { it.name.contains(pista.nombre, true) || pista.nombre.contains(it.name, true) }
            if (ficha == null) placeDePista(pista) else completarConPista(ficha, pista)
        }
    }

    /** Ficha mínima cuando la segunda pasada falla: mejor una parada con nombre que ninguna. */
    private fun placeDePista(p: Pista): Place {
        val lat = p.lat ?: 0.0
        val lng = p.lng ?: 0.0
        val valida = PlaceJsonParser.isInEcuador(lat, lng)
        return Place(
            name = p.nombre,
            category = PlaceCategory.fromKeyword(p.categoria),
            region = if (valida) EcuadorRegion.fromCoordinates(lat, lng) else EcuadorRegion.fromKeyword(p.zona),
            province = p.zona,
            city = p.zona,
            latitude = if (valida) lat else 0.0,
            longitude = if (valida) lng else 0.0,
            shortDescription = p.porque,
            sources = p.fuentes,
            socialMentions = p.menciones,
            verification = if (p.fuentes.isNotEmpty()) VerificationLevel.VERIFIED else VerificationLevel.AI_SUGGESTED
        )
    }

    private fun completarConPista(ficha: Place, p: Pista): Place {
        val lat = if (PlaceJsonParser.isInEcuador(ficha.latitude, ficha.longitude)) ficha.latitude else (p.lat ?: 0.0)
        val lng = if (PlaceJsonParser.isInEcuador(ficha.latitude, ficha.longitude)) ficha.longitude else (p.lng ?: 0.0)
        return ficha.copy(
            latitude = lat,
            longitude = lng,
            region = if (PlaceJsonParser.isInEcuador(lat, lng)) EcuadorRegion.fromCoordinates(lat, lng) else ficha.region,
            shortDescription = ficha.shortDescription.ifBlank { p.porque },
            province = ficha.province.ifBlank { p.zona },
            city = ficha.city.ifBlank { p.zona },
            sources = (ficha.sources + p.fuentes).distinctBy { it.url },
            socialMentions = maxOf(ficha.socialMentions, p.menciones),
            verification = if ((ficha.sources + p.fuentes).isNotEmpty()) VerificationLevel.VERIFIED else ficha.verification
        )
    }

    // ======================================================================
    //  Descubrimiento completo (lo que usa la pestaña Radar)
    // ======================================================================

    suspend fun discover(
        request: RadarRequest,
        corridor: List<Pair<Double, Double>> = emptyList(),
        maxPlaces: Int = 14,
        existingPlaces: List<Place> = emptyList(),
        /** Si el viaje no tiene origen, la zona de búsqueda alrededor del destino. */
        area: SearchArea? = null,
        onProgress: (String) -> Unit = {}
    ): RadarResult {
        if (!gemini.isAvailable) {
            return RadarResult(emptyList(), "Configura GEMINI_API_KEY en el archivo .env para activar el Radar IA en vivo.", emptyList())
        }

        onProgress(if (area != null) "Buscando planes alrededor de " + area.name + "…" else "Rastreando TikTok, Instagram, Facebook, YouTube y web…")
        val rastreo = rastrear(request, maxPlaces)
        if (rastreo.pistas.isEmpty()) {
            return RadarResult(emptyList(), rastreo.problema.ifBlank { "No se encontraron paradas para este tramo." }, rastreo.fuentes, area = area)
        }

        onProgress("Armando la ficha de " + rastreo.pistas.size + " paradas…")
        val parsed = fichas(rastreo.pistas, request)
        if (parsed.isEmpty()) {
            return RadarResult(emptyList(), "La IA encontró los lugares pero no pudo armar sus fichas. Vuelve a intentarlo.", rastreo.fuentes, area = area)
        }

        onProgress("Ubicándolas en el mapa…")
        val located = ubicar(parsed)

        // Filtro geográfico:
        //  - con destino únicamente, distancia al centro de la zona (no a una vía que no existe);
        //  - con ruta, desvío respecto al corredor por carretera.
        val filtered = when {
            area != null -> {
                // Solo lo que está dentro del radio. Antes, si la IA devolvía poco, se
                // colaban lugares de todo el país y el viajero terminaba viendo playas a
                // 400 km de su destino. Mejor no mostrar nada y decirlo.
                located.filter {
                    RoutingService.haversineKm(area.latitude, area.longitude, it.latitude, it.longitude) <= area.radiusKm + 5
                }.sortedBy { RoutingService.haversineKm(area.latitude, area.longitude, it.latitude, it.longitude) }
            }
            corridor.size >= 2 -> located.filter {
                RoutingService.distanceToPathKm(it.latitude, it.longitude, corridor) <= request.maxDetourKm + 5
            }.ifEmpty { located }
            else -> located
        }

        // Fusión con lugares ya conocidos solo si queda algo dentro de la zona.
        if (filtered.isEmpty() && area != null) {
            return RadarResult(
                emptyList(),
                "No encontré lugares a menos de " + area.radiusKm.toInt() + " km de " + area.name +
                    ". Prueba a ampliar el radio o escribe otro destino.",
                rastreo.fuentes,
                area = area
            )
        }

        // Fusionar con lugares ya conocidos (mismo nombre o < 300 m) para no duplicar
        val merged = filtered.map { fresh ->
            val known = existingPlaces.firstOrNull { k ->
                k.name.equals(fresh.name, ignoreCase = true) ||
                    RoutingService.haversineKm(k.latitude, k.longitude, fresh.latitude, fresh.longitude) < 0.3
            }
            if (known == null) fresh else mergeInto(known, fresh)
        }.distinctBy { it.id }

        onProgress("Buscando fotos, contactos y videos…")
        val enriched = enriquecer(merged)

        // Cuánto hay que desviarse para llegar a cada parada. En modo "solo destino" es la
        // distancia al centro de la zona; con ruta, el desvío respecto a la vía.
        val desvios = when {
            area != null -> enriched.associate { p ->
                p.id to RoutingService.haversineKm(area.latitude, area.longitude, p.latitude, p.longitude)
            }
            corridor.size >= 2 -> enriched.associate { p ->
                p.id to RoutingService.distanceToPathKm(p.latitude, p.longitude, corridor)
            }
            else -> emptyMap()
        }

        return RadarResult(
            places = enriched.sortedWith(
                compareByDescending<Place> { it.socialMentions + it.sources.size * 2 }
                    .thenBy { desvios[it.id] ?: Double.MAX_VALUE }
            ),
            summary = rastreo.resumen,
            groundingSources = rastreo.fuentes,
            detourKmByPlace = desvios,
            corridor = corridor,
            area = area
        )
    }

    /** Geocodifica los que vinieron sin coordenadas (máximo 6 para respetar Nominatim). */
    suspend fun ubicar(lugares: List<Place>): List<Place> {
        var presupuesto = 6
        return lugares.map { place ->
            if (PlaceJsonParser.isInEcuador(place.latitude, place.longitude) || presupuesto <= 0) place
            else {
                presupuesto--
                val hit = runCatching {
                    geocoding.search(place.name + " " + place.city.ifBlank { place.province }).firstOrNull()
                }.getOrNull()
                if (hit != null) place.copy(
                    latitude = hit.latitude, longitude = hit.longitude,
                    province = place.province.ifBlank { hit.province }, city = place.city.ifBlank { hit.city },
                    region = EcuadorRegion.fromCoordinates(hit.latitude, hit.longitude)
                ) else place
            }
        }.filter { PlaceJsonParser.isInEcuador(it.latitude, it.longitude) }
    }

    /** Enriquece en paralelo con Google Places y YouTube, con presupuesto limitado. */
    suspend fun enriquecer(lugares: List<Place>): List<Place> = coroutineScope {
        lugares.mapIndexed { idx, place ->
            async {
                var p = place
                if (places.isAvailable && idx < 8) p = runCatching { places.enrich(p) }.getOrDefault(p)
                if (youtube.isAvailable && idx < 5) {
                    val vids = runCatching {
                        youtube.searchVideos(p.name + " " + p.city.ifBlank { p.province }, 3)
                    }.getOrDefault(emptyList())
                    if (vids.isNotEmpty()) p = p.copy(
                        sources = (p.sources + vids).distinctBy { it.url },
                        socialMentions = p.socialMentions + vids.size,
                        imageUrls = p.imageUrls.ifEmpty { vids.mapNotNull { v -> v.thumbnailUrl.ifBlank { null } }.take(1) },
                        verification = if (p.verification == VerificationLevel.AI_SUGGESTED) VerificationLevel.VERIFIED else p.verification
                    )
                }
                p
            }
        }.map { it.await() }
    }

    /** Une la información nueva de la IA sobre un lugar ya existente sin perder datos curados. */
    private fun mergeInto(known: Place, fresh: Place): Place = known.copy(
        shortDescription = known.shortDescription.ifBlank { fresh.shortDescription },
        fullDescription = known.fullDescription.ifBlank { fresh.fullDescription },
        costMinUsd = if (known.costMinUsd == 0.0 && known.costMaxUsd == 0.0) fresh.costMinUsd else known.costMinUsd,
        costMaxUsd = if (known.costMinUsd == 0.0 && known.costMaxUsd == 0.0) fresh.costMaxUsd else known.costMaxUsd,
        costNotes = known.costNotes.ifBlank { fresh.costNotes },
        openingHours = known.openingHours.ifBlank { fresh.openingHours },
        howToGetThere = known.howToGetThere.ifBlank { fresh.howToGetThere },
        transportOptions = (known.transportOptions + fresh.transportOptions).distinct(),
        tags = (known.tags + fresh.tags).distinct().take(8),
        localTips = (known.localTips + fresh.localTips).distinct().take(6),
        contact = PlaceContact(
            phone = known.contact.phone.ifBlank { fresh.contact.phone },
            whatsapp = known.contact.whatsapp.ifBlank { fresh.contact.whatsapp },
            website = known.contact.website.ifBlank { fresh.contact.website },
            instagram = known.contact.instagram.ifBlank { fresh.contact.instagram },
            tiktok = known.contact.tiktok.ifBlank { fresh.contact.tiktok },
            facebook = known.contact.facebook.ifBlank { fresh.contact.facebook },
            email = known.contact.email.ifBlank { fresh.contact.email }
        ),
        sources = (known.sources + fresh.sources).distinctBy { it.url },
        socialMentions = maxOf(known.socialMentions, fresh.socialMentions),
        imageUrls = (known.imageUrls + fresh.imageUrls).distinct()
    )

    /** Saca la lista de lugares del texto del modelo, venga como objeto o como array suelto. */
    private fun extractPlaces(text: String): List<Place> {
        if (text.isBlank()) return emptyList()
        val root = JsonExtract.firstObject(text)
        val arr = root?.optJSONArray("places") ?: JsonExtract.firstArray(text)
        val objetos: List<JSONObject> = if (arr != null) {
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } else {
            // La respuesta se cortó: rescatamos los lugares que sí llegaron completos.
            JsonExtract.objectsInArray(text, "places").ifEmpty { JsonExtract.objectsInArray(text, null) }
        }
        return objetos.mapNotNull { PlaceJsonParser.parse(it) }
    }

    /**
     * Explica en cristiano por qué no salió nada. IMPORTANTE: si llegamos aquí es porque el
     * servidor de Gemini SÍ contestó, así que nunca se culpa a la conexión del teléfono.
     */
    private fun sinResultados(result: GeminiClient.Result): String = when {
        result.blocked ->
            "La IA bloqueó esta consulta por sus filtros de seguridad. Prueba a reformular lo que escribiste en \"Algo más\"."
        result.truncated ->
            "La respuesta de la IA se cortó por longitud. Prueba con menos intereses o con un tramo más corto."
        result.isEmpty ->
            "La IA contestó en blanco (no es tu conexión: el servidor sí respondió). Suele arreglarse volviendo a tocar el botón."
        else ->
            "No se encontraron paradas para este tramo. Prueba con otros intereses, amplía el desvío máximo o escribe origen y destino de forma más conocida (por ejemplo \"Quito\" y \"Baños de Agua Santa\")."
    }

    private fun esErrorDeRed(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        return m.contains("Unable to resolve host") || m.contains("timeout", true) ||
            m.contains("Failed to connect") || m.contains("No address associated")
    }

    private fun errorLegible(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            esErrorDeRed(e) -> "Sin conexión a internet: el Radar no pudo buscar. Tus lugares guardados siguen disponibles."
            m.contains("API_KEY_INVALID") -> "La clave de Gemini no es válida. Genera una nueva en aistudio.google.com/apikey."
            m.contains("HTTP 429") -> "Cuota de la IA agotada por ahora. Intenta en unos minutos."
            m.contains("HTTP 403") -> "La clave existe pero no tiene habilitada la API de Gemini."
            else -> "Falló la consulta a la IA: " + m.take(140)
        }
    }

    /**
     * Ubica un nombre del Ecuador (catálogo del teléfono → Nominatim). Devuelve null si no se
     * puede o si cae fuera del país, para no armar búsquedas en el lugar equivocado.
     */
    suspend fun locate(name: String): Pair<Double, Double>? {
        val limpio = name.trim()
        if (limpio.length < 2) return null
        val hit = runCatching { geocoding.search(limpio, limit = 1).firstOrNull() }.getOrNull() ?: return null
        if (!PlaceJsonParser.isInEcuador(hit.latitude, hit.longitude)) return null
        return Pair(hit.latitude, hit.longitude)
    }

    /**
     * Zona de búsqueda para el modo "solo destino". El radio sale del desvío que eligió el
     * viajero en el control (reinterpretado como "hasta dónde estoy dispuesto a moverme desde
     * el destino"), acotado para que no devuelva medio país ni una manzana.
     */
    suspend fun areaFor(request: RadarRequest, radiusKm: Int = request.maxDetourKm): SearchArea? {
        if (!request.onlyDestination) return null
        val centro = locate(request.destinationName) ?: return null
        return SearchArea(
            name = request.destinationName.trim(),
            latitude = centro.first,
            longitude = centro.second,
            // 25 km es el máximo que el explorador de OpenStreetMap puede cubrir de verdad
            // alrededor de un punto; prometer más en el mapa sería mentir.
            radiusKm = radiusKm.coerceIn(5, 25).toDouble()
        )
    }

    /**
     * Geocodifica origen/destino/waypoints y calcula el corredor por carretera (para filtrar y dibujar).
     * Si no hay origen, devuelve solo el centro del destino: no hay carretera que trazar.
     */
    suspend fun buildCorridor(request: RadarRequest): List<Pair<Double, Double>> {
        if (request.onlyDestination) {
            return locate(request.destinationName)?.let { listOf(it) } ?: emptyList()
        }
        val names = listOf(request.originName) + request.waypoints + listOf(request.destinationName)
        val points = names.mapNotNull { n -> runCatching { geocoding.search(n).firstOrNull() }.getOrNull()?.let { Pair(it.latitude, it.longitude) } }
        if (points.size < 2) return points
        val fakeStops = points.mapIndexed { i, p ->
            RouteStop(id = "c$i", routeId = "corridor", placeId = "", placeName = names.getOrElse(i) { "" }, latitude = p.first, longitude = p.second)
        }
        val road = routing.routeBetween(fakeStops)
        return road.legs.flatMap { it.geometry }.ifEmpty { points }
    }
}

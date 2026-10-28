package ec.travesias.app.data.repository

import ec.travesias.app.data.ai.GeminiTravelService
import ec.travesias.app.data.local.*
import ec.travesias.app.data.model.*
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.remote.PlacesService
import ec.travesias.app.data.remote.RadarService
import ec.travesias.app.data.remote.RouteScoutService
import ec.travesias.app.data.remote.RoutingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONArray

/**
 * Repositorio central de lugares, rutas y paradas. Todo se persiste en Room (offline-first).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteRepository(
    private val database: AppDatabase,
    private val radar: RadarService,
    private val routing: RoutingService,
    private val ai: GeminiTravelService,
    private val placesApi: PlacesService,
    private val scout: RouteScoutService
) {
    private val placeDao = database.placeDao()
    private val routeDao = database.routeDao()
    private val stopDao = database.routeStopDao()
    private val radarCacheDao = database.radarCacheDao()

    // ---------------- Lugares ----------------

    val allPlaces: Flow<List<Place>> = placeDao.getAllPlaces().map { list -> list.map { it.toDomain() } }
    val savedPlaces: Flow<List<Place>> = placeDao.getSavedPlaces().map { list -> list.map { it.toDomain() } }

    suspend fun getPlace(id: String): Place? = placeDao.getById(id)?.toDomain()
    suspend fun getPlaces(ids: List<String>): List<Place> = if (ids.isEmpty()) emptyList() else placeDao.getByIds(ids).map { it.toDomain() }
    suspend fun allPlacesOnce(): List<Place> = placeDao.getAllPlaces().first().map { it.toDomain() }

    /**
     * Guarda un lugar. Con [share] = true además lo pone en la cola para compartirlo con el
     * resto de viajeros (lo que uno descubre le sirve a todos). Lo personal —que lo tengas
     * guardado, tus fotos— nunca sale: eso lo limpia [ec.travesias.app.data.social.CatalogSync].
     */
    suspend fun upsertPlace(place: Place, share: Boolean = false) {
        placeDao.upsert(place.toEntity())
        if (share) runCatching { catalog?.queue(place) }
    }

    suspend fun upsertPlaces(places: List<Place>, share: Boolean = false) {
        placeDao.upsertAll(places.map { it.toEntity() })
        if (share) runCatching { catalog?.queueAll(places) }
    }

    /** Conector del catálogo común. Lo pone AppContainer al arrancar. */
    var catalog: ec.travesias.app.data.social.CatalogSync? = null

    /** Ubica origen y destino de la ruta; lo inyecta AppContainer (se crea después). */
    var endpoints: RouteEndpointsResolver? = null

    /** Extremos ya ubicados de una ruta (vacío si no se pueden ubicar). */
    suspend fun endpointsFor(route: TravelRoute?): RouteSchedule.Endpoints =
        runCatching { endpoints?.resolve(route) }.getOrNull() ?: RouteSchedule.Endpoints.NONE
    suspend fun setSaved(id: String, saved: Boolean) = placeDao.setSaved(id, saved)
    suspend fun deletePlace(id: String) = placeDao.deleteById(id)

    /** Completa un lugar con Google Places (si hay clave) y lo guarda. */
    suspend fun enrichPlace(place: Place): Place {
        val enriched = placesApi.enrich(place)
        if (enriched != place) upsertPlace(enriched)
        return enriched
    }

    // ---------------- Rutas ----------------

    val allRoutes: Flow<List<TravelRoute>> = routeDao.getAllRoutes().map { list -> list.map { it.toDomain() } }

    /** Ruta activa con sus paradas, reactiva a cualquier cambio. */
    val activeRoute: Flow<TravelRoute?> = routeDao.getActiveRoute().flatMapLatest { entity ->
        if (entity == null) flowOf(null)
        else stopDao.getStopsForRoute(entity.id).map { stops -> entity.toDomain(stops.map { it.toDomain() }) }
    }

    suspend fun createRoute(route: TravelRoute, makeActive: Boolean = true): TravelRoute {
        routeDao.upsert(route.toEntity())
        if (makeActive) routeDao.setActive(route.id)
        return route
    }

    /** Foto puntual de la ruta activa con sus paradas (para el agente y los exportadores). */
    suspend fun activeRouteOnce(): TravelRoute? = routeDao.getActiveRoute().first()?.let { e ->
        e.toDomain(stopDao.getStopsForRouteOnce(e.id).map { it.toDomain() })
    }

    suspend fun updateRoute(route: TravelRoute) = routeDao.upsert(route.toEntity())
    suspend fun setActiveRoute(id: String) = routeDao.setActive(id)
    suspend fun getRoute(id: String): TravelRoute? = routeDao.getById(id)?.let { e ->
        e.toDomain(stopDao.getStopsForRouteOnce(e.id).map { it.toDomain() })
    }

    suspend fun deleteRoute(id: String) {
        stopDao.deleteForRoute(id)
        routeDao.deleteById(id)
    }

    /** Garantiza que exista una ruta activa; si no, crea "Mi primera travesía". */
    suspend fun ensureActiveRoute(): TravelRoute {
        val current = routeDao.getActiveRoute().first()
        if (current != null) return current.toDomain(stopDao.getStopsForRouteOnce(current.id).map { it.toDomain() })
        return createRoute(TravelRoute(name = "Mi travesía por Ecuador", totalDays = 3, travelers = 2))
    }

    // ---------------- Paradas ----------------

    suspend fun addStop(place: Place, day: Int = 1, routeId: String? = null): RouteStop? {
        val route = routeId?.let { routeDao.getById(it)?.toDomain() } ?: ensureActiveRoute()
        if (stopDao.countPlaceInRoute(route.id, place.id) > 0) return null
        // Aseguramos que el lugar esté persistido
        if (placeDao.getById(place.id) == null) placeDao.upsert(place.toEntity())
        val d = day.coerceIn(1, maxOf(1, route.totalDays))
        val stop = RouteStop(
            routeId = route.id,
            placeId = place.id,
            placeName = place.name,
            latitude = place.latitude,
            longitude = place.longitude,
            dayNumber = d,
            orderInDay = stopDao.nextOrderForDay(route.id, d),
            estimatedCostUsd = if (place.costMaxUsd > 0) (place.costMinUsd + place.costMaxUsd) / 2 else place.costMinUsd,
            durationMinutes = when (place.category) {
                PlaceCategory.FOOD -> 60
                PlaceCategory.VIEWPOINT, PlaceCategory.SERVICES -> 30
                // Antes eran 720 min (12 h): el hospedaje "comía" el día entero y todo salía
                // como "día muy cargado". Aquí cuenta el registro; la noche ya se muestra aparte.
                PlaceCategory.LODGING -> 30
                PlaceCategory.NATURE, PlaceCategory.ADVENTURE -> 180
                else -> 90
            }
        )
        stopDao.upsert(stop.toEntity())
        return stop
    }

    suspend fun updateStop(stop: RouteStop) = stopDao.upsert(stop.toEntity())
    suspend fun removeStop(stopId: String) = stopDao.deleteById(stopId)

    suspend fun moveStop(stop: RouteStop, newDay: Int, newOrder: Int) {
        val all = stopDao.getStopsForRouteOnce(stop.routeId).map { it.toDomain() }.filter { it.id != stop.id }.toMutableList()
        val moved = stop.copy(dayNumber = newDay, orderInDay = newOrder)
        val dayList = all.filter { it.dayNumber == newDay }.sortedBy { it.orderInDay }.toMutableList()
        dayList.add(newOrder.coerceIn(0, dayList.size), moved)
        val rest = all.filter { it.dayNumber != newDay }
        val renumbered = rest + dayList.mapIndexed { i, s -> s.copy(orderInDay = i) }
        stopDao.upsertAll(renumbered.map { it.toEntity() })
    }

    suspend fun replaceStops(routeId: String, stops: List<RouteStop>) {
        stopDao.deleteForRoute(routeId)
        stopDao.upsertAll(stops.map { it.copy(routeId = routeId).toEntity() })
    }

    /**
     * Ordena y reparte las paradas de la ruta activa.
     *
     * 1. Calcula SIEMPRE un orden propio sin internet ([RouteOptimizer]): sale del origen real,
     *    quita el "ir y volver" y reparte los días equilibrando horas, no número de paradas.
     * 2. Si hay IA, le pide su propuesta (puede saber de horarios de apertura) y SOLO la acepta
     *    si no alarga el recorrido más de un 10 % ni deja un día bastante más cargado.
     */
    suspend fun optimizeActiveRoute(): Boolean {
        val route = routeDao.getActiveRoute().first()?.let { e -> e.toDomain(stopDao.getStopsForRouteOnce(e.id).map { it.toDomain() }) } ?: return false
        // La salida y la llegada no son paradas que ordenar: se apartan antes de optimizar y
        // se vuelven a poner en los extremos. Antes el optimizador las movía como una parada
        // más y la ruta podía terminar "saliendo" desde el destino.
        val visitas = route.journeyStops()
        if (visitas.size < 2) return false
        val ordenadas = route.stopsInOrder()
        val anclaSalida = ordenadas.firstOrNull()?.takeIf {
            route.originName.isNotBlank() && it.placeName.equals(route.originName.trim(), true)
        }
        val anclaLlegada = ordenadas.lastOrNull()?.takeIf {
            route.destinationName.isNotBlank() && it.placeName.equals(route.destinationName.trim(), true)
        }

        val ends = endpointsFor(route)
        val local = RouteOptimizer.optimize(route, visitas, ends)

        val places = getPlaces(visitas.map { it.placeId }).associateBy { it.id }
        val deLaIa = runCatching { ai.optimizeStops(route, visitas, places, ends) }.getOrNull()
        val elegido = if (deLaIa != null && deLaIa.size == visitas.size) {
            val kmIa = RouteOptimizer.pathKm(deLaIa, ends.origin, ends.finish)
            val maxIa = RouteOptimizer.maxDayMinutes(route, deLaIa, ends.origin)
            val usaTodosLosDias = deLaIa.map { it.dayNumber }.toSet().size >= minOf(route.totalDays, visitas.size)
            if (kmIa <= local.totalKm * 1.10 && maxIa <= maxOf(local.maxDayMinutes * 1.2, 600.0) && usaTodosLosDias) deLaIa
            else local.stops
        } else local.stops

        val ultimoDiaUsado = elegido.maxOfOrNull { it.dayNumber } ?: 1
        val conExtremos = ArrayList<RouteStop>(elegido.size + 2)
        anclaSalida?.let { conExtremos.add(it.copy(dayNumber = 1, orderInDay = -1)) }
        conExtremos.addAll(elegido)
        anclaLlegada?.let { conExtremos.add(it.copy(dayNumber = ultimoDiaUsado, orderInDay = 999)) }

        replaceStops(route.id, renumerar(conExtremos))
        return true
    }

    /** Deja los órdenes de cada día como 0, 1, 2… sin huecos ni repetidos. */
    private fun renumerar(stops: List<RouteStop>): List<RouteStop> =
        stops.groupBy { it.dayNumber }.flatMap { (_, delDia) ->
            delDia.sortedBy { it.orderInDay }.mapIndexed { i, s -> s.copy(orderInDay = i) }
        }

    suspend fun roadRouteFor(stops: List<RouteStop>): RoutingService.RoadRoute = routing.routeBetween(stops)

    // ---------------- Radar IA ----------------

    /**
     * @param corridor el corredor ya trazado por quien llama, para no volver a pedirlo
     *   a Nominatim y OSRM (el ViewModel ya lo necesita para pintarlo en el mapa).
     */
    suspend fun radarDiscover(
        request: RadarRequest,
        useCache: Boolean = true,
        corridor: List<Pair<Double, Double>>? = null,
        /** Zona de búsqueda cuando no hay origen (modo "solo destino"). */
        area: SearchArea? = null,
        onProgress: (String) -> Unit = {}
    ): RadarResult {
        val key = cacheKey(request) + if (area != null) "#area" + area.radiusKm.toInt() else ""
        val via = corridor ?: runCatching { radar.buildCorridor(request) }.getOrDefault(emptyList())
        if (useCache) {
            radarCacheDao.get(key)?.let { cached ->
                if (System.currentTimeMillis() - cached.createdAt < 1000L * 60 * 60 * 24 * 7) {
                    val ids = JsonCodec.parseStringList(cached.placeIdsJson)
                    val places = getPlaces(ids)
                    if (places.isNotEmpty()) {
                        // Los desvíos no se guardan en caché: se recalculan contra el corredor
                        // actual o contra el centro de la zona, que es barato una vez trazado.
                        val desvios = when {
                            area != null -> places.associate { p ->
                                p.id to RoutingService.haversineKm(area.latitude, area.longitude, p.latitude, p.longitude)
                            }
                            via.size >= 2 -> places.associate { p ->
                                p.id to RoutingService.distanceToPathKm(p.latitude, p.longitude, via)
                            }
                            else -> emptyMap()
                        }
                        return RadarResult(
                            places, cached.summary, JsonCodec.parseSources(cached.groundingSourcesJson),
                            fromCache = true, detourKmByPlace = desvios, corridor = via, area = area
                        )
                    }
                }
            }
        }
        val existing = allPlacesOnce()
        val result = radar.discover(request, via, existingPlaces = existing, area = area, onProgress = onProgress)
        if (result.places.isNotEmpty()) {
            upsertPlaces(result.places, share = true)
            radarCacheDao.put(
                RadarCacheEntity(
                    queryKey = key,
                    summary = result.summary,
                    placeIdsJson = JsonCodec.stringList(result.places.map { it.id }),
                    groundingSourcesJson = JsonCodec.sources(result.groundingSources),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return result.copy(places = result.places)
    }

    // ======================================================================
    //  Explorador de ruta (OpenStreetMap + catálogo). No necesita IA.
    // ======================================================================

    /**
     * Barre el camino pueblo por pueblo y devuelve todo lo que hay alrededor de la vía.
     *
     * Los lugares que salen de OpenStreetMap se guardan en ESTE teléfono para poder ponerlos en
     * la ruta y verlos en el mapa, pero NO se suben al catálogo común: son miles y ya están en
     * OSM para quien los quiera. Al catálogo común solo sube lo que aporta una persona.
     */
    suspend fun exploreRoute(
        request: RadarRequest,
        corridor: List<Pair<Double, Double>>? = null,
        onProgress: (String) -> Unit = {}
    ): RouteScoutService.Exploracion {
        val via = corridor ?: runCatching { radar.buildCorridor(request) }.getOrDefault(emptyList())
        val exploracion = scout.explorar(request, via, catalogo = allPlacesOnce(), onProgress = onProgress)
        val nuevos = exploracion.lugares.filter { it.id.startsWith("osm-") }
        if (nuevos.isNotEmpty()) upsertPlaces(nuevos, share = false)
        return exploracion
    }

    suspend fun radarCorridor(request: RadarRequest): List<Pair<Double, Double>> =
        runCatching { radar.buildCorridor(request) }.getOrDefault(emptyList())

    /**
     * Zona de búsqueda cuando el viajero no indicó salida. Devuelve null en modo ruta (hay
     * corredor) o si el destino no se pudo ubicar. El mapa la usa para dibujar el radio.
     *
     * Si OpenStreetMap no responde, se intenta el nomenclátor del teléfono y el catálogo: el
     * modo "solo destino" tiene que funcionar también sin señal.
     */
    suspend fun radarArea(request: RadarRequest): SearchArea? {
        if (!request.onlyDestination) return null
        runCatching { radar.areaFor(request) }.getOrNull()?.let { return it }
        val punto = runCatching {
            endpointsFor(
                TravelRoute(
                    name = "Área de búsqueda",
                    originName = "",
                    destinationName = request.destinationName
                )
            ).finish
        }.getOrNull() ?: return null
        return SearchArea(
            name = request.destinationName.trim(),
            latitude = punto.latitude,
            longitude = punto.longitude,
            radiusKm = request.maxDetourKm.coerceIn(5, 25).toDouble()
        )
    }

    private fun cacheKey(r: RadarRequest): String =
        listOf(r.originName, r.destinationName, r.waypoints.joinToString("|"), r.interests.sorted().joinToString("|"), r.maxDetourKm, r.travelStyle, r.freeText)
            .joinToString("#").lowercase().trim()

    /** Exporta la ruta activa como texto para compartir por WhatsApp, etc. */
    suspend fun exportActiveRouteAsText(): String {
        val route = routeDao.getActiveRoute().first()?.let { e -> e.toDomain(stopDao.getStopsForRouteOnce(e.id).map { it.toDomain() }) }
            ?: return "Aún no tienes una ruta activa en Travesías en Ecuador."
        val places = getPlaces(route.stops.map { it.placeId }).associateBy { it.id }
        val sb = StringBuilder()
        sb.appendLine("🇪🇨 ${route.name}")
        if (route.originName.isNotBlank() || route.destinationName.isNotBlank()) {
            // Ida y vuelta desde la misma base (el modo "solo destino" se guarda así): se dice
            // claro en vez de imprimir "Baños → Baños".
            if (route.originName.isNotBlank() && route.originName.equals(route.destinationName, true)) {
                sb.appendLine("Ida y vuelta desde ${route.originName}")
            } else {
                sb.appendLine("${route.originName} → ${route.destinationName}")
            }
        }
        sb.appendLine("${route.totalDays} día(s) · ${route.travelers} viajero(s) · estilo ${route.travelStyle}")
        sb.appendLine("Transporte: ${route.transportMode.displayName} · se sale cada día a las ${route.dailyStartTime}")
        // Horario real con tiempos de carretera, contando la salida desde el origen y el regreso.
        val ends = endpointsFor(route)
        val road = runCatching { roadRouteFor(RouteSchedule.roadSequence(route, ends)) }.getOrNull()
        val planes = RouteSchedule.build(route, road, ends)
        val agenda = planes.associateBy { it.day }
        val costo = TripCostEstimator.estimate(route, planes, VehicleSettings.profile.value)
        sb.appendLine("Recorrido total: ${costo.totalKm.toInt()} km")
        sb.appendLine("Costo estimado por persona: $${TripCostEstimator.fmtUsd(costo.perPersonUsd)} " +
            "(entradas $${TripCostEstimator.fmtUsd(costo.entriesPerPersonUsd)} + transporte $${TripCostEstimator.fmtUsd(costo.transportPerPersonUsd)})")
        if (costo.fuelUsd > 0) {
            sb.appendLine("Combustible: ${TripCostEstimator.fmt1(costo.fuelUnits)} ${costo.fuelUnit} de ${costo.fuelName} ≈ $${TripCostEstimator.fmtUsd(costo.fuelUsd)}" +
                (if (costo.tollsUsd > 0) " · peajes ≈ $${TripCostEstimator.fmtUsd(costo.tollsUsd)}" else ""))
        }
        sb.appendLine()

        for (day in 1..route.totalDays) {
            val stops = route.stopsForDay(day)
            if (stops.isEmpty()) continue
            val plan = agenda[day]
            sb.append("📅 Día $day")
            RouteCoherence.dayLabel(route.startDate, day).takeIf { it.isNotBlank() }?.let { sb.append(" · $it") }
            if (plan != null) sb.append("  (${plan.start} → ${plan.end} · ${RouteSchedule.durationLabel(plan.travelMinutes)} en camino · ${plan.distanceKm.toInt()} km)")
            sb.appendLine()
            if (plan != null && plan.startPlace.isNotBlank()) sb.appendLine("  ${plan.start} Salida de ${plan.startPlace}")
            stops.forEach { s ->
                val p = places[s.placeId]
                val h = plan?.stops?.firstOrNull { it.stop.id == s.id }
                if (h != null && h.travelMinutes > 0) {
                    sb.appendLine("     ↓ ${h.travelLabel} en ${h.transport.shortName}${if (h.distanceKm >= 1) " (${h.distanceKm.toInt()} km)" else ""}")
                }
                sb.append("  • ")
                if (h != null) sb.append("${h.arrival}–${h.departure} · ")
                else if (s.plannedTime.isNotBlank()) sb.append("${s.plannedTime} · ")
                sb.append(s.placeName)
                if (s.estimatedCostUsd > 0) sb.append(" (~$${Place.formatUsd(s.estimatedCostUsd)})")
                sb.appendLine()
                p?.let {
                    if (it.howToGetThere.isNotBlank()) sb.appendLine("     Cómo llegar: ${it.howToGetThere}")
                    sb.appendLine("     Mapa: https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}")
                }
            }
            plan?.finishLeg?.let { l ->
                sb.appendLine("     ↓ ${l.travelLabel} en ${l.transport.shortName} (${l.distanceKm.toInt()} km)")
                sb.appendLine("  ${l.arrival} ${if (l.isReturnHome) "De vuelta en" else "Llegada a"} ${l.toName}")
            }
            plan?.overnight?.let { sb.appendLine("  🛏 Noche en $it") }
            sb.appendLine()
        }
        sb.appendLine("Creado con Travesías en Ecuador")
        return sb.toString()
    }

    /** Serializa la ruta activa para compartirla con un grupo (Comunidad). */
    suspend fun exportActiveRouteAsJson(): String? {
        val route = routeDao.getActiveRoute().first()?.let { e -> e.toDomain(stopDao.getStopsForRouteOnce(e.id).map { it.toDomain() }) } ?: return null
        val places = getPlaces(route.stops.map { it.placeId })
        val arr = JSONArray()
        places.forEach { p -> arr.put(org.json.JSONObject(JsonCodecPlace.encode(p))) }
        return org.json.JSONObject().apply {
            put("name", route.name); put("origin", route.originName); put("destination", route.destinationName)
            put("days", route.totalDays); put("travelers", route.travelers); put("style", route.travelStyle)
            // Antes se perdían al compartir: el compañero recibía la ruta "en bus" y a las 08:00.
            put("transport", route.transportMode.name); put("startTime", route.dailyStartTime); put("startDate", route.startDate)
            put("stops", JSONArray().apply {
                route.stops.forEach { s ->
                    put(org.json.JSONObject().apply {
                        put("placeId", s.placeId); put("day", s.dayNumber); put("order", s.orderInDay); put("time", s.plannedTime)
                        put("cost", s.estimatedCostUsd); put("duration", s.durationMinutes); put("notes", s.notes)
                        put("locked", s.timeLocked); s.transportMode?.let { put("transport", it.name) }
                    })
                }
            })
            put("places", arr)
        }.toString()
    }

    /** Importa una ruta compartida por un compañero (crea copia local y la activa). */
    suspend fun importRouteFromJson(json: String, sharedBy: String = ""): TravelRoute? {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val placesArr = o.optJSONArray("places") ?: JSONArray()
        val importedPlaces = (0 until placesArr.length()).mapNotNull { i ->
            placesArr.optJSONObject(i)?.let { JsonCodecPlace.decode(it.toString()) }
        }.map { it.copy(verification = VerificationLevel.SHARED) }
        upsertPlaces(importedPlaces)
        val stopsArr = o.optJSONArray("stops") ?: JSONArray()
        val byId = importedPlaces.associateBy { it.id }
        val stops = (0 until stopsArr.length()).mapNotNull { i ->
            val s = stopsArr.optJSONObject(i) ?: return@mapNotNull null
            val p = byId[s.optString("placeId")] ?: return@mapNotNull null
            RouteStop(
                routeId = "", placeId = p.id, placeName = p.name, latitude = p.latitude, longitude = p.longitude,
                dayNumber = s.optInt("day", 1), orderInDay = s.optInt("order", i), plannedTime = s.optString("time", ""),
                estimatedCostUsd = s.optDouble("cost", 0.0), durationMinutes = s.optInt("duration", 90), notes = s.optString("notes", ""),
                timeLocked = s.optBoolean("locked", false),
                transportMode = s.optString("transport", "").takeIf { it.isNotBlank() }?.let { TransportMode.from(it) }
            )
        }
        if (importedPlaces.isEmpty() || stops.isEmpty()) return null
        val route = TravelRoute(
            name = o.optString("name", "Ruta compartida") + if (sharedBy.isNotBlank()) " (de @$sharedBy)" else "",
            originName = o.optString("origin", ""), destinationName = o.optString("destination", ""),
            totalDays = o.optInt("days", 1).coerceIn(1, 30), travelers = o.optInt("travelers", 1).coerceIn(1, 20), travelStyle = o.optString("style", "Moderado"),
            transportMode = TransportMode.from(o.optString("transport", "")),
            dailyStartTime = o.optString("startTime", "").takeIf { RouteSchedule.parseHhmmOrNull(it) != null } ?: "08:00",
            startDate = RouteCoherence.cleanDate(o.optString("startDate", ""))
        )
        createRoute(route, makeActive = true)
        stopDao.upsertAll(stops.map { it.copy(routeId = route.id).toEntity() })
        return route
    }
}

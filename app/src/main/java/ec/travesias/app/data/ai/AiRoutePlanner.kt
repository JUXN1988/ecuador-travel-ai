package ec.travesias.app.data.ai

import ec.travesias.app.data.model.DraftStop
import ec.travesias.app.data.model.DraftStopKind
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.RadarRequest
import ec.travesias.app.data.model.RouteDraft
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.SearchArea
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.VerificationLevel
import ec.travesias.app.data.remote.GeocodingService
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.remote.RadarService
import ec.travesias.app.data.remote.RouteScoutService
import ec.travesias.app.data.remote.RoutingService
import ec.travesias.app.data.repository.PlaceIndexRepository
import ec.travesias.app.data.repository.RouteDraftMath
import ec.travesias.app.data.repository.RouteRepository
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.data.repository.RouteOptimizer
import ec.travesias.app.data.repository.normalizeSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Armador automático de rutas: "de dónde a dónde" y la IA hace el resto.
 *
 * Usa EXACTAMENTE el mismo motor que ya funciona en la Guía IA y en el Radar (Gemini con
 * búsqueda en Google, que es lo que indexa TikTok, Instagram, Facebook, YouTube, blogs y
 * prensa), pero además:
 *   1. ubica origen y destino con el nomenclátor del teléfono (funciona sin señal) y traza
 *      el corredor por carretera real con OSRM;
 *   2. mide de cada parada cuántos kilómetros hay que salirse de la vía;
 *   3. reparte las paradas por días según lo avanzado del camino;
 *   4. separa las que quedan un poco más lejos en "cerca de tu ruta", para que el viajero
 *      decida si valen el desvío.
 *
 * No guarda NADA: devuelve un [RouteDraft] que la pantalla deja editar antes de guardar.
 */
class AiRoutePlanner(
    private val radar: RadarService,
    private val routing: RoutingService,
    private val geocoding: GeocodingService,
    private val index: PlaceIndexRepository,
    private val routes: RouteRepository,
    /**
     * Explorador de OpenStreetMap: aporta los lugares reales de la vía y del destino, con
     * coordenadas exactas. La IA dice qué está de moda; el mapa dice qué existe.
     */
    private val scout: RouteScoutService
) {

    /** Paradas nuevas por día según el ritmo del viaje. */
    private fun paradasPorDia(estilo: String): Int = when {
        estilo.contains("mochil", true) -> 4
        estilo.contains("lujo", true) || estilo.contains("relax", true) -> 2
        else -> 3
    }

    suspend fun plan(
        request: RadarRequest,
        days: Int,
        travelers: Int,
        transport: TransportMode,
        onProgress: (String) -> Unit = {}
    ): RouteDraft = withContext(Dispatchers.IO) {
        val dias = days.coerceIn(1, 30)

        // ---------- 0. ¿Ruta o solo destino? ----------
        // Si el viajero no escribió de dónde sale, no hay ruta que trazar. Antes esto se caía
        // con "No pude ubicar """ y el botón moría. Ahora se arma una escapada con base en el
        // destino: se buscan lugares en un radio y se reparten por días saliendo y volviendo a
        // esa base. Es lo que la persona pidió.
        if (request.onlyDestination) {
            return@withContext planAlrededorDelDestino(request, dias, travelers, transport, onProgress)
        }

        // ---------- 1. Origen y destino ----------
        onProgress("Ubicando " + request.originName + " y " + request.destinationName + "…")
        val origen = resolver(request.originName)
            ?: throw IllegalStateException("No pude ubicar \"" + request.originName + "\". Escríbelo como sale en el mapa (por ejemplo \"Santo Domingo de los Tsáchilas\").")
        val destino = resolver(request.destinationName)
            ?: throw IllegalStateException("No pude ubicar \"" + request.destinationName + "\". Escríbelo como sale en el mapa (por ejemplo \"Baños de Agua Santa\").")

        val intermedios = request.waypoints.mapNotNull { resolver(it) }

        // Ida y vuelta sin punto intermedio: el "corredor" mide 0 km y el radar no tiene por
        // dónde buscar. Se avisa claro en vez de devolver una ruta sin sentido.
        val idaYVuelta = mismoLugar(origen, destino)
        if (idaYVuelta && intermedios.isEmpty()) {
            throw IllegalStateException(
                "Para un viaje de ida y vuelta escribe como destino el punto más lejano al que quieres llegar " +
                    "(por ejemplo \"Limoncocha\"). Al guardar, la app suma sola el regreso a " + request.originName.trim() + "."
            )
        }

        // ---------- 2. Corredor por carretera ----------
        onProgress("Trazando la vía por carretera…")
        val puntos = listOf(origen) + intermedios + listOf(destino)
        val falsas = puntos.mapIndexed { i, p ->
            RouteStop(id = "c$i", routeId = "draft", placeId = p.id, placeName = p.name, latitude = p.latitude, longitude = p.longitude)
        }
        val carretera = runCatching { routing.routeBetween(falsas) }.getOrNull()
        val corredor = carretera?.legs?.flatMap { it.geometry }?.ifEmpty { puntos.map { Pair(it.latitude, it.longitude) } }
            ?: puntos.map { Pair(it.latitude, it.longitude) }

        // ---------- 3. Rastreo doble: redes sociales + mapa real, en paralelo ----------
        // Solo con la IA salían 3 o 4 paradas y el destino quedaba vacío ("llegas a Loja y no
        // hay nada"). El explorador de OpenStreetMap aporta TODO lo que existe en la vía y en
        // el destino, con coordenadas exactas, aunque la IA no lo mencione.
        val tope = (dias * paradasPorDia(request.travelStyle) + 6).coerceIn(10, 20)
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        // En rutas largas, 25 km a cada lado de 600 km es una consulta enorme y lenta. Se
        // ajusta el radio del mapa para que siga trayendo pueblos y sitios sin tardar minutos.
        val kmRuta = carretera?.totalDistanceKm ?: 0.0
        val radioMapa = when {
            kmRuta > 400 -> 8
            kmRuta > 200 -> 12
            else -> request.maxDetourKm.coerceAtMost(25)
        }
        val requestMapa = request.copy(maxDetourKm = radioMapa)
        onProgress("Buscando en redes y en el mapa de la vía…")
        val (resultado, exploracion) = coroutineScope {
            val deRedes = async {
                runCatching {
                    radar.discover(request, corredor, maxPlaces = tope, existingPlaces = catalogo, onProgress = onProgress)
                }.getOrNull()
            }
            val deMapa = async {
                runCatching { scout.explorar(requestMapa, corredor, catalogo) }.getOrNull()
            }
            deRedes.await() to deMapa.await()
        }

        var aviso = ""
        val deIa = resultado?.places.orEmpty()
        val deMapa = exploracion?.lugares.orEmpty()
        var candidatos = unirCandidatos(deIa, deMapa)
        if (candidatos.isEmpty()) {
            // Ni la IA ni Overpass respondieron: al menos lo que ya está guardado en el teléfono.
            candidatos = cercaDelCorredor(catalogo, corredor, request.maxDetourKm)
            aviso = if (candidatos.isEmpty())
                "No encontré lugares en la vía ni en el destino. Guarda la ruta y añade paradas a mano, o vuelve a intentarlo con otro destino."
            else "La IA y el mapa no respondieron. Armé la ruta con los lugares que ya tienes guardados."
        } else if (deIa.isEmpty()) {
            aviso = "La IA no respondió, pero el mapa encontró " + deMapa.size +
                " lugares reales en la vía y en el destino. Revisa las sugerencias y elige los que quieras."
        } else if (deMapa.isNotEmpty()) {
            aviso = "Además de lo que se comenta en redes, sumé " + deMapa.size +
                " lugares reales del mapa para que no se quede nada por visitar. Los que no entran al itinerario están en «Sugerencias»."
        }

        // ---------- 4. Desvío y avance de cada candidato ----------
        onProgress("Midiendo cuánto hay que desviarse por cada parada…")
        val medidos = candidatos
            .filter { PlaceJsonParser.isInEcuador(it.latitude, it.longitude) }
            .filterNot { mismoLugar(it, origen) || mismoLugar(it, destino) }
            .map { p ->
                Triple(
                    p,
                    RoutingService.distanceToPathKm(p.latitude, p.longitude, corredor),
                    avanceEnCorredor(p.latitude, p.longitude, corredor)
                )
            }

        val limiteRuta = request.maxDetourKm.toDouble()
        val limiteCerca = (request.maxDetourKm * 2.5).coerceAtLeast(40.0)

        val enLaVia = medidos.filter { it.second <= limiteRuta }.sortedBy { it.third }
        val alrededor = medidos.filter { it.second > limiteRuta && it.second <= limiteCerca }.sortedBy { it.second }

        // Si son demasiadas para los días que hay, se elige una por tramo del recorrido para
        // que la ruta no se quede solo con lo de la primera mitad (Loja y su zona incluidos).
        val cupo = (dias * paradasPorDia(request.travelStyle)).coerceAtLeast(3)
        val turisticas = enLaVia.filter { esTuristica(it.first) }.ifEmpty { enLaVia }
        var elegidas = elegirCobertura(turisticas, cupo)

        // El destino no puede quedar vacío: si la cobertura no eligió al menos dos lugares
        // cerca de la ciudad de llegada, se cambian los de menor puntaje por los mejores de
        // esa zona. Es lo que reclamaba el viajero: "llego a Loja y no hay nada que visitar".
        val cercaDelDestino = medidos
            .filter { it.second <= limiteRuta && esTuristica(it.first) }
            .filter { RoutingService.haversineKm(destino.latitude, destino.longitude, it.first.latitude, it.first.longitude) <= 12.0 }
            .sortedByDescending { puntaje(it.first) }
        val minimoEnDestino = minOf(2, cercaDelDestino.size)
        val yaEnDestino = elegidas.count {
            RoutingService.haversineKm(destino.latitude, destino.longitude, it.first.latitude, it.first.longitude) <= 12.0
        }
        if (minimoEnDestino > yaEnDestino) {
            val faltan = cercaDelDestino
                .filterNot { c -> elegidas.any { it.first.id == c.first.id } }
                .take(minimoEnDestino - yaEnDestino)
            if (faltan.isNotEmpty()) {
                val reemplazables = elegidas
                    .filter { RoutingService.haversineKm(destino.latitude, destino.longitude, it.first.latitude, it.first.longitude) > 12.0 }
                    .sortedBy { puntaje(it.first) }
                    .take(faltan.size)
                    .map { it.first.id }
                    .toSet()
                elegidas = (elegidas.filterNot { it.first.id in reemplazables } + faltan)
                    .sortedBy { it.third }
            }
        }
        val idsElegidos = elegidas.map { it.first.id }.toSet()

        // Todo lo demás queda como sugerencia para que el viajero elija ANTES de guardar.
        val resto = (enLaVia + alrededor)
            .filterNot { it.first.id in idsElegidos }
            .sortedWith(compareBy({ if (esTuristica(it.first)) 0 else 1 }, { it.third }))
            .take(60)

        // ---------- 5. Repartir por días ----------
        val paradas = ArrayList<DraftStop>()
        paradas.add(
            DraftStop(
                place = origen, day = 1, order = 0, detourKm = 0.0,
                why = "Punto de partida", kind = DraftStopKind.ORIGIN, progress = 0.0
            )
        )
        // Reparto por TIEMPO, no solo por avance en el mapa: cada parada "pesa" lo que se tarda
        // en llegar desde la anterior más lo que dura la visita. Así no queda un día de 14 h
        // (todo el trayecto largo) y otro de 2 h. Se reparte con la misma partición equilibrada
        // que usa "Ordenar mejor".
        val minutosCorredor = RouteSchedule.travelMinutes(transport, carretera?.totalDurationMinutes ?: 0, carretera?.totalDistanceKm ?: 0.0)
        val pesos = elegidas.mapIndexed { i, (lugar, _, avance) ->
            val avanceAnterior = if (i == 0) 0.0 else elegidas[i - 1].third
            ((avance - avanceAnterior).coerceAtLeast(0.0) * minutosCorredor).toInt() + duracionSugerida(lugar.category)
        }.toMutableList()
        if (pesos.isNotEmpty()) {
            // lo que queda de camino hasta el destino cae sobre el último día
            pesos[pesos.lastIndex] = pesos.last() + ((1.0 - elegidas.last().third).coerceAtLeast(0.0) * minutosCorredor).toInt()
        }
        val tamanos = RouteOptimizer.particionLineal(pesos, minOf(dias, elegidas.size.coerceAtLeast(1)))
        val diaDe = IntArray(elegidas.size)
        run {
            var k = 0
            tamanos.forEachIndexed { g, tam -> repeat(tam) { diaDe[k++] = g + 1 } }
        }
        elegidas.forEachIndexed { i, (lugar, desvio, avance) ->
            paradas.add(
                DraftStop(
                    place = lugar,
                    day = if (elegidas.size >= dias) diaDe[i] else RouteDraftMath.diaPorAvance(avance, dias),
                    order = i + 1,
                    detourKm = desvio,
                    why = razonDe(lugar),
                    kind = DraftStopKind.STOP,
                    progress = avance
                )
            )
        }
        paradas.add(
            DraftStop(
                place = destino, day = dias, order = 900, detourKm = 0.0,
                why = "Destino final", kind = DraftStopKind.DESTINATION, progress = 1.0
            )
        )

        val cercanas = resto.map { (lugar, desvio, avance) ->
            DraftStop(
                place = lugar,
                day = RouteDraftMath.diaPorAvance(avance, dias),
                detourKm = desvio,
                why = razonDe(lugar),
                progress = avance
            )
        }

        val resumen = buildString {
            if (!resultado?.summary.isNullOrBlank()) append(resultado?.summary)
            if (deMapa.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("El mapa de la vía sumó ")
                append(deMapa.size)
                append(" lugares reales; los que no están en el itinerario quedan como sugerencias.")
            }
        }

        val borrador = RouteDraft(
            name = request.originName.trim() + " → " + request.destinationName.trim(),
            originName = request.originName.trim(),
            destinationName = request.destinationName.trim(),
            days = dias,
            travelers = travelers.coerceIn(1, 20),
            transport = transport,
            travelStyle = request.travelStyle,
            summary = resumen,
            corridorKm = carretera?.totalDistanceKm ?: 0.0,
            corridorMinutes = RouteSchedule.travelMinutes(transport, carretera?.totalDurationMinutes ?: 0, carretera?.totalDistanceKm ?: 0.0),
            stops = paradas,
            nearby = cercanas,
            sources = resultado?.groundingSources.orEmpty(),
            corridor = corredor,
            warning = aviso
        )

        // Tramos reales del itinerario con OSRM (una sola llamada); si falla, se estiman solos.
        conTramosReales(RouteDraftMath.ordenar(borrador))
    }

    /**
     * Arma una escapada cuando el viajero NO indicó de dónde sale.
     *
     * Antes esto era imposible: el planificador exigía origen y destino. Ahora la base es el
     * destino elegido; se buscan lugares en un radio (el "desvío máximo" pasa a leerse como
     * "hasta dónde me muevo desde la base") y se reparten por días. La ruta se guarda como
     * ida y vuelta a esa base, que es exactamente lo que hace alguien que se queda a dormir
     * en un pueblo y sale a recorrer los alrededores.
     */
    private suspend fun planAlrededorDelDestino(
        request: RadarRequest,
        dias: Int,
        travelers: Int,
        transport: TransportMode,
        onProgress: (String) -> Unit
    ): RouteDraft {
        onProgress("Ubicando " + request.destinationName + "…")
        val base = resolver(request.destinationName)
            ?: throw IllegalStateException(
                "No pude ubicar \"" + request.destinationName + "\". Escríbelo como sale en el mapa " +
                    "(por ejemplo \"Baños de Agua Santa\")."
            )
        val radio = request.maxDetourKm.coerceIn(10, 25)
        val area = SearchArea(base.name, base.latitude, base.longitude, radio.toDouble())

        val tope = (dias * paradasPorDia(request.travelStyle) + 6).coerceIn(10, 24)
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())

        onProgress("Buscando planes y lugares reales en " + radio + " km a la redonda…")
        val (resultado, exploracion) = coroutineScope {
            val deRedes = async {
                runCatching {
                    radar.discover(
                        request, emptyList(), maxPlaces = tope,
                        existingPlaces = catalogo, area = area, onProgress = onProgress
                    )
                }.getOrNull()
            }
            val deMapa = async {
                runCatching { scout.explorar(request, emptyList(), catalogo) }.getOrNull()
            }
            deRedes.await() to deMapa.await()
        }

        var aviso = ""
        val deIa = resultado?.places.orEmpty()
        val deMapa = exploracion?.lugares.orEmpty()
        var candidatos = unirCandidatos(deIa, deMapa)
        if (candidatos.isEmpty()) {
            // Plan C sin red: lo que ya está guardado alrededor de la base.
            candidatos = catalogo
                .filter { PlaceJsonParser.isInEcuador(it.latitude, it.longitude) && !mismoLugar(it, base) }
                .filter { RoutingService.haversineKm(base.latitude, base.longitude, it.latitude, it.longitude) <= radio + 5 }
                .sortedByDescending { puntaje(it) }
                .take(tope)
            aviso = if (candidatos.isEmpty())
                "No encontré lugares alrededor de " + base.name + ". Puedes guardar la ruta y añadirlos a mano."
            else "La IA y el mapa no respondieron. Armé la escapada con los lugares que ya tienes guardados."
        } else if (deIa.isEmpty()) {
            aviso = "La IA no respondió, pero el mapa encontró " + deMapa.size +
                " lugares reales alrededor de " + base.name + ". Revisa las sugerencias y elige."
        } else if (deMapa.isNotEmpty()) {
            aviso = "Sumé " + deMapa.size + " lugares reales del mapa a las sugerencias de la IA. " +
                "Los que no entran al itinerario puedes añadirlos antes de guardar."
        }

        // Distancia a la base = el dato que ordena todo: primero lo que está a mano, después
        // lo que queda más lejos. El viajero ve "a 4 km de tu base", no un desvío de carretera.
        val medidos = candidatos
            .filter { PlaceJsonParser.isInEcuador(it.latitude, it.longitude) }
            .filterNot { mismoLugar(it, base) }
            .map { p ->
                val km = RoutingService.haversineKm(base.latitude, base.longitude, p.latitude, p.longitude)
                Triple(p, km, (km / radio).coerceIn(0.0, 1.0))
            }
            .filter { it.second <= radio + 5 }
            .distinctBy { it.first.id }
            .sortedBy { it.second }

        val cupo = (dias * paradasPorDia(request.travelStyle)).coerceAtLeast(3)
        val turisticas = medidos.filter { esTuristica(it.first) }.ifEmpty { medidos }
        val elegidas = elegirCobertura(turisticas, cupo)
        val idsElegidos = elegidas.map { it.first.id }.toSet()
        val descartadas = medidos
            .filterNot { it.first.id in idsElegidos }
            .sortedWith(compareBy({ if (esTuristica(it.first)) 0 else 1 }, { it.second }))
            .take(60)

        // Peso de cada parada: ida y vuelta desde la base (porque cada día se vuelve a dormir
        // allí) + lo que dura la visita. Así no queda un día de 12 h y otro de 40 minutos.
        val pesos = elegidas.map { (lugar, km) ->
            RouteSchedule.travelMinutes(transport, 0, km * 2 * 1.3) + duracionSugerida(lugar.category)
        }
        val tamanos = RouteOptimizer.particionLineal(pesos, minOf(dias, elegidas.size.coerceAtLeast(1)))
        val diaDe = IntArray(elegidas.size)
        run {
            var k = 0
            tamanos.forEachIndexed { g, tam -> repeat(tam) { diaDe[k++] = g + 1 } }
        }

        val paradas = ArrayList<DraftStop>()
        paradas.add(
            DraftStop(
                place = base, day = 1, order = 0, detourKm = 0.0,
                why = "Base del viaje: sales y vuelves aquí", kind = DraftStopKind.ORIGIN, progress = 0.0
            )
        )
        elegidas.forEachIndexed { i, (lugar, km, avance) ->
            paradas.add(
                DraftStop(
                    place = lugar,
                    day = if (elegidas.size >= dias) diaDe[i] else RouteDraftMath.diaPorAvance(avance, dias),
                    order = i + 1,
                    detourKm = km,
                    why = razonDe(lugar),
                    kind = DraftStopKind.STOP,
                    progress = avance
                )
            )
        }
        paradas.add(
            DraftStop(
                place = base, day = dias, order = 900, detourKm = 0.0,
                why = "Vuelves a la base", kind = DraftStopKind.DESTINATION, progress = 1.0
            )
        )

        val cercanas = descartadas.map { (lugar, km, avance) ->
            DraftStop(
                place = lugar,
                day = RouteDraftMath.diaPorAvance(avance, dias),
                detourKm = km,
                why = razonDe(lugar),
                progress = avance
            )
        }

        val resumen = buildString {
            if (!resultado?.summary.isNullOrBlank()) append(resultado?.summary)
            if (deMapa.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("El mapa sumó ")
                append(deMapa.size)
                append(" lugares reales alrededor de ")
                append(base.name)
                append(".")
            }
        }

        val borrador = RouteDraft(
            // El nombre dice la verdad: no hay una ruta desde otra ciudad, hay una base.
            name = "Escapadas desde " + base.name,
            originName = "",                 // el viajero no indicó salida
            destinationName = base.name,
            days = dias,
            travelers = travelers.coerceIn(1, 20),
            transport = transport,
            travelStyle = request.travelStyle,
            summary = resumen,
            corridorKm = 0.0,
            corridorMinutes = 0,
            stops = paradas,
            nearby = cercanas,
            sources = resultado?.groundingSources.orEmpty(),
            corridor = emptyList(),
            area = area,
            warning = aviso
        )
        return conTramosReales(RouteDraftMath.ordenar(borrador))
    }

    /**
     * Guarda el borrador como ruta nueva y la deja activa. Devuelve la ruta creada.
     * Los lugares del borrador se persisten en el catálogo para que funcionen sin señal.
     */
    suspend fun guardar(draft: RouteDraft): TravelRoute = withContext(Dispatchers.IO) {
        val ordenado = RouteDraftMath.ordenar(draft)
        val lugares = ordenado.stops.map { it.place }.distinctBy { it.id }
        // Los lugares de OpenStreetMap se guardan en el teléfono pero NO se suben al catálogo
        // común: son miles y ya están en OSM. Solo se comparte lo que aportó una persona o la IA.
        routes.upsertPlaces(lugares.filter { it.verification != VerificationLevel.OSM }, share = true)
        routes.upsertPlaces(lugares.filter { it.verification == VerificationLevel.OSM }, share = false)

        val ruta = TravelRoute(
            name = ordenado.name.ifBlank { ordenado.originName + " → " + ordenado.destinationName },
            // Si no hubo salida (escapada alrededor de una base), la ruta se guarda como ida y
            // vuelta a esa base: así el horario, el regreso y el mapa tienen un punto real.
            originName = ordenado.originName.ifBlank { ordenado.destinationName },
            destinationName = ordenado.destinationName,
            totalDays = ordenado.days.coerceIn(1, 30),
            travelers = ordenado.travelers.coerceIn(1, 20),
            travelStyle = ordenado.travelStyle,
            transportMode = ordenado.transport,
            dailyStartTime = ordenado.dailyStartTime,
            notes = ordenado.summary.take(500)
        )
        routes.createRoute(ruta, makeActive = true)

        val paradas = ordenado.stops.sortedWith(compareBy({ it.day }, { it.order })).map { d ->
            RouteStop(
                routeId = ruta.id,
                placeId = d.place.id,
                placeName = d.place.name,
                latitude = d.place.latitude,
                longitude = d.place.longitude,
                dayNumber = d.day,
                orderInDay = d.order,
                estimatedCostUsd = if (d.place.costMaxUsd > 0) (d.place.costMinUsd + d.place.costMaxUsd) / 2 else d.place.costMinUsd,
                // La salida y la llegada no se "visitan": no deben sumar tiempo en el día.
                durationMinutes = if (d.isAnchor) 0 else duracionSugerida(d.place.category),
                notes = d.why.take(240)
            )
        }
        routes.replaceStops(ruta.id, paradas)
        ruta
    }

    // ======================================================================
    //  Apoyo
    // ======================================================================

    /** Tramos de verdad (OSRM) para el itinerario ya ordenado. Si no hay red, se estiman. */
    private suspend fun conTramosReales(draft: RouteDraft): RouteDraft {
        val secuencia = draft.stops.sortedWith(compareBy({ it.day }, { it.order }))
        if (secuencia.size < 2) return RouteDraftMath.recalcular(draft)

        val falsas = secuencia.mapIndexed { i, d ->
            RouteStop(id = "d$i", routeId = "draft", placeId = d.place.id, placeName = d.place.name, latitude = d.place.latitude, longitude = d.place.longitude)
        }
        val road = runCatching { routing.routeBetween(falsas) }.getOrNull()
            ?: return RouteDraftMath.recalcular(draft)
        if (road.isEstimate || road.legs.size < secuencia.size - 1) return RouteDraftMath.recalcular(draft)

        val conTramos = secuencia.mapIndexed { i, d ->
            if (i == 0) d.copy(legKm = 0.0, legMinutes = 0)
            else {
                val tramo = road.legs[i - 1]
                d.copy(
                    legKm = tramo.distanceKm,
                    legMinutes = RouteSchedule.travelMinutes(draft.transport, tramo.durationMinutes, tramo.distanceKm)
                )
            }
        }
        return draft.copy(stops = conTramos)
    }

    /** Nombre escrito → lugar real. Catálogo → nomenclátor del teléfono → Nominatim. */
    private suspend fun resolver(nombre: String): Place? {
        val q = normalizeSearch(nombre)
        if (q.isBlank()) return null

        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        catalogo.firstOrNull { normalizeSearch(it.name) == q }?.let { return it }

        val sugerencia = runCatching { index.suggest(nombre, 3) }.getOrDefault(emptyList()).firstOrNull()
        if (sugerencia != null) {
            if (sugerencia.placeId.isNotBlank()) routes.getPlace(sugerencia.placeId)?.let { return it }
            val coords = runCatching { index.resolveCoordinates(sugerencia) }.getOrNull()
            if (coords != null && PlaceJsonParser.isInEcuador(coords.first, coords.second)) {
                return lugarLigero(sugerencia.name, sugerencia.parent, coords.first, coords.second)
            }
        }

        catalogo.firstOrNull { normalizeSearch(it.name).contains(q) || q.contains(normalizeSearch(it.name)) }?.let { return it }

        val hit = runCatching { geocoding.search(nombre, limit = 1).firstOrNull() }.getOrNull() ?: return null
        if (!PlaceJsonParser.isInEcuador(hit.latitude, hit.longitude)) return null
        return lugarLigero(nombre.trim(), hit.city.ifBlank { hit.province }, hit.latitude, hit.longitude)
    }

    private fun lugarLigero(nombre: String, zona: String, lat: Double, lng: Double) = Place(
        id = "ruta_" + UUID.randomUUID().toString().replace("-", "").take(14),
        name = nombre,
        category = PlaceCategory.SERVICES,
        region = EcuadorRegion.fromCoordinates(lat, lng),
        province = zona,
        city = zona,
        latitude = lat,
        longitude = lng,
        shortDescription = "Punto del trayecto.",
        verification = VerificationLevel.AI_SUGGESTED
    )

    /** Lugares del catálogo que caen junto a la vía: el plan B cuando la IA no responde. */
    private fun cercaDelCorredor(catalogo: List<Place>, corredor: List<Pair<Double, Double>>, desvioMax: Int): List<Place> {
        if (corredor.size < 2) return emptyList()
        return catalogo
            .filter { RoutingService.distanceToPathKm(it.latitude, it.longitude, corredor) <= desvioMax + 10 }
            .sortedByDescending { puntaje(it) }
            .take(14)
    }

    /**
     * Une lo que encontró la IA con lo que encontró el mapa. La ficha de la IA manda (tiene
     * descripción y fuentes); del mapa solo se agrega lo que no estaba ya.
     */
    private fun unirCandidatos(ia: List<Place>, mapa: List<Place>): List<Place> {
        val salida = ArrayList<Place>(ia.size + mapa.size)
        salida.addAll(ia)
        mapa.forEach { p ->
            val repetido = salida.any { previo ->
                previo.id == p.id ||
                    (previo.name.equals(p.name, ignoreCase = true) &&
                        RoutingService.haversineKm(previo.latitude, previo.longitude, p.latitude, p.longitude) < 1.5)
            }
            if (!repetido) salida.add(p)
        }
        return salida
    }

    /**
     * Un lugar "turístico" de verdad: naturaleza, miradores, cultura, aventura, playa y comida
     * con reseñas/menciones. Gasolineras, farmacias y terminales se quedan como sugerencia
     * práctica, pero no ocupan un hueco del itinerario.
     */
    private fun esTuristica(p: Place): Boolean = when (p.category) {
        PlaceCategory.NATURE, PlaceCategory.VIEWPOINT, PlaceCategory.CULTURE,
        PlaceCategory.ADVENTURE, PlaceCategory.BEACH -> true
        PlaceCategory.FOOD -> p.socialMentions > 0 ||
            p.verification == VerificationLevel.CURATED ||
            p.verification == VerificationLevel.VERIFIED
        else -> false
    }

    /**
     * Elige [cupo] paradas repartidas a lo largo del recorrido, no las [cupo] mejores de un
     * solo tramo. Se parte el corredor en tramos iguales y se toma la mejor de cada uno: así
     * hay paradas antes de llegar, en el destino y en el regreso.
     */
    private fun elegirCobertura(
        candidatos: List<Triple<Place, Double, Double>>,
        cupo: Int
    ): List<Triple<Place, Double, Double>> {
        if (candidatos.isEmpty() || cupo <= 0) return emptyList()
        if (candidatos.size <= cupo) return candidatos.sortedBy { it.third }
        val ordenados = candidatos.sortedBy { it.third }
        val elegidos = LinkedHashMap<String, Triple<Place, Double, Double>>()
        for (i in 0 until cupo) {
            val desde = i.toDouble() / cupo
            val hasta = (i + 1).toDouble() / cupo
            val delTramo = ordenados.filter {
                if (i == cupo - 1) it.third >= desde else it.third >= desde && it.third < hasta
            }
            val mejor = delTramo.maxByOrNull { puntaje(it.first) }
            if (mejor != null) elegidos[mejor.first.id] = mejor
        }
        if (elegidos.size < cupo) {
            ordenados.filterNot { it.first.id in elegidos }
                .sortedByDescending { puntaje(it.first) }
                .forEach { if (elegidos.size < cupo) elegidos[it.first.id] = it }
        }
        return elegidos.values.sortedBy { it.third }
    }

    /** Por qué se propone una parada, sin dejar la tarjeta en blanco cuando viene del mapa. */
    private fun razonDe(p: Place): String = when {
        p.shortDescription.isNotBlank() -> p.shortDescription
        p.city.isNotBlank() -> "En " + p.city + " · " + p.category.displayName
        p.province.isNotBlank() -> "En " + p.province + " · " + p.category.displayName
        else -> p.category.displayName
    }

    /** Qué tan "imperdible" es: menciones en redes y fuentes reales pesan más que el resto. */
    private fun puntaje(p: Place): Int =
        p.socialMentions + p.sources.size * 2 +
            (if (p.verification == VerificationLevel.CURATED) 6 else 0) +
            (if (p.verification == VerificationLevel.VERIFIED) 3 else 0) +
            (if (p.imageUrls.isNotEmpty()) 1 else 0)

    /** Posición 0..1 del punto más cercano del corredor: sirve para ordenar el itinerario. */
    private fun avanceEnCorredor(lat: Double, lng: Double, corredor: List<Pair<Double, Double>>): Double {
        if (corredor.size < 2) return 0.5
        var mejor = Double.MAX_VALUE
        var indice = 0
        corredor.forEachIndexed { i, punto ->
            val d = RoutingService.haversineKm(lat, lng, punto.first, punto.second)
            if (d < mejor) { mejor = d; indice = i }
        }
        return indice.toDouble() / (corredor.size - 1).toDouble()
    }

    private fun mismoLugar(a: Place, b: Place): Boolean =
        a.id == b.id || a.name.equals(b.name, ignoreCase = true) ||
            RoutingService.haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) < 1.5

    private fun duracionSugerida(c: PlaceCategory): Int = when (c) {
        PlaceCategory.FOOD -> 60
        PlaceCategory.VIEWPOINT, PlaceCategory.SERVICES -> 30
        // Antes 720 (12 h): la noche se muestra aparte, aquí solo cuenta el registro.
        PlaceCategory.LODGING -> 30
        PlaceCategory.NATURE, PlaceCategory.ADVENTURE -> 180
        else -> 90
    }
}

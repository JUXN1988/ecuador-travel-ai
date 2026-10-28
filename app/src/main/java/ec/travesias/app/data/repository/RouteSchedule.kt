package ec.travesias.app.data.repository

import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.remote.RoutingService

/**
 * Convierte la ruta en un horario real: a qué hora se sale, cuánto se tarda en llegar a cada
 * parada según el transporte elegido, cuánto se queda uno y a qué hora sigue.
 *
 * NOVEDAD (oct-2026): el viaje ya no "empieza" mágicamente en la primera parada. Si la ruta
 * tiene ORIGEN (p. ej. Santo Domingo) se suma el tramo origen → primera parada el día 1, y el
 * último día se suma el tramo última parada → destino (o de vuelta a casa si es ida y vuelta).
 * Antes salía "Llegas a Lago Agrio a las 07:30" saliendo de Santo Domingo a las 07:30.
 *
 * Los tiempos de carretera vienen de OSRM (tiempos de carro); para bus, taxi, tour o moto se
 * ajustan con un factor y se suma la espera típica de cada medio. Bicicleta y caminata se
 * calculan por velocidad propia. Si no hay ruta por carretera se estima por distancia directa.
 */
object RouteSchedule {

    /** Ids de las "paradas falsas" de origen y llegada que se mandan a OSRM. */
    const val ORIGIN_ID = "__origen__"
    const val FINISH_ID = "__llegada__"

    /** Por debajo de esta distancia el origen/destino se considera la misma parada. */
    private const val SAME_PLACE_KM = 3.0

    /** Un punto con nombre (origen o destino de la ruta). */
    data class Point(val name: String, val latitude: Double, val longitude: Double)

    /**
     * Extremos del viaje ya ubicados en el mapa. [finish] es el destino, o el mismo origen
     * cuando la ruta es de ida y vuelta.
     */
    data class Endpoints(val origin: Point? = null, val finish: Point? = null, val roundTrip: Boolean = false) {
        companion object { val NONE = Endpoints() }
    }

    data class ScheduledStop(
        val stop: RouteStop,
        val transport: TransportMode,
        /** Minutos de viaje desde el punto anterior (0 si es la primera de todo el viaje y no hay origen). */
        val travelMinutes: Int,
        val distanceKm: Double,
        val arrivalMinutes: Int,
        val departureMinutes: Int,
        /** true si el traslado viene del día anterior (se durmió en otro sitio). */
        val fromPreviousDay: Boolean,
        /** De dónde se viene: el origen del viaje o la parada anterior. */
        val fromName: String = ""
    ) {
        val arrival: String get() = hhmm(arrivalMinutes)
        val departure: String get() = hhmm(departureMinutes)
        val travelLabel: String get() = durationLabel(travelMinutes)
        val visitLabel: String get() = durationLabel(stop.durationMinutes)
    }

    /** Tramo final del viaje: llegada al destino o regreso a casa. */
    data class Leg(
        val fromName: String,
        val toName: String,
        val transport: TransportMode,
        val travelMinutes: Int,
        val distanceKm: Double,
        val departMinutes: Int,
        val arrivalMinutes: Int,
        val isReturnHome: Boolean
    ) {
        val arrival: String get() = hhmm(arrivalMinutes)
        val travelLabel: String get() = durationLabel(travelMinutes)
    }

    data class DayPlan(
        val day: Int,
        val stops: List<ScheduledStop>,
        /** Minutos en camino del día, INCLUIDOS el tramo desde el origen y el de llegada. */
        val travelMinutes: Int,
        val visitMinutes: Int,
        val distanceKm: Double,
        val startMinutes: Int,
        val endMinutes: Int,
        /** Entradas y gastos de las paradas del día (por persona). */
        val costUsd: Double,
        /** De dónde se sale ese día (origen, o donde se durmió). */
        val startPlace: String = "",
        /** Solo el último día con paradas: tramo hasta el destino o de regreso. */
        val finishLeg: Leg? = null,
        /** Dónde se pasa la noche (la última parada del día), salvo el último día. */
        val overnight: String? = null
    ) {
        val start: String get() = hhmm(startMinutes)
        val end: String get() = hhmm(endMinutes)
        val isLongDay: Boolean get() = endMinutes - startMinutes > 12 * 60
        /** Más de 8 h metidos en el carro/bus en un solo día. */
        val isHeavyTravel: Boolean get() = travelMinutes > 8 * 60
        val summary: String
            get() = buildString {
                append(stops.size)
                append(if (stops.size == 1) " parada · " else " paradas · ")
                append(durationLabel(travelMinutes))
                append(" en camino · ")
                append(durationLabel(visitMinutes))
                append(" de visitas")
                if (distanceKm > 0) {
                    append(" · ")
                    append(distanceKm.toInt())
                    append(" km")
                }
            }
    }

    // ------------------------------------------------------------------ extremos

    private fun ordenadas(route: TravelRoute): List<RouteStop> =
        route.stops.sortedWith(compareBy({ it.dayNumber }, { it.orderInDay }))

    private fun lejos(p: Point, s: RouteStop): Boolean =
        RoutingService.haversineKm(p.latitude, p.longitude, s.latitude, s.longitude) > SAME_PLACE_KM

    /** El origen solo cuenta si la primera parada no está ya en él. */
    fun originLeg(route: TravelRoute, ends: Endpoints): Point? {
        val primera = ordenadas(route).firstOrNull() ?: return null
        return ends.origin?.takeIf { lejos(it, primera) }
    }

    /** La llegada solo cuenta si la última parada no está ya en el destino. */
    fun finishLeg(route: TravelRoute, ends: Endpoints): Point? {
        val ultima = ordenadas(route).lastOrNull() ?: return null
        return ends.finish?.takeIf { lejos(it, ultima) }
    }

    /**
     * Secuencia completa para pedir la carretera a OSRM: origen + paradas + llegada.
     * Los extremos van como paradas falsas con ids [ORIGIN_ID] y [FINISH_ID].
     */
    fun roadSequence(route: TravelRoute, ends: Endpoints): List<RouteStop> {
        val lista = ordenadas(route)
        if (lista.isEmpty()) return emptyList()
        val salida = originLeg(route, ends)?.let { falsa(route.id, ORIGIN_ID, it) }
        val llegada = finishLeg(route, ends)?.let { falsa(route.id, FINISH_ID, it) }
        return listOfNotNull(salida) + lista + listOfNotNull(llegada)
    }

    private fun falsa(routeId: String, id: String, p: Point) = RouteStop(
        id = id, routeId = routeId, placeId = "", placeName = p.name,
        latitude = p.latitude, longitude = p.longitude, durationMinutes = 0
    )

    // ------------------------------------------------------------------ horario

    private data class Previo(val id: String, val name: String, val lat: Double, val lng: Double)

    /** Construye el horario día por día. */
    fun build(route: TravelRoute, road: RoutingService.RoadRoute?, ends: Endpoints = Endpoints.NONE): List<DayPlan> {
        val lista = ordenadas(route)
        if (lista.isEmpty()) return emptyList()

        val tramos = HashMap<String, Pair<Double, Int>>()
        road?.legs?.forEach { l -> tramos[l.fromStopId + "|" + l.toStopId] = Pair(l.distanceKm, l.durationMinutes) }

        val inicioDia = parseHhmm(route.dailyStartTime, 8 * 60)
        val origen = originLeg(route, ends)
        val llegadaFinal = finishLeg(route, ends)
        val ultimoDia = lista.maxOf { it.dayNumber }
        val planes = ArrayList<DayPlan>()

        // Punto desde el que se sale hacia la siguiente parada (se arrastra entre días).
        var previo: Previo? = origen?.let { Previo(ORIGIN_ID, it.name, it.latitude, it.longitude) }

        for (dia in 1..maxOf(route.totalDays, ultimoDia, 1)) {
            val delDia = lista.filter { it.dayNumber == dia }
            if (delDia.isEmpty()) continue

            val lugarSalida = previo?.name ?: delDia.first().placeName
            var reloj = inicioDia
            val agenda = ArrayList<ScheduledStop>()
            var viajeTotal = 0
            var visitaTotal = 0
            var kmTotal = 0.0

            for ((indice, parada) in delDia.withIndex()) {
                val medio = parada.transportMode ?: route.transportMode
                val desde = previo
                var km = 0.0
                var minutos = 0
                if (desde != null) {
                    val t = tramo(tramos, desde.id, parada.id, desde.lat, desde.lng, parada.latitude, parada.longitude)
                    km = t.first
                    minutos = travelMinutes(medio, t.second, km)
                }

                // Solo si el viajero (o una razón real: ferry, tour, horario de apertura) fijó
                // la hora a mano manda esa hora. Si no, se calcula según el transporte elegido.
                val fijada = if (parada.timeLocked) parseHhmmOrNull(parada.plannedTime) else null
                val llegada = fijada ?: (reloj + minutos)
                val salida = llegada + parada.durationMinutes

                agenda.add(
                    ScheduledStop(
                        stop = parada,
                        transport = medio,
                        travelMinutes = minutos,
                        distanceKm = km,
                        arrivalMinutes = llegada,
                        departureMinutes = salida,
                        fromPreviousDay = indice == 0 && desde != null && desde.id != ORIGIN_ID,
                        fromName = desde?.name.orEmpty()
                    )
                )
                viajeTotal += minutos
                visitaTotal += parada.durationMinutes
                kmTotal += km
                reloj = salida
                previo = Previo(parada.id, parada.placeName, parada.latitude, parada.longitude)
            }

            // Último día con paradas: falta llegar al destino (o volver a casa).
            var tramoFinal: Leg? = null
            val ultimoPunto = previo
            if (dia == ultimoDia && llegadaFinal != null && ultimoPunto != null) {
                val t = tramo(tramos, ultimoPunto.id, FINISH_ID, ultimoPunto.lat, ultimoPunto.lng, llegadaFinal.latitude, llegadaFinal.longitude)
                val minutos = travelMinutes(route.transportMode, t.second, t.first)
                tramoFinal = Leg(
                    fromName = ultimoPunto.name,
                    toName = llegadaFinal.name,
                    transport = route.transportMode,
                    travelMinutes = minutos,
                    distanceKm = t.first,
                    departMinutes = reloj,
                    arrivalMinutes = reloj + minutos,
                    isReturnHome = ends.roundTrip
                )
                viajeTotal += minutos
                kmTotal += t.first
            }

            val primera = agenda.first()
            planes.add(
                DayPlan(
                    day = dia,
                    stops = agenda,
                    travelMinutes = viajeTotal,
                    visitMinutes = visitaTotal,
                    distanceKm = kmTotal,
                    startMinutes = minOf(inicioDia, primera.arrivalMinutes - primera.travelMinutes),
                    endMinutes = tramoFinal?.arrivalMinutes ?: agenda.last().departureMinutes,
                    costUsd = delDia.sumOf { it.estimatedCostUsd },
                    startPlace = lugarSalida,
                    finishLeg = tramoFinal,
                    overnight = if (dia < ultimoDia) delDia.last().placeName else null
                )
            )
        }
        return planes
    }

    /** km y minutos-en-carro de un tramo: los de OSRM si los hay; si no, línea recta × 1,3. */
    private fun tramo(
        tramos: Map<String, Pair<Double, Int>>, desdeId: String, hastaId: String,
        lat1: Double, lng1: Double, lat2: Double, lng2: Double
    ): Pair<Double, Int> {
        tramos[desdeId + "|" + hastaId]?.let { return it }
        // las carreteras andinas no van en línea recta
        return Pair(RoutingService.haversineKm(lat1, lng1, lat2, lng2) * 1.3, 0)
    }

    /**
     * Minutos reales de un tramo según el medio de transporte.
     * @param carMinutes tiempo en carro que devolvió OSRM (0 si no se conoce).
     */
    fun travelMinutes(mode: TransportMode, carMinutes: Int, distanceKm: Double): Int {
        if (distanceKm <= 0.05 && carMinutes <= 0) return 0
        if (mode.ownSpeedKmh > 0.0) {
            val propio = (distanceKm / mode.ownSpeedKmh * 60.0).toInt()
            return (propio + mode.waitMinutes).coerceAtLeast(1)
        }
        val base = if (carMinutes > 0) carMinutes else (distanceKm / 55.0 * 60.0).toInt()
        return ((base * mode.factor).toInt() + mode.waitMinutes).coerceAtLeast(if (distanceKm > 0.5) 5 else 1)
    }

    /** "08:00" -> 480. Devuelve [porDefecto] si no se entiende. */
    fun parseHhmm(text: String?, porDefecto: Int): Int = parseHhmmOrNull(text) ?: porDefecto

    fun parseHhmmOrNull(text: String?): Int? {
        val t = text?.trim() ?: return null
        val m = Regex("^(\\d{1,2})[:h\\.]?(\\d{2})?").find(t) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    /** "7:5" o "730" no; "07:30" sí. Para validar lo que escribe el viajero en los diálogos. */
    fun isValidHhmm(text: String?): Boolean =
        text != null && Regex("^([01]?\\d|2[0-3])[:h.]([0-5]\\d)$").matches(text.trim())

    /** 545 -> "09:05". Si pasa de medianoche sigue contando (25:10 se muestra como 01:10 +1d). */
    fun hhmm(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        val h = m / 60
        val mm = m % 60
        val extra = if (minutes >= 1440) " +1d" else ""
        return String.format(java.util.Locale.US, "%02d:%02d", h, mm) + extra
    }

    fun durationLabel(minutes: Int): String = when {
        minutes <= 0 -> "—"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

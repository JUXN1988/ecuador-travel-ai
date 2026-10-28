package ec.travesias.app.data.repository

import ec.travesias.app.data.model.DraftStop
import ec.travesias.app.data.model.DraftStopKind
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.RouteDraft
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.remote.RoutingService

/**
 * Las cuentas del borrador de ruta, TODAS sin internet.
 *
 * Cada vez que el viajero quita, sube, baja o cambia de día una parada hay que renumerar el
 * orden y volver a estimar los tramos. Hacerlo con OSRM en cada toque sería lento y gastaría
 * datos, así que aquí se estima en línea recta por un factor de carretera andina; los tiempos
 * reales los calcula la pantalla de Ruta (con OSRM) en cuanto se guarda.
 */
object RouteDraftMath {

    /** Factor sobre la línea recta: las vías del Ecuador no van derechas. */
    private const val FACTOR_CARRETERA = 1.35

    /**
     * Renumera el orden dentro de cada día. El origen queda siempre primero del día 1 y el
     * destino último del último día: son los extremos del viaje, no se pueden mover.
     */
    fun ordenar(draft: RouteDraft): RouteDraft {
        val dias = draft.days.coerceAtLeast(1)
        val ajustadas = draft.stops.map { s ->
            when (s.kind) {
                DraftStopKind.ORIGIN -> s.copy(day = 1)
                DraftStopKind.DESTINATION -> s.copy(day = dias)
                else -> s.copy(day = s.day.coerceIn(1, dias))
            }
        }
        val renumeradas = ArrayList<DraftStop>(ajustadas.size)
        for (dia in 1..dias) {
            val delDia = ajustadas.filter { it.day == dia }.sortedWith(
                compareBy(
                    { if (it.kind == DraftStopKind.ORIGIN) -1 else if (it.kind == DraftStopKind.DESTINATION) 1 else 0 },
                    { it.order }
                )
            )
            delDia.forEachIndexed { i, s -> renumeradas.add(s.copy(order = i)) }
        }
        return draft.copy(stops = renumeradas)
    }

    /** Renumera y vuelve a estimar distancia y tiempo de cada tramo. */
    fun recalcular(draft: RouteDraft): RouteDraft {
        val ordenado = ordenar(draft)
        val secuencia = ordenado.stops.sortedWith(compareBy({ it.day }, { it.order }))
        val conTramos = secuencia.mapIndexed { i, s ->
            if (i == 0) s.copy(legKm = 0.0, legMinutes = 0)
            else {
                val ant = secuencia[i - 1]
                val km = RoutingService.haversineKm(
                    ant.place.latitude, ant.place.longitude, s.place.latitude, s.place.longitude
                ) * FACTOR_CARRETERA
                s.copy(legKm = km, legMinutes = RouteSchedule.travelMinutes(draft.transport, 0, km))
            }
        }
        return ordenado.copy(stops = conTramos)
    }

    /** Quita una parada del itinerario y la devuelve a la lista de "cerca de tu ruta". */
    fun quitar(draft: RouteDraft, stopId: String): RouteDraft {
        val fuera = draft.stops.firstOrNull { it.id == stopId } ?: return draft
        if (fuera.isAnchor) return draft   // el origen y el destino no se quitan
        return recalcular(
            draft.copy(
                stops = draft.stops.filterNot { it.id == stopId },
                nearby = (draft.nearby + fuera.copy(legKm = 0.0, legMinutes = 0)).distinctBy { it.place.name.lowercase() }
            )
        )
    }

    /**
     * Sube o baja una parada. Si ya es la primera de su día sube al día anterior, y si es la
     * última baja al siguiente: así se reparte el viaje sin menús.
     */
    fun mover(draft: RouteDraft, stopId: String, arriba: Boolean): RouteDraft {
        val actual = draft.stops.firstOrNull { it.id == stopId } ?: return draft
        if (actual.isAnchor) return draft
        val delDia = draft.stops.filter { it.day == actual.day }.sortedBy { it.order }
        val pos = delDia.indexOfFirst { it.id == stopId }
        if (pos < 0) return draft

        val vecino = if (arriba) delDia.getOrNull(pos - 1) else delDia.getOrNull(pos + 1)
        if (vecino != null && !vecino.isAnchor) {
            // Intercambio simple dentro del mismo día
            val nuevas = draft.stops.map { s ->
                when (s.id) {
                    actual.id -> s.copy(order = vecino.order)
                    vecino.id -> s.copy(order = actual.order)
                    else -> s
                }
            }
            return recalcular(draft.copy(stops = nuevas))
        }

        // Estaba pegada a un extremo: cambia de día
        val nuevoDia = if (arriba) actual.day - 1 else actual.day + 1
        if (nuevoDia < 1 || nuevoDia > draft.days) return draft
        val ordenEnDestino = if (arriba) 999 else -1   // al final del día anterior / al inicio del siguiente
        return recalcular(draft.copy(stops = draft.stops.map { if (it.id == stopId) it.copy(day = nuevoDia, order = ordenEnDestino) else it }))
    }

    /** Mueve una parada a un día concreto (queda al final de ese día). */
    fun cambiarDia(draft: RouteDraft, stopId: String, dia: Int): RouteDraft {
        val actual = draft.stops.firstOrNull { it.id == stopId } ?: return draft
        if (actual.isAnchor) return draft
        val d = dia.coerceIn(1, draft.days.coerceAtLeast(1))
        return recalcular(draft.copy(stops = draft.stops.map { if (it.id == stopId) it.copy(day = d, order = 998) else it }))
    }

    /**
     * Suma un lugar del catálogo o del radar al itinerario en armado.
     * Si ya estaba, no duplica. El origen y el destino no se tocan.
     */
    fun agregarLugar(draft: RouteDraft, place: Place, dia: Int? = null): RouteDraft {
        if (draft.stops.any { it.place.id == place.id }) return draft
        val diaSugerido = (dia ?: 1).coerceIn(1, draft.days.coerceAtLeast(1))
        val cercano = draft.nearby.firstOrNull { it.place.id == place.id }
        if (cercano != null) return agregarCercano(draft, cercano.id, diaSugerido)
        val entrante = DraftStop(
            place = place,
            day = diaSugerido,
            order = 500,
            kind = DraftStopKind.STOP,
            why = "Lo añadiste tú",
            progress = 0.5
        )
        return recalcular(draft.copy(stops = draft.stops + entrante))
    }

    /** Mete al itinerario uno de los lugares cercanos que la IA dejó fuera. */
    fun agregarCercano(draft: RouteDraft, stopId: String, dia: Int? = null): RouteDraft {
        val cercano = draft.nearby.firstOrNull { it.id == stopId } ?: return draft
        // Sin día indicado, entra donde le toca por su avance en el corredor.
        val diaSugerido = dia ?: diaPorAvance(cercano.progress, draft.days)
        val entrante = cercano.copy(day = diaSugerido, order = 500, kind = DraftStopKind.STOP)
        // Entra en el sitio que le toca por geografía, no al final del día: si queda antes
        // que otra parada en el camino, se ordena antes.
        val delDia = (draft.stops.filter { it.day == diaSugerido && !it.isAnchor } + entrante)
            .sortedBy { it.progress }
        val reordenadas = delDia.mapIndexed { i, s -> s.copy(order = i + 1) }
        val resto = draft.stops.filterNot { it.day == diaSugerido && !it.isAnchor }
        return recalcular(
            draft.copy(
                stops = resto + reordenadas,
                nearby = draft.nearby.filterNot { it.id == stopId }
            )
        )
    }

    /** Cambia el número de días del viaje sin dejar paradas huérfanas en días que ya no existen. */
    fun cambiarDias(draft: RouteDraft, dias: Int): RouteDraft {
        val d = dias.coerceIn(1, 30)
        return recalcular(draft.copy(days = d, stops = draft.stops.map { it.copy(day = it.day.coerceIn(1, d)) }))
    }

    /** Cambiar el transporte cambia los tiempos de todos los tramos. */
    fun cambiarTransporte(draft: RouteDraft, modo: TransportMode): RouteDraft =
        recalcular(draft.copy(transport = modo))

    /** Qué día le toca a algo que está al X% del camino. */
    fun diaPorAvance(avance: Double, dias: Int): Int {
        val d = dias.coerceAtLeast(1)
        return (1 + (avance.coerceIn(0.0, 0.999) * d).toInt()).coerceIn(1, d)
    }
}

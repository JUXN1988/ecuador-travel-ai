package ec.travesias.app.data.model

import java.util.UUID

/**
 * BORRADOR de ruta: lo que la IA propone ANTES de guardarlo.
 *
 * No toca la base de datos. El viajero puede quitar paradas, reordenarlas, moverlas de día,
 * meter lugares cercanos que quedaron fuera y cambiar nombre, días, viajeros y transporte.
 * Solo cuando toca "Guardar ruta" se crea la [TravelRoute] de verdad con sus [RouteStop].
 */
data class RouteDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val originName: String,
    val destinationName: String,
    val days: Int,
    val travelers: Int,
    val transport: TransportMode,
    val travelStyle: String = "Moderado",
    val dailyStartTime: String = "08:00",
    /** Qué está de moda en esta ruta, en palabras de la IA. */
    val summary: String = "",
    /** Kilómetros y minutos del trayecto principal (carretera real, según OSRM). */
    val corridorKm: Double = 0.0,
    val corridorMinutes: Int = 0,
    val stops: List<DraftStop> = emptyList(),
    /** Lugares que quedaron cerca de la vía pero fuera del itinerario: se pueden añadir. */
    val nearby: List<DraftStop> = emptyList(),
    val sources: List<PlaceSource> = emptyList(),
    /** Polilínea del corredor, para pintarla en el mapa. */
    val corridor: List<Pair<Double, Double>> = emptyList(),
    /**
     * Cuando el viajero no indicó salida, la ruta se arma alrededor del destino. Guardamos el
     * círculo para pintarlo en el mapa y para que la pantalla lo explique ("buscamos en 30 km
     * a la redonda"), en vez de mostrar una ruta que empieza en el aire.
     */
    val area: SearchArea? = null,
    /** Aviso honesto cuando algo no se pudo hacer (sin internet, sin fuentes…). */
    val warning: String = ""
) {
    fun stopsForDay(day: Int): List<DraftStop> = stops.filter { it.day == day }.sortedBy { it.order }

    val totalCostUsd: Double get() = stops.sumOf { it.place.costMinUsd } * travelers

    /** Kilómetros que se recorren de verdad sumando todos los tramos del itinerario. */
    val totalKm: Double get() = stops.sumOf { it.legKm }

    val totalTravelMinutes: Int get() = stops.sumOf { it.legMinutes }

    /** Paradas descubiertas por la IA (sin contar origen y destino). */
    val discoveredCount: Int get() = stops.count { it.kind == DraftStopKind.STOP }
}

/** Una parada dentro del borrador. Envuelve el [Place] con lo que aporta el planificador. */
data class DraftStop(
    val id: String = UUID.randomUUID().toString(),
    val place: Place,
    val day: Int = 1,
    val order: Int = 0,
    /** Cuánto hay que salirse de la vía principal para llegar (km en línea recta). */
    val detourKm: Double = 0.0,
    /** Distancia y tiempo desde la parada anterior del itinerario (aproximados). */
    val legKm: Double = 0.0,
    val legMinutes: Int = 0,
    /** Por qué la IA la propone: "la cascada más viral de la vía", etc. */
    val why: String = "",
    val kind: DraftStopKind = DraftStopKind.STOP,
    /** Posición 0..1 a lo largo del corredor: sirve para ordenar de origen a destino. */
    val progress: Double = 0.0
) {
    val isAnchor: Boolean get() = kind != DraftStopKind.STOP

    /** Etiqueta corta de la distancia, tal como se muestra en la tarjeta. */
    val detourLabel: String get() = when {
        isAnchor -> ""
        detourKm < 1.0 -> "sobre el camino"
        detourKm < 10.0 -> String.format(java.util.Locale.US, "a %.1f km", detourKm)
        else -> "a " + detourKm.toInt() + " km"
    }
}

enum class DraftStopKind { ORIGIN, STOP, DESTINATION }

/** Estado del planificador de rutas con IA, para la pantalla. */
sealed class RoutePlanUiState {
    object Idle : RoutePlanUiState()
    data class Loading(val message: String) : RoutePlanUiState()
    data class Ready(val draft: RouteDraft) : RoutePlanUiState()
    data class Error(val message: String) : RoutePlanUiState()
}

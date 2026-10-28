package ec.travesias.app.data.model

/**
 * Lo que el viajero contesta al abrir la app: "voy de aquí a allá, estos días".
 *
 * POR QUÉ EXISTE
 * Antes la portada era un menú de seis iconos y el viajero tenía que adivinar por dónde
 * empezar; recién dentro de Descubrir aparecía el formulario de origen y destino. Ahora la
 * primera pregunta de la app es la única que de verdad importa: ¿a dónde vas? Este objeto
 * guarda esa respuesta para rellenar los demás formularios (Radar, planificador, Mi Ruta)
 * sin volver a escribirla, y para que la app pueda ser proactiva al volver a abrirla.
 *
 * [origin] puede ir VACÍO a propósito: significa "no me interesa una ruta, búscame lugares
 * solo alrededor del destino". Ese es el modo [destinationOnly].
 */
data class TripIntent(
    val origin: String = "",
    val destination: String = "",
    val days: Int = 3,
    val travelers: Int = 2
) {
    /** true si hay destino pero no salida: se busca en un radio alrededor del destino. */
    val destinationOnly: Boolean get() = origin.isBlank() && destination.isNotBlank()

    /** Se puede planear en cuanto hay un destino. */
    val ready: Boolean get() = destination.isNotBlank()

    /** Texto corto para botones y títulos. */
    val label: String
        get() = when {
            destination.isBlank() -> "Tu próximo viaje"
            destinationOnly -> "Alrededor de ${destination.trim()}"
            else -> "${origin.trim()} → ${destination.trim()}"
        }
}

/**
 * Zona de búsqueda cuando NO hay punto de partida. El mapa dibuja este círculo para que el
 * viajero vea exactamente hasta dónde está buscando la app, en vez de recibir lugares de todo
 * el país sin explicación.
 */
data class SearchArea(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double
) {
    val radiusLabel: String
        get() = if (radiusKm % 1.0 == 0.0) "${radiusKm.toInt()} km" else String.format(java.util.Locale.US, "%.1f km", radiusKm)
}

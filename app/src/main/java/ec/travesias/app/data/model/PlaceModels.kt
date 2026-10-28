package ec.travesias.app.data.model

import java.util.UUID

/**
 * Nivel de verificación de un lugar descubierto.
 */
enum class VerificationLevel(val displayName: String) {
    CURATED("Curado por el equipo"),
    VERIFIED("Verificado por IA con fuentes"),
    AI_SUGGESTED("Sugerido por IA"),
    USER("Añadido por el viajero"),
    SHARED("Compartido por un compañero"),
    /**
     * Sacado del mapa de OpenStreetMap por el explorador de ruta. Las coordenadas son exactas,
     * pero la ficha es la del mapa, no una reseña escrita para un viajero. Son muchísimos, así
     * que no llenan la lista del Catálogo: aparecen cuando se busca algo o se explora una ruta.
     */
    OSM("Del mapa (OpenStreetMap)")
}

/**
 * Plataforma de origen de una fuente social / web.
 */
enum class SourcePlatform(val displayName: String) {
    TIKTOK("TikTok"),
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    YOUTUBE("YouTube"),
    WEB("Web"),
    GOOGLE_PLACES("Google Places"),
    OTHER("Otra");

    companion object {
        fun fromUrl(url: String): SourcePlatform {
            val u = url.lowercase()
            return when {
                "tiktok.com" in u -> TIKTOK
                "instagram.com" in u -> INSTAGRAM
                "facebook.com" in u || "fb.watch" in u || "fb.com" in u -> FACEBOOK
                "youtube.com" in u || "youtu.be" in u -> YOUTUBE
                "google.com/maps" in u || "maps.app.goo.gl" in u -> GOOGLE_PLACES
                u.startsWith("http") -> WEB
                else -> OTHER
            }
        }
    }
}

/**
 * Referencia a la fuente que respalda la información de un lugar
 * (un video de TikTok, un Reel, un video de YouTube, una página web, etc.).
 */
data class PlaceSource(
    val platform: SourcePlatform,
    val url: String,
    val title: String = "",
    val author: String = "",
    val thumbnailUrl: String = ""
)

/**
 * Datos de contacto de un lugar. Todos opcionales.
 */
data class PlaceContact(
    val phone: String = "",
    val whatsapp: String = "",
    val website: String = "",
    val instagram: String = "",
    val tiktok: String = "",
    val facebook: String = "",
    val email: String = ""
) {
    fun isEmpty(): Boolean =
        phone.isBlank() && whatsapp.isBlank() && website.isBlank() &&
            instagram.isBlank() && tiktok.isBlank() && facebook.isBlank() && email.isBlank()
}

/**
 * Lugar / parada: la unidad central de la app.
 * Puede venir del catálogo curado, del Radar IA, de un enlace compartido,
 * de Google Places o de un compañero de viaje.
 */
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: PlaceCategory,
    val region: EcuadorRegion,
    val province: String = "",
    val city: String = "",
    val address: String = "",
    val latitude: Double,
    val longitude: Double,
    val shortDescription: String = "",
    val fullDescription: String = "",
    val costMinUsd: Double = 0.0,
    val costMaxUsd: Double = 0.0,
    val costNotes: String = "",
    val openingHours: String = "",
    val howToGetThere: String = "",
    val transportOptions: List<String> = emptyList(),
    val altitudeMeters: Int? = null,
    val averageTempCelsius: Int? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val imageUrls: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val localTips: List<String> = emptyList(),
    val contact: PlaceContact = PlaceContact(),
    val sources: List<PlaceSource> = emptyList(),
    val verification: VerificationLevel = VerificationLevel.AI_SUGGESTED,
    val socialMentions: Int = 0,
    val googlePlaceId: String = "",
    val isSaved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Fotos que el propio viajero tomó y subió. Viven en este teléfono (ver PhotoStore). */
    val localPhotoPaths: List<String> = emptyList(),
    /**
     * true cuando quien agregó el lugar no estaba seguro de la ubicación exacta.
     * Invita a que otros viajeros la confirmen con un comentario.
     */
    val locationApproximate: Boolean = false
) {
    val costLabel: String
        get() = when {
            costMinUsd <= 0.0 && costMaxUsd <= 0.0 -> "Gratis / por confirmar"
            costMaxUsd <= costMinUsd -> "$${formatUsd(costMinUsd)} USD"
            else -> "$${formatUsd(costMinUsd)} - $${formatUsd(costMaxUsd)} USD"
        }

    val primaryImage: String?
        get() = imageUrls.firstOrNull { it.isNotBlank() }

    val locationLabel: String
        get() = listOf(city, province).filter { it.isNotBlank() }.distinct().joinToString(", ")
            .ifBlank { region.displayName }

    companion object {
        fun formatUsd(v: Double): String =
            if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v)
    }
}

/**
 * Ruta / travesía que el viajero arma. Contiene paradas ordenadas por día.
 */
data class TravelRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val originName: String = "",
    val destinationName: String = "",
    val startDate: String = "",
    val totalDays: Int = 1,
    val travelers: Int = 1,
    val travelStyle: String = "Moderado",
    val notes: String = "",
    val isActive: Boolean = true,
    val sharedGroupId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Transporte por defecto de toda la travesía. */
    val transportMode: TransportMode = TransportMode.BUS,
    /** A qué hora arranca cada día ("08:00"). */
    val dailyStartTime: String = "08:00",
    val stops: List<RouteStop> = emptyList()
) {
    val totalEstimatedCostUsd: Double
        get() = stops.sumOf { it.estimatedCostUsd }

    fun stopsForDay(day: Int): List<RouteStop> =
        stops.filter { it.dayNumber == day }.sortedBy { it.orderInDay }
}

/**
 * Parada dentro de una ruta, ligada a un lugar.
 */
data class RouteStop(
    val id: String = UUID.randomUUID().toString(),
    val routeId: String,
    val placeId: String,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val dayNumber: Int = 1,
    val orderInDay: Int = 0,
    val plannedTime: String = "",
    val durationMinutes: Int = 90,
    val estimatedCostUsd: Double = 0.0,
    val notes: String = "",
    val isDone: Boolean = false,
    /** Transporte de este tramo. Si es null se usa el de la ruta. */
    val transportMode: TransportMode? = null,
    /**
     * true solo si [plannedTime] es una hora REAL fija (sale un ferry, abre a cierta hora, reserva de tour).
     * Si es false, [plannedTime] es apenas una sugerencia y la llegada/salida se calculan según el
     * transporte elegido — así el horario cambia de verdad al cambiar de carro a bus, taxi, moto, etc.
     */
    val timeLocked: Boolean = false
)

/**
 * Tramo calculado entre dos paradas (OSRM).
 */
data class RouteLeg(
    val fromStopId: String,
    val toStopId: String,
    val distanceKm: Double,
    val durationMinutes: Int,
    val geometry: List<Pair<Double, Double>> // lat, lng
)

/**
 * Consulta al Radar IA: qué buscar a lo largo de un corredor.
 *
 * [originName] puede quedar VACÍO: en ese caso no hay corredor y la búsqueda se hace en un
 * radio alrededor de [destinationName] (ver [onlyDestination]). Así el viajero que solo sabe
 * "quiero ir a Baños" no recibe lugares de todo el Ecuador ni una ruta inventada.
 */
data class RadarRequest(
    val originName: String,
    val destinationName: String,
    val waypoints: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val maxDetourKm: Int = 25,
    val travelStyle: String = "Moderado",
    val freeText: String = ""
) {
    /** Sin salida indicada: se busca alrededor del destino, no a lo largo de una vía. */
    val onlyDestination: Boolean get() = originName.isBlank() && destinationName.isNotBlank()

    /** "Quito → Baños" o "Alrededor de Baños", para títulos y mensajes de progreso. */
    val label: String
        get() = if (onlyDestination) "Alrededor de " + destinationName.trim()
        else originName.trim() + " → " + destinationName.trim()
}

/**
 * Resultado del Radar IA.
 */
data class RadarResult(
    val places: List<Place>,
    val summary: String,
    val groundingSources: List<PlaceSource>,
    val fromCache: Boolean = false,
    /**
     * Cuántos kilómetros hay que desviarse de la vía principal para llegar a cada parada,
     * por id de lugar. Cuando la búsqueda es solo de destino, es la distancia al centro.
     */
    val detourKmByPlace: Map<String, Double> = emptyMap(),
    /** El corredor por carretera que se usó, para dibujarlo y recalcular desvíos. */
    val corridor: List<Pair<Double, Double>> = emptyList(),
    /** Zona de búsqueda cuando no hubo origen (círculo que se pinta en el mapa). */
    val area: SearchArea? = null
) {
    /** "junto a la vía" / "a 12 km de la vía" o "a 4 km del centro" en modo destino. */
    fun detourLabel(placeId: String): String? {
        val km = detourKmByPlace[placeId] ?: return null
        val donde = if (area != null) "del centro" else "de la vía"
        return when {
            km < 1.0 -> if (area != null) "en el centro" else "junto a la vía"
            km < 10.0 -> "a " + String.format(java.util.Locale.US, "%.1f", km) + " km " + donde
            else -> "a " + km.toInt() + " km " + donde
        }
    }
}

/**
 * Estado de la extracción de un enlace compartido (TikTok, Reel, Short...).
 */
sealed class LinkExtractionState {
    object Idle : LinkExtractionState()
    data class Loading(val message: String) : LinkExtractionState()
    data class Success(val place: Place, val source: PlaceSource) : LinkExtractionState()
    data class Error(val message: String) : LinkExtractionState()
}

package ec.travesias.app.data.model

/**
 * Regiones geográficas de Ecuador
 */
enum class EcuadorRegion(val displayName: String, val description: String) {
    ANDES("Sierra / Andes", "Volcanes, ciudades coloniales y páramos andinos"),
    COAST("Costa del Pacífico", "Playas vírgenes, gastronomía marina y manglares"),
    AMAZON("Amazonía / Oriente", "Biodiversidad pura, selva tropical y comunidades indígenas"),
    GALAPAGOS("Islas Galápagos", "Fauna única en el mundo y reservas marinas protegidas");

    companion object {
        fun fromKeyword(k: String?): EcuadorRegion {
            val v = k?.trim()?.lowercase() ?: return ANDES
            return when {
                "galap" in v || "galáp" in v -> GALAPAGOS
                "amaz" in v || "orient" in v || "selva" in v -> AMAZON
                "costa" in v || "coast" in v || "pacif" in v || "pacíf" in v -> COAST
                else -> ANDES
            }
        }

        /** Región aproximada según coordenadas (Ecuador continental e insular). */
        fun fromCoordinates(lat: Double, lng: Double): EcuadorRegion = when {
            lng < -85.0 -> GALAPAGOS
            lng > -77.9 -> AMAZON
            lng > -79.4 -> ANDES   // corredor andino aprox. (Loja, Cuenca, Quito, Ibarra)
            else -> COAST
        }
    }
}

/**
 * Categoría de atractivo turístico
 */
enum class PlaceCategory(val displayName: String, val aiKeyword: String) {
    ALL("Todo", "all"),
    NATURE("Naturaleza", "nature"),
    FOOD("Gastronomía", "food"),
    CULTURE("Cultura", "culture"),
    ADVENTURE("Aventura", "adventure"),
    BEACH("Playa", "beach"),
    VIEWPOINT("Mirador / Foto", "viewpoint"),
    LODGING("Hospedaje", "lodging"),
    SERVICES("Servicios / Parada", "services");

    companion object {
        fun fromKeyword(k: String?): PlaceCategory {
            val v = k?.trim()?.lowercase() ?: return NATURE
            return values().firstOrNull { it.aiKeyword == v || it.displayName.lowercase() == v }
                ?: when {
                    "comida" in v || "restaurant" in v || "gastro" in v || "hueca" in v -> FOOD
                    "playa" in v -> BEACH
                    "mirador" in v || "foto" in v -> VIEWPOINT
                    "hotel" in v || "hostal" in v || "hosped" in v -> LODGING
                    "gasolin" in v || "servicio" in v || "parada" in v || "peaje" in v -> SERVICES
                    "aventura" in v || "rafting" in v || "canopy" in v -> ADVENTURE
                    "cultura" in v || "museo" in v || "iglesia" in v || "historia" in v -> CULTURE
                    else -> NATURE
                }
        }
    }
}

/**
 * Destino o punto de interés en Ecuador
 */
data class DestinationItem(
    val id: String,
    val name: String,
    val region: EcuadorRegion,
    val province: String,
    val category: PlaceCategory,
    val shortDescription: String,
    val fullDescription: String,
    val altitudeMeters: Int,
    val averageTempCelsius: Int,
    val estimatedBudgetUsd: Double,
    val imageUrl: String,
    val tags: List<String> = emptyList(),
    val localTips: List<String> = emptyList(),
    val isSaved: Boolean = false
)

/**
 * Ítem de actividad en el timeline
 */
data class ActivityItem(
    val id: String,
    val time: String,
    val title: String,
    val description: String,
    val location: String,
    val isCompleted: Boolean = false,
    val isNext: Boolean = false,
    val hasWeatherAlert: Boolean = false,
    val alternativePlan: String? = null,
    val costUsd: Double = 0.0
)

/**
 * Día dentro de un itinerario
 */
data class ItineraryDay(
    val dayNumber: Int,
    val title: String,
    val region: String,
    val temperatureC: Int,
    val weatherCondition: String,
    val activities: List<ActivityItem>
)

/**
 * Plan de viaje / Itinerario completo
 */
data class ItineraryPlan(
    val id: String,
    val title: String,
    val summary: String,
    val targetRegion: EcuadorRegion,
    val totalDays: Int,
    val travelStyle: String, // "Mochilero", "Moderado", "Lujo"
    val travelers: Int,
    val totalBudgetUsd: Double,
    val remainingBudgetUsd: Double,
    val days: List<ItineraryDay>
)

/**
 * Hallazgo o tendencia del Radar Social IA
 */
data class RadarDiscovery(
    val id: String,
    val title: String,
    val location: String,
    val platformSource: String, // "TikTok", "Instagram", "YouTube"
    val videoCount: Int,
    val verifiedTip: String,
    val priceEstimate: String,
    val imageUrl: String,
    val tags: List<String>
)

/**
 * Mensaje para el Asistente Guía IA
 */
data class ChatMessage(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val suggestions: List<String> = emptyList(),
    /** Herramientas que usó el agente y cambios que hizo de verdad en la ruta. */
    val steps: List<String> = emptyList(),
    /** true si esta respuesta modificó la ruta del viajero. */
    val routeChanged: Boolean = false,
    /** true si se respondió sin conexión, con el planificador local. */
    val offline: Boolean = false
)

enum class MessageSender {
    USER,
    AI,
    SYSTEM
}

/**
 * Directorio de emergencia en Ecuador
 */
data class EmergencyContact(
    val title: String,
    val number: String,
    val description: String,
    val iconType: String
)

/**
 * Información de aclimatación para el mal de altura (Soroche)
 */
data class AltitudeGuide(
    val cityOrMountain: String,
    val altitudeMeters: Int,
    val riskLevel: String, // "Bajo", "Moderado", "Alto", "Extremo"
    val recommendations: List<String>
)

/**
 * Elemento de checklist de equipaje
 */
data class PackingItem(
    val id: Long = 0,
    val name: String,
    val category: String, // "Documentos", "Ropa Sierra", "Ropa Costa/Galápagos", "Salud/Botiquín"
    val isChecked: Boolean = false
)

/**
 * Cómo se mueve el viajero entre paradas. Sirve para calcular tiempos reales:
 * OSRM devuelve tiempos de carro, así que el resto se ajusta con un factor y una
 * espera fija (terminales, esperar el bus, pedir el taxi…).
 */
enum class TransportMode(
    val displayName: String,
    val shortName: String,
    /** Multiplicador sobre el tiempo en carro de OSRM. 0 = se calcula por velocidad propia. */
    val factor: Double,
    /** Minutos fijos de espera/abordaje por tramo. */
    val waitMinutes: Int,
    /** Velocidad media km/h cuando no se usa la carretera (bici, a pie). */
    val ownSpeedKmh: Double = 0.0,
    val displayNameEn: String = "",
    val shortNameEn: String = ""
) {
    CAR("Carro propio", "carro", 1.0, 0, displayNameEn = "Own car", shortNameEn = "car"),
    BUS("Bus interprovincial", "bus", 1.45, 25, displayNameEn = "Intercity bus", shortNameEn = "bus"),
    TAXI("Taxi o app", "taxi", 1.05, 8, displayNameEn = "Taxi or app", shortNameEn = "taxi"),
    MOTO("Moto", "moto", 1.1, 10, displayNameEn = "Motorcycle", shortNameEn = "moto"),
    TOUR("Tour contratado", "tour", 1.15, 20, displayNameEn = "Booked tour", shortNameEn = "tour"),
    BIKE("Bicicleta", "bici", 0.0, 5, ownSpeedKmh = 15.0, displayNameEn = "Bicycle", shortNameEn = "bike"),
    WALK("A pie", "a pie", 0.0, 0, ownSpeedKmh = 4.5, displayNameEn = "On foot", shortNameEn = "on foot"),
    MIXED("Combinado", "mixto", 1.3, 20, displayNameEn = "Mixed", shortNameEn = "mixed");

    companion object {
        fun from(value: String?): TransportMode {
            val v = value?.trim()?.lowercase() ?: return BUS
            return values().firstOrNull {
                it.name.lowercase() == v || it.displayName.lowercase() == v || it.shortName == v
            } ?: when {
                v.contains("carro") || v.contains("auto") || v.contains("coche") || v.contains("propio") -> CAR
                v.contains("bus") || v.contains("buseta") -> BUS
                v.contains("taxi") || v.contains("uber") || v.contains("cabify") -> TAXI
                v.contains("moto") -> MOTO
                v.contains("tour") || v.contains("agencia") -> TOUR
                v.contains("bici") || v.contains("bike") -> BIKE
                v.contains("pie") || v.contains("camin") -> WALK
                else -> BUS
            }
        }
    }
}
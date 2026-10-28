package ec.travesias.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ec.travesias.app.data.model.*

/**
 * Lugar / parada persistido. Es la caché offline de todo lo que la IA,
 * el catálogo, los enlaces compartidos o los compañeros aportan.
 */
@Entity(tableName = "places", indices = [Index("region"), Index("category"), Index("isSaved")])
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val region: String,
    val province: String,
    val city: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val shortDescription: String,
    val fullDescription: String,
    val costMinUsd: Double,
    val costMaxUsd: Double,
    val costNotes: String,
    val openingHours: String,
    val howToGetThere: String,
    val transportOptionsJson: String,
    val altitudeMeters: Int?,
    val averageTempCelsius: Int?,
    val rating: Double?,
    val ratingCount: Int?,
    val imageUrlsJson: String,
    val tagsJson: String,
    val localTipsJson: String,
    val contactJson: String,
    val sourcesJson: String,
    val verification: String,
    val socialMentions: Int,
    val googlePlaceId: String,
    val isSaved: Boolean,
    val createdAt: Long,
    val localPhotoPathsJson: String = "[]",
    val locationApproximate: Boolean = false
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val originName: String,
    val destinationName: String,
    val startDate: String,
    val totalDays: Int,
    val travelers: Int,
    val travelStyle: String,
    val notes: String,
    val isActive: Boolean,
    val sharedGroupId: String,
    val createdAt: Long,
    /** Nullable a propósito: así la migración no necesita DEFAULT y Room no se queja. */
    val transportMode: String? = null,
    val dailyStartTime: String? = null
)

@Entity(tableName = "route_stops", indices = [Index("routeId"), Index("placeId")])
data class RouteStopEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val placeId: String,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val dayNumber: Int,
    val orderInDay: Int,
    val plannedTime: String,
    val durationMinutes: Int,
    val estimatedCostUsd: Double,
    val notes: String,
    val isDone: Boolean,
    val transportMode: String? = null,
    val timeLocked: Boolean = false
)

/** Caché de consultas del Radar IA para funcionar offline y ahorrar cuota. */
@Entity(tableName = "radar_cache")
data class RadarCacheEntity(
    @PrimaryKey val queryKey: String,
    val summary: String,
    val placeIdsJson: String,
    val groundingSourcesJson: String,
    val createdAt: Long
)

// ---------- Entidades heredadas (se mantienen) ----------

@Entity(tableName = "saved_itineraries")
data class SavedItineraryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val region: String,
    val totalDays: Int,
    val travelStyle: String,
    val travelers: Int,
    val totalBudgetUsd: Double,
    val remainingBudgetUsd: Double,
    val daysJson: String,
    val createdAtTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "offline_packages")
data class OfflinePackageEntity(
    @PrimaryKey val id: String,
    val regionName: String,
    val province: String,
    val sizeMb: Int,
    val isDownloaded: Boolean,
    val downloadProgress: Float, // 0.0 to 1.0
    val lastUpdated: String,
    // Bounding box del paquete para descargar teselas del mapa
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0
)

@Entity(tableName = "packing_checklist")
data class PackingChecklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val isChecked: Boolean = false
)

// ---------- Mapeos dominio <-> entidad ----------

fun Place.toEntity(): PlaceEntity = PlaceEntity(
    id = id,
    name = name,
    category = category.name,
    region = region.name,
    province = province,
    city = city,
    address = address,
    latitude = latitude,
    longitude = longitude,
    shortDescription = shortDescription,
    fullDescription = fullDescription,
    costMinUsd = costMinUsd,
    costMaxUsd = costMaxUsd,
    costNotes = costNotes,
    openingHours = openingHours,
    howToGetThere = howToGetThere,
    transportOptionsJson = JsonCodec.stringList(transportOptions),
    altitudeMeters = altitudeMeters,
    averageTempCelsius = averageTempCelsius,
    rating = rating,
    ratingCount = ratingCount,
    imageUrlsJson = JsonCodec.stringList(imageUrls),
    tagsJson = JsonCodec.stringList(tags),
    localTipsJson = JsonCodec.stringList(localTips),
    contactJson = JsonCodec.contact(contact),
    sourcesJson = JsonCodec.sources(sources),
    verification = verification.name,
    socialMentions = socialMentions,
    googlePlaceId = googlePlaceId,
    isSaved = isSaved,
    createdAt = createdAt,
    localPhotoPathsJson = JsonCodec.stringList(localPhotoPaths),
    locationApproximate = locationApproximate
)

fun PlaceEntity.toDomain(): Place = Place(
    id = id,
    name = name,
    category = runCatching { PlaceCategory.valueOf(category) }.getOrDefault(PlaceCategory.NATURE),
    region = runCatching { EcuadorRegion.valueOf(region) }.getOrDefault(EcuadorRegion.ANDES),
    province = province,
    city = city,
    address = address,
    latitude = latitude,
    longitude = longitude,
    shortDescription = shortDescription,
    fullDescription = fullDescription,
    costMinUsd = costMinUsd,
    costMaxUsd = costMaxUsd,
    costNotes = costNotes,
    openingHours = openingHours,
    howToGetThere = howToGetThere,
    transportOptions = JsonCodec.parseStringList(transportOptionsJson),
    altitudeMeters = altitudeMeters,
    averageTempCelsius = averageTempCelsius,
    rating = rating,
    ratingCount = ratingCount,
    imageUrls = JsonCodec.parseStringList(imageUrlsJson),
    tags = JsonCodec.parseStringList(tagsJson),
    localTips = JsonCodec.parseStringList(localTipsJson),
    contact = JsonCodec.parseContact(contactJson),
    sources = JsonCodec.parseSources(sourcesJson),
    verification = runCatching { VerificationLevel.valueOf(verification) }.getOrDefault(VerificationLevel.AI_SUGGESTED),
    socialMentions = socialMentions,
    googlePlaceId = googlePlaceId,
    isSaved = isSaved,
    createdAt = createdAt,
    localPhotoPaths = JsonCodec.parseStringList(localPhotoPathsJson),
    locationApproximate = locationApproximate
)

fun TravelRoute.toEntity(): RouteEntity = RouteEntity(
    id, name, originName, destinationName, startDate, totalDays, travelers, travelStyle, notes, isActive,
    sharedGroupId, createdAt, transportMode.name, dailyStartTime
)

fun RouteEntity.toDomain(stops: List<RouteStop> = emptyList()): TravelRoute = TravelRoute(
    id = id, name = name, originName = originName, destinationName = destinationName, startDate = startDate,
    totalDays = totalDays, travelers = travelers, travelStyle = travelStyle, notes = notes, isActive = isActive,
    sharedGroupId = sharedGroupId, createdAt = createdAt,
    transportMode = TransportMode.from(transportMode),
    dailyStartTime = dailyStartTime?.takeIf { it.isNotBlank() } ?: "08:00",
    stops = stops
)

fun RouteStop.toEntity(): RouteStopEntity = RouteStopEntity(
    id, routeId, placeId, placeName, latitude, longitude, dayNumber, orderInDay, plannedTime, durationMinutes,
    estimatedCostUsd, notes, isDone, transportMode?.name, timeLocked
)

fun RouteStopEntity.toDomain(): RouteStop = RouteStop(
    id, routeId, placeId, placeName, latitude, longitude, dayNumber, orderInDay, plannedTime, durationMinutes,
    estimatedCostUsd, notes, isDone,
    transportMode?.takeIf { it.isNotBlank() }?.let { TransportMode.from(it) },
    timeLocked
)


/**
 * Cola de lugares por compartir con el resto de viajeros.
 *
 * Cuando alguien agrega un lugar (a mano, con el Radar o desde un enlace) se apunta aquí.
 * En cuanto hay señal, [ec.travesias.app.data.social.CatalogSync] los sube al catálogo común
 * y borra la fila. Así no se pierde nada aunque el aporte se haga sin internet.
 */
@Entity(tableName = "catalog_outbox")
data class CatalogOutboxEntity(
    @PrimaryKey val placeId: String,
    val queuedAt: Long
)

package ec.travesias.app.data.repository

import ec.travesias.app.data.remote.RoutingService
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.GazetteerData
import ec.travesias.app.data.local.GazetteerEntity
import ec.travesias.app.data.remote.GeocodingService
import java.text.Normalizer

/** Una sugerencia del buscador: sale del teléfono, no de internet. */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val kind: String,
    val parent: String,
    val latitude: Double,
    val longitude: Double,
    val placeId: String
) {
    val hasCoordinates: Boolean get() = latitude != 0.0 || longitude != 0.0
    val subtitle: String get() = if (parent.isBlank() || parent == "Ecuador") kind else "$kind · $parent"
}

/** Quita tildes y pasa a minúsculas para que "banos" encuentre "Baños". */
fun normalizeSearch(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Buscador de lugares de toda la app: provincias, cantones, localidades turísticas y los
 * lugares del catálogo, todo desde la base local. Aprende los sitios nuevos que se buscan.
 */
class PlaceIndexRepository(
    private val db: AppDatabase,
    private val geocoding: GeocodingService
) {
    private val dao = db.gazetteerDao()
    private val placeDao = db.placeDao()

    companion object {
        const val KIND_PLACE = "lugar"
        const val KIND_ADDRESS = "dirección"
    }

    /** Carga el nomenclátor la primera vez y refresca los lugares del catálogo. */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (dao.count() < GazetteerData.all.size) {
            dao.upsertAll(GazetteerData.all.map { it.toRow(now) })
        }
        val places = runCatching { placeDao.getInBounds(-90.0, 90.0, -180.0, 180.0) }.getOrDefault(emptyList())
        if (places.isNotEmpty()) {
            dao.upsertAll(
                places.map { p ->
                    GazetteerEntity(
                        id = "lugar:" + p.id,
                        name = p.name,
                        normalized = normalizeSearch(p.name + " " + p.city + " " + p.province),
                        kind = KIND_PLACE,
                        parent = listOf(p.city, p.province).filter { it.isNotBlank() }.joinToString(", "),
                        latitude = p.latitude,
                        longitude = p.longitude,
                        placeId = p.id,
                        usageCount = 1,
                        updatedAt = now
                    )
                }
            )
        }
    }

    /** Sugerencias mientras se escribe. Instantáneo: no toca la red. */
    /**
     * Nombre del sitio poblado más cercano a unas coordenadas, usando el nomenclátor que va
     * compilado en la app. Funciona sin señal y es instantáneo: se usa para rellenar solo el
     * campo "Desde" del Radar con el lugar donde está el viajero.
     *
     * Devuelve null si lo más cercano queda a más de [maxKm] (por ejemplo, fuera de Ecuador).
     */
    fun nearestName(lat: Double, lng: Double, maxKm: Double = 60.0): String? {
        var mejor: GazetteerData.Entry? = null
        var mejorPuntaje = Double.MAX_VALUE
        var mejorDist = Double.MAX_VALUE
        for (e in GazetteerData.all) {
            if (e.latitude == 0.0 && e.longitude == 0.0) continue
            val d = RoutingService.haversineKm(lat, lng, e.latitude, e.longitude)
            // El cantón es lo que el viajero llamaría "donde estoy" (Quito, Cuenca, Guayaquil).
            // La provincia es el respaldo, y las localidades van al final porque muchas son
            // sitios turísticos: estando en Guayaquil no queremos poner "Cerro Santa Ana" como
            // origen, ni "Centro Histórico de Quito" estando en Quito.
            //
            // La penalización se SUMA en km, no se multiplica: estando encima de una ciudad
            // todas las distancias son ~0 y multiplicar dejaría el empate sin resolver, con lo
            // que ganaba la que apareciera primero en la lista.
            val castigoKm = when (e.kind) {
                GazetteerData.KIND_CANTON -> 0.0
                GazetteerData.KIND_PROVINCE -> 6.0
                else -> 12.0
            }
            val puntaje = d + castigoKm
            if (puntaje < mejorPuntaje) { mejorPuntaje = puntaje; mejorDist = d; mejor = e }
        }
        // El límite se mide con la distancia REAL, no con la penalizada.
        if (mejor == null || mejorDist > maxKm) return null
        return mejor.name
    }

    suspend fun suggest(query: String, limit: Int = 8): List<PlaceSuggestion> {
        val q = normalizeSearch(query)
        if (q.length < 2) return emptyList()
        val head = dao.startsWith(q, limit)
        val rest = if (head.size >= limit) emptyList() else dao.contains(q, limit * 2)
        return (head + rest)
            .distinctBy { it.id }
            .take(limit)
            .map { it.toSuggestion() }
    }

    /** Marca que se usó una sugerencia, para que suba en la lista la próxima vez. */
    suspend fun markUsed(suggestion: PlaceSuggestion) {
        runCatching { dao.bump(suggestion.id, System.currentTimeMillis()) }
    }

    /**
     * Devuelve las coordenadas de la sugerencia. Si el nomenclátor todavía no las tenía,
     * las busca una sola vez en OpenStreetMap y las guarda en el teléfono para siempre.
     */
    suspend fun resolveCoordinates(suggestion: PlaceSuggestion): Pair<Double, Double>? {
        if (suggestion.hasCoordinates) return Pair(suggestion.latitude, suggestion.longitude)
        val termino = if (suggestion.parent.isBlank() || suggestion.parent == "Ecuador") suggestion.name
        else suggestion.name + ", " + suggestion.parent
        val hit = runCatching { geocoding.search(termino, limit = 1).firstOrNull() }.getOrNull() ?: return null
        runCatching { dao.setCoordinates(suggestion.id, hit.latitude, hit.longitude, System.currentTimeMillis()) }
        return Pair(hit.latitude, hit.longitude)
    }

    /**
     * Provincia más cercana en línea recta a un punto, SIN internet. Se usa como respaldo cuando
     * no hay señal para geocodificar (por ejemplo, al agregar un lugar nuevo desde el campo).
     */
    fun nearestProvince(latitude: Double, longitude: Double): String? =
        GazetteerData.provinces.minByOrNull {
            RoutingService.haversineKm(latitude, longitude, it.latitude, it.longitude)
        }?.name

    /** Cantones conocidos de una provincia, offline, para elegir cuando no se puede geocodificar. */
    fun cantonsOf(province: String): List<String> =
        GazetteerData.cantons.filter { it.parent.equals(province, ignoreCase = true) }.map { it.name }

    /** Guarda una dirección que el usuario encontró, para que la próxima vez salga sola. */
    suspend fun learnAddress(name: String, parent: String, latitude: Double, longitude: Double) {
        if (name.isBlank()) return
        val normalized = normalizeSearch(name + " " + parent)
        if (normalized.isBlank()) return
        val id = "dir:" + normalized.replace(" ", "_").take(80)
        val existing = dao.getById(id)
        dao.upsert(
            GazetteerEntity(
                id = id,
                name = name.take(80),
                normalized = normalized,
                kind = KIND_ADDRESS,
                parent = parent.take(80),
                latitude = latitude,
                longitude = longitude,
                placeId = "",
                usageCount = (existing?.usageCount ?: 0) + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ---------------------------------------------------------------- mapeos

    private fun GazetteerData.Entry.toRow(now: Long) = GazetteerEntity(
        id = kind + ":" + normalizeSearch(name).replace(" ", "_"),
        name = name,
        normalized = normalizeSearch(name + " " + parent),
        kind = kind,
        parent = parent,
        latitude = latitude,
        longitude = longitude,
        placeId = "",
        usageCount = when (kind) {
            GazetteerData.KIND_PROVINCE -> 30
            GazetteerData.KIND_CANTON -> 20
            else -> 10
        },
        updatedAt = now
    )

    private fun GazetteerEntity.toSuggestion() = PlaceSuggestion(
        id = id, name = name, kind = kind, parent = parent,
        latitude = latitude, longitude = longitude, placeId = placeId
    )
}

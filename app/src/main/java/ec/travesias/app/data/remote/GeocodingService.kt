package ec.travesias.app.data.remote

import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * Geocodificación gratuita con Nominatim (OpenStreetMap), limitada a Ecuador.
 * Respeta la política de uso: 1 petición por segundo y User-Agent identificable.
 */
class GeocodingService {

    data class GeoResult(
        val latitude: Double,
        val longitude: Double,
        val displayName: String,
        val province: String,
        val city: String
    )

    private var lastCallAt = 0L

    private suspend fun throttle() {
        val now = System.currentTimeMillis()
        val wait = 1100 - (now - lastCallAt)
        if (wait > 0) delay(wait)
        lastCallAt = System.currentTimeMillis()
    }

    /** Busca un lugar por texto dentro de Ecuador. */
    suspend fun search(query: String, limit: Int = 1): List<GeoResult> {
        if (query.isBlank()) return emptyList()
        throttle()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ec&addressdetails=1&limit=$limit&q=" +
            Http.encode(if (query.contains("ecuador", ignoreCase = true)) query else "$query, Ecuador")
        val raw = runCatching { Http.get(url, mapOf("Accept-Language" to "es")) }.getOrNull() ?: return emptyList()
        return parse(raw)
    }

    /** Coordenadas -> nombre de lugar (para la ubicación actual del usuario). */
    suspend fun reverse(lat: Double, lng: Double): GeoResult? {
        throttle()
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&lat=$lat&lon=$lng"
        val raw = runCatching { Http.get(url, mapOf("Accept-Language" to "es")) }.getOrNull() ?: return null
        return runCatching { parse("[$raw]").firstOrNull() }.getOrNull()
    }

    private fun parse(raw: String): List<GeoResult> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val addr = o.optJSONObject("address")
            GeoResult(
                latitude = lat,
                longitude = lon,
                displayName = o.optString("display_name", ""),
                province = addr?.optString("state", "")?.removePrefix("Provincia de ")?.trim() ?: "",
                city = addr?.let { a ->
                    listOf("city", "town", "village", "municipality", "county").map { a.optString(it, "") }.firstOrNull { it.isNotBlank() }
                } ?: ""
            )
        }
    }
}

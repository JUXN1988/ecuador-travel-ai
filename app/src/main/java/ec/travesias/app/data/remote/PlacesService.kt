package ec.travesias.app.data.remote

import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceContact
import ec.travesias.app.data.model.PlaceSource
import ec.travesias.app.data.model.SourcePlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Places API (New) — enriquece un lugar con teléfono, web, horario,
 * calificación, rango de precio, dirección y fotos. Opcional (PLACES_API_KEY).
 */
class PlacesService(private val apiKeyProvider: () -> String = { Secrets.placesApiKey }) {

    val isAvailable: Boolean get() = apiKeyProvider().isNotBlank()

    private val fieldMask = listOf(
        "places.id", "places.displayName", "places.formattedAddress", "places.location",
        "places.rating", "places.userRatingCount", "places.priceLevel", "places.websiteUri",
        "places.internationalPhoneNumber", "places.nationalPhoneNumber", "places.regularOpeningHours",
        "places.photos", "places.googleMapsUri", "places.editorialSummary"
    ).joinToString(",")

    /**
     * Busca el lugar por nombre cerca de sus coordenadas y devuelve una copia enriquecida.
     * Nunca lanza: si algo falla devuelve el lugar original.
     */
    suspend fun enrich(place: Place): Place {
        val key = apiKeyProvider()
        if (key.isBlank()) return place
        return runCatching {
            val body = JSONObject().apply {
                put("textQuery", "${place.name} ${place.city.ifBlank { place.province }} Ecuador")
                put("languageCode", "es")
                put("regionCode", "EC")
                put("maxResultCount", 1)
                put("locationBias", JSONObject().put("circle", JSONObject().apply {
                    put("center", JSONObject().put("latitude", place.latitude).put("longitude", place.longitude))
                    put("radius", 15000.0)
                }))
            }
            val raw = Http.postJson(
                "https://places.googleapis.com/v1/places:searchText",
                body.toString(),
                mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to fieldMask)
            )
            val first = JSONObject(raw).optJSONArray("places")?.optJSONObject(0) ?: return place
            merge(place, first, key)
        }.getOrDefault(place)
    }

    private fun merge(place: Place, p: JSONObject, key: String): Place {
        val phone = p.optString("nationalPhoneNumber", p.optString("internationalPhoneNumber", ""))
        val website = p.optString("websiteUri", "")
        val hours = p.optJSONObject("regularOpeningHours")?.optJSONArray("weekdayDescriptions")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.joinToString(" · ")
        } ?: ""
        val photos = p.optJSONArray("photos")?.let { arr ->
            (0 until minOf(3, arr.length())).mapNotNull { i ->
                val name = arr.optJSONObject(i)?.optString("name", "") ?: ""
                if (name.isBlank()) null else "https://places.googleapis.com/v1/$name/media?maxWidthPx=800&key=$key"
            }
        } ?: emptyList()
        val (min, max) = priceRange(p.optString("priceLevel", ""))
        val mapsUri = p.optString("googleMapsUri", "")
        val editorial = p.optJSONObject("editorialSummary")?.optString("text", "") ?: ""
        val loc = p.optJSONObject("location")

        return place.copy(
            googlePlaceId = p.optString("id", place.googlePlaceId),
            address = p.optString("formattedAddress", place.address).ifBlank { place.address },
            latitude = loc?.optDouble("latitude", place.latitude) ?: place.latitude,
            longitude = loc?.optDouble("longitude", place.longitude) ?: place.longitude,
            rating = p.optDouble("rating", Double.NaN).takeIf { !it.isNaN() } ?: place.rating,
            ratingCount = p.optInt("userRatingCount", -1).takeIf { it >= 0 } ?: place.ratingCount,
            openingHours = hours.ifBlank { place.openingHours },
            costMinUsd = if (place.costMinUsd == 0.0 && place.costMaxUsd == 0.0 && min > 0) min else place.costMinUsd,
            costMaxUsd = if (place.costMinUsd == 0.0 && place.costMaxUsd == 0.0 && max > 0) max else place.costMaxUsd,
            fullDescription = place.fullDescription.ifBlank { editorial },
            imageUrls = (place.imageUrls + photos).distinct(),
            contact = place.contact.copy(
                phone = place.contact.phone.ifBlank { phone },
                website = place.contact.website.ifBlank { website }
            ),
            sources = if (mapsUri.isNotBlank() && place.sources.none { it.url == mapsUri })
                place.sources + PlaceSource(SourcePlatform.GOOGLE_PLACES, mapsUri, "Ficha en Google Maps")
            else place.sources
        )
    }

    private fun priceRange(level: String): Pair<Double, Double> = when (level) {
        "PRICE_LEVEL_FREE" -> 0.0 to 0.0
        "PRICE_LEVEL_INEXPENSIVE" -> 3.0 to 10.0
        "PRICE_LEVEL_MODERATE" -> 10.0 to 25.0
        "PRICE_LEVEL_EXPENSIVE" -> 25.0 to 60.0
        "PRICE_LEVEL_VERY_EXPENSIVE" -> 60.0 to 150.0
        else -> 0.0 to 0.0
    }
}

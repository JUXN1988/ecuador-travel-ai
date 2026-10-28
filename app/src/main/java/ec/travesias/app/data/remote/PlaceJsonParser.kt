package ec.travesias.app.data.remote

import ec.travesias.app.data.model.*
import ec.travesias.app.data.remote.JsonExtract.optDoubleOrNull
import ec.travesias.app.data.remote.JsonExtract.optStringList
import org.json.JSONObject

/**
 * Convierte el JSON que devuelve la IA en un [Place]. Tolerante a campos faltantes
 * y a nombres alternativos (snake_case / camelCase).
 */
object PlaceJsonParser {

    /** Esquema que se le pide al modelo. Se incluye en los prompts. */
    val SCHEMA = """
        {
          "name": "string",
          "category": "nature|food|culture|adventure|beach|viewpoint|lodging|services",
          "province": "string",
          "city": "string",
          "address": "string",
          "latitude": number,
          "longitude": number,
          "short_description": "1 frase",
          "full_description": "2-4 frases",
          "cost_min_usd": number,
          "cost_max_usd": number,
          "cost_notes": "string (qué incluye, entradas, etc.)",
          "opening_hours": "string",
          "how_to_get_there": "string (indicaciones concretas desde la vía principal o ciudad cercana)",
          "transport_options": ["bus", "taxi", "vehículo propio", ...],
          "altitude_m": number|null,
          "tags": ["string"],
          "local_tips": ["string"],
          "contact": {"phone": "", "whatsapp": "", "website": "", "instagram": "", "tiktok": "", "facebook": ""},
          "social_mentions": number (cuántos videos/publicaciones encontraste),
          "sources": [{"platform": "tiktok|instagram|facebook|youtube|web", "url": "https://...", "title": "string"}]
        }
    """.trimIndent()

    fun parse(o: JSONObject, defaultVerification: VerificationLevel = VerificationLevel.AI_SUGGESTED): Place? {
        val name = o.optString("name", o.optString("nombre", "")).trim()
        if (name.isBlank()) return null
        val lat = o.optDoubleOrNull("latitude") ?: o.optDoubleOrNull("lat") ?: o.optDoubleOrNull("latitud")
        val lng = o.optDoubleOrNull("longitude") ?: o.optDoubleOrNull("lng") ?: o.optDoubleOrNull("lon") ?: o.optDoubleOrNull("longitud")
        val validCoords = lat != null && lng != null && isInEcuador(lat, lng)

        val c = o.optJSONObject("contact") ?: o.optJSONObject("contacto") ?: JSONObject()
        val contact = PlaceContact(
            phone = c.optString("phone", c.optString("telefono", "")),
            whatsapp = c.optString("whatsapp", ""),
            website = c.optString("website", c.optString("web", "")),
            instagram = c.optString("instagram", ""),
            tiktok = c.optString("tiktok", ""),
            facebook = c.optString("facebook", ""),
            email = c.optString("email", "")
        )

        val sources = (o.optJSONArray("sources") ?: o.optJSONArray("fuentes"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i)
                if (s != null) {
                    val url = s.optString("url", "")
                    if (url.isBlank() || !url.startsWith("http")) null
                    else PlaceSource(
                        platform = platformOf(s.optString("platform", ""), url),
                        url = url,
                        title = s.optString("title", ""),
                        author = s.optString("author", "")
                    )
                } else {
                    val url = arr.optString(i, "")
                    if (url.startsWith("http")) PlaceSource(SourcePlatform.fromUrl(url), url) else null
                }
            }
        } ?: emptyList()

        val province = o.optString("province", o.optString("provincia", "")).trim()
        val region = if (validCoords) EcuadorRegion.fromCoordinates(lat!!, lng!!)
        else EcuadorRegion.fromKeyword(o.optString("region", province))

        val costMin = o.optDoubleOrNull("cost_min_usd") ?: o.optDoubleOrNull("costMinUsd") ?: o.optDoubleOrNull("cost_usd") ?: 0.0
        val costMax = o.optDoubleOrNull("cost_max_usd") ?: o.optDoubleOrNull("costMaxUsd") ?: costMin

        return Place(
            name = name,
            category = PlaceCategory.fromKeyword(o.optString("category", o.optString("categoria", ""))),
            region = region,
            province = province,
            city = o.optString("city", o.optString("ciudad", "")).trim(),
            address = o.optString("address", o.optString("direccion", "")).trim(),
            latitude = if (validCoords) lat!! else 0.0,
            longitude = if (validCoords) lng!! else 0.0,
            shortDescription = o.optString("short_description", o.optString("shortDescription", o.optString("descripcion_corta", ""))).trim(),
            fullDescription = o.optString("full_description", o.optString("fullDescription", o.optString("descripcion", ""))).trim(),
            costMinUsd = costMin.coerceAtLeast(0.0),
            costMaxUsd = costMax.coerceAtLeast(0.0),
            costNotes = o.optString("cost_notes", o.optString("costNotes", "")).trim(),
            openingHours = o.optString("opening_hours", o.optString("openingHours", o.optString("horario", ""))).trim(),
            howToGetThere = o.optString("how_to_get_there", o.optString("howToGetThere", o.optString("como_llegar", ""))).trim(),
            transportOptions = o.optStringList("transport_options").ifEmpty { o.optStringList("transportOptions") },
            altitudeMeters = (o.optDoubleOrNull("altitude_m") ?: o.optDoubleOrNull("altitudeMeters"))?.toInt(),
            tags = o.optStringList("tags"),
            localTips = o.optStringList("local_tips").ifEmpty { o.optStringList("localTips") },
            contact = contact,
            sources = sources,
            socialMentions = (o.optDoubleOrNull("social_mentions") ?: o.optDoubleOrNull("socialMentions") ?: 0.0).toInt(),
            imageUrls = o.optStringList("image_urls").ifEmpty { o.optStringList("imageUrls") }.filter { it.startsWith("http") },
            verification = if (sources.isNotEmpty() && defaultVerification == VerificationLevel.AI_SUGGESTED) VerificationLevel.VERIFIED else defaultVerification
        )
    }

    private fun platformOf(raw: String, url: String): SourcePlatform {
        val r = raw.lowercase()
        return when {
            "tiktok" in r -> SourcePlatform.TIKTOK
            "instagram" in r -> SourcePlatform.INSTAGRAM
            "facebook" in r -> SourcePlatform.FACEBOOK
            "youtube" in r -> SourcePlatform.YOUTUBE
            "google" in r || "maps" in r -> SourcePlatform.GOOGLE_PLACES
            else -> SourcePlatform.fromUrl(url)
        }
    }

    fun isInEcuador(lat: Double, lng: Double): Boolean =
        lat in -5.2..1.8 && lng in -92.2..-75.0 && !(lat == 0.0 && lng == 0.0)
}

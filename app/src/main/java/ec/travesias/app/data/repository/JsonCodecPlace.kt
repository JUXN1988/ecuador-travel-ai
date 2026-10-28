package ec.travesias.app.data.repository

import ec.travesias.app.data.local.JsonCodec
import ec.travesias.app.data.model.*
import ec.travesias.app.data.remote.PlaceJsonParser
import org.json.JSONArray

/** Codificación completa de un [Place] a JSON (para compartir entre viajeros). */
object JsonCodecPlace {
    fun encode(p: Place): String = org.json.JSONObject().apply {
        put("id", p.id); put("name", p.name); put("category", p.category.name); put("region", p.region.name)
        put("province", p.province); put("city", p.city); put("address", p.address)
        put("latitude", p.latitude); put("longitude", p.longitude)
        put("shortDescription", p.shortDescription); put("fullDescription", p.fullDescription)
        put("costMinUsd", p.costMinUsd); put("costMaxUsd", p.costMaxUsd); put("costNotes", p.costNotes)
        put("openingHours", p.openingHours); put("howToGetThere", p.howToGetThere)
        put("transportOptions", JSONArray(p.transportOptions)); put("altitudeMeters", p.altitudeMeters ?: -1)
        put("rating", p.rating ?: -1.0); put("ratingCount", p.ratingCount ?: -1)
        put("imageUrls", JSONArray(p.imageUrls)); put("tags", JSONArray(p.tags)); put("localTips", JSONArray(p.localTips))
        put("contact", org.json.JSONObject(JsonCodec.contact(p.contact))); put("sources", JSONArray(JsonCodec.sources(p.sources)))
        put("verification", p.verification.name); put("socialMentions", p.socialMentions); put("googlePlaceId", p.googlePlaceId)
    }.toString()

    fun decode(json: String): Place? = runCatching {
        val o = org.json.JSONObject(json)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        // Un lugar que llega compartido por otro viajero es la única vía de entrada al catálogo que
        // no pasaba por isInEcuador(): sin esto, un JSON corrupto mete coordenadas imposibles al mapa.
        val lat = o.getDouble("latitude")
        val lng = o.getDouble("longitude")
        if (!PlaceJsonParser.isInEcuador(lat, lng)) return@runCatching null
        Place(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            name = o.getString("name"),
            category = runCatching { PlaceCategory.valueOf(o.optString("category")) }.getOrDefault(PlaceCategory.NATURE),
            region = runCatching { EcuadorRegion.valueOf(o.optString("region")) }.getOrDefault(EcuadorRegion.ANDES),
            province = o.optString("province"), city = o.optString("city"), address = o.optString("address"),
            latitude = lat, longitude = lng,
            shortDescription = o.optString("shortDescription"), fullDescription = o.optString("fullDescription"),
            costMinUsd = o.optDouble("costMinUsd", 0.0), costMaxUsd = o.optDouble("costMaxUsd", 0.0), costNotes = o.optString("costNotes"),
            openingHours = o.optString("openingHours"), howToGetThere = o.optString("howToGetThere"),
            transportOptions = list("transportOptions"),
            altitudeMeters = o.optInt("altitudeMeters", -1).takeIf { it >= 0 },
            rating = o.optDouble("rating", -1.0).takeIf { it >= 0 },
            ratingCount = o.optInt("ratingCount", -1).takeIf { it >= 0 },
            imageUrls = list("imageUrls"), tags = list("tags"), localTips = list("localTips"),
            contact = JsonCodec.parseContact(o.optJSONObject("contact")?.toString()),
            sources = JsonCodec.parseSources(o.optJSONArray("sources")?.toString()),
            verification = runCatching { VerificationLevel.valueOf(o.optString("verification")) }.getOrDefault(VerificationLevel.SHARED),
            socialMentions = o.optInt("socialMentions", 0), googlePlaceId = o.optString("googlePlaceId")
        )
    }.getOrNull()
}

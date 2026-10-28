package ec.travesias.app.data.local

import ec.travesias.app.data.model.PlaceContact
import ec.travesias.app.data.model.PlaceSource
import ec.travesias.app.data.model.SourcePlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialización JSON ligera (org.json) para columnas compuestas de Room.
 * Se evita la reflexión para que funcione igual con o sin R8.
 */
object JsonCodec {

    fun stringList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        }.getOrElse {
            // Compatibilidad con el formato antiguo separado por comas
            json.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    fun contact(c: PlaceContact): String = JSONObject().apply {
        put("phone", c.phone)
        put("whatsapp", c.whatsapp)
        put("website", c.website)
        put("instagram", c.instagram)
        put("tiktok", c.tiktok)
        put("facebook", c.facebook)
        put("email", c.email)
    }.toString()

    fun parseContact(json: String?): PlaceContact {
        if (json.isNullOrBlank()) return PlaceContact()
        return runCatching {
            val o = JSONObject(json)
            PlaceContact(
                phone = o.optString("phone", ""),
                whatsapp = o.optString("whatsapp", ""),
                website = o.optString("website", ""),
                instagram = o.optString("instagram", ""),
                tiktok = o.optString("tiktok", ""),
                facebook = o.optString("facebook", ""),
                email = o.optString("email", "")
            )
        }.getOrDefault(PlaceContact())
    }

    fun sources(list: List<PlaceSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("platform", s.platform.name)
                put("url", s.url)
                put("title", s.title)
                put("author", s.author)
                put("thumbnailUrl", s.thumbnailUrl)
            })
        }
        return arr.toString()
    }

    fun parseSources(json: String?): List<PlaceSource> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PlaceSource(
                    platform = runCatching { SourcePlatform.valueOf(o.optString("platform", "WEB")) }.getOrDefault(SourcePlatform.WEB),
                    url = o.optString("url", ""),
                    title = o.optString("title", ""),
                    author = o.optString("author", ""),
                    thumbnailUrl = o.optString("thumbnailUrl", "")
                )
            }
        }.getOrDefault(emptyList())
    }
}

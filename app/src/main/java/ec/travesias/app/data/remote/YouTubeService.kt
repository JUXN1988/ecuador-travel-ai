package ec.travesias.app.data.remote

import ec.travesias.app.data.model.PlaceSource
import ec.travesias.app.data.model.SourcePlatform
import org.json.JSONObject

/**
 * YouTube Data API v3 — la única red social con búsqueda pública oficial.
 * Requiere YOUTUBE_API_KEY (gratis: 10.000 unidades/día; una búsqueda cuesta 100).
 */
class YouTubeService(private val apiKeyProvider: () -> String = { Secrets.youtubeApiKey }) {

    val isAvailable: Boolean get() = apiKeyProvider().isNotBlank()

    suspend fun searchVideos(query: String, maxResults: Int = 5): List<PlaceSource> {
        val key = apiKeyProvider()
        if (key.isBlank() || query.isBlank()) return emptyList()
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&relevanceLanguage=es&regionCode=EC" +
            "&maxResults=$maxResults&q=${Http.encode("$query Ecuador")}&key=$key"
        val raw = runCatching { Http.get(url) }.getOrNull() ?: return emptyList()
        return runCatching {
            val items = JSONObject(raw).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val id = it.optJSONObject("id")?.optString("videoId", "") ?: ""
                if (id.isBlank()) return@mapNotNull null
                val sn = it.optJSONObject("snippet") ?: return@mapNotNull null
                PlaceSource(
                    platform = SourcePlatform.YOUTUBE,
                    url = "https://www.youtube.com/watch?v=$id",
                    title = sn.optString("title", ""),
                    author = sn.optString("channelTitle", ""),
                    thumbnailUrl = sn.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url", "") ?: ""
                )
            }
        }.getOrDefault(emptyList())
    }
}

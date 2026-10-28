package ec.travesias.app.data.remote

/**
 * Lee las claves generadas por el Secrets Gradle Plugin en BuildConfig.
 * Se usa reflexión para que la app compile aunque una clave no exista en .env.
 *
 * Claves soportadas en .env:
 *   GEMINI_API_KEY   (obligatoria para IA online)
 *   YOUTUBE_API_KEY  (opcional: búsqueda real de videos)
 *   PLACES_API_KEY   (opcional: teléfono, horarios, reseñas y fotos de Google Places)
 */
object Secrets {
    private fun read(name: String): String = try {
        val clazz = Class.forName("ec.travesias.app.BuildConfig")
        val field = clazz.getField(name)
        (field.get(null) as? String)?.trim()?.trim('"') ?: ""
    } catch (e: Throwable) {
        ""
    }

    private fun valid(v: String): String =
        if (v.isBlank() || v.startsWith("MY_") || v.contains("PLACEHOLDER", ignoreCase = true) || v == "null") "" else v

    val geminiApiKey: String by lazy { valid(read("GEMINI_API_KEY")) }
    val youtubeApiKey: String by lazy { valid(read("YOUTUBE_API_KEY")) }
    val placesApiKey: String by lazy { valid(read("PLACES_API_KEY")) }

    val hasGemini: Boolean get() = geminiApiKey.isNotBlank()
    val hasYouTube: Boolean get() = youtubeApiKey.isNotBlank()
    val hasPlaces: Boolean get() = placesApiKey.isNotBlank()
}

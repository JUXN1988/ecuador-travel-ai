package ec.travesias.app.data.remote

import android.content.Context
import ec.travesias.app.data.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Fotos de los lugares del catálogo.
 *
 * Busca una imagen libre en Wikipedia/Wikimedia (sin clave ni cuota), la descarga UNA VEZ y la
 * guarda dentro de la app. A partir de ahí la ficha se ve igual sin internet: lo que se guarda
 * en el lugar es la ruta local del archivo, no la URL.
 */
class PlacePhotoService(private val context: Context) {

    private fun dir(): File {
        val d = File(context.filesDir, "lugares")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun localFile(placeId: String): File = File(dir(), placeId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    /** true si ya tenemos la foto guardada en este teléfono. */
    fun hasLocal(placeId: String): Boolean = runCatching { localFile(placeId).let { it.exists() && it.length() > 1024 } }.getOrDefault(false)

    fun localUri(placeId: String): String = "file://" + localFile(placeId).absolutePath

    /**
     * Devuelve el lugar con su foto ya descargada. Si ya la tenía, no toca la red.
     * Nunca lanza: si no encuentra nada devuelve el lugar tal cual.
     */
    suspend fun ensurePhoto(place: Place): Place = withContext(Dispatchers.IO) {
        if (hasLocal(place.id)) {
            val uri = localUri(place.id)
            return@withContext if (place.imageUrls.firstOrNull() == uri) place
            else place.copy(imageUrls = (listOf(uri) + place.imageUrls.filter { it != uri }).distinct())
        }

        // 1) Una URL que ya tuviera la ficha, 2) Wikipedia, 3) Wikimedia Commons
        val url = place.imageUrls.firstOrNull { it.startsWith("http") }
            ?: buscarEnWikipedia(place)
            ?: buscarEnCommons(place)
            ?: return@withContext place

        val bytes = Http.getBytes(url) ?: return@withContext place
        val guardado = runCatching {
            FileOutputStream(localFile(place.id)).use { it.write(bytes) }
            true
        }.getOrDefault(false)
        if (!guardado) return@withContext place

        val uri = localUri(place.id)
        place.copy(imageUrls = (listOf(uri, url) + place.imageUrls).distinct().take(4))
    }

    // ------------------------------------------------------------ fuentes

    private suspend fun buscarEnWikipedia(place: Place): String? {
        val consulta = listOf(place.name, place.province).filter { it.isNotBlank() }.joinToString(" ") + " Ecuador"
        val url = "https://es.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages" +
            "&piprop=thumbnail&pithumbsize=900&generator=search&gsrlimit=1&gsrsearch=" + Http.encode(consulta)
        val raw = runCatching { Http.get(url) }.getOrNull() ?: return null
        return runCatching {
            val pages = JSONObject(raw).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val src = pages.optJSONObject(keys.next())?.optJSONObject("thumbnail")?.optString("source", "")
                if (!src.isNullOrBlank()) return src
            }
            null
        }.getOrNull()
    }

    private suspend fun buscarEnCommons(place: Place): String? {
        val consulta = place.name + " Ecuador"
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search" +
            "&gsrnamespace=6&gsrlimit=1&prop=imageinfo&iiprop=url&iiurlwidth=900&gsrsearch=" + Http.encode(consulta)
        val raw = runCatching { Http.get(url) }.getOrNull() ?: return null
        return runCatching {
            val pages = JSONObject(raw).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val info = pages.optJSONObject(keys.next())?.optJSONArray("imageinfo")?.optJSONObject(0)
                val src = info?.optString("thumburl", "")?.ifBlank { info.optString("url", "") }
                if (!src.isNullOrBlank()) return src
            }
            null
        }.getOrNull()
    }

    /** Borra todas las fotos guardadas (para liberar espacio). */
    fun clear(): Int {
        var n = 0
        runCatching { dir().listFiles()?.forEach { if (it.delete()) n++ } }
        return n
    }

    /** Espacio que ocupan las fotos guardadas, en MB. */
    fun sizeMb(): Long = runCatching {
        var bytes = 0L
        dir().listFiles()?.forEach { bytes += it.length() }
        bytes / (1024 * 1024)
    }.getOrDefault(0L)
}

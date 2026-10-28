package ec.travesias.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP compartido (OkHttp). Todas las llamadas de red de la app pasan por aquí.
 */
object Http {
    const val USER_AGENT = "TravesiasEnEcuador/1.0 (Android; contacto: soporte@travesias.ec)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val formMedia = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    class HttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(300)}")

    /**
     * POST de formulario. Lo pide Overpass (el buscador de OpenStreetMap): su consulta puede
     * ocupar varios kilobytes y no cabe bien en una URL, así que va en el cuerpo.
     */
    suspend fun postForm(
        url: String,
        campos: Map<String, String>,
        readTimeoutSeconds: Long = 90
    ): String = withContext(Dispatchers.IO) {
        val cuerpo = campos.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(cuerpo.toRequestBody(formMedia)).build()
        val cliente = client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        cliente.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpException(resp.code, body)
            body
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpException(resp.code, body)
            body
        }
    }

    /**
     * POST con JSON.
     *
     * @param readTimeoutSeconds cuánto esperar la respuesta. El valor por defecto (60 s) se queda
     *   corto para Gemini con búsqueda web activada: el modelo busca, lee páginas y "piensa" antes
     *   de escribir, y pasar de 60 s es normal. Cuando se agotaba, OkHttp lanzaba un timeout y la
     *   app lo interpretaba como "no hay internet" aunque la conexión estuviera perfecta.
     */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSeconds: Long = 60
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(json.toRequestBody(jsonMedia)).build()
        val cliente = if (readTimeoutSeconds == 60L) client
        else client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        cliente.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpException(resp.code, body)
            body
        }
    }

    /** Descarga binaria (fotos). Devuelve null si falla o si pesa más de [maxBytes]. */
    suspend fun getBytes(url: String, maxBytes: Long = 3_000_000L): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val len = resp.body?.contentLength() ?: -1L
                if (len > maxBytes) return@use null
                resp.body?.bytes()?.takeIf { it.size <= maxBytes }
            }
        }.getOrNull()
    }

    fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}

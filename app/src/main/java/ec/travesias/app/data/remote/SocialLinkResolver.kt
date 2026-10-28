package ec.travesias.app.data.remote

import ec.travesias.app.data.model.SourcePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Saca el texto real de una publicación de TikTok, Instagram, Facebook o YouTube
 * SIN necesitar tokens ni claves de esas redes.
 *
 * Estrategia, en este orden:
 *  1. Resolver el enlace corto (vm.tiktok.com, fb.watch, youtu.be, l.instagram.com…) siguiendo
 *     las redirecciones hasta la URL canónica.
 *  2. oEmbed oficial donde existe y es público (TikTok, YouTube) → título y autor limpios.
 *  3. Etiquetas Open Graph del HTML (og:title, og:description, og:image). Las cuatro redes
 *     las sirven a los rastreadores, así que se pide la página con un User-Agent de bot.
 *  4. Restos útiles del HTML: <title> y description.
 *
 * Lo que se obtenga se le entrega a Gemini como CONTEXTO REAL de la publicación, en vez de
 * pasarle una URL que el modelo no siempre puede abrir (Instagram y Facebook bloquean a
 * los lectores automáticos de la IA; por eso "no funcionaba").
 */
class SocialLinkResolver {

    data class SocialPost(
        val originalUrl: String,
        /** URL tras seguir las redirecciones; es la que conviene enseñar y guardar. */
        val canonicalUrl: String,
        val platform: SourcePlatform,
        val title: String = "",
        val description: String = "",
        val author: String = "",
        val thumbnail: String = "",
        /** Hashtags encontrados en el título o la descripción: pistas potentes del lugar. */
        val hashtags: List<String> = emptyList(),
        /** Cómo se consiguió el texto: "oembed", "opengraph", "html", "url" o "ninguno". */
        val via: String = "ninguno"
    ) {
        /** Texto corrido con todo lo que se leyó, listo para el prompt de la IA. */
        val texto: String
            get() = listOf(title, description, hashtags.joinToString(" ") { "#" + it })
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()

        /** ¿Se consiguió algo aprovechable, más allá de la propia URL? */
        val tieneContenido: Boolean get() = texto.length >= 12

        /**
         * Pistas de ubicación que trae la propia URL: TikTok y muchas cuentas usan el nombre
         * del lugar en el slug (…/video/quilotoa-laguna-…). Es débil pero a veces salva.
         */
        val pistaDeUrl: String
            get() = Regex("""[/\-_]([a-záéíóúñ]{4,}(?:[\-_][a-záéíóúñ]{3,}){0,4})""", RegexOption.IGNORE_CASE)
                .findAll(canonicalUrl.substringBefore('?').lowercase())
                .map { it.groupValues[1].replace('-', ' ').replace('_', ' ') }
                .filter { it !in RUIDO_URL }
                .joinToString(", ")
                .take(120)
    }

    companion object {
        /** Un User-Agent de rastreador: es a quien estas redes sí le sirven las etiquetas og:. */
        private const val BOT_UA =
            "Mozilla/5.0 (compatible; facebookexternalhit/1.1; +http://www.facebook.com/externalhit_uatext.php)"

        /** Palabras del path que no son nombres de lugar. */
        private val RUIDO_URL = setOf(
            "video", "videos", "watch", "reel", "reels", "share", "shorts", "story", "stories",
            "photo", "posts", "post", "profile", "user", "embed", "live", "permalink",
            "channel", "playlist", "www", "com", "https", "http", "index", "tiktok", "instagram",
            "facebook", "youtube", "youtu", "search", "explore", "hashtag", "tag", "media", "content"
        )
    }

    /** Cliente propio: sin seguir redirecciones automáticamente, para poder leer la cadena. */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Punto de entrada: de una URL a todo lo que se pueda saber de esa publicación. */
    suspend fun resolve(url: String): SocialPost = withContext(Dispatchers.IO) {
        val canonical = runCatching { seguirRedirecciones(url) }.getOrDefault(url)
        val platform = SourcePlatform.fromUrl(canonical).let {
            if (it == SourcePlatform.WEB || it == SourcePlatform.OTHER) SourcePlatform.fromUrl(url) else it
        }
        var post = SocialPost(url, canonical, platform)

        // 1) oEmbed público (TikTok y YouTube): lo más limpio cuando responde.
        oembed(canonical, platform)?.let {
            post = post.copy(
                title = it.optString("title", ""),
                author = it.optString("author_name", ""),
                thumbnail = it.optString("thumbnail_url", ""),
                via = "oembed"
            )
        }

        // 2) Open Graph del HTML: aquí es donde Instagram y Facebook sí sueltan el texto.
        val html = descargarHtml(canonical)
        if (html != null) {
            val og = leerOpenGraph(html)
            post = post.copy(
                title = post.title.ifBlank { og["title"].orEmpty() },
                description = og["description"].orEmpty().ifBlank { post.description },
                author = post.author.ifBlank { autorDeUrl(canonical, platform) },
                thumbnail = post.thumbnail.ifBlank { og["image"].orEmpty() },
                via = when {
                    post.via == "oembed" && og.isNotEmpty() -> "oembed+opengraph"
                    post.via == "oembed" -> "oembed"
                    og.isNotEmpty() -> "opengraph"
                    else -> "html"
                }
            )
        }

        if (post.author.isBlank()) post = post.copy(author = autorDeUrl(canonical, platform))
        post = post.copy(
            title = limpiar(post.title),
            description = limpiar(post.description),
            hashtags = hashtags(post.title + " " + post.description)
        )
        if (!post.tieneContenido && post.via != "ninguno") post = post.copy(via = "url")
        post
    }

    /**
     * Sigue hasta 6 redirecciones. Los enlaces de TikTok (vm.tiktok.com) y de Facebook
     * (fb.watch, l.facebook.com) siempre son cortos, y la URL final es la que trae el
     * nombre de usuario y el id del video.
     */
    private fun seguirRedirecciones(url: String, maximo: Int = 6): String {
        var actual = limpiarUrl(url)
        repeat(maximo) {
            val req = Request.Builder().url(actual)
                .header("User-Agent", BOT_UA)
                .header("Accept-Language", "es-EC,es;q=0.9,en;q=0.8")
                .head().build()
            val siguiente = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.code in 300..399) resp.header("Location") else null
                }
            }.getOrNull() ?: return limpiarUrl(actual)
            actual = if (siguiente.startsWith("http")) siguiente
            else runCatching { java.net.URI(actual).resolve(siguiente).toString() }.getOrDefault(actual)
        }
        return limpiarUrl(actual)
    }

    /** Quita los parámetros de rastreo que ensucian la URL y despistan a la IA. */
    private fun limpiarUrl(url: String): String {
        val basura = setOf("utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
            "_r", "_t", "is_from_webapp", "sender_device", "web_id", "fbclid", "igshid", "igsh", "si", "mibextid")
        val partes = url.split("?", limit = 2)
        if (partes.size < 2) return url
        val query = partes[1].split("&")
            .filter { it.substringBefore("=").lowercase() !in basura }
            .joinToString("&")
        return if (query.isBlank()) partes[0] else partes[0] + "?" + query
    }

    /** oEmbed público. Instagram y Facebook exigen token, así que ni se intenta. */
    private fun oembed(url: String, platform: SourcePlatform): JSONObject? {
        val endpoint = when (platform) {
            SourcePlatform.TIKTOK -> "https://www.tiktok.com/oembed?url=" + Http.encode(url)
            SourcePlatform.YOUTUBE -> "https://www.youtube.com/oembed?format=json&url=" + Http.encode(url)
            else -> return null
        }
        val raw = runCatching {
            client.newBuilder().followRedirects(true).build()
                .newCall(Request.Builder().url(endpoint).header("User-Agent", Http.USER_AGENT).get().build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** Pide el HTML como si fuéramos un rastreador, que es a quien le dan las etiquetas og:. */
    private fun descargarHtml(url: String, maxBytes: Long = 600_000L): String? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", BOT_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "es-EC,es;q=0.9,en;q=0.8")
            .get().build()
        client.newBuilder().followRedirects(true).followSslRedirects(true).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                // peekBody lee solo la cabecera del HTML: las etiquetas og: van en el <head>,
                // así que no hace falta bajarse el megabyte de scripts que trae TikTok.
                resp.peekBody(maxBytes).string()
            }
    }.getOrNull()

    /** Extrae og:title, og:description y og:image (y sus equivalentes de Twitter y <title>). */
    private fun leerOpenGraph(html: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val meta = Regex("""<meta[^>]+>""", RegexOption.IGNORE_CASE)
        for (etiqueta in meta.findAll(html).map { it.value }) {
            val clave = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(etiqueta)?.groupValues?.get(1)?.lowercase() ?: continue
            val valor = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(etiqueta)?.groupValues?.get(1) ?: continue
            if (valor.isBlank()) continue
            when (clave) {
                "og:title", "twitter:title" -> if ("title" !in out) out["title"] = valor
                "og:description", "twitter:description", "description" -> if ("description" !in out) out["description"] = valor
                "og:image", "twitter:image", "og:image:secure_url" -> if ("image" !in out) out["image"] = valor
            }
        }
        if ("title" !in out) {
            Regex("""<title[^>]*>([^<]{3,300})</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { out["title"] = it }
        }
        return out
    }

    /** El @usuario suele estar en la propia URL: tiktok.com/@quitoaventura/video/123. */
    private fun autorDeUrl(url: String, platform: SourcePlatform): String = when (platform) {
        SourcePlatform.TIKTOK -> Regex("""tiktok\.com/@([\w.\-]+)""").find(url)?.groupValues?.get(1).orEmpty()
        SourcePlatform.INSTAGRAM -> Regex("""instagram\.com/([\w.\-]+)/(?:p|reel|tv)/""").find(url)?.groupValues?.get(1).orEmpty()
        SourcePlatform.FACEBOOK -> Regex("""facebook\.com/([\w.\-]+)/(?:videos|posts|reel)""").find(url)?.groupValues?.get(1).orEmpty()
        else -> ""
    }

    private fun hashtags(texto: String): List<String> =
        Regex("""#([\p{L}\p{N}_]{2,30})""").findAll(texto)
            .map { it.groupValues[1] }
            .filter { it.lowercase() !in setOf("fyp", "foryou", "parati", "viral", "tiktok", "reels", "trending", "fyppppppppppppppppppppppp") }
            .distinct().take(15).toList()

    /** Decodifica entidades HTML y normaliza espacios; el og: viene escapado. */
    private fun limpiar(texto: String): String = texto
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(1200)
}

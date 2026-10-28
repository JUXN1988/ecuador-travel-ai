package ec.travesias.app.data.remote

import ec.travesias.app.data.model.*
import org.json.JSONObject

/**
 * "Compartir a Travesías": el viajero comparte o pega un TikTok, Reel, video de Facebook,
 * YouTube o cualquier enlace, y esto lo convierte en un [Place] listo para la ruta.
 *
 * Por qué antes fallaba: se le pasaba la URL a Gemini y se esperaba que la leyera. Instagram y
 * Facebook bloquean a los lectores automáticos, y TikTok casi siempre también, así que la IA
 * recibía una URL vacía de contenido y no podía identificar nada.
 *
 * Cómo funciona ahora (cadena de 4 pasos, cada uno rescata al anterior):
 *  1. [SocialLinkResolver] sigue las redirecciones y saca el texto REAL de la publicación
 *     (oEmbed + etiquetas Open Graph). Eso sí lo sueltan las cuatro redes.
 *  2. Con ese texto, [WebResearchService] busca en internet el lugar concreto y arma la ficha
 *     completa verificada (coordenadas, costo, horario, cómo llegar, contactos).
 *  3. Si no hay texto que leer, se busca por autor + pistas de la URL + lo que escribió el viajero.
 *  4. Si aun así no se identifica, se devuelve [NeedsHint] con lo que SÍ se leyó, para que el
 *     viajero escriba el nombre del lugar o la ciudad y se reintente con esa pista.
 */
class LinkExtractorService(
    private val gemini: GeminiClient,
    private val geocoding: GeocodingService,
    private val places: PlacesService,
    private val research: WebResearchService = WebResearchService(gemini),
    private val resolver: SocialLinkResolver = SocialLinkResolver()
) {

    data class LinkMeta(
        val url: String,
        val platform: SourcePlatform,
        val title: String,
        val author: String,
        val thumbnail: String,
        val description: String = "",
        val hashtags: List<String> = emptyList(),
        /** De dónde salió el texto: "oembed", "opengraph", "url"… Sirve para diagnosticar. */
        val via: String = "ninguno"
    ) {
        val tieneContenido: Boolean get() = (title + " " + description).trim().length >= 12
    }

    /**
     * No se pudo identificar el lugar solo, pero SÍ se leyó algo de la publicación.
     * Se le enseña al viajero y se le pide una pista para reintentar.
     */
    class NeedsHint(
        val meta: LinkMeta,
        val sharedText: String,
        val motivo: String
    ) : Exception(motivo)

    private val urlRegex = Regex("""https?://[^\s<>"')\]]+""")

    fun extractUrl(sharedText: String): String? =
        urlRegex.find(sharedText)?.value?.trimEnd('.', ',', ')', ']')

    /** Metadatos reales de la publicación: redirecciones + oEmbed + Open Graph. */
    suspend fun fetchMeta(url: String): LinkMeta {
        val post = runCatching { resolver.resolve(url) }.getOrNull()
            ?: return LinkMeta(url, SourcePlatform.fromUrl(url), "", "", "")
        return LinkMeta(
            url = post.canonicalUrl,
            platform = post.platform,
            title = post.title,
            author = post.author,
            thumbnail = post.thumbnail,
            description = post.description,
            hashtags = post.hashtags,
            via = post.via
        )
    }

    /**
     * Convierte un enlace (y el texto que lo acompaña) en un lugar.
     *
     * @param hint pista que escribe el viajero cuando el primer intento no identificó nada
     *             ("es la laguna del Quilotoa", "queda en Baños").
     * @throws NeedsHint cuando se leyó la publicación pero no se pudo ubicar el lugar.
     * @throws IllegalStateException para el resto de fallos.
     */
    suspend fun extractPlace(
        sharedText: String,
        hint: String = "",
        onProgress: (String) -> Unit = {}
    ): Pair<Place, PlaceSource> {
        check(gemini.isAvailable) { "Configura GEMINI_API_KEY para analizar enlaces con IA." }

        val url = extractUrl(sharedText)
        val caption = sharedText.replace(url ?: "", "").trim()

        onProgress(if (url != null) "Abriendo la publicación…" else "Leyendo el texto…")
        // Se resuelve UNA sola vez: seguir redirecciones y bajar el HTML cuesta segundos.
        val post = if (url != null) runCatching { resolver.resolve(url) }.getOrNull() else null
        val meta = post?.let {
            LinkMeta(it.canonicalUrl, it.platform, it.title, it.author, it.thumbnail, it.description, it.hashtags, it.via)
        } ?: LinkMeta(url ?: "", url?.let { SourcePlatform.fromUrl(it) } ?: SourcePlatform.OTHER, "", "", "")

        // ---- Todo lo que sabemos, junto, para dárselo a la búsqueda ----
        val pistas = buildString {
            if (meta.title.isNotBlank()) appendLine("Título de la publicación: " + meta.title)
            if (meta.description.isNotBlank()) appendLine("Descripción: " + meta.description)
            if (meta.hashtags.isNotEmpty()) appendLine("Hashtags: " + meta.hashtags.joinToString(" ") { "#" + it })
            if (meta.author.isNotBlank()) appendLine("Cuenta que lo publicó: @" + meta.author + " en " + meta.platform.displayName)
            if (caption.isNotBlank()) appendLine("Lo que compartió el viajero: " + caption)
            if (hint.isNotBlank()) appendLine("PISTA DEL VIAJERO (dale prioridad, él vio el video): " + hint)
            if (post != null && !meta.tieneContenido && post.pistaDeUrl.isNotBlank()) {
                appendLine("Palabras que aparecen en el enlace: " + post.pistaDeUrl)
            }
        }.trim()

        // Si no hay NADA de dónde agarrarse, no vale la pena gastar una llamada a la IA.
        val hayDondeAgarrarse = meta.tieneContenido || caption.length >= 8 || hint.isNotBlank() ||
            meta.author.isNotBlank()
        if (!hayDondeAgarrarse) {
            throw NeedsHint(meta, sharedText,
                "No pude leer el contenido de esa publicación: " + meta.platform.displayName +
                    " no deja que las apps vean el texto de los videos privados o restringidos.")
        }

        // ---- Paso 1: identificar QUÉ lugar es, buscando en internet ----
        onProgress("Buscando el lugar en internet…")
        val identificacion = research.buscar(
            pregunta = "¿De qué lugar de Ecuador habla esta publicación de " + meta.platform.displayName + "? " +
                "Busca en internet el nombre de la cuenta, el título y los hashtags para identificarlo. " +
                "Responde con el nombre exacto del lugar, el cantón y la provincia. Si no puedes determinarlo " +
                "con seguridad, responde exactamente: NO_IDENTIFICADO",
            contexto = pistas,
            urls = listOfNotNull(url).filter { meta.platform == SourcePlatform.YOUTUBE || meta.tieneContenido },
            maxPalabras = 150
        )

        val identificado = identificacion.texto.takeIf {
            identificacion.ok && !it.contains("NO_IDENTIFICADO")
        }

        // ---- Paso 2: ficha completa verificada en internet ----
        onProgress(if (identificado != null) "Verificando datos del lugar…" else "Investigando a fondo…")
        val consulta = identificado ?: pistas
        val (json, hallazgo) = research.ficha(
            nombreOPistas = consulta.take(500),
            pistas = pistas,
            urls = listOfNotNull(url).filter { meta.platform == SourcePlatform.YOUTUBE || meta.tieneContenido }
        )

        val datos = json ?: throw hintOError(meta, sharedText, hallazgo.problema, identificado == null)

        var place = PlaceJsonParser.parse(datos, VerificationLevel.USER)
            ?: throw NeedsHint(meta, sharedText, "La publicación no menciona un lugar reconocible de Ecuador.")

        // ---- Paso 3: ubicarlo en el mapa si la IA no dio coordenadas buenas ----
        if (!PlaceJsonParser.isInEcuador(place.latitude, place.longitude)) {
            onProgress("Ubicando en el mapa…")
            val consultaMapa = (place.name + " " + place.city.ifBlank { place.province } + " Ecuador").trim()
            geocoding.search(consultaMapa).firstOrNull()?.let { hit ->
                place = place.copy(
                    latitude = hit.latitude, longitude = hit.longitude,
                    province = place.province.ifBlank { hit.province },
                    city = place.city.ifBlank { hit.city },
                    region = EcuadorRegion.fromCoordinates(hit.latitude, hit.longitude)
                )
            }
        }
        if (!PlaceJsonParser.isInEcuador(place.latitude, place.longitude)) {
            throw NeedsHint(meta, sharedText,
                "Encontré \"" + place.name + "\" pero no pude ubicarlo en el mapa de Ecuador.")
        }

        // ---- Paso 4: fuentes, foto y datos de Google Places ----
        val source = PlaceSource(
            platform = meta.platform,
            url = url ?: "",
            title = meta.title.ifBlank { caption.take(80) },
            author = meta.author,
            thumbnailUrl = meta.thumbnail
        )
        place = place.copy(
            sources = (listOf(source).filter { it.url.isNotBlank() } + place.sources +
                hallazgo.fuentes + identificacion.fuentes).distinctBy { it.url },
            imageUrls = (place.imageUrls + listOf(meta.thumbnail).filter { it.isNotBlank() }).distinct(),
            socialMentions = maxOf(1, place.socialMentions),
            verification = VerificationLevel.USER
        )
        if (places.isAvailable) {
            onProgress("Completando teléfono, horario y reseñas…")
            place = runCatching { places.enrich(place) }.getOrDefault(place)
        }
        return place to source
    }

    /**
     * Cuando falla: si leímos algo de la publicación, pedimos una pista al viajero
     * (se puede recuperar). Si el fallo es de red o de clave, es un error de verdad.
     */
    private fun hintOError(meta: LinkMeta, sharedText: String, problema: String, sinIdentificar: Boolean): Exception {
        val esFalloDeRed = problema.contains("conexión") || problema.contains("clave") ||
            problema.contains("cuota") || problema.contains("bloquearon")
        return if (esFalloDeRed) IllegalStateException(problema)
        else NeedsHint(meta, sharedText, when {
            sinIdentificar && meta.tieneContenido ->
                "Leí la publicación pero no logré identificar el lugar exacto."
            sinIdentificar ->
                meta.platform.displayName + " no me dejó ver el contenido del video."
            else -> problema.ifBlank { "No pude completar los datos del lugar." }
        })
    }
}

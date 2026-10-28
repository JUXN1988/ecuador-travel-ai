package ec.travesias.app.data.social

/**
 * Una foto del álbum de viajes de un viajero.
 *
 * Es el recorte estilo Instagram del perfil: la foto se ve grande, se etiqueta el lugar
 * visitado y se puede mencionar a los compañeros por @usuario. La original se queda en el
 * teléfono de quien la subió; lo que viaja es una miniatura para que otros la vean.
 */
data class AlbumPhoto(
    val id: String = "",
    /** Huella del dueño del álbum. */
    val ownerFingerprint: String = "",
    /** Ruta de la foto original en ESTE teléfono. Vacía si es de otra persona. */
    val photoPath: String = "",
    /** Miniatura comprimida (base64) que sí se sincroniza. */
    val photoThumb: String = "",
    /** Pie de foto libre. */
    val caption: String = "",
    /** Lugar visitado, tal como lo etiquetó el viajero (ej. "Baños de Agua Santa"). */
    val placeName: String = "",
    /** Id del lugar del catálogo, si lo eligió de la lista. Vacío si lo escribió a mano. */
    val placeId: String = "",
    /** Compañeros mencionados, sin la @ (ej. ["gabriel_ec", "ana_andina"]). */
    val taggedUsernames: List<String> = emptyList(),
    val createdAt: Long = 0L,
    /** true mientras la miniatura no haya salido de este teléfono. */
    val pendingSync: Boolean = false,
    /** Id de la publicación: varias fotos con el mismo setId son un carrusel. */
    val setId: String = "",
    /** Orden dentro del carrusel (0 = portada). */
    val indexInSet: Int = 0
) {
    val hasPlace: Boolean get() = placeName.isNotBlank()
    val mentionsLabel: String
        get() = taggedUsernames.filter { it.isNotBlank() }.joinToString(" ") { "@$it" }
    val postKey: String get() = setId.ifBlank { id }
}

/**
 * Una publicación del feed: una o varias fotos, como en Instagram.
 */
data class AlbumPost(
    val setId: String,
    val ownerFingerprint: String,
    val username: String,
    val ownerThumb: String = "",
    val photos: List<AlbumPhoto>
) {
    val cover: AlbumPhoto? get() = photos.minByOrNull { it.indexInSet } ?: photos.firstOrNull()
    val caption: String get() = cover?.caption.orEmpty()
    val placeName: String get() = cover?.placeName.orEmpty()
    val mentionsLabel: String get() = cover?.mentionsLabel.orEmpty()
    val createdAt: Long get() = cover?.createdAt ?: 0L
    val isCarousel: Boolean get() = photos.size > 1
}

/**
 * Agrupa fotos sueltas en publicaciones (carrusel) para el feed y el perfil.
 * Las que no tienen setId se tratan como publicación de una sola imagen.
 */
fun agruparPublicaciones(
    fotos: List<AlbumPhoto>,
    usernameOf: (ownerFingerprint: String) -> String,
    thumbOf: (ownerFingerprint: String) -> String = { "" }
): List<AlbumPost> {
    return fotos
        .groupBy { it.postKey }
        .map { (sid, lista) ->
            val ordenadas = lista.sortedWith(compareBy<AlbumPhoto> { it.indexInSet }.thenBy { it.id })
            val dueño = ordenadas.first().ownerFingerprint
            AlbumPost(
                setId = sid,
                ownerFingerprint = dueño,
                username = usernameOf(dueño).ifBlank { "viajero" },
                ownerThumb = thumbOf(dueño),
                photos = ordenadas
            )
        }
        .sortedByDescending { it.createdAt }
}

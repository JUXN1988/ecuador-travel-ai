package ec.travesias.app.data.social

/**
 * Perfil de viajero. Vive en el teléfono; Firebase solo lo replica para que
 * los demás puedan encontrarte.
 */
data class TravelerProfile(
    val uid: String = "",
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val region: String = "",            // Región del próximo viaje (Sierra, Costa, Amazonía, Galápagos)
    val routeOrigin: String = "",
    val routeDestination: String = "",
    val travelMonth: String = "",       // "2026-10"
    val groupIds: List<String> = emptyList(),
    /** Ruta de la foto original. Nunca sale de este teléfono. */
    val avatarPath: String = "",
    /** Miniatura comprimida (base64). Es lo único que se sincroniza. */
    val avatarThumb: String = "",
    val createdAt: Long = 0L,
    /** Etiquetas de viaje: mochilero/a, busco/ofrezco aventón, etc. Para encontrar compañeros afines. */
    val travelTags: List<String> = emptyList(),
    /** Llave pública de la cuenta: es el carné con el que los demás verifican mis mensajes. */
    val publicKey: String = "",
    /** true si el viajero aceptó salir en la vitrina para que otros lo encuentren. */
    val showcase: Boolean = false,
    /** Quién puede ver este perfil y el álbum: público, solo amigos o privado. */
    val visibility: ProfileVisibility = ProfileVisibility.FRIENDS,
    /**
     * Correo de recuperación, OPCIONAL y aparte del correo interno con el que la app entra
     * a Firebase. Sirve para recordar el @usuario y, si algún día se encienden, para avisos.
     * Nunca es obligatorio: la app se sigue usando sin dar ningún correo.
     */
    val recoveryEmail: String = "",
    val recoveryVerified: Boolean = false
) {
    val routeLabel: String
        get() = if (routeOrigin.isNotBlank() || routeDestination.isNotBlank()) "$routeOrigin → $routeDestination" else ""

    val initials: String
        get() = username.take(2).uppercase().ifBlank { "??" }

    /** true si el identificador todavía es local (se creó sin conexión). */
    val isLocalOnly: Boolean get() = uid.startsWith(LOCAL_UID_PREFIX)

    companion object {
        const val LOCAL_UID_PREFIX = "local-"
    }
}

/** Grupo de compañeros de viaje. */
data class TravelGroup(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val inviteCode: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    val lastMessagePreview: String = "",
    /** true mientras el grupo no se haya podido subir todavía. */
    val pendingSync: Boolean = false,
    /**
     * Llave secreta del grupo (base64). SOLO vive en este teléfono y dentro del código de
     * invitación: nunca se sube a Firebase. Es lo que cifra y descifra los mensajes.
     */
    val secretKey: String = "",
    /** true si los mensajes de este grupo viajan cifrados. */
    val encrypted: Boolean = false,
    /**
     * Gente invitada que todavía NO ha aceptado. Mientras alguien esté aquí, para él esto no
     * es un chat: es una solicitud que puede aceptar, rechazar o bloquear.
     */
    val pendingMembers: List<String> = emptyList(),
    /** Huella de quien me invitó. Vacío si el grupo lo creé yo. */
    val invitedBy: String = "",
    /** true si es un chat de dos personas (antes se adivinaba por el nombre; ya no). */
    val direct: Boolean = false,
    /** Silenciado: no suena ni notifica, pero se sigue recibiendo. */
    val muted: Boolean = false
) {
    /** true si YO todavía no he aceptado: esto me llegó como solicitud. */
    fun isRequestFor(myFingerprint: String?): Boolean =
        myFingerprint != null && myFingerprint in pendingMembers

    /** La otra persona de un chat de dos. */
    fun otherMember(myFingerprint: String?): String? =
        if (!direct) null else memberUids.firstOrNull { it != myFingerprint }

    /** true si el otro todavía no ha aceptado ni respondido mi solicitud. */
    fun waitingForOther(myFingerprint: String?): Boolean =
        pendingMembers.any { it != myFingerprint }

    /** true si YO puedo escribir ahora mismo: o ya no hay pendientes, o me toca a mí responder. */
    fun yoPuedoEscribir(myFingerprint: String?): Boolean =
        myFingerprint != null && (pendingMembers.isEmpty() || myFingerprint in pendingMembers)
}

/**
 * Tipos de publicación dentro de un grupo.
 *
 * FILE es el que lleva videos, documentos, audios y fotos pesadas: el archivo viaja aparte,
 * cifrado y en trozos (ver [Attachments]), y el mensaje solo trae su ficha y la miniatura.
 */
enum class PostType { MESSAGE, PHOTO, LOCATION, PLACE, ROUTE, FILE }

/**
 * Mensaje del grupo. La foto original se queda en el teléfono ([photoPath]);
 * lo que se sincroniza es [photoThumb], una miniatura comprimida.
 */
data class GroupPost(
    val id: String = "",
    val groupId: String = "",
    val type: PostType = PostType.MESSAGE,
    val authorUid: String = "",
    val authorName: String = "",
    val authorThumb: String = "",
    val text: String = "",
    val payloadJson: String = "",       // Place o Ruta serializados
    val photoPath: String = "",
    val photoThumb: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationLabel: String = "",
    val createdAt: Long = 0L,
    /** true mientras el mensaje no haya llegado al resto del grupo. */
    val pendingSync: Boolean = false,
    /** Llave pública con la que venía firmado (vacía en los mensajes antiguos). */
    val authorKey: String = "",
    /** Si la firma se pudo comprobar. Es lo que decide la insignia en la burbuja. */
    val trust: PostTrust = PostTrust.LEGACY
) {
    val hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0
}

/** Categoría de un comentario/tip sobre un lugar. */
enum class CommentCategory(val displayName: String, val emoji: String) {
    COMIDA("Dónde comer", "\uD83C\uDF7D\uFE0F"),
    PRECIO("Precios", "\uD83D\uDCB5"),
    COMO_LLEGAR("Cómo llegar", "\uD83E\uDDED"),
    SEGURIDAD("Seguridad", "\uD83D\uDEE1\uFE0F"),
    UBICACION("Ubicación exacta", "\uD83D\uDCCD"),
    GENERAL("Comentario general", "\uD83D\uDCAC");

    companion object {
        fun from(raw: String): CommentCategory = entries.firstOrNull { it.name == raw } ?: GENERAL
    }
}

/**
 * Comentario o tip de un viajero sobre un lugar: dónde comer, precios, cómo llegar,
 * seguridad, o para confirmar la ubicación cuando quien lo agregó no estaba seguro.
 * Igual que los mensajes de grupo: se guarda en el teléfono y Firebase solo lo replica.
 */
data class PlaceComment(
    val id: String = "",
    val placeId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val authorThumb: String = "",
    val text: String = "",
    val category: CommentCategory = CommentCategory.GENERAL,
    /** Ruta de la foto original. Nunca sale de este teléfono. */
    val photoPath: String = "",
    /** Miniatura comprimida (base64). Es lo único que se sincroniza. */
    val photoThumb: String = "",
    val createdAt: Long = 0L,
    val pendingSync: Boolean = false
)

/**
 * En qué punto está la relación con otro viajero.
 *
 * CONOCIDO es el que apareció en un grupo o cuyo carné llegó en un mensaje: se le puede ver el
 * perfil pero no es "de la casa". CONTACTO es con quien ya hubo un sí de las dos partes.
 * BLOQUEADO no puede volver a escribir ni aparecer.
 */
enum class ContactState {
    CONOCIDO, CONTACTO, BLOQUEADO;

    companion object {
        fun from(raw: String?): ContactState = entries.firstOrNull { it.name == raw } ?: CONOCIDO
    }
}

/**
 * Ficha de otro viajero, tal como la puede ver quien abre su perfil. Se arma con lo que él
 * mismo publicó (vitrina, opcional) y con lo que llegó firmado dentro de sus mensajes: nunca
 * con datos que él no haya decidido soltar.
 */
data class TravelerCard(
    val fingerprint: String,
    val username: String,
    val displayName: String = "",
    val thumb: String = "",
    val publicKey: String = "",
    val state: ContactState = ContactState.CONOCIDO,
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val travelTags: List<String> = emptyList(),
    val region: String = "",
    val travelMonth: String = "",
    val routeLabel: String = "",
    /** Desde cuándo lo conozco (primer mensaje suyo que vi). 0 si aún no lo he visto escribir. */
    val knownSince: Long = 0L,
    /** Cuántos mensajes suyos he recibido: la señal más honesta de si es alguien real. */
    val messagesSeen: Int = 0,
    /** Cuántos lugares ha aportado al catálogo de todos. */
    val contributions: Int = 0,
    /** Gustos que compartimos. */
    val inCommon: List<String> = emptyList(),
    /** true si sus mensajes vienen firmados y la firma cuadra. */
    val verified: Boolean = false,
    /** true si tiene la vitrina encendida (o sea, quiso que lo encontraran). */
    val showcase: Boolean = false,
    /** Recorte de su álbum de viajes (fotos grandes con lugar y menciones). */
    val album: List<AlbumPhoto> = emptyList(),
    /** Visibilidad que eligió el dueño del perfil. */
    val visibility: ProfileVisibility = ProfileVisibility.FRIENDS,
    /** true si YO puedo ver álbum y detalles según su privacidad. */
    val canViewProfile: Boolean = true
) {
    val initials: String get() = username.take(2).uppercase().ifBlank { "??" }
    val isBlocked: Boolean get() = state == ContactState.BLOQUEADO
}

/** Sugerencia de compañero con puntuación de afinidad. */
data class BuddyMatch(
    val profile: TravelerProfile,
    val score: Int,
    val reasons: List<String>
)

/** Estado del conector de sincronización. */
enum class SyncState { LOCAL_ONLY, CONNECTING, ONLINE, ERROR }

data class SyncStatus(
    val state: SyncState = SyncState.CONNECTING,
    val detail: String = ""
) {
    val isOnline: Boolean get() = state == SyncState.ONLINE

    val label: String
        get() = when (state) {
            SyncState.ONLINE -> "Sincronizado"
            SyncState.CONNECTING -> "Conectando…"
            SyncState.LOCAL_ONLY -> "Solo en este teléfono"
            SyncState.ERROR -> "Sin sincronizar"
        }
}

package ec.travesias.app.data.social

/**
 * Filtros para no listarme a mí mismo como si fuera otro viajero.
 * Gente, chats y afines usan esto; el cifrado y los grupos no se tocan.
 */
private const val DIRECT_PREFIX = "\uD83D\uDC64 "

/** true si la huella o el @usuario coinciden con mi cuenta. */
fun esLaMismaPersona(
    fingerprint: String?,
    username: String?,
    yoFingerprint: String?,
    miUsuario: String?,
    yoUid: String? = null
): Boolean {
    if (!fingerprint.isNullOrBlank()) {
        if (!yoFingerprint.isNullOrBlank() && fingerprint == yoFingerprint) return true
        if (!yoUid.isNullOrBlank() && fingerprint == yoUid) return true
    }
    if (!username.isNullOrBlank() && !miUsuario.isNullOrBlank() &&
        username.equals(miUsuario, ignoreCase = true)
    ) {
        return true
    }
    return false
}

fun TravelerCard.esYo(
    yoFingerprint: String?,
    miUsuario: String?,
    yoUid: String? = null
): Boolean = esLaMismaPersona(fingerprint, username, yoFingerprint, miUsuario, yoUid)

fun TravelerProfile.esYo(yoFingerprint: String?, miUsuario: String?): Boolean =
    esLaMismaPersona(uid, username, yoFingerprint, miUsuario, uid)

fun BuddyMatch.esYo(
    yoFingerprint: String?,
    miUsuario: String?,
    yoUid: String? = null
): Boolean = esLaMismaPersona(profile.uid, profile.username, yoFingerprint, miUsuario, yoUid)

/**
 * Chat de dos donde el “otro” soy yo (mismo uid dos veces, o el título es mi @usuario
 * y no hay otro miembro). Esos no deben verse en la bandeja.
 */
fun TravelGroup.esConversacionConmigo(
    yoFingerprint: String?,
    miUsuario: String?,
    yoUid: String? = null
): Boolean {
    if (!direct) return false
    val idsMios = listOfNotNull(yoFingerprint, yoUid).filter { it.isNotBlank() }.toSet()
    val uids = memberUids.filter { it.isNotBlank() }
    val otro = otherMember(yoFingerprint)
    if (idsMios.isNotEmpty() && uids.isNotEmpty() && uids.all { it in idsMios }) return true
    if (otro != null && otro in idsMios) return true
    val nombre = name.removePrefix(DIRECT_PREFIX).trim()
    if (!miUsuario.isNullOrBlank() && nombre.equals(miUsuario, ignoreCase = true)) {
        if (otro == null || otro in idsMios) return true
    }
    val nombresAjenos = memberNames.filter { n ->
        n.isNotBlank() && (miUsuario == null || !n.equals(miUsuario, ignoreCase = true))
    }
    if (!miUsuario.isNullOrBlank() &&
        nombresAjenos.isEmpty() &&
        memberNames.any { it.equals(miUsuario, ignoreCase = true) }
    ) {
        if (otro == null || otro in idsMios) return true
    }
    return false
}

/** Nombre de la otra persona en un chat directo (nunca el mío). */
fun TravelGroup.nombreDelInterlocutor(yoFingerprint: String?, miUsuario: String?): String {
    val delTitulo = name.removePrefix(DIRECT_PREFIX).trim()
    if (delTitulo.isNotBlank() &&
        (miUsuario.isNullOrBlank() || !delTitulo.equals(miUsuario, ignoreCase = true))
    ) {
        return delTitulo
    }
    val deMiembros = memberNames.firstOrNull { n ->
        n.isNotBlank() && (miUsuario == null || !n.equals(miUsuario, ignoreCase = true))
    }
    return deMiembros ?: delTitulo.ifBlank { "Chat" }
}

/** true si este mensaje lo escribí yo (huella, uid o @usuario con uid mío/vacío). */
fun esMensajeMio(
    authorUid: String,
    authorName: String,
    yoFingerprint: String?,
    me: TravelerProfile?
): Boolean {
    val ids = listOfNotNull(yoFingerprint, me?.uid).filter { it.isNotBlank() }.toSet()
    if (authorUid.isNotBlank() && authorUid in ids) return true
    val user = me?.username.orEmpty()
    if (user.isNotBlank() && authorName.equals(user, ignoreCase = true)) {
        if (authorUid.isBlank() || authorUid in ids) return true
    }
    return false
}

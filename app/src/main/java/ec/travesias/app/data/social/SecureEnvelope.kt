package ec.travesias.app.data.social

import org.json.JSONObject

/**
 * El sobre en el que viaja cada mensaje de la comunidad.
 *
 * QUÉ VE FIREBASE con este formato:
 *   { v:1, a:"tv_xxxx", k:"<llave pública del autor>", c:"<ruido cifrado>", s:"<firma>", createdAt, exp }
 * O sea: no ve el texto, ni la foto, ni la ubicación, ni el nombre de quien escribe. Solo un
 * bulto cifrado y de quién viene. Sirve de cartero, no de lector.
 *
 * DOS PROTECCIONES DISTINTAS, que conviene no confundir:
 *  - La FIRMA (s) demuestra QUIÉN lo escribió. Sin la llave privada del autor no se puede
 *    falsificar, así que nadie puede publicar haciéndose pasar por otro.
 *  - El CIFRADO (c) esconde QUÉ dice. Solo quien tenga la llave del grupo puede abrirlo.
 *
 * Cada mensaje lleva además el "carné" firmado de su autor (usuario, llave pública, foto
 * pequeña). Así el grupo aprende a la gente de los propios mensajes y no hace falta guardar
 * el directorio de perfiles en Firebase.
 *
 * Los mensajes viejos (antes de esta versión) se siguen leyendo: llegan sin firma y se
 * marcan como [PostTrust.LEGACY] para que la pantalla lo diga con honestidad.
 */
object SecureEnvelope {

    const val VERSION = 1

    /** Cuánto vive un mensaje en el buzón de Firebase. Después solo queda en los teléfonos. */
    const val MAILBOX_DAYS = 30L
    const val MAILBOX_MILLIS = MAILBOX_DAYS * 24 * 60 * 60 * 1000L

    // ======================================================================
    //  Empaquetar (al enviar)
    // ======================================================================

    /**
     * @param groupKey llave del grupo; si es null el mensaje viaja legible (grupos antiguos).
     * @param card carné del autor, tal como lo arma [TravelerIdentity.card].
     */
    fun seal(
        post: GroupPost,
        identity: TravelerIdentity,
        card: String,
        groupKey: ByteArray?
    ): Map<String, Any> {
        val payload = payloadOf(post, card)
        val firma = identity.signText(canonical(post.groupId, post.id, post.createdAt, payload))
        val base = mutableMapOf<String, Any>(
            "v" to VERSION,
            "a" to identity.fingerprint,
            "k" to identity.publicKeyB64,
            "s" to firma,
            "createdAt" to post.createdAt,
            "exp" to (post.createdAt + MAILBOX_MILLIS)
        )
        if (groupKey != null) {
            base["c"] = CryptoBox.sealText(groupKey, payload, post.groupId)
        } else {
            base["p"] = payload
        }
        return base
    }

    /** Todo el contenido del mensaje en un solo JSON: es lo que se cifra y lo que se firma. */
    fun payloadOf(post: GroupPost, card: String): String = JSONObject().apply {
        put("ty", post.type.name)
        put("tx", post.text)
        put("pl", post.payloadJson)
        put("th", post.photoThumb)
        put("la", post.latitude)
        put("lo", post.longitude)
        put("ll", post.locationLabel)
        put("card", card)
    }.toString()

    /** Lo que se firma. Incluye grupo, id y hora: un mensaje no se puede mover ni reciclar. */
    fun canonical(groupId: String, postId: String, createdAt: Long, payload: String): String =
        "tv1|" + groupId + "|" + postId + "|" + createdAt + "|" + CryptoBox.b64(CryptoBox.sha256(payload.toByteArray(Charsets.UTF_8)))

    // ======================================================================
    //  Abrir (al recibir)
    // ======================================================================

    /**
     * @param knownKeyForUsername devuelve la huella que ya conocíamos para ese usuario, si la hay.
     *        Sirve para avisar cuando alguien intenta usar el nombre de otro (TOFU).
     */
    fun open(
        postId: String,
        groupId: String,
        data: Map<String, Any?>,
        groupKey: ByteArray?,
        knownKeyForUsername: (String) -> String? = { null }
    ): OpenedPost? {
        val version = (data["v"] as? Number)?.toInt() ?: 0
        if (version < 1) return legacy(postId, groupId, data)

        val autor = data["a"] as? String ?: return null
        val llave = data["k"] as? String ?: return null
        val firma = data["s"] as? String ?: ""
        val createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L

        // OJO: aquí antes se hacía `return null` cuando no se podía descifrar, y el mensaje
        // DESAPARECÍA sin decir nada. Era la peor cara del problema "escribo y no le llegan":
        // los mensajes llegaban, pero la app los tiraba a la basura en silencio y nadie podía
        // saber si el fallo era de red, de llave o de qué. Ahora se devuelve un mensaje CERRADO,
        // que la pantalla enseña con un candado y un botón para arreglarlo.
        val payload = when {
            data["c"] is String && groupKey != null ->
                CryptoBox.openText(groupKey, data["c"] as String, groupId)
                    ?: return cerrado(postId, groupId, autor, llave, createdAt, MOTIVO_LLAVE_MALA)
            data["p"] is String -> data["p"] as String
            data["c"] is String -> return cerrado(postId, groupId, autor, llave, createdAt, MOTIVO_SIN_LLAVE)
            else -> return null
        }

        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val card = parseCard(o.optString("card", ""))

        // 1) ¿La firma cuadra con la llave que dice traer?
        var confianza = if (CryptoBox.verify(llave, canonical(groupId, postId, createdAt, payload), firma))
            PostTrust.VERIFIED else PostTrust.SUSPICIOUS
        // 2) ¿La huella corresponde de verdad a esa llave pública?
        if (confianza == PostTrust.VERIFIED && CryptoBox.fingerprint(CryptoBox.b64d(llave)) != autor) {
            confianza = PostTrust.SUSPICIOUS
        }
        // 3) ¿Ese usuario ya lo conocíamos con OTRA llave? Alguien está usando su nombre.
        val usuario = card?.username.orEmpty()
        if (confianza == PostTrust.VERIFIED && usuario.isNotBlank()) {
            val conocida = knownKeyForUsername(usuario)
            if (conocida != null && conocida != autor) confianza = PostTrust.SUSPICIOUS
        }

        val post = GroupPost(
            id = postId,
            groupId = groupId,
            type = runCatching { PostType.valueOf(o.optString("ty", "MESSAGE")) }.getOrDefault(PostType.MESSAGE),
            authorUid = autor,
            authorName = card?.username.orEmpty().ifBlank { autor.take(9) },
            authorThumb = card?.thumb.orEmpty(),
            text = o.optString("tx", ""),
            payloadJson = o.optString("pl", ""),
            photoThumb = o.optString("th", ""),
            latitude = o.optDouble("la", 0.0),
            longitude = o.optDouble("lo", 0.0),
            locationLabel = o.optString("ll", ""),
            createdAt = createdAt,
            pendingSync = false,
            authorKey = llave,
            trust = confianza
        )
        return OpenedPost(post, card, confianza)
    }

    const val MOTIVO_SIN_LLAVE = "Este chat todavía no tiene la llave en este teléfono."
    const val MOTIVO_LLAVE_MALA = "La llave de este chat no abre este mensaje."

    /**
     * Un mensaje que llegó pero no se puede leer. Se muestra igual, con candado y con el motivo:
     * es infinitamente mejor que hacerlo desaparecer, porque así se sabe que el otro SÍ escribió
     * y qué hay que arreglar.
     */
    private fun cerrado(
        postId: String, groupId: String, autor: String, llave: String, createdAt: Long, motivo: String
    ): OpenedPost {
        val post = GroupPost(
            id = postId,
            groupId = groupId,
            type = PostType.MESSAGE,
            authorUid = autor,
            authorName = autor.take(9),
            text = motivo,
            createdAt = createdAt,
            pendingSync = false,
            authorKey = llave,
            trust = PostTrust.LOCKED
        )
        return OpenedPost(post, null, PostTrust.LOCKED)
    }

    /** Mensajes creados antes de que existieran las firmas. Se leen, pero se marcan. */
    private fun legacy(postId: String, groupId: String, data: Map<String, Any?>): OpenedPost? {
        val autor = data["authorUid"] as? String ?: return null
        val post = GroupPost(
            id = postId,
            groupId = groupId,
            type = runCatching { PostType.valueOf(data["type"] as? String ?: "MESSAGE") }.getOrDefault(PostType.MESSAGE),
            authorUid = autor,
            authorName = data["authorName"] as? String ?: "",
            authorThumb = data["authorThumb"] as? String ?: "",
            text = data["text"] as? String ?: "",
            payloadJson = data["payloadJson"] as? String ?: "",
            photoThumb = data["photoThumb"] as? String ?: "",
            latitude = (data["latitude"] as? Number)?.toDouble() ?: 0.0,
            longitude = (data["longitude"] as? Number)?.toDouble() ?: 0.0,
            locationLabel = data["locationLabel"] as? String ?: "",
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            pendingSync = false,
            trust = PostTrust.LEGACY
        )
        return OpenedPost(post, null, PostTrust.LEGACY)
    }

    fun parseCard(json: String): IdentityCard? {
        if (json.isBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val fp = o.optString("fp", "")
        if (fp.isBlank()) return null
        return IdentityCard(
            username = o.optString("u", ""),
            fingerprint = fp,
            publicKeyB64 = o.optString("k", ""),
            displayName = o.optString("n", ""),
            thumb = o.optString("t", "")
        )
    }

    // ======================================================================
    //  Códigos de invitación: llevan la llave del grupo dentro
    // ======================================================================
    //  Formato:  TRV1-<id del grupo>-<llave en base32>
    //  La llave NUNCA se sube a Firebase: solo existe en los teléfonos y en este código,
    //  que se comparte por WhatsApp, en persona o como uno quiera.

    fun inviteCode(groupId: String, keyB64: String): String =
        "TRV1-" + groupId + "-" + CryptoBox.base32(CryptoBox.b64d(keyB64))

    /** Devuelve (idDelGrupo, llaveBase64) o null si el código no sirve. */
    fun parseInvite(code: String): Pair<String, String>? {
        val limpio = code.trim().replace(" ", "")
        if (!limpio.uppercase().startsWith("TRV1-")) return null
        val partes = limpio.substring(5).split("-")
        if (partes.size < 2) return null
        val id = partes[0]
        val llave = CryptoBox.base32d(partes.drop(1).joinToString(""))
        if (id.isBlank() || llave.size != 32) return null
        return id to CryptoBox.b64(llave)
    }
}

/** Un mensaje ya abierto: el contenido, el carné de quien lo mandó y cuánto se le cree. */
data class OpenedPost(
    val post: GroupPost,
    val card: IdentityCard?,
    val trust: PostTrust
)

/** El carné público que cada quien reparte dentro de sus mensajes. */
data class IdentityCard(
    val username: String,
    val fingerprint: String,
    val publicKeyB64: String,
    val displayName: String,
    val thumb: String
)

/** Cuánto se puede confiar en que un mensaje lo escribió quien dice. */
enum class PostTrust {
    /** Firma comprobada: es esa persona, sin duda. */
    VERIFIED,

    /** Mensaje de la época anterior a las firmas. Se muestra, pero sin garantía. */
    LEGACY,

    /** La firma no cuadra o el nombre no corresponde a la llave conocida. */
    SUSPICIOUS,

    /** Llegó, pero no se pudo abrir: falta la llave del chat o no es la correcta. */
    LOCKED;

    val label: String
        get() = when (this) {
            VERIFIED -> "Verificado"
            LEGACY -> "Sin firma"
            SUSPICIOUS -> "No se pudo verificar"
            LOCKED -> "No se pudo abrir"
        }
}

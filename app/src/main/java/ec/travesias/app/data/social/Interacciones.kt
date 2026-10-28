package ec.travesias.app.data.social

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.PostCommentEntity
import ec.travesias.app.data.local.PostReactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Una reacción a una publicación. Una por persona: volver a tocar la quita. */
data class Reaccion(
    val quien: String,
    val usuario: String,
    val emoji: String,
    val at: Long
)

/** Un comentario, tanto de una publicación como del muro de un perfil. */
data class Comentario(
    val id: String,
    val postKey: String,
    val quien: String,
    val usuario: String,
    val texto: String,
    val at: Long,
    /** true mientras no haya salido de este teléfono. */
    val pendiente: Boolean = false
)

/** Lo social de una publicación (o de un muro), ya junto para pintarlo. */
data class SocialDePost(
    val postKey: String = "",
    val reacciones: List<Reaccion> = emptyList(),
    val comentarios: List<Comentario> = emptyList()
) {
    val cuantasReacciones: Int get() = reacciones.size
    val cuantosComentarios: Int get() = comentarios.size

    /** Con qué emoji reaccioné yo, o null si todavía no. */
    fun miReaccion(yo: String?): String? =
        if (yo.isNullOrBlank()) null else reacciones.firstOrNull { it.quien == yo }?.emoji

    /** Los emojis distintos que ha puesto la gente, los más usados primero (máx. 3). */
    val emojisResumen: List<String>
        get() = reacciones.groupingBy { it.emoji }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(3)

    /** "A ana y 3 más les gusta" sin repetir la lógica en la pantalla. */
    fun resumen(yo: String?): String {
        if (reacciones.isEmpty()) return ""
        val mia = miReaccion(yo) != null
        val otros = reacciones.filter { it.quien != yo }
        return when {
            mia && otros.isEmpty() -> "Te gusta"
            mia && otros.size == 1 -> "A ti y a @" + otros.first().usuario + " les gusta"
            mia -> "A ti y a " + otros.size + " más les gusta"
            otros.size == 1 -> "A @" + otros.first().usuario + " le gusta"
            else -> "A " + otros.size + " personas les gusta"
        }
    }
}

/** Reacciones que se ofrecen al mantener pulsado el corazón. */
object Reacciones {
    val LISTA = listOf("❤️", "👍", "😍", "🔥", "😮", "👏")
    const val POR_DEFECTO = "❤️"
}

/** Clave del muro de un perfil, para que comparta tabla con las publicaciones. */
fun muroKey(huella: String): String = "muro:" + huella

/**
 * Reacciones y comentarios de la comunidad.
 *
 * Mismo trato que el resto de Viajeros: el teléfono manda. Todo se guarda en Room, así que
 * el feed sale pintado al instante y con avión activado; Firestore solo sirve de puente y
 * guarda cuatro campos por comentario (quién, su @usuario, el texto y la hora).
 *
 * Dónde vive en Firestore, colgando del álbum del dueño de la publicación:
 *   albums/{dueño}/social/{setId}/likes/{huella}     -> { e, u, at }
 *   albums/{dueño}/social/{setId}/comments/{id}      -> { by, u, tx, at }
 *   albums/{dueño}/wall/{id}                         -> { by, u, tx, at }
 */
class InteraccionesRepository(
    private val context: android.content.Context,
    db: AppDatabase
) {

    private val reaccionDao = db.postReactionDao()
    private val comentarioDao = db.postCommentDao()

    /**
     * Avisos ya lanzados en esta sesión, para no repetir el mismo "me gusta" cada vez que
     * la publicación vuelve a pasar por delante.
     */
    private val yaAvisado = HashSet<String>()

    /** Todo lo cacheado, agrupado por publicación, listo para el feed. */
    fun observar(): Flow<Map<String, SocialDePost>> =
        combine(reaccionDao.observeAll(), comentarioDao.observeAll()) { reacciones, comentarios ->
            val porPost = HashMap<String, SocialDePost>()
            reacciones.groupBy { it.postKey }.forEach { (key, filas) ->
                porPost[key] = SocialDePost(
                    postKey = key,
                    reacciones = filas.map { Reaccion(it.quien, it.usuario, it.emoji, it.at) }
                )
            }
            comentarios.groupBy { it.postKey }.forEach { (key, filas) ->
                val previo = porPost[key] ?: SocialDePost(postKey = key)
                porPost[key] = previo.copy(comentarios = filas.map { it.toDomain() }.sortedBy { it.at })
            }
            porPost
        }

    /** El muro de un perfil, reactivo (comparte tabla con las publicaciones). */
    fun observarMuro(huella: String): Flow<List<Comentario>> {
        val key = muroKey(huella)
        return comentarioDao.observeAll().map { filas ->
            filas.filter { it.postKey == key }.map { it.toDomain() }.sortedBy { it.at }
        }
    }

    // ------------------------------------------------------------------ bajar

    /** Trae de Firestore las reacciones y comentarios de una publicación y los cachea. */
    suspend fun refrescarPost(fs: FirebaseFirestore?, dueño: String, setId: String, yo: String? = null) {
        if (fs == null || dueño.isBlank() || setId.isBlank()) return
        val base = fs.collection(AlbumRepository.COL).document(dueño)
            .collection(COL_SOCIAL).document(setId)
        // Solo se avisa de lo que pasa en MIS publicaciones, y de lo que hacen OTROS.
        val esMia = !yo.isNullOrBlank() && dueño == yo

        withTimeoutOrNull(15_000L) {
            runCatching {
                val likes = base.collection(COL_LIKES).limit(200).get().await()
                val filas = likes.documents.mapNotNull { doc ->
                    val emoji = doc.getString("e").orEmpty().ifBlank { Reacciones.POR_DEFECTO }
                    PostReactionEntity(
                        id = idReaccion(setId, doc.id),
                        postKey = setId,
                        ownerFingerprint = dueño,
                        quien = doc.id,
                        usuario = doc.getString("u").orEmpty(),
                        emoji = emoji,
                        at = doc.getLong("at") ?: 0L
                    )
                }
                // Qué hay de nuevo respecto a lo que ya teníamos guardado aquí.
                val conocidas = reaccionDao.listOf(setId).map { it.id }.toSet()
                reaccionDao.clearPost(setId)
                if (filas.isNotEmpty()) reaccionDao.upsertAll(filas)
                if (esMia) {
                    filas.filter { it.id !in conocidas && it.quien != yo }
                        .forEach { avisar(setId, it.usuario, it.emoji, it.at, false) }
                }
            }
        }

        refrescarComentarios(base.collection(COL_COMENTARIOS), setId, dueño, esMia, yo)
    }

    /**
     * Lanza el aviso al teléfono de un "me gusta" o un comentario en una foto mía.
     *
     * Dos frenos, para no convertirlo en una plaga:
     *  - solo cosas de las últimas 48 horas, así la primera sincronización de una
     *    publicación vieja no dispara veinte avisos de golpe;
     *  - una sola vez por persona y publicación en cada sesión.
     */
    private fun avisar(clave: String, usuario: String, detalle: String, cuando: Long, esComentario: Boolean) {
        if (cuando <= 0L) return
        if (System.currentTimeMillis() - cuando > VENTANA_AVISO) return
        val marca = clave + "|" + usuario + "|" + esComentario
        if (!yaAvisado.add(marca)) return
        runCatching {
            ec.travesias.app.data.notify.NotificationHelper.notifyReaccion(
                context,
                clave,
                usuario.ifBlank { "alguien" },
                detalle,
                esComentario
            )
        }
    }

    /** Trae el muro de un perfil. */
    suspend fun refrescarMuro(fs: FirebaseFirestore?, dueño: String, yo: String? = null) {
        if (fs == null || dueño.isBlank()) return
        val col = fs.collection(AlbumRepository.COL).document(dueño).collection(COL_MURO)
        // Si el muro es el mío, lo que escriban ahí también merece un aviso.
        val esMio = !yo.isNullOrBlank() && dueño == yo
        refrescarComentarios(col, muroKey(dueño), dueño, esMio, yo)
    }

    private suspend fun refrescarComentarios(
        col: com.google.firebase.firestore.CollectionReference,
        postKey: String,
        dueño: String,
        avisarNuevos: Boolean = false,
        yo: String? = null
    ) {
        withTimeoutOrNull(15_000L) {
            runCatching {
                val snap = col.orderBy("at", Query.Direction.ASCENDING).limit(100).get().await()
                val filas = snap.documents.mapNotNull { doc ->
                    val texto = doc.getString("tx").orEmpty()
                    if (texto.isBlank()) return@mapNotNull null
                    PostCommentEntity(
                        id = doc.id,
                        postKey = postKey,
                        ownerFingerprint = dueño,
                        quien = doc.getString("by").orEmpty(),
                        usuario = doc.getString("u").orEmpty(),
                        texto = texto,
                        at = doc.getLong("at") ?: 0L,
                        pendingSync = false
                    )
                }
                val conocidos = comentarioDao.listOf(postKey).map { it.id }.toSet()
                comentarioDao.clearPost(postKey)
                if (filas.isNotEmpty()) comentarioDao.upsertAll(filas)
                if (avisarNuevos) {
                    filas.filter { it.id !in conocidos && it.quien != yo }
                        .forEach { avisar(postKey, it.usuario, it.texto, it.at, true) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ escribir

    /**
     * Pone o quita mi reacción. Se guarda primero aquí (la pantalla responde al toque) y
     * después se manda; si no hay red, al menos no se pierde el gesto en este teléfono.
     */
    suspend fun reaccionar(
        fs: FirebaseFirestore?,
        dueño: String,
        setId: String,
        yo: String,
        miUsuario: String,
        emoji: String?
    ) {
        if (yo.isBlank() || setId.isBlank()) return
        val id = idReaccion(setId, yo)
        if (emoji == null) {
            reaccionDao.deleteById(id)
        } else {
            reaccionDao.upsert(
                PostReactionEntity(
                    id = id,
                    postKey = setId,
                    ownerFingerprint = dueño,
                    quien = yo,
                    usuario = miUsuario,
                    emoji = emoji,
                    at = System.currentTimeMillis()
                )
            )
        }
        if (fs == null || dueño.isBlank()) return
        val doc = fs.collection(AlbumRepository.COL).document(dueño)
            .collection(COL_SOCIAL).document(setId)
            .collection(COL_LIKES).document(yo)
        runCatching {
            if (emoji == null) doc.delete().await()
            else doc.set(mapOf("e" to emoji, "u" to miUsuario, "at" to System.currentTimeMillis())).await()
        }
    }

    /** Comenta una publicación. Devuelve el comentario ya guardado en este teléfono. */
    suspend fun comentar(
        fs: FirebaseFirestore?,
        dueño: String,
        setId: String,
        yo: String,
        miUsuario: String,
        texto: String
    ): Comentario = escribir(fs, dueño, setId, yo, miUsuario, texto) { f ->
        f.collection(AlbumRepository.COL).document(dueño)
            .collection(COL_SOCIAL).document(setId)
            .collection(COL_COMENTARIOS)
    }

    /** Escribe en el muro de un perfil (el de un amigo, o el mío). */
    suspend fun escribirEnMuro(
        fs: FirebaseFirestore?,
        dueño: String,
        yo: String,
        miUsuario: String,
        texto: String
    ): Comentario = escribir(fs, dueño, muroKey(dueño), yo, miUsuario, texto) { f ->
        f.collection(AlbumRepository.COL).document(dueño).collection(COL_MURO)
    }

    private suspend fun escribir(
        fs: FirebaseFirestore?,
        dueño: String,
        postKey: String,
        yo: String,
        miUsuario: String,
        texto: String,
        coleccion: (FirebaseFirestore) -> com.google.firebase.firestore.CollectionReference
    ): Comentario {
        val limpio = texto.trim().take(MAX_TEXTO)
        if (limpio.isBlank()) throw IllegalStateException("Escribe algo primero.")
        if (yo.isBlank()) throw IllegalStateException("Entra con tu cuenta para comentar.")
        val id = "cm-" + UUID.randomUUID().toString().replace("-", "").take(20)
        val ahora = System.currentTimeMillis()
        val fila = PostCommentEntity(
            id = id,
            postKey = postKey,
            ownerFingerprint = dueño,
            quien = yo,
            usuario = miUsuario,
            texto = limpio,
            at = ahora,
            pendingSync = true
        )
        comentarioDao.upsert(fila)
        if (fs != null && dueño.isNotBlank()) {
            val ok = withTimeoutOrNull(20_000L) {
                runCatching {
                    coleccion(fs).document(id)
                        .set(mapOf("by" to yo, "u" to miUsuario, "tx" to limpio, "at" to ahora))
                        .await()
                }.isSuccess
            }
            if (ok == true) comentarioDao.markSynced(id)
        }
        return fila.toDomain().copy(pendiente = false)
    }

    /**
     * Borra un comentario. Lo puede hacer quien lo escribió y también el dueño de la
     * publicación o del muro: en su sitio manda él.
     */
    suspend fun borrarComentario(fs: FirebaseFirestore?, dueño: String, comentario: Comentario) {
        comentarioDao.deleteById(comentario.id)
        val fs2 = fs ?: return
        if (dueño.isBlank()) return
        val esMuro = comentario.postKey.startsWith("muro:")
        val destino = if (esMuro) {
            fs2.collection(AlbumRepository.COL).document(dueño).collection(COL_MURO)
        } else {
            fs2.collection(AlbumRepository.COL).document(dueño)
                .collection(COL_SOCIAL).document(comentario.postKey)
                .collection(COL_COMENTARIOS)
        }
        runCatching { destino.document(comentario.id).delete().await() }
    }

    /** Manda los comentarios que se escribieron sin internet. */
    suspend fun subirPendientes(fs: FirebaseFirestore?) {
        val fs2 = fs ?: return
        comentarioDao.getPending().forEach { fila ->
            val esMuro = fila.postKey.startsWith("muro:")
            val col = if (esMuro) {
                fs2.collection(AlbumRepository.COL).document(fila.ownerFingerprint).collection(COL_MURO)
            } else {
                fs2.collection(AlbumRepository.COL).document(fila.ownerFingerprint)
                    .collection(COL_SOCIAL).document(fila.postKey)
                    .collection(COL_COMENTARIOS)
            }
            if (fila.ownerFingerprint.isBlank()) return@forEach
            val ok = withTimeoutOrNull(15_000L) {
                runCatching {
                    col.document(fila.id)
                        .set(mapOf("by" to fila.quien, "u" to fila.usuario, "tx" to fila.texto, "at" to fila.at))
                        .await()
                }.isSuccess
            }
            if (ok == true) comentarioDao.markSynced(fila.id)
        }
    }

    /** Al borrar una publicación se lleva por delante sus reacciones y comentarios. */
    suspend fun borrarTodoDe(fs: FirebaseFirestore?, dueño: String, setId: String) {
        reaccionDao.clearPost(setId)
        comentarioDao.clearPost(setId)
        val fs2 = fs ?: return
        if (dueño.isBlank() || setId.isBlank()) return
        val base = fs2.collection(AlbumRepository.COL).document(dueño)
            .collection(COL_SOCIAL).document(setId)
        runCatching {
            base.collection(COL_LIKES).get().await().documents.forEach { it.reference.delete().await() }
        }
        runCatching {
            base.collection(COL_COMENTARIOS).get().await().documents.forEach { it.reference.delete().await() }
        }
    }

    // ------------------------------------------------------------------ internos

    private fun idReaccion(postKey: String, quien: String): String = postKey + "|" + quien

    companion object {
        const val COL_SOCIAL = "social"
        const val COL_LIKES = "likes"
        const val COL_COMENTARIOS = "comments"
        const val COL_MURO = "wall"
        const val MAX_TEXTO = 500

        /** Solo se avisa de reacciones y comentarios de las últimas 48 horas. */
        const val VENTANA_AVISO = 48L * 60L * 60L * 1000L
    }
}

private fun PostCommentEntity.toDomain(): Comentario = Comentario(
    id = id,
    postKey = postKey,
    quien = quien,
    usuario = usuario,
    texto = texto,
    at = at,
    pendiente = pendingSync
)

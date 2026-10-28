package ec.travesias.app.data.social

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.toDomain
import ec.travesias.app.data.local.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Álbum de fotos de viaje del perfil.
 *
 * Vive en el teléfono. Firebase es solo el puente: un metadato chiquito y, cuando alguien
 * quiere ver la foto, los trozos. No es la galería.
 */
class AlbumRepository(
    private val context: Context,
    db: AppDatabase
) {
    private val dao = db.albumPhotoDao()

    /** Mi álbum, reactivo para pintarlo en el perfil. */
    fun observeMine(ownerFingerprint: String): Flow<List<AlbumPhoto>> =
        dao.observeMine(ownerFingerprint).map { lista -> lista.map { it.toDomain() } }

    /** Fotos cacheadas (mías y de amigos) para armar el feed. */
    fun observeCached(): Flow<List<AlbumPhoto>> =
        dao.observeAll().map { lista -> lista.map { it.toDomain() } }

    /**
     * Añade una foto al álbum: se guarda grande en el teléfono y se marca para subir la
     * miniatura. [taggedUsernames] van sin la @.
     */
    suspend fun addPhoto(
        ownerFingerprint: String,
        uri: Uri,
        caption: String,
        placeName: String,
        placeId: String,
        taggedUsernames: List<String>
    ): AlbumPhoto = addPhotos(ownerFingerprint, listOf(uri), caption, placeName, placeId, taggedUsernames).first()

    /**
     * Publica varias fotos juntas (carrusel). Todas comparten setId; la primera es la portada.
     * Máximo 10, como en Instagram.
     */
    suspend fun addPhotos(
        ownerFingerprint: String,
        uris: List<Uri>,
        caption: String,
        placeName: String,
        placeId: String,
        taggedUsernames: List<String>
    ): List<AlbumPhoto> {
        if (ownerFingerprint.isBlank()) {
            throw IllegalStateException("Entra con tu cuenta para publicar fotos en tu perfil.")
        }
        val elegidas = uris.distinct().take(MAX_FOTOS_POR_POST)
        if (elegidas.isEmpty()) {
            throw IllegalStateException("Elige al menos una foto.")
        }
        val tagged = taggedUsernames.map { AccountService.normalizeUsername(it) }
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
        val setId = "set-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val pie = caption.trim().take(180)
        val lugar = placeName.trim().take(80)
        val pid = placeId.trim()
        val publicadas = mutableListOf<AlbumPhoto>()
        elegidas.forEachIndexed { i, uri ->
            val saved = PhotoStore.saveAlbum(context, uri) ?: return@forEachIndexed
            val foto = AlbumPhoto(
                id = "alb-" + UUID.randomUUID().toString().replace("-", "").take(20),
                ownerFingerprint = ownerFingerprint,
                photoPath = saved.localPath,
                photoThumb = saved.thumbBase64,
                caption = pie,
                placeName = lugar,
                placeId = pid,
                taggedUsernames = tagged,
                createdAt = now,
                pendingSync = true,
                setId = setId,
                indexInSet = i
            )
            dao.upsert(foto.toEntity())
            publicadas += foto
        }
        if (publicadas.isEmpty()) {
            throw IllegalStateException("No se pudo leer esas imágenes.")
        }
        return publicadas
    }

    /** Borra una foto mía del teléfono y, si hay red, el metadato y los trozos del puente. */
    suspend fun deletePhoto(id: String, firestore: FirebaseFirestore?) {
        val local = dao.getById(id) ?: return
        dao.deleteById(id)
        val fs = firestore ?: return
        if (local.ownerFingerprint.isBlank()) return
        runCatching {
            fs.collection(COL).document(local.ownerFingerprint)
                .collection(PHOTOS).document(id).delete().await()
        }
        runCatching { AlbumBridge.borrar(fs, local.ownerFingerprint, id) }
    }

    /** Borra todas las fotos de una publicación (el carrusel entero). */
    suspend fun deleteSet(setId: String, firestore: FirebaseFirestore?) {
        if (setId.isBlank()) return
        dao.listBySet(setId).forEach { fila -> deletePhoto(fila.id, firestore) }
    }

    /**
     * Publica el metadato (chiquito) y sube la foto a trozos por el puente.
     * No se marca sincronizada hasta que los trozos llegaron: así no queda un hueco vacío.
     */
    suspend fun pushPending(firestore: FirebaseFirestore?, ownerFingerprint: String) {
        val fs = firestore ?: return
        if (ownerFingerprint.isBlank()) return
        dao.getPending().filter { it.ownerFingerprint == ownerFingerprint }.forEach { fila ->
            val foto = fila.toDomain()
            val micro = when {
                PhotoStore.exists(foto.photoPath) -> PhotoStore.microFromFile(foto.photoPath).ifBlank { foto.photoThumb }
                else -> PhotoStore.shrinkThumb(foto.photoThumb)
            }
            val metaOk = withTimeoutOrNull(20_000L) {
                runCatching {
                    fs.collection(COL).document(ownerFingerprint)
                        .collection(PHOTOS).document(fila.id)
                        .set(toMap(foto.copy(photoThumb = micro))).await()
                }.isSuccess
            }
            if (metaOk != true) return@forEach
            val binOk = if (PhotoStore.exists(foto.photoPath)) {
                withTimeoutOrNull(90_000L) {
                    runCatching { AlbumBridge.subir(fs, ownerFingerprint, fila.id, foto.photoPath) }.isSuccess
                } == true
            } else true
            if (binOk) dao.markSynced(fila.id)
        }
    }

    /**
     * Por si ya había fotos publicadas a la antigua: sube los trozos desde este teléfono
     * para que los demás las pidan al puente y no al documento de la vitrina.
     */
    suspend fun publishBinsForMine(firestore: FirebaseFirestore?, ownerFingerprint: String) {
        val fs = firestore ?: return
        if (ownerFingerprint.isBlank()) return
        dao.listMine(ownerFingerprint).forEach { fila ->
            if (!PhotoStore.exists(fila.photoPath)) return@forEach
            runCatching { AlbumBridge.subir(fs, ownerFingerprint, fila.id, fila.photoPath) }
        }
    }

    /**
     * Lista el álbum de otro viajero: solo metadato + previsualización minúscula.
     * La foto grande se pide después con [ensureLocal], desde el teléfono de origen vía el puente.
     */
    suspend fun fetchRemote(firestore: FirebaseFirestore?, fingerprint: String): List<AlbumPhoto> {
        if (fingerprint.isBlank()) return emptyList()
        val cached = dao.listOf(fingerprint).map { it.toDomain() }
        val byId = cached.associateBy { it.id }
        val fs = firestore ?: return cached
        val snap = withTimeoutOrNull(15_000L) {
            runCatching {
                fs.collection(COL).document(fingerprint).collection(PHOTOS)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(40)
                    .get().await()
            }.getOrNull()
        } ?: return cached
        val remotas = snap.documents.mapNotNull { doc ->
            if (!doc.exists()) return@mapNotNull null
            val previa = byId[doc.id]
            val cacheFile = AlbumBridge.cacheFile(context, doc.id)
            val ruta = when {
                PhotoStore.exists(previa?.photoPath.orEmpty()) -> previa!!.photoPath
                cacheFile.exists() -> cacheFile.absolutePath
                else -> ""
            }
            AlbumPhoto(
                id = doc.id,
                ownerFingerprint = fingerprint,
                photoPath = ruta,
                photoThumb = doc.getString("th").orEmpty().ifBlank { previa?.photoThumb.orEmpty() },
                caption = doc.getString("tx").orEmpty(),
                placeName = doc.getString("pl").orEmpty(),
                placeId = doc.getString("pi").orEmpty(),
                taggedUsernames = strings(doc.get("tg")),
                createdAt = doc.getLong("createdAt") ?: previa?.createdAt ?: 0L,
                pendingSync = false,
                setId = doc.getString("sid").orEmpty().ifBlank { doc.id },
                indexInSet = (doc.getLong("ix") ?: 0L).toInt()
            )
        }
        dao.clearRemoteCache(fingerprint)
        if (remotas.isNotEmpty()) dao.upsertAll(remotas.map { it.toEntity() })
        return remotas.ifEmpty { cached.filter { it.photoPath.isNotBlank() } }
    }

    /**
     * Asegura la foto en ESTE teléfono: si ya está, no hace nada; si no, la pide por el
     * puente (trozos) o, en álbumes viejos, reconstruye el JPEG desde la miniatura.
     */
    suspend fun ensureLocal(firestore: FirebaseFirestore?, foto: AlbumPhoto): AlbumPhoto {
        // Ojo: tener la PREVISUALIZACIÓN no cuenta como tenerla. Si contara, la foto buena
        // no se pediría nunca más y la publicación se quedaría borrosa de por vida.
        if (PhotoStore.exists(foto.photoPath) && !AlbumBridge.esPrevia(foto.photoPath)) return foto
        val cache = AlbumBridge.cacheFile(context, foto.id)
        if (cache.exists() && cache.length() > 0L) {
            dao.setPath(foto.id, cache.absolutePath)
            return foto.copy(photoPath = cache.absolutePath)
        }
        val fs = firestore
        if (fs != null && foto.ownerFingerprint.isNotBlank()) {
            val ruta = withTimeoutOrNull(60_000L) {
                runCatching { AlbumBridge.bajar(context, fs, foto.ownerFingerprint, foto.id) }.getOrNull()
            }
            if (!ruta.isNullOrBlank()) {
                dao.setPath(foto.id, ruta)
                return foto.copy(photoPath = ruta)
            }
        }
        // No se pudo traer la buena (sin red, o el dueño todavía no ha subido los trozos).
        // Se deja la previsualización como tapaagujeros, con nombre de previa, para que la
        // próxima vez se vuelva a intentar la de verdad.
        val previa = AlbumBridge.previewFile(context, foto.id)
        if (!previa.exists() && foto.photoThumb.isNotBlank()) {
            val bmp = PhotoStore.decodeThumb(foto.photoThumb)
            if (bmp != null) {
                runCatching {
                    java.io.FileOutputStream(previa).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                    }
                }
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        if (previa.exists() && previa.length() > 0L && foto.photoPath != previa.absolutePath) {
            dao.setPath(foto.id, previa.absolutePath)
            return foto.copy(photoPath = previa.absolutePath)
        }
        return foto
    }

    /**
     * Se adelanta a bajar las fotos del feed (las más nuevas primero) para que ya estén
     * nítidas cuando el viajero llegue a ellas bajando. Sin esto, cada publicación se ve
     * primero borrosa y solo se arregla cuando pasa por delante.
     */
    suspend fun prefetch(firestore: FirebaseFirestore?, fotos: List<AlbumPhoto>, tope: Int = 12) {
        val fs = firestore ?: return
        fotos.asSequence()
            .filter { it.ownerFingerprint.isNotBlank() }
            .filter { !PhotoStore.exists(it.photoPath) || AlbumBridge.esPrevia(it.photoPath) }
            .sortedByDescending { it.createdAt }
            .take(tope)
            .toList()
            .forEach { foto -> runCatching { ensureLocal(fs, foto) } }
    }

    private fun toMap(foto: AlbumPhoto): Map<String, Any> = mapOf(
        "th" to PhotoStore.shrinkThumb(foto.photoThumb),
        "tx" to foto.caption,
        "pl" to foto.placeName,
        "pi" to foto.placeId,
        "tg" to foto.taggedUsernames,
        "createdAt" to foto.createdAt,
        "by" to foto.ownerFingerprint,
        "sid" to foto.setId.ifBlank { foto.id },
        "ix" to foto.indexInSet
    )

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        else -> emptyList()
    }

    companion object {
        const val COL = "albums"
        const val PHOTOS = "photos"
        const val MAX_FOTOS_POR_POST = 10
    }
}

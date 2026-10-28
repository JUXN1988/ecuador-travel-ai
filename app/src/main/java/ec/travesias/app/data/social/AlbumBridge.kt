package ec.travesias.app.data.social

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

/**
 * Puente de fotos del álbum.
 *
 * Firebase NO es la galería: no se listan ni se leen las fotos enteras al abrir Comunidad.
 * Aquí solo viajan trozos ciegos, igual que los adjuntos del chat, y solo cuando alguien
 * pide ver ESA foto. Quien ya la bajó la tiene en su teléfono para siempre.
 *
 * El dueño publica: metadato chiquito (lugar, pie, menciones) + trozos bajo bin/.
 * El visitante, al mirar el perfil o el feed, pide los trozos una vez y los guarda aquí.
 */
object AlbumBridge {

    const val COL_BIN = "bin"
    const val COL_PARTES = "parts"

    /** Carpeta donde quedan las fotos ajenas ya bajadas. */
    fun cacheDir(context: Context): File {
        val d = File(context.filesDir, "fotos-ajenas")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun cacheFile(context: Context, fotoId: String): File =
        File(cacheDir(context), "alb-" + fotoId.take(40) + ".jpg")

    /** Prefijo de las fotos "de mentira": la previsualización minúscula usada como tapaagujeros. */
    private const val PREFIJO_PREVIA = "prev-"

    /**
     * Fichero donde se deja la previsualización minúscula cuando la foto de verdad todavía
     * no ha bajado. Va con OTRO nombre a propósito: antes se guardaba con el nombre de la
     * foto buena y entonces la app creía que ya la tenía, así que no la volvía a pedir NUNCA
     * y esa publicación se quedaba borrosa para siempre.
     */
    fun previewFile(context: Context, fotoId: String): File =
        File(cacheDir(context), PREFIJO_PREVIA + fotoId.take(40) + ".jpg")

    /** true si esa ruta es solo la previsualización, no la foto de verdad. */
    fun esPrevia(path: String): Boolean =
        path.isNotBlank() && path.substringAfterLast('/').substringAfterLast('\\').startsWith(PREFIJO_PREVIA)

    /**
     * Sube la foto local en trozos. Firestore solo ve base64; la ficha del álbum no lleva
     * la imagen. Devuelve cuántos trozos se mandaron.
     */
    suspend fun subir(
        fs: FirebaseFirestore,
        ownerFingerprint: String,
        fotoId: String,
        rutaLocal: String
    ): Int {
        val archivo = File(rutaLocal)
        if (!archivo.exists() || archivo.length() <= 0L) {
            throw IllegalStateException("La foto ya no está en este teléfono.")
        }
        val trozos = ((archivo.length() + Attachments.TROZO - 1) / Attachments.TROZO).toInt().coerceAtLeast(1)
        val bulto = fs.collection(AlbumRepository.COL).document(ownerFingerprint)
            .collection(COL_BIN).document(fotoId)
        val ya = runCatching { bulto.get().await() }.getOrNull()
        if (ya != null && ya.exists() && ya.getBoolean("listo") == true) return trozos

        for (i in 0 until trozos) {
            val crudo = Attachments.leerTrozo(rutaLocal, i)
                ?: throw IllegalStateException("No se pudo leer un trozo de la foto.")
            bulto.collection(COL_PARTES).document(i.toString())
                .set(mapOf("i" to i, "d" to CryptoBox.b64(crudo))).await()
        }
        bulto.set(
            mapOf(
                "n" to trozos,
                "listo" to true,
                "by" to ownerFingerprint,
                "sz" to archivo.length()
            )
        ).await()
        return trozos
    }

    /**
     * Baja los trozos y arma el JPEG en la caché de este teléfono.
     * Si ya estaba, no gasta lecturas otra vez.
     */
    suspend fun bajar(
        context: Context,
        fs: FirebaseFirestore,
        ownerFingerprint: String,
        fotoId: String
    ): String? {
        val destino = cacheFile(context, fotoId)
        if (destino.exists() && destino.length() > 0L) return destino.absolutePath

        val bulto = fs.collection(AlbumRepository.COL).document(ownerFingerprint)
            .collection(COL_BIN).document(fotoId)
        val cabeza = runCatching { bulto.get().await() }.getOrNull()
        if (cabeza == null || !cabeza.exists() || cabeza.getBoolean("listo") != true) return null
        val n = (cabeza.getLong("n") ?: 0L).toInt()
        if (n <= 0) return null

        val partes = bulto.collection(COL_PARTES)
        val tmp = File(destino.absolutePath + ".part")
        runCatching {
            FileOutputStream(tmp).use { out ->
                for (i in 0 until n) {
                    val doc = partes.document(i.toString()).get().await()
                    if (!doc.exists()) {
                        tmp.delete()
                        return null
                    }
                    val bytes = CryptoBox.b64d(doc.getString("d").orEmpty())
                    if (bytes.isEmpty()) {
                        tmp.delete()
                        return null
                    }
                    out.write(bytes)
                }
            }
        }.onFailure {
            tmp.delete()
            return null
        }
        if (destino.exists()) destino.delete()
        if (!tmp.renameTo(destino)) {
            tmp.copyTo(destino, overwrite = true)
            tmp.delete()
        }
        return if (destino.exists() && destino.length() > 0L) destino.absolutePath else null
    }

    /** Borra los trozos de una foto (al quitarla del álbum). */
    suspend fun borrar(fs: FirebaseFirestore, ownerFingerprint: String, fotoId: String) {
        val bulto = fs.collection(AlbumRepository.COL).document(ownerFingerprint)
            .collection(COL_BIN).document(fotoId)
        runCatching {
            val partes = bulto.collection(COL_PARTES).get().await()
            partes.documents.forEach { it.reference.delete().await() }
            bulto.delete().await()
        }
    }
}

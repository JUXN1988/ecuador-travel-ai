package ec.travesias.app.data.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Fotos de la comunidad.
 *
 * La imagen original se guarda SIEMPRE en el almacenamiento privado de este teléfono.
 * Lo único que viaja por Firebase es una miniatura comprimida en base64, para que el
 * resto del grupo pueda verla sin que las fotos se almacenen en la nube.
 */
object PhotoStore {

    /** Lado máximo de la copia local (px). */
    private const val LOCAL_MAX = 1280

    /** Lado máximo de una foto del álbum guardada en este teléfono (px). */
    private const val ALBUM_MAX = 2048

    /** Lado máximo de la miniatura que se sincroniza (px). */
    private const val THUMB_MAX = 320

    /** Tamaño máximo aproximado de la miniatura (bytes de base64). */
    private const val THUMB_MAX_BYTES = 60_000

    /**
     * Previsualización del álbum / vitrina: cabe en un documento chiquito.
     *
     * Antes eran 96 px, y como es lo ÚNICO que se ve mientras la foto de verdad todavía no
     * ha bajado del teléfono del dueño, el feed enseñaba una imagen de 96 px estirada a todo
     * el ancho: eso es lo que se veía borroso. A 224 px sigue pesando ~8 KB (la regla de
     * Firestore admite hasta 12000 caracteres) y ya se ve digna mientras llega la buena.
     */
    private const val MICRO_MAX = 224
    private const val MICRO_MAX_CHARS = 11_000

    data class SavedPhoto(val localPath: String, val thumbBase64: String)

    /**
     * Foto del álbum: grande en ESTE teléfono. Lo que viaja por el puente es una
     * previsualización minúscula (para la rejilla) — la foto se pide aparte, a trozos.
     */
    suspend fun saveAlbum(context: Context, uri: Uri): SavedPhoto? = withContext(Dispatchers.IO) {
        // 2048 px y calidad 92: la copia que se guarda aquí es la que verán los amigos
        // cuando la pidan por el puente, así que no se recorta más de la cuenta.
        val original = decode(context, uri, ALBUM_MAX) ?: return@withContext null
        val file = File(dir(context), "album-" + System.currentTimeMillis() + ".jpg")
        runCatching {
            FileOutputStream(file).use { out -> original.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        }.onFailure { return@withContext null }

        val micro = scaleTo(original, MICRO_MAX)
        val base64 = encodeBounded(micro, MICRO_MAX_CHARS)
        if (micro !== original) micro.recycle()
        original.recycle()
        SavedPhoto(file.absolutePath, base64)
    }

    /** Recorte diminuto a partir de un JPEG que ya está en el teléfono. */
    fun microFromFile(path: String): String {
        val bmp = decodeLocal(path, MICRO_MAX) ?: return ""
        val out = encodeBounded(bmp, MICRO_MAX_CHARS)
        if (!bmp.isRecycled) bmp.recycle()
        return out
    }

    /** Reduce un thumb base64 para la vitrina (listas de gente), sin rehacer el archivo. */
    fun shrinkThumb(base64: String, maxChars: Int = MICRO_MAX_CHARS): String {
        if (base64.isBlank() || base64.length <= maxChars) return base64
        val bmp = decodeThumb(base64) ?: return ""
        val menor = scaleTo(bmp, MICRO_MAX)
        val out = encodeBounded(menor, maxChars)
        if (menor !== bmp && !menor.isRecycled) menor.recycle()
        if (!bmp.isRecycled) bmp.recycle()
        return out
    }

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "fotos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Copia la imagen elegida al teléfono y genera la miniatura sincronizable.
     * Devuelve null si la imagen no se pudo leer.
     */
    suspend fun save(context: Context, uri: Uri, prefix: String): SavedPhoto? = withContext(Dispatchers.IO) {
        val original = decode(context, uri, LOCAL_MAX) ?: return@withContext null
        val file = File(dir(context), prefix + "-" + System.currentTimeMillis() + ".jpg")
        runCatching {
            FileOutputStream(file).use { out -> original.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        }.onFailure { return@withContext null }

        val thumb = scaleTo(original, THUMB_MAX)
        val base64 = encodeBounded(thumb, THUMB_MAX_BYTES)
        if (thumb !== original) thumb.recycle()
        original.recycle()
        SavedPhoto(file.absolutePath, base64)
    }

    /** Miniatura base64 -> bitmap para pintarla. Devuelve null si viene vacía o corrupta. */
    fun decodeThumb(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** Lee una foto guardada en el teléfono, reducida para pintarla sin gastar memoria. */
    fun decodeLocal(path: String, maxSide: Int = 720): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= maxSide || h / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull() ?: return null
        // La cámara suele guardar el giro en EXIF, no en los píxeles: sin esto sale de lado.
        return aplicarOrientacion(bitmap, orientacionDeArchivo(path))
    }

    /**
     * Lee una imagen elegida por el viajero (galería, cámara, archivos) ya reducida.
     * La usan los adjuntos para no mandar una foto de 8 MB tal cual salió del teléfono.
     */
    fun decodeLocalUri(context: Context, uri: Uri, maxSide: Int = LOCAL_MAX): Bitmap? = decode(context, uri, maxSide)

    /**
     * Coordenadas guardadas por la cámara dentro de la foto (EXIF).
     * Devuelve null si la foto no las trae — pasa mucho: WhatsApp y las capturas las borran.
     */
    fun coordenadasDe(context: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val salida = FloatArray(2)
            if (ExifInterface(stream).getLatLong(salida)) {
                val lat = salida[0].toDouble()
                val lng = salida[1].toDouble()
                if (lat == 0.0 && lng == 0.0) null else lat to lng
            } else null
        }
    }.getOrNull()

    /** Lo mismo para una foto que ya está guardada en el teléfono. */
    fun coordenadasDeArchivo(path: String): Pair<Double, Double>? = runCatching {
        val salida = FloatArray(2)
        if (ExifInterface(path).getLatLong(salida)) {
            val lat = salida[0].toDouble()
            val lng = salida[1].toDouble()
            if (lat == 0.0 && lng == 0.0) null else lat to lng
        } else null
    }.getOrNull()

    /** true si el archivo original sigue en este teléfono. */
    fun exists(path: String): Boolean = path.isNotBlank() && runCatching { File(path).exists() }.getOrDefault(false)

    // ------------------------------------------------------------ internos

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / (sample * 2) >= maxSide || h / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        val girada = aplicarOrientacion(bitmap, orientacionDeUri(context, uri))
        return scaleTo(girada, maxSide)
    }

    /** Lee el tag de orientación EXIF de una foto ya guardada en el teléfono. */
    private fun orientacionDeArchivo(path: String): Int =
        runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Lee el tag EXIF de una foto de la galería (otro stream: el decode ya consumió el anterior). */
    private fun orientacionDeUri(context: Context, uri: Uri): Int =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Gira o voltea el bitmap según EXIF. Si no hace falta, devuelve el mismo. */
    private fun aplicarOrientacion(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val girada = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (girada !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            girada
        }.getOrDefault(bitmap)
    }

    private fun scaleTo(source: Bitmap, maxSide: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = if (w > h) w else h
        if (longest <= maxSide || longest == 0) return source
        val factor = maxSide.toDouble() / longest.toDouble()
        val nw = (w * factor).toInt().coerceAtLeast(1)
        val nh = (h * factor).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(source, nw, nh, true) }.getOrDefault(source)
    }

    /**
     * Miniatura JPEG en base64, tope [maxChars]. Nunca vacía: si no cabe se entrega la mejor.
     */
    fun encodeThumb(bitmap: Bitmap): String = encodeBounded(bitmap, THUMB_MAX_BYTES)

    private fun encodeBounded(bitmap: Bitmap, maxChars: Int): String {
        var candidata = bitmap
        var reducciones = 0
        var mejor = ""
        while (reducciones < 4) {
            var quality = 70
            while (quality >= 20) {
                val out = ByteArrayOutputStream()
                candidata.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (mejor.isEmpty() || base64.length < mejor.length) mejor = base64
                if (base64.length <= maxChars) return base64
                quality -= 15
            }
            val lado = (maxOf(candidata.width, candidata.height) * 0.7).toInt()
            if (lado < 48) break
            val menor = scaleTo(candidata, lado)
            if (menor === candidata) break
            candidata = menor
            reducciones++
        }
        if (mejor.isNotEmpty()) return mejor
        val out = ByteArrayOutputStream()
        scaleTo(bitmap, 48).compress(Bitmap.CompressFormat.JPEG, 20, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

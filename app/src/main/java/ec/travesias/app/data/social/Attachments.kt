package ec.travesias.app.data.social

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Fotos, videos y archivos dentro de los chats — **sin pagar nada**.
 *
 * EL PROBLEMA
 * Lo normal para esto es Firebase Storage, pero desde hace un tiempo Storage exige activar el
 * plan de pago con tarjeta aunque no se gaste un centavo. Firestore, en cambio, sigue gratis.
 *
 * LA SOLUCIÓN
 * El archivo se parte en trozos, cada trozo se cifra con la llave del grupo y viaja como un
 * documento más de Firestore, igual que los mensajes. El que recibe baja los trozos, los
 * descifra, los pega y comprueba que el archivo sea EXACTAMENTE el que se mandó (sha-256).
 * Firebase, por el camino, solo ve ruido: ni el nombre del archivo sale sin cifrar.
 *
 * LOS LÍMITES (y por qué son estos)
 *  - Un documento de Firestore no puede pasar de 1 MB. Un trozo de 480 KB, cifrado y en
 *    base64, queda en unos 640 KB: entra de sobra.
 *  - El tope por archivo es de 12 MB. Un video de más no es que "no se pueda": es que gastaría
 *    los datos del que lo recibe y llenaría la cuota gratis del proyecto. Para eso está la
 *    grabación corta, o compartir el enlace.
 *  - Las fotos ni siquiera llegan a eso: se reencuadran a 1600 px y bajan solas de 1 MB.
 */
object Attachments {

    /** Tope por archivo. Más que esto se rechaza con un mensaje claro, no con un fallo raro. */
    const val MAX_BYTES = 12L * 1024 * 1024

    /** Tamaño de cada trozo ANTES de cifrar. Cifrado y en base64 ocupa ~4/3 de esto. */
    const val TROZO = 480 * 1024

    /** Lado máximo de una foto enviada como archivo (para que no pese de más). */
    private const val FOTO_MAX = 1600

    /** Subcolección donde viven los trozos de un archivo. */
    const val COL_BULTOS = "blobs"
    const val COL_PARTES = "parts"

    /**
     * Ficha de un archivo adjunto. Viaja DENTRO del mensaje cifrado, así que ni el nombre ni el
     * tamaño se ven desde fuera.
     */
    data class Ficha(
        val id: String,
        val nombre: String,
        val mime: String,
        val bytes: Long,
        val trozos: Int,
        val sha: String,
        /** Segundos de duración, si es video o audio. 0 si no aplica. */
        val duracion: Int = 0
    ) {
        val esImagen: Boolean get() = mime.startsWith("image/")
        val esVideo: Boolean get() = mime.startsWith("video/")
        val esAudio: Boolean get() = mime.startsWith("audio/")

        /** "3,4 MB", "820 KB"… tal como lo diría una persona. */
        val tamanoLegible: String
            get() = when {
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0)).replace('.', ',')
                bytes >= 1024 -> (bytes / 1024) .toString() + " KB"
                else -> bytes.toString() + " B"
            }

        val duracionLegible: String
            get() = if (duracion <= 0) "" else String.format("%d:%02d", duracion / 60, duracion % 60)

        fun toJson(): String = JSONObject().apply {
            put("id", id); put("n", nombre); put("m", mime)
            put("b", bytes); put("t", trozos); put("h", sha); put("d", duracion)
        }.toString()

        companion object {
            fun fromJson(json: String): Ficha? {
                if (json.isBlank()) return null
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
                val id = o.optString("id", "")
                if (id.isBlank()) return null
                return Ficha(
                    id = id,
                    nombre = o.optString("n", "archivo"),
                    mime = o.optString("m", "application/octet-stream"),
                    bytes = o.optLong("b", 0L),
                    trozos = o.optInt("t", 0),
                    sha = o.optString("h", ""),
                    duracion = o.optInt("d", 0)
                )
            }
        }
    }

    /** Un archivo ya listo para mandarse: copiado al teléfono, medido y con su miniatura. */
    data class Preparado(
        val ficha: Ficha,
        val rutaLocal: String,
        val miniatura: String
    )

    // ======================================================================
    //  Antes de enviar
    // ======================================================================

    private fun carpeta(context: Context): File {
        val d = File(context.filesDir, "adjuntos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Copia el archivo elegido al almacenamiento privado de la app, lo mide y le saca una
     * miniatura (la propia imagen, o el primer fotograma si es video).
     *
     * @throws IllegalStateException si no se puede leer o si pasa del tope, siempre con un
     *   mensaje que una persona entienda.
     */
    suspend fun preparar(context: Context, uri: Uri): Preparado = withContext(Dispatchers.IO) {
        val (nombre, tamanoDeclarado) = datosDe(context, uri)
        val mime = context.contentResolver.getType(uri) ?: adivinarMime(nombre)

        // Las fotos se reencuadran: casi siempre bajan de 8 MB a menos de 1 sin que se note.
        val destino = File(carpeta(context), "adj-" + System.currentTimeMillis() + "-" + limpiar(nombre))
        if (mime.startsWith("image/")) {
            val bmp = PhotoStore.decodeLocalUri(context, uri, FOTO_MAX)
                ?: throw IllegalStateException("No se pudo leer esa imagen.")
            FileOutputStream(destino).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            bmp.recycle()
        } else {
            if (tamanoDeclarado > MAX_BYTES) throw IllegalStateException(mensajeDemasiadoGrande(tamanoDeclarado))
            context.contentResolver.openInputStream(uri)?.use { entrada ->
                FileOutputStream(destino).use { salida -> entrada.copyTo(salida, 64 * 1024) }
            } ?: throw IllegalStateException("No se pudo leer ese archivo.")
        }

        val bytes = destino.length()
        if (bytes <= 0L) { destino.delete(); throw IllegalStateException("Ese archivo llegó vacío.") }
        if (bytes > MAX_BYTES) { destino.delete(); throw IllegalStateException(mensajeDemasiadoGrande(bytes)) }

        val sha = CryptoBox.b64(sha256DeArchivo(destino))
        val trozos = ((bytes + TROZO - 1) / TROZO).toInt()
        val duracion = if (mime.startsWith("video/") || mime.startsWith("audio/")) duracionSegundos(destino) else 0
        val miniatura = when {
            mime.startsWith("image/") -> miniaturaDeImagen(destino)
            mime.startsWith("video/") -> miniaturaDeVideo(destino)
            else -> ""
        }

        Preparado(
            ficha = Ficha(
                id = "b" + System.currentTimeMillis().toString(36) + CryptoBox.base32(CryptoBox.randomBytes(5)).lowercase(),
                nombre = nombre,
                mime = if (mime.startsWith("image/")) "image/jpeg" else mime,
                bytes = bytes,
                trozos = trozos,
                sha = sha,
                duracion = duracion
            ),
            rutaLocal = destino.absolutePath,
            miniatura = miniatura
        )
    }

    private fun mensajeDemasiadoGrande(bytes: Long): String =
        "Ese archivo pesa " + String.format("%.1f", bytes / (1024.0 * 1024.0)).replace('.', ',') +
            " MB y el tope por envío es de 12 MB. Manda un trozo más corto o comparte el enlace."

    /** Nombre y tamaño tal como los declara el sistema. */
    private fun datosDe(context: Context, uri: Uri): Pair<String, Long> {
        var nombre = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        var tamano = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iS = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (iN >= 0 && !c.isNull(iN)) nombre = c.getString(iN)
                    if (iS >= 0 && !c.isNull(iS)) tamano = c.getLong(iS)
                }
            }
        }
        return nombre.take(120) to tamano
    }

    private fun limpiar(nombre: String): String =
        nombre.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60).ifBlank { "archivo" }

    private fun adivinarMime(nombre: String): String = when (nombre.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "gpx" -> "application/gpx+xml"
        else -> "application/octet-stream"
    }

    // ======================================================================
    //  Trocear y volver a juntar
    // ======================================================================

    /** Lee el trozo número [indice] del archivo. */
    fun leerTrozo(ruta: String, indice: Int): ByteArray? {
        val f = File(ruta)
        if (!f.exists()) return null
        val desde = indice.toLong() * TROZO
        if (desde >= f.length()) return null
        val cuanto = minOf(TROZO.toLong(), f.length() - desde).toInt()
        val buffer = ByteArray(cuanto)
        f.inputStream().use { entrada ->
            var saltado = 0L
            while (saltado < desde) {
                val n = entrada.skip(desde - saltado)
                if (n <= 0) return null
                saltado += n
            }
            var leidos = 0
            while (leidos < cuanto) {
                val n = entrada.read(buffer, leidos, cuanto - leidos)
                if (n < 0) break
                leidos += n
            }
            if (leidos < cuanto) return null
        }
        return buffer
    }

    /** Junta los trozos recibidos y comprueba que el archivo sea el mismo que se mandó. */
    fun armar(context: Context, ficha: Ficha, trozos: List<ByteArray>): String? {
        if (trozos.size != ficha.trozos) return null
        val destino = File(carpeta(context), "rec-" + ficha.id + "-" + limpiar(ficha.nombre))
        runCatching {
            FileOutputStream(destino).use { out -> trozos.forEach { out.write(it) } }
        }.onFailure { return null }
        val sha = CryptoBox.b64(sha256DeArchivo(destino))
        if (ficha.sha.isNotBlank() && sha != ficha.sha) {
            // Llegó cambiado: mejor no dejarlo que dejarlo mal.
            destino.delete()
            return null
        }
        return destino.absolutePath
    }

    fun existe(ruta: String): Boolean = ruta.isNotBlank() && runCatching { File(ruta).exists() }.getOrDefault(false)

    private fun sha256DeArchivo(f: File): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { entrada ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = entrada.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest()
    }

    // ======================================================================
    //  Miniaturas
    // ======================================================================

    private fun miniaturaDeImagen(f: File): String {
        val bmp = PhotoStore.decodeLocal(f.absolutePath, 320) ?: return ""
        val salida = comprimir(bmp)
        bmp.recycle()
        return salida
    }

    private fun miniaturaDeVideo(f: File): String {
        val r = MediaMetadataRetriever()
        return runCatching {
            r.setDataSource(f.absolutePath)
            val frame = r.frameAtTime ?: return@runCatching ""
            val chico = if (maxOf(frame.width, frame.height) > 320) {
                val factor = 320.0 / maxOf(frame.width, frame.height)
                Bitmap.createScaledBitmap(frame, (frame.width * factor).toInt().coerceAtLeast(1), (frame.height * factor).toInt().coerceAtLeast(1), true)
            } else frame
            val s = comprimir(chico)
            if (chico !== frame) chico.recycle()
            frame.recycle()
            s
        }.getOrDefault("").also { runCatching { r.release() } }
    }

    private fun duracionSegundos(f: File): Int {
        val r = MediaMetadataRetriever()
        return runCatching {
            r.setDataSource(f.absolutePath)
            ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
        }.getOrDefault(0).also { runCatching { r.release() } }
    }

    private fun comprimir(bmp: Bitmap): String = PhotoStore.encodeThumb(bmp)
}

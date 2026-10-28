package ec.travesias.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import ec.travesias.app.data.model.Place

/**
 * Acciones externas: llamar, WhatsApp, abrir web/redes, navegar con la app de mapas del usuario, compartir.
 */
object Intents {

    private fun safeStart(context: Context, intent: Intent, fallbackMessage: String = "No hay una app para abrir esto") {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, fallbackMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Abre un archivo que llegó por el chat (video, PDF, audio, lo que sea) con la app que el
     * viajero tenga. El archivo vive en la carpeta privada de Travesías, así que se entrega a
     * través del FileProvider con un permiso temporal de solo lectura: la otra app ve ESE
     * archivo y nada más.
     */
    fun openFile(context: Context, path: String, mime: String) {
        val archivo = java.io.File(path)
        if (!archivo.exists()) {
            Toast.makeText(context, "El archivo ya no está en el teléfono", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".archivos", archivo)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(context, intent, "No tienes una app para abrir este tipo de archivo")
    }

    /** Reenviar un adjunto a WhatsApp, al correo, a donde sea. */
    fun shareFile(context: Context, path: String, mime: String) {
        val archivo = java.io.File(path)
        if (!archivo.exists()) return
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".archivos", archivo)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(context, Intent.createChooser(intent, "Compartir archivo"))
    }

    fun dial(context: Context, phone: String) {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        safeStart(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
    }

    fun whatsapp(context: Context, phone: String, text: String = "") {
        var clean = phone.replace(Regex("[^0-9]"), "")
        if (clean.startsWith("0") && clean.length == 10) clean = "593" + clean.drop(1)   // celular ecuatoriano 09xxxxxxxx
        else if (clean.length == 9 && clean.startsWith("9")) clean = "593$clean"
        val url = "https://wa.me/$clean" + if (text.isNotBlank()) "?text=${Uri.encode(text)}" else ""
        safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun openUrl(context: Context, url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun openInstagram(context: Context, handle: String) {
        val h = handle.removePrefix("@").substringAfterLast("/").trim()
        if (handle.startsWith("http")) openUrl(context, handle) else openUrl(context, "https://instagram.com/$h")
    }

    fun openTikTok(context: Context, handle: String) {
        val h = handle.removePrefix("@").substringAfterLast("/").trim()
        if (handle.startsWith("http")) openUrl(context, handle) else openUrl(context, "https://www.tiktok.com/@$h")
    }

    fun openFacebook(context: Context, handle: String) {
        if (handle.startsWith("http")) openUrl(context, handle) else openUrl(context, "https://facebook.com/${handle.removePrefix("@")}")
    }

    fun email(context: Context, address: String) {
        safeStart(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))
    }

    /** Abre la navegación en la app preferida del usuario (Google Maps, Waze, OsmAnd, Maps.me…). */
    fun navigateTo(context: Context, lat: Double, lng: Double, label: String) {
        val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        val intent = Intent(Intent.ACTION_VIEW, geo)
        try {
            context.startActivity(Intent.createChooser(intent, "Cómo llegar con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            openUrl(context, "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
        }
    }

    fun openInGoogleMaps(context: Context, lat: Double, lng: Double) {
        openUrl(context, "https://www.google.com/maps/search/?api=1&query=$lat,$lng")
    }

    fun shareText(context: Context, text: String, title: String = "Compartir") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        safeStart(context, Intent.createChooser(intent, title))
    }

    fun placeShareText(p: Place): String = buildString {
        appendLine("📍 ${p.name} — ${p.locationLabel}")
        if (p.shortDescription.isNotBlank()) appendLine(p.shortDescription)
        appendLine("💵 Costo: ${p.costLabel}")
        if (p.openingHours.isNotBlank()) appendLine("🕒 Horario: ${p.openingHours}")
        if (p.howToGetThere.isNotBlank()) appendLine("🚌 Cómo llegar: ${p.howToGetThere}")
        if (p.contact.phone.isNotBlank()) appendLine("📞 ${p.contact.phone}")
        if (p.contact.whatsapp.isNotBlank()) appendLine("💬 WhatsApp: ${p.contact.whatsapp}")
        if (p.contact.website.isNotBlank()) appendLine("🌐 ${p.contact.website}")
        appendLine("🗺️ https://www.google.com/maps/search/?api=1&query=${p.latitude},${p.longitude}")
        appendLine("Compartido desde Travesías en Ecuador")
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
    }
}

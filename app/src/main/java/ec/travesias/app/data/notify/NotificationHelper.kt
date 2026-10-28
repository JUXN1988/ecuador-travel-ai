package ec.travesias.app.data.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ec.travesias.app.MainActivity
import ec.travesias.app.R
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.ui.i18n.t

/**
 * Notificaciones reales del teléfono: mensajes de la comunidad, me gusta y comentarios en
 * tus fotos, y avisos de tu ruta (llegadas, hora de salir). Son locales: se disparan aquí
 * mismo cuando la app detecta el evento, sin depender de un servidor push aparte.
 *
 * ==========================================================================
 *  POR QUÉ LOS CANALES LLEVAN "_v2" — esto es lo que hacía que no sonaran
 * ==========================================================================
 * Un canal de notificación, en Android 8 en adelante, se configura UNA SOLA VEZ: la
 * primera vez que la app lo crea. A partir de ahí el sonido, la vibración y la
 * importancia los manda el sistema (y el usuario desde Ajustes), y `createNotificationChannel`
 * con los mismos datos NO cambia nada — se ignora en silencio.
 *
 * Las primeras versiones de la app crearon "mensajes_comunidad" y cualquier cosa que le
 * pasara a ese canal (que se creara con importancia baja, que se silenciara sin querer,
 * que un "no molestar" lo dejara mudo) quedó grabada para siempre en el teléfono. Por eso
 * se podía tocar el código todo lo que uno quisiera y las notificaciones seguían mudas.
 *
 * La ÚNICA forma de arreglarlo es crear canales con un id nuevo. Eso hace este fichero:
 * borra los viejos y crea "..._v2" con sonido, vibración y luz explícitos.
 * Si algún día hay que volver a tocarles el sonido, habrá que subirlos a _v3.
 */
object NotificationHelper {

    // Canales nuevos. Los viejos se borran en ensureChannels().
    const val CHANNEL_MESSAGES = "mensajes_comunidad_v2"
    const val CHANNEL_SOCIAL = "reacciones_v2"
    const val CHANNEL_ROUTES = "avisos_ruta_v2"
    const val CHANNEL_SERVICE = "travesias_activo_v2"

    /** Ids de canales de versiones anteriores, que pueden haber quedado mudos. */
    private val CANALES_VIEJOS = listOf("mensajes_comunidad", "avisos_ruta", "servicio_segundo_plano")

    const val SERVICE_NOTIFICATION_ID = 9001
    private const val PRUEBA_ID = 9002

    /** Todos los mensajes se agrupan bajo una misma cabecera en la bandeja. */
    private const val GRUPO_MENSAJES = "ec.travesias.app.MENSAJES"
    private const val GRUPO_SOCIAL = "ec.travesias.app.SOCIAL"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Fuera los canales antiguos: si quedaron mudos, no hay manera de recuperarlos, y
        // además ensuciaban la pantalla de ajustes con entradas repetidas.
        CANALES_VIEJOS.forEach { viejo -> runCatching { nm.deleteNotificationChannel(viejo) } }

        val sonido = runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()
        val audio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Mensajes de viajeros", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suena cuando alguien te escribe, en un chat o en un grupo"
                if (sonido != null) setSound(sonido, audio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOCIAL, "Me gusta y comentarios", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cuando alguien reacciona a tus fotos o te comenta"
                if (sonido != null) setSound(sonido, audio)
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ROUTES, "Avisos de tu ruta", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Llegadas, horas de salida y paradas de tu itinerario"
                if (sonido != null) setSound(sonido, audio)
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Travesías en marcha", NotificationManager.IMPORTANCE_MIN).apply {
                description = "El aviso fijo, sin sonido, mientras la app esta pendiente de tus mensajes"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** ¿Tenemos el permiso de Android 13+ para poder notificar? */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * ¿Están las notificaciones apagadas para toda la app, desde los ajustes del teléfono?
     * Es distinto del permiso: se puede tener el permiso y aun así haberlas silenciado.
     */
    fun apagadasEnElSistema(context: Context): Boolean =
        runCatching { !NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    /** ¿El canal de mensajes está silenciado por el propio viajero? Solo Android 8+. */
    fun canalDeMensajesMudo(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val canal = runCatching { nm.getNotificationChannel(CHANNEL_MESSAGES) }.getOrNull() ?: return false
        return canal.importance == NotificationManager.IMPORTANCE_NONE ||
            canal.importance == NotificationManager.IMPORTANCE_MIN ||
            canal.importance == NotificationManager.IMPORTANCE_LOW
    }

    /** Abre los ajustes de notificaciones de la app en el teléfono. */
    fun abrirAjustes(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * A dónde lleva tocar la notificación. Con [groupId] se abre ESE chat; con
     * [pantalla] se abre esa pestaña de la app (por ejemplo Viajeros).
     */
    private fun openAppIntent(context: Context, groupId: String? = null, pantalla: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!groupId.isNullOrBlank()) putExtra(MainActivity.EXTRA_OPEN_CHAT, groupId)
            if (!pantalla.isNullOrBlank()) putExtra(MainActivity.EXTRA_OPEN_ROUTE, pantalla)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Cada chat necesita su propio requestCode: con uno solo, Android reutilizaría el
        // PendingIntent del primero y todas las notificaciones abrirían el mismo chat.
        val codigo = when {
            !groupId.isNullOrBlank() -> groupId.hashCode()
            !pantalla.isNullOrBlank() -> pantalla.hashCode()
            else -> 0
        }
        return PendingIntent.getActivity(context, codigo, intent, flags)
    }

    private fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        groupId: String? = null,
        pantalla: String? = null,
        grupoBandeja: String? = null,
        categoria: String? = null
    ) {
        if (!hasPermission(context)) return
        ensureChannels(context)
        val sonido = runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_map_pin)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context, groupId, pantalla))
        // En Android 7 y anteriores no hay canales: el sonido y la vibracion se piden aqui.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (sonido != null) b.setSound(sonido)
            b.setVibrate(longArrayOf(0, 250, 150, 250))
            b.setDefaults(NotificationCompat.DEFAULT_LIGHTS)
        }
        if (categoria != null) b.setCategory(categoria)
        if (grupoBandeja != null) b.setGroup(grupoBandeja)
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    /**
     * Un mensaje nuevo de alguien más llegó a un chat o a un grupo.
     * Se agrupan por chat: si llegan tres seguidos del mismo, no salen tres notificaciones.
     */
    fun notifyMessage(context: Context, groupId: String, groupName: String, authorName: String, text: String) {
        val lang = AppSettings.language.value
        val id = ("msg_" + groupId).hashCode()
        val cuerpo = text.ifBlank { t(lang, "Envió una foto o una ubicación", "Sent a photo or a location") }
        notify(
            context, CHANNEL_MESSAGES, id, "$groupName · $authorName", cuerpo,
            groupId = groupId,
            grupoBandeja = GRUPO_MENSAJES,
            categoria = NotificationCompat.CATEGORY_MESSAGE
        )
    }

    /**
     * Alguien reaccionó a una foto tuya o te dejó un comentario.
     * [quien] va sin la arroba; [detalle] es el emoji o el texto del comentario.
     */
    fun notifyReaccion(context: Context, clave: String, quien: String, detalle: String, esComentario: Boolean) {
        val lang = AppSettings.language.value
        val id = ("soc_" + clave + quien + esComentario).hashCode()
        val titulo = if (esComentario) {
            t(lang, "@$quien comentó tu foto", "@$quien commented on your photo")
        } else {
            t(lang, "A @$quien le gustó tu foto", "@$quien liked your photo")
        }
        val cuerpo = detalle.ifBlank { if (esComentario) "💬" else "❤️" }
        notify(
            context, CHANNEL_SOCIAL, id, titulo, cuerpo,
            pantalla = "community",
            grupoBandeja = GRUPO_SOCIAL,
            categoria = NotificationCompat.CATEGORY_SOCIAL
        )
    }

    /** Aviso de ruta: llegada a una parada, hora de salir, etc. */
    fun notifyRouteEvent(context: Context, id: Int, title: String, text: String) {
        notify(context, CHANNEL_ROUTES, id, title, text, categoria = NotificationCompat.CATEGORY_EVENT)
    }

    /**
     * Notificación de prueba, para comprobar en el momento si suenan de verdad.
     * La lanza el botón de Kit → Permisos.
     */
    fun notifyPrueba(context: Context) {
        val lang = AppSettings.language.value
        notify(
            context, CHANNEL_MESSAGES, PRUEBA_ID,
            t(lang, "Prueba de notificación", "Notification test"),
            t(lang, "Si escuchaste el sonido y viste este aviso, los mensajes te van a llegar igual.",
                "If you heard the sound and saw this, messages will reach you the same way."),
            categoria = NotificationCompat.CATEGORY_MESSAGE
        )
    }

    /**
     * Notificación fija del servicio en segundo plano.
     *
     * Android OBLIGA a llevarla mientras el servicio está vivo: no hay forma de esconderla,
     * y ese servicio es lo que mantiene la app pendiente de los mensajes con la pantalla
     * apagada. O sea: o aviso fijo y mensajes que suenan, o nada de aviso y mensajes que
     * solo llegan con la app abierta. Se puede elegir en Kit → Permisos.
     *
     * Va en un canal de importancia MÍNIMA y sin sonido: aparece abajo del todo de la
     * bandeja, sin insignia y sin molestar.
     */
    fun serviceNotification(context: Context): Notification {
        ensureChannels(context)
        val lang = AppSettings.language.value
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_map_pin)
            .setContentTitle(t(lang, "Travesías está pendiente", "Travesías is listening"))
            .setContentText(t(lang, "Para avisarte de mensajes y de tu ruta con la app cerrada",
                "So it can alert you about messages and your route while the app is closed"))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(context))
            .build()
    }
}

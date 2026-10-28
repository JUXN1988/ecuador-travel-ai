package ec.travesias.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import ec.travesias.app.TravesiasApp
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.notify.NotificationHelper
import ec.travesias.app.data.remote.RoutingService
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.ui.i18n.label
import ec.travesias.app.ui.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Mantiene la app "viva" en segundo plano para que:
 *  1. Los mensajes de la comunidad (ver CommunityRepository) sigan notificándose aunque
 *     la app esté minimizada.
 *  2. Se avise de verdad cuando el viajero llega a una parada de su ruta activa, o cuando
 *     ya toca salir hacia la siguiente, usando la ubicación real del teléfono.
 *
 * Requiere una notificación fija (Android obliga a todo foreground service a tenerla).
 * Sin permiso de ubicación sigue funcionando igual para los mensajes; simplemente no
 * hace la parte de avisos de ruta.
 */
class TravesiasBackgroundService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Para no repetir el mismo aviso una y otra vez mientras el servicio sigue vivo.
    private val avisosLlegada = HashSet<String>()
    private val avisosSalida = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        val tieneUbicacion = DeviceLocation.hasPermission(this)
        val tipo = if (tieneUbicacion) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        runCatching {
            ServiceCompat.startForeground(this, NotificationHelper.SERVICE_NOTIFICATION_ID, NotificationHelper.serviceNotification(this), tipo)
        }
        job = scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        while (true) {
            val seguir = runCatching { ciclo() }.getOrDefault(true)
            // Solo se apaga si el viajero apagó el interruptor. Mientras esté encendido el
            // servicio SIGUE VIVO aunque no haya ruta: es lo único que mantiene a la app
            // pendiente de los mensajes con la pantalla apagada.
            if (!seguir) {
                runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
                stopSelf()
                return
            }
            delay(90_000L)
        }
    }

    /** Devuelve false SOLO cuando el viajero apagó el aviso en segundo plano. */
    private suspend fun ciclo(): Boolean {
        if (!AppSettings.backgroundTracking.value) return false

        // Tocar el contenedor es lo importante para los MENSAJES: obliga a que la
        // CommunityRepository exista y tenga sus escuchas de Firestore enganchadas, que es
        // quien lanza la notificación cuando llega algo. Antes esto estaba después de los
        // "return" de abajo, así que sin ruta activa ni permiso de ubicación el servicio se
        // apagaba y los mensajes dejaban de avisar. Ese fue el fallo.
        val container = TravesiasApp.from(this)

        // De aquí para abajo es la parte de la RUTA, que sí necesita ubicación y ruta activa.
        // Si no las hay no pasa nada: se sigue vivo por los mensajes.
        if (!DeviceLocation.hasPermission(this)) return true
        val route = container.routeRepository.activeRouteOnce() ?: return true
        val stops = route.stops
        if (stops.isEmpty()) return true

        // Mismo horario que ve el viajero en Mi Ruta: con la salida desde el origen y el regreso.
        val ends = container.routeRepository.endpointsFor(route)
        val road = runCatching { container.routing.routeBetween(RouteSchedule.roadSequence(route, ends)) }.getOrNull()
        val agenda = RouteSchedule.build(route, road, ends)
        val diaDeHoy = diaDeHoyDesde(route.startDate)

        val ubicacion = runCatching { DeviceLocation.current(this, timeoutMs = 12_000L) }.getOrNull() ?: return true

        // Llegada real a una parada (funciona haya fecha o no).
        val planHoy = if (diaDeHoy != null) agenda.firstOrNull { it.day == diaDeHoy } else agenda.firstOrNull()
        planHoy?.stops?.forEach { prog ->
            val distanciaKm = RoutingService.haversineKm(ubicacion.latitude, ubicacion.longitude, prog.stop.latitude, prog.stop.longitude)
            if (distanciaKm <= 0.3 && avisosLlegada.add(prog.stop.id)) {
                val lang = AppSettings.language.value
                NotificationHelper.notifyRouteEvent(
                    this,
                    ("llegada_" + prog.stop.id).hashCode(),
                    t(lang, "Llegaste a " + prog.stop.placeName, "You arrived at " + prog.stop.placeName),
                    t(lang, "Puedes visitarla " + prog.visitLabel + " · sales " + prog.departure,
                        "You can visit for " + prog.visitLabel + " · leave at " + prog.departure)
                )
            }
        }

        // Aviso de "hora de salir" solo si sabemos qué día es hoy en la ruta.
        if (diaDeHoy != null && planHoy != null) {
            val ahoraMin = minutosDeAhora()
            planHoy.stops.forEachIndexed { i, prog ->
                val siguiente = planHoy.stops.getOrNull(i + 1) ?: return@forEachIndexed
                val faltan = prog.departureMinutes - ahoraMin
                if (faltan in 0..15 && avisosSalida.add(prog.stop.id)) {
                    val lang = AppSettings.language.value
                    NotificationHelper.notifyRouteEvent(
                        this,
                        ("salida_" + prog.stop.id).hashCode(),
                        t(lang, "Sal pronto de " + prog.stop.placeName, "Time to leave " + prog.stop.placeName),
                        t(lang, "En " + faltan + " min toca salir hacia " + siguiente.stop.placeName + " (" + siguiente.transport.label(lang) + ")",
                            "In " + faltan + " min you should head to " + siguiente.stop.placeName + " (" + siguiente.transport.label(lang) + ")")
                    )
                }
            }
        }
        return true
    }

    private fun minutosDeAhora(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** Día 1, 2, 3... de la ruta según route.startDate ("AAAA-MM-DD") y la fecha de hoy. Null si no hay fecha. */
    private fun diaDeHoyDesde(startDate: String): Int? {
        if (startDate.isBlank()) return null
        return runCatching {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formato.isLenient = false
            val inicio = Calendar.getInstance().apply {
                time = formato.parse(startDate) ?: return null
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val hoy = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val diffDias = ((hoy.timeInMillis - inicio.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
            diffDias + 1
        }.getOrNull()
    }

    companion object {
        /** Arranca el servicio si el viajero quiere seguimiento en segundo plano. No falla si ya está activo. */
        fun start(context: Context) {
            if (!AppSettings.backgroundTracking.value) return
            val intent = Intent(context, TravesiasBackgroundService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TravesiasBackgroundService::class.java)) }
        }
    }
}

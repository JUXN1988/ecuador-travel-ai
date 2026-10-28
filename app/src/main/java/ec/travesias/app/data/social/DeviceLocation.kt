package ec.travesias.app.data.social

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Ubicación del teléfono sin dependencias de Google Play Services.
 * Primero prueba la última posición conocida y, si no hay, pide una lectura nueva.
 */
object DeviceLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Última posición conocida por el sistema (instantánea, puede ser null). */
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        return best
    }

    /**
     * Posición actual. Usa la última conocida si es reciente; si no, espera una lectura
     * nueva hasta [timeoutMs]. Devuelve null si no hay permiso o no llega ninguna señal.
     */
    suspend fun current(context: Context, timeoutMs: Long = 15_000L): Location? {
        if (!hasPermission(context)) return null
        val cached = lastKnown(context)
        if (cached != null && System.currentTimeMillis() - cached.time < 60_000L) return cached
        val fresh = withTimeoutOrNull(timeoutMs) { requestSingle(context) }
        return fresh ?: cached
    }

    private suspend fun requestSingle(context: Context): Location? = suspendCancellableCoroutine { cont ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                runCatching { lmRemove(lm, this) }
                if (cont.isActive) cont.resume(location)
            }

            // Necesarios para compatibilidad con Android 8 y anteriores.
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: IllegalArgumentException) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            done = true
            Handler(Looper.getMainLooper()).post { runCatching { lmRemove(lm, listener) } }
        }
    }

    private fun lmRemove(lm: LocationManager, listener: LocationListener) {
        try {
            lm.removeUpdates(listener)
        } catch (e: SecurityException) {
            // sin permiso: nada que quitar
        }
    }
}

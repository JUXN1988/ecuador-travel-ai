package ec.travesias.app.data.social

import android.content.Context
import android.net.Uri
import ec.travesias.app.data.remote.GeocodingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * De dónde salió una foto.
 *
 * Orden de intentos, del dato más fiable al menos:
 *  1. Las coordenadas que la cámara escribió DENTRO de la foto (EXIF). Es lo exacto:
 *     dice dónde se tomó, no dónde está el viajero ahora.
 *  2. El GPS del teléfono, si la foto no trae nada (WhatsApp y las capturas borran el EXIF).
 *  3. Nada: entonces la pantalla deja escribir el lugar a mano.
 *
 * El nombre del sitio se saca de OpenStreetMap (Nominatim), que ya usa el resto de la app.
 */
object LugarDeLaFoto {

    enum class Origen { FOTO, GPS, NINGUNO }

    data class Hallazgo(
        val lat: Double,
        val lng: Double,
        val nombre: String,
        val origen: Origen
    ) {
        /** Lo que se le enseña al viajero para que sepa de dónde salió el dato. */
        val explicacion: String
            get() = when (origen) {
                Origen.FOTO -> "Tomada aquí, según la foto"
                Origen.GPS -> "Donde estás ahora"
                Origen.NINGUNO -> ""
            }
    }

    private val geocoder = GeocodingService()

    /**
     * @param permitirGps false si no se quiere molestar con el GPS (por ejemplo cuando el
     *        viajero ya escribió el lugar a mano).
     */
    suspend fun detectar(context: Context, uri: Uri, permitirGps: Boolean = true): Hallazgo? =
        withContext(Dispatchers.IO) {
            val deLaFoto = PhotoStore.coordenadasDe(context, uri)
            if (deLaFoto != null) {
                return@withContext conNombre(deLaFoto.first, deLaFoto.second, Origen.FOTO)
            }
            if (!permitirGps || !DeviceLocation.hasPermission(context)) return@withContext null
            val pos = withTimeoutOrNull(12_000L) { DeviceLocation.current(context) } ?: return@withContext null
            conNombre(pos.latitude, pos.longitude, Origen.GPS)
        }

    /** Solo el GPS, para el botón "usar dónde estoy". */
    suspend fun soloGps(context: Context): Hallazgo? = withContext(Dispatchers.IO) {
        if (!DeviceLocation.hasPermission(context)) return@withContext null
        val pos = withTimeoutOrNull(12_000L) { DeviceLocation.current(context) } ?: return@withContext null
        conNombre(pos.latitude, pos.longitude, Origen.GPS)
    }

    /** Nombre legible del punto. Si Nominatim no contesta, quedan las coordenadas. */
    private suspend fun conNombre(lat: Double, lng: Double, origen: Origen): Hallazgo {
        // Nominatim devuelve la dirección entera ("Casa, calle, barrio, ciudad, provincia,
        // Ecuador"). Para etiquetar una foto sobra con ciudad y provincia.
        val punto = withTimeoutOrNull(9_000L) {
            runCatching { geocoder.reverse(lat, lng) }.getOrNull()
        }
        val nombre = when {
            punto == null -> ""
            punto.city.isNotBlank() && punto.province.isNotBlank() -> punto.city + ", " + punto.province
            punto.city.isNotBlank() -> punto.city
            punto.province.isNotBlank() -> punto.province
            else -> punto.displayName.substringBefore(",").trim()
        }
        return Hallazgo(
            lat = lat,
            lng = lng,
            nombre = nombre.ifBlank { formatearCoords(lat, lng) },
            origen = origen
        )
    }

    private fun formatearCoords(lat: Double, lng: Double): String =
        String.format(java.util.Locale.US, "%.4f, %.4f", lat, lng)
}

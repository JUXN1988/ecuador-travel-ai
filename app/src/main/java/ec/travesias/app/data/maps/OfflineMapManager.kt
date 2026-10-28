package ec.travesias.app.data.maps

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox

/**
 * Guarda el mapa para usarlo sin señal (teselas de OpenStreetMap en la caché de osmdroid).
 *
 * Detalle importante: la fuente MAPNIK que trae osmdroid lleva la bandera "NO_BULK" y el
 * CacheManager LANZA UNA EXCEPCIÓN si se le pide descargar con ella (era lo que cerraba la app).
 * Aquí se usa una fuente equivalente, con el mismo nombre para compartir la misma caché, y con
 * descargas acotadas: solo el área que el viajero pide, con tope de teselas y zoom limitado.
 */
class OfflineMapManager(private val context: Context) {

    data class DownloadState(
        val packageId: String,
        val progress: Float,       // 0..1
        val tilesDone: Int,
        val tilesTotal: Int,
        val isRunning: Boolean,
        val error: String? = null
    )

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    /**
     * Misma URL y mismo nombre que MAPNIK (para que el mapa lea estas teselas), pero con una
     * política que sí permite guardarlas. Se descarga solo lo que el viajero pide, nunca el país.
     */
    private val offlineSource: OnlineTileSourceBase by lazy {
        XYTileSource(
            "Mapnik", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/"
            ),
            "© OpenStreetMap contributors",
            TileSourcePolicy(
                2,
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
            )
        )
    }

    private val provider by lazy { MapTileProviderBasic(context.applicationContext, offlineSource) }
    private val writer by lazy { SqlTileWriter() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    companion object {
        const val ZOOM_MIN = 10
        const val ZOOM_MAX_REGION = 14
        const val ZOOM_MAX_ROUTE = 15

        /** Tope duro de teselas por descarga (~15 KB cada una ≈ 90 MB). */
        const val MAX_TILES = 6000
    }

    private fun cacheManager(zoomMax: Int): CacheManager? =
        runCatching { CacheManager(provider, writer, ZOOM_MIN, zoomMax) }.getOrNull()

    /** Número de teselas del área. Devuelve 0 si no se puede calcular (nunca lanza). */
    fun estimateTiles(north: Double, south: Double, east: Double, west: Double, zoomMax: Int = ZOOM_MAX_REGION): Int =
        runCatching {
            val cm = cacheManager(zoomMax) ?: return 0
            cm.possibleTilesInArea(BoundingBox(north, east, south, west), ZOOM_MIN, zoomMax)
        }.getOrDefault(0)

    fun estimateSizeMb(north: Double, south: Double, east: Double, west: Double, zoomMax: Int = ZOOM_MAX_REGION): Int =
        (estimateTiles(north, south, east, west, zoomMax) * 15L / 1024L).toInt().coerceAtLeast(1)

    /**
     * Descarga el área indicada. Si sale demasiado grande baja el zoom automáticamente hasta que
     * quepa; si aun así no cabe, avisa en vez de intentarlo. El progreso se publica en [downloads].
     */
    fun downloadArea(
        packageId: String,
        north: Double, south: Double, east: Double, west: Double,
        zoomMax: Int = ZOOM_MAX_REGION,
        onDone: (Boolean) -> Unit = {}
    ) {
        if (_downloads.value[packageId]?.isRunning == true) return

        val bb = runCatching { BoundingBox(north, east, south, west) }.getOrNull()
        if (bb == null) {
            fail(packageId, "El área del mapa no es válida.", onDone)
            return
        }

        // Ajustar el zoom hasta que el número de teselas sea razonable.
        var zMax = zoomMax.coerceIn(ZOOM_MIN, 16)
        var cm = cacheManager(zMax)
        if (cm == null) {
            fail(packageId, "Este dispositivo no puede guardar el mapa sin señal.", onDone)
            return
        }
        var total = runCatching { cm!!.possibleTilesInArea(bb, ZOOM_MIN, zMax) }.getOrDefault(0)
        while (total > MAX_TILES && zMax > ZOOM_MIN) {
            zMax--
            cm = cacheManager(zMax)
            total = runCatching { cm?.possibleTilesInArea(bb, ZOOM_MIN, zMax) ?: 0 }.getOrDefault(0)
        }
        val manager = cm
        if (manager == null || total <= 0) {
            fail(packageId, "No se pudo preparar la descarga del mapa.", onDone)
            return
        }
        if (total > MAX_TILES) {
            fail(packageId, "Esa zona es demasiado grande para guardarla. Descarga el mapa de tu ruta en su lugar.", onDone)
            return
        }

        val tilesTotal = total
        val zoomFinal = zMax
        _downloads.update { it + (packageId to DownloadState(packageId, 0f, 0, tilesTotal, true)) }

        // osmdroid arranca la descarga con un AsyncTask: tiene que lanzarse desde el hilo principal.
        main.post {
            val ok = runCatching {
                manager.downloadAreaAsyncNoUI(
                    context.applicationContext, bb, ZOOM_MIN, zoomFinal,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            _downloads.update { it + (packageId to DownloadState(packageId, 1f, tilesTotal, tilesTotal, false)) }
                            onDone(true)
                        }

                        override fun onTaskFailed(errors: Int) {
                            val aceptable = errors < tilesTotal / 4
                            _downloads.update {
                                it + (packageId to DownloadState(
                                    packageId,
                                    if (aceptable) 1f else 0f,
                                    (tilesTotal - errors).coerceAtLeast(0),
                                    tilesTotal,
                                    false,
                                    if (aceptable) null else "Fallaron $errors teselas. Revisa tu conexión e inténtalo de nuevo."
                                ))
                            }
                            onDone(aceptable)
                        }

                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                            val frac = if (tilesTotal > 0) (progress.toFloat() / tilesTotal).coerceIn(0f, 1f) else 0f
                            _downloads.update { it + (packageId to DownloadState(packageId, frac, progress, tilesTotal, true)) }
                        }

                        override fun downloadStarted() {}

                        override fun setPossibleTilesInArea(total: Int) {}
                    }
                )
                true
            }.getOrDefault(false)

            if (!ok) fail(packageId, "No se pudo iniciar la descarga del mapa.", onDone)
        }
    }

    private fun fail(packageId: String, mensaje: String, onDone: (Boolean) -> Unit) {
        _downloads.update { it + (packageId to DownloadState(packageId, 0f, 0, 0, false, mensaje)) }
        onDone(false)
    }

    /** Borra las teselas guardadas de un área (libera espacio). Nunca lanza. */
    fun deleteArea(north: Double, south: Double, east: Double, west: Double, zoomMax: Int = ZOOM_MAX_REGION) {
        Thread {
            runCatching {
                val bb = BoundingBox(north, east, south, west)
                val tiles = CacheManager.getTilesCoverage(bb, ZOOM_MIN, zoomMax)
                for (index in tiles) {
                    runCatching { writer.remove(offlineSource, index) }
                    runCatching { writer.remove(TileSourceFactory.MAPNIK, index) }
                }
            }
        }.start()
        _downloads.update { current -> current.filterValues { it.progress < 1f || it.isRunning } }
    }

    /** Tamaño actual de la caché de teselas en MB. */
    fun cacheSizeMb(): Long = runCatching {
        val dir = org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
        var bytes = 0L
        dir?.walkTopDown()?.forEach { if (it.isFile) bytes += it.length() }
        bytes / (1024 * 1024)
    }.getOrDefault(0L)
}

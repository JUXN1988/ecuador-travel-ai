package ec.travesias.app

import android.app.Application
import android.content.Context
import ec.travesias.app.data.ai.GeminiTravelService
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.notify.NotificationHelper
import ec.travesias.app.data.remote.*
import ec.travesias.app.data.repository.RouteRepository
import ec.travesias.app.data.repository.TravelRepository
import ec.travesias.app.data.social.AlbumRepository
import ec.travesias.app.data.social.CommunityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

/**
 * Contenedor de dependencias simple (sin frameworks) compartido por todos los ViewModels.
 */
class AppContainer(context: Context, appScope: CoroutineScope) {
    val database: AppDatabase = AppDatabase.getDatabase(context)
    val gemini: GeminiClient = GeminiClient()
    val geocoding = GeocodingService()
    val routing = RoutingService()
    val places = PlacesService()
    val youtube = YouTubeService()
    val radar = RadarService(gemini, geocoding, routing, places, youtube)
    /** OpenStreetMap crudo: pueblos y lugares reales del camino, gratis y sin clave. */
    val overpass = OverpassService()
    /** Explorador de ruta: barre el corredor pueblo por pueblo sin depender de la IA. */
    val scout = RouteScoutService(geocoding, routing, overpass)
    /** Buscador de internet con fuentes: lo usan la Guia IA, el Radar y el lector de enlaces. */
    val research = WebResearchService(gemini)
    val socialLinks = SocialLinkResolver()
    val linkExtractor = LinkExtractorService(gemini, geocoding, places, research, socialLinks)
    val aiService = GeminiTravelService(Secrets.geminiApiKey, gemini)
    val travelRepository = TravelRepository(database, aiService)
    val routeRepository = RouteRepository(database, radar, routing, aiService, places, scout)
    val community = CommunityRepository(context, database, appScope)
    /** Álbum de fotos del perfil de viajero (lugares visitados y menciones). */
    val album = AlbumRepository(context, database).also { community.albumRepo = it }

    /** Reacciones y comentarios de las publicaciones y de los muros de los perfiles. */
    val interacciones = ec.travesias.app.data.social.InteraccionesRepository(context, database)

    /**
     * Catálogo común: los lugares que aporta cualquiera se comparten con todos. Lo personal
     * (perfil, rutas, lugares guardados, fotos propias) nunca entra aquí.
     */
    val catalogSync = ec.travesias.app.data.social.CatalogSync(context, database).also { sync ->
        sync.authorProvider = { community.account.identity?.fingerprint ?: "" }
        routeRepository.catalog = sync
        community.catalog = sync
    }
    val placeIndex = ec.travesias.app.data.repository.PlaceIndexRepository(database, geocoding)
    /** Guía IA con herramientas: busca, crea y edita rutas de verdad. */
    val agent = ec.travesias.app.data.ai.TravelAgent(gemini, routeRepository, placeIndex, radar, geocoding, research, linkExtractor)
    /** Armador automático de rutas: "de dónde a dónde" y la IA propone el itinerario editable. */
    val routePlanner = ec.travesias.app.data.ai.AiRoutePlanner(radar, routing, geocoding, placeIndex, routeRepository, scout)
    /** Ubica en el mapa el origen y el destino escritos en la ruta (para sumar la salida y el regreso). */
    val routeEndpoints = ec.travesias.app.data.repository.RouteEndpointsResolver(placeIndex, routeRepository)
        .also { routeRepository.endpoints = it }
    /** Precios vigentes del combustible en Ecuador (búsqueda en internet con fuentes). */
    val fuelPrices = ec.travesias.app.data.remote.FuelPriceService(gemini)
    val offlineMaps = ec.travesias.app.data.maps.OfflineMapManager(context)
    /** Fotos de los lugares: se buscan una vez y quedan guardadas en el teléfono. */
    val photos = ec.travesias.app.data.remote.PlacePhotoService(context)

    /**
     * Completa las fotos que falten en el catálogo. Va despacio a propósito para no
     * abusar de Wikimedia y solo baja las que no estén ya en el teléfono.
     */
    suspend fun backfillPhotos(limite: Int = 60) {
        val lugares = runCatching { routeRepository.allPlacesOnce() }.getOrDefault(emptyList())
        var hechas = 0
        for (p in lugares) {
            if (hechas >= limite) break
            if (photos.hasLocal(p.id)) continue
            val conFoto = runCatching { photos.ensurePhoto(p) }.getOrNull() ?: continue
            if (conFoto.imageUrls != p.imageUrls) {
                runCatching { routeRepository.upsertPlace(conFoto) }
                hechas++
                kotlinx.coroutines.delay(700)
            }
        }
    }
}

class TravesiasApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        // Vehículo del viajero y precios del combustible (para el costo de la ruta en carro propio).
        ec.travesias.app.data.local.VehicleSettings.init(this)
        NotificationHelper.ensureChannels(this)
        // osmdroid: identificador obligatorio para los servidores de teselas de OpenStreetMap
        Configuration.getInstance().apply {
            load(this@TravesiasApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            // El User-Agent debe fijarse DESPUÉS de load(): OSM bloquea el valor por defecto.
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
            tileDownloadThreads = 4
            tileFileSystemCacheMaxBytes = 1024L * 1024L * 1024L // 1 GB para mapas offline
            tileFileSystemCacheTrimBytes = 900L * 1024L * 1024L
            expirationOverrideDuration = 1000L * 60 * 60 * 24 * 365 // las teselas descargadas duran 1 año offline
        }
        container = AppContainer(this, appScope)
        appScope.launch {
            runCatching { container.database.seedIfEmpty() }
            // Índice de provincias, cantones y lugares para el autocompletado de toda la app
            runCatching { container.placeIndex.refresh() }
            // Fotos del catálogo: se descargan una vez y quedan guardadas para verlas sin señal
            runCatching { container.backfillPhotos() }
        }
        container.community.start()
    }

    companion object {
        fun from(context: Context): AppContainer = (context.applicationContext as TravesiasApp).container
    }
}

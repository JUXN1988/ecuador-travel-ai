package ec.travesias.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.SearchArea
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mapas base entre los que puede elegir el viajero.
 *
 * POR QUÉ HACEN FALTA VARIOS
 * El mapa de calles es el mejor para orientarse, pero en Ecuador se viaja mucho por sitios
 * donde lo que importa no son las calles: una playa, un sendero al páramo, una cascada al
 * final de un camino de tierra. Ahí el satélite y el topográfico dicen la verdad y el mapa de
 * calles no. Todas estas capas son gratis y no necesitan ninguna clave.
 */
enum class MapBaseLayer(val clave: String, val etiqueta: String, val explicacion: String) {
    CALLES("calles", "Calles", "El de siempre: vías, pueblos y nombres."),
    SATELITE("satelite", "Satélite", "Foto desde el aire: playas, senderos y lo que hay de verdad."),
    TOPO("topo", "Topográfico", "Curvas de nivel y relieve, para montaña y caminatas."),
    CAMINOS("caminos", "Caminos y comunidades", "Resalta caminos de tierra, senderos y poblados pequeños.");

    companion object {
        fun from(clave: String?): MapBaseLayer = entries.firstOrNull { it.clave == clave } ?: CALLES
    }
}

/**
 * Satélite de Esri. Se necesita una fuente propia porque su servidor pide el orden
 * z/y/x, al revés del z/x/y que usa todo lo demás.
 */
private val FUENTE_SATELITE: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "Esri.WorldImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
            "/" + MapTileIndex.getY(pMapTileIndex) +
            "/" + MapTileIndex.getX(pMapTileIndex)
}

private val FUENTE_TOPO: ITileSource = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/"
    ),
    "© OpenTopoMap (CC-BY-SA), datos de OpenStreetMap"
)

private val FUENTE_CAMINOS: ITileSource = XYTileSource(
    "OSM.HOT", 0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.fr/hot/",
        "https://b.tile.openstreetmap.fr/hot/"
    ),
    "© OpenStreetMap, estilo humanitario (HOT)"
)

private fun fuenteDe(capa: MapBaseLayer): ITileSource = when (capa) {
    MapBaseLayer.CALLES -> TileSourceFactory.MAPNIK
    MapBaseLayer.SATELITE -> FUENTE_SATELITE
    MapBaseLayer.TOPO -> FUENTE_TOPO
    MapBaseLayer.CAMINOS -> FUENTE_CAMINOS
}

/** Marcador que se dibuja en el mapa. */
data class MapPin(
    val id: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val subtitle: String = "",
    val category: PlaceCategory = PlaceCategory.NATURE,
    val isRouteStop: Boolean = false,
    val stopLabel: String = "",   // "D1·1" (día 1, parada 1)
    val highlighted: Boolean = false
)

fun Place.toPin(highlighted: Boolean = false) = MapPin(id, latitude, longitude, name, locationLabel, category, false, "", highlighted)

/**
 * Una parada de la ruta se dibuja NUMERADA (1, 2, 3…) en el orden real del viaje. Antes todas
 * eran pines iguales y en una ruta de 10 paradas no se sabía cuál iba primero; el número es
 * la forma más rápida de leer un itinerario en el mapa.
 */
fun RouteStop.toPin(index: Int, category: PlaceCategory = PlaceCategory.NATURE) =
    MapPin(
        placeId, latitude, longitude, placeName,
        "Día $dayNumber · parada ${orderInDay + 1}",
        category, true, "${index + 1}",
        highlighted = false
    )

/** Colores por categoría (ARGB Android) para los marcadores. */
fun categoryArgb(category: PlaceCategory): Int = when (category) {
    PlaceCategory.NATURE -> 0xFF2E7D32.toInt()
    PlaceCategory.FOOD -> 0xFFE65100.toInt()
    PlaceCategory.CULTURE -> 0xFF6A1B9A.toInt()
    PlaceCategory.ADVENTURE -> 0xFFC62828.toInt()
    PlaceCategory.BEACH -> 0xFF0288D1.toInt()
    PlaceCategory.VIEWPOINT -> 0xFFD4AF37.toInt()
    PlaceCategory.LODGING -> 0xFF5D4037.toInt()
    PlaceCategory.SERVICES -> 0xFF546E7A.toInt()
    PlaceCategory.ALL -> 0xFF002F56.toInt()
}

/**
 * Mapa OpenStreetMap (osmdroid) integrado en Compose.
 *
 * @param pins marcadores; @param routePath polilínea de la ruta por carretera (lat,lng);
 * @param corridor polilínea secundaria (corredor del radar); @param focus centro/zoom a aplicar una vez.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    pins: List<MapPin> = emptyList(),
    routePath: List<Pair<Double, Double>> = emptyList(),
    corridor: List<Pair<Double, Double>> = emptyList(),
    /** Círculo de búsqueda cuando el viaje no tiene origen: "estamos mirando aquí". */
    searchArea: SearchArea? = null,
    focus: Pair<Double, Double>? = null,
    focusZoom: Double = 13.0,
    /** Cambiar este número fuerza a encuadrar la ruta otra vez (botón "Ver toda mi ruta"). */
    fitSignal: Int = 0,
    /** Mapa base: calles, satélite, topográfico o caminos. */
    baseLayer: MapBaseLayer = MapBaseLayer.CALLES,
    fitToPins: Boolean = true,
    interactive: Boolean = true,
    showMyLocation: Boolean = false,
    /** true = el mapa sigue al GPS como en Google Maps. Se apaga solo si el viajero mueve el mapa. */
    followMyLocation: Boolean = false,
    onFollowChanged: (Boolean) -> Unit = {},
    onMyLocationChanged: (Double, Double) -> Unit = { _, _ -> },
    onPinClick: (MapPin) -> Unit = {},
    onMapLongClick: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Memoria de la cámara: sirve para NO volver a centrar el mapa en cada recomposición.
    // Este era el motivo de que el mapa se "regresara" solo mientras uno lo movía.
    val camara = remember { EstadoDeCamara() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 5.5
            maxZoomLevel = 19.0
            // Limitar el scroll al Ecuador ampliado (continente + Galápagos)
            setScrollableAreaLimitDouble(BoundingBox(3.5, -73.0, -7.0, -94.0))
            controller.setZoom(6.8)
            controller.setCenter(GeoPoint(-1.5, -80.5))
            if (interactive) {
                overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true); setScaleBarOffset(40, 40) })
            }
        }
    }

    // Ciclo de vida: pausa/reanuda el mapa con la pantalla
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    /**
     * Mi ubicación, al estilo Google: punto azul con su círculo de precisión que se queda en
     * el mapa y se mueve solo cuando se mueve el GPS.
     *
     * Antes esto se creaba UNA vez y solo si ya había permiso, así que quien aceptaba el
     * permiso después no volvía a ver el punto hasta reiniciar la pantalla. Ahora el overlay
     * existe siempre y lo que se enciende y apaga es el seguimiento.
     */
    val myLocationOverlay = remember {
        val proveedor = GpsMyLocationProvider(context).apply {
            locationUpdateMinTime = 1200L      // una lectura cada segundo y pico
            locationUpdateMinDistance = 2f     // o cada 2 metros
        }
        MyLocationNewOverlay(proveedor, mapView).apply {
            isDrawAccuracyEnabled = true
            val punto = puntoDeUbicacion(context, false)
            val flecha = puntoDeUbicacion(context, true)
            setDirectionIcon(flecha)
            setPersonIcon(punto)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
        }
    }

    // El GPS se apaga al salir de la pantalla y se vuelve a encender al volver: ni gasta
    // batería de más ni deja escuchando la ubicación cuando la app está en segundo plano.
    DisposableEffect(myLocationOverlay, showMyLocation) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_RESUME -> if (showMyLocation) runCatching { myLocationOverlay.enableMyLocation() }
                Lifecycle.Event.ON_PAUSE -> runCatching { myLocationOverlay.disableMyLocation() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observador)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observador)
            runCatching { myLocationOverlay.disableFollowLocation() }
            runCatching { myLocationOverlay.disableMyLocation() }
        }
    }

    // Encender / apagar la lectura del GPS según el permiso.
    LaunchedEffect(showMyLocation) {
        if (showMyLocation) {
            runCatching { myLocationOverlay.enableMyLocation() }
            myLocationOverlay.runOnFirstFix {
                myLocationOverlay.myLocation?.let { p -> onMyLocationChanged(p.latitude, p.longitude) }
            }
        } else {
            runCatching { myLocationOverlay.disableMyLocation() }
        }
        mapView.invalidate()
    }

    // Seguir al GPS (y dejar de seguirlo) sin recrear nada.
    LaunchedEffect(followMyLocation, showMyLocation) {
        if (showMyLocation && followMyLocation) {
            runCatching { myLocationOverlay.enableFollowLocation() }
            camara.siguiendo = true
            myLocationOverlay.myLocation?.let { p ->
                mapView.controller.animateTo(GeoPoint(p.latitude, p.longitude), maxOf(mapView.zoomLevelDouble, 16.0), 600L)
            }
        } else {
            runCatching { myLocationOverlay.disableFollowLocation() }
            camara.siguiendo = false
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            if (interactive) {
                val receiver = object : org.osmdroid.events.MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p != null) onMapLongClick(p.latitude, p.longitude)
                        return true
                    }
                }
                mapView.overlays.add(0, org.osmdroid.views.overlay.MapEventsOverlay(receiver))
            }
            mapView.overlays.add(myLocationOverlay)
            if (!interactive) {
                mapView.setOnTouchListener { _, _ -> true } // mini-mapa estático
            } else {
                // Igual que en Google Maps: en cuanto el viajero arrastra el mapa, este deja
                // de perseguirlo y deja de reencuadrarse solo. Recupera el control él.
                mapView.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                        camara.movidoPorElViajero = true
                        if (camara.siguiendo) {
                            camara.siguiendo = false
                            runCatching { myLocationOverlay.disableFollowLocation() }
                            onFollowChanged(false)
                        }
                    }
                    false   // el mapa sigue recibiendo el gesto con normalidad
                }
            }
            mapView
        },
        update = { map ->
            // Mapa base: solo se cambia cuando de verdad cambió, para no tirar la caché de
            // teselas ni parpadear en cada recomposición.
            val fuente = fuenteDe(baseLayer)
            if (map.tileProvider.tileSource?.name() != fuente.name()) {
                map.setTileSource(fuente)
                // El satélite se ve mejor sin el escalado por densidad; el resto sí lo quiere.
                map.isTilesScaledToDpi = baseLayer != MapBaseLayer.SATELITE
            }

            // Limpiar capas dinámicas (marcadores y polilíneas) conservando las fijas
            map.overlays.removeAll { it is Marker || it is Polyline || it is Polygon }

            // Zona de búsqueda: un círculo claro debajo de todo. Sin esto, el viajero que no
            // escribió salida veía lugares "sueltos" sin saber que la app estaba mirando en un
            // radio concreto alrededor del destino.
            if (searchArea != null) {
                val lat = searchArea.latitude
                val lng = searchArea.longitude
                val dLat = searchArea.radiusKm / 111.32
                val dLng = searchArea.radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.15))
                val pts = (0..72).map { i ->
                    val ang = Math.toRadians(i * 5.0)
                    GeoPoint(lat + dLat * sin(ang), lng + dLng * cos(ang))
                }
                map.overlays.add(Polygon(map).apply {
                    setPoints(pts)
                    fillPaint.color = 0x1AF0B400
                    outlinePaint.color = 0x99F0B400.toInt()
                    outlinePaint.strokeWidth = 5f
                })
            }

            if (corridor.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(corridor.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = 0x66D4AF37
                    outlinePaint.strokeWidth = 18f
                })
            }
            if (routePath.size >= 2) {
                // Primero una línea blanca más gruesa debajo: así la ruta se lee sobre satélite,
                // sobre topográfico y sobre el mapa de calles sin perder el azul de la marca.
                map.overlays.add(Polyline(map).apply {
                    setPoints(routePath.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = 0xFFF8F9FA.toInt()
                    outlinePaint.strokeWidth = 16f
                })
                map.overlays.add(Polyline(map).apply {
                    setPoints(routePath.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = 0xFF002F56.toInt()
                    outlinePaint.strokeWidth = 8f
                })
            }

            pins.forEach { pin ->
                val marker = Marker(map).apply {
                    position = GeoPoint(pin.lat, pin.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = pin.title
                    snippet = pin.subtitle
                    icon = markerIcon(map, pin)
                    setOnMarkerClickListener { m, _ ->
                        onPinClick(pin)
                        m.showInfoWindow()
                        true
                    }
                    if (!interactive) setInfoWindow(null)
                }
                map.overlays.add(marker)
            }

            // ---- Cámara ----
            // Regla: el mapa solo se mueve solo cuando hay una razón NUEVA (el viajero tocó
            // "ver en el mapa", buscó una dirección, cambió el conjunto de marcadores...).
            // Mientras esté siguiendo al GPS o el viajero haya movido el mapa a mano, no se
            // le toca la vista. Antes se reencuadraba en CADA recomposición y por eso el
            // mapa "se regresaba" solo.
            val firma = pins.joinToString(",") { it.id } + "|" + routePath.size + "|" + corridor.size + "|" + (searchArea != null)
            when {
                // El viajero pidió explícitamente "ver toda mi ruta": se encuadra aunque haya
                // movido el mapa a mano. Es el botón que faltaba cuando uno se perdía.
                fitSignal != camara.ultimaSenal && (pins.isNotEmpty() || routePath.isNotEmpty()) -> {
                    camara.ultimaSenal = fitSignal
                    camara.movidoPorElViajero = false
                    val points = pins.map { GeoPoint(it.lat, it.lng) } + routePath.map { GeoPoint(it.first, it.second) }
                    if (points.size == 1) {
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(points[0])
                    } else if (points.size > 1) {
                        val bb = BoundingBox.fromGeoPointsSafe(points)
                        map.post { runCatching { map.zoomToBoundingBox(bb.increaseByScale(1.25f), true, 60) } }
                    }
                }
                focus != null && focus != camara.ultimoFoco -> {
                    camara.ultimoFoco = focus
                    camara.movidoPorElViajero = false
                    map.controller.animateTo(GeoPoint(focus.first, focus.second), focusZoom, 600L)
                }
                camara.siguiendo -> {
                    // El overlay ya centra el mapa en cada lectura del GPS.
                }
                fitToPins && pins.isNotEmpty() && firma != camara.ultimaFirma && !camara.movidoPorElViajero -> {
                    val points = pins.map { GeoPoint(it.lat, it.lng) } + routePath.map { GeoPoint(it.first, it.second) }
                    if (points.size == 1) {
                        map.controller.setZoom(13.0)
                        map.controller.setCenter(points[0])
                    } else {
                        val bb = BoundingBox.fromGeoPointsSafe(points)
                        map.post { runCatching { map.zoomToBoundingBox(bb.increaseByScale(1.25f), true, 60) } }
                    }
                }
            }
            camara.ultimaFirma = firma
            if (focus == null) camara.ultimoFoco = null
            map.invalidate()
        }
    )
}

/** Lo que el mapa recuerda entre recomposiciones para no moverse solo. */
private class EstadoDeCamara {
    var ultimoFoco: Pair<Double, Double>? = null
    var ultimaFirma: String = ""
    var ultimaSenal: Int = 0
    var movidoPorElViajero: Boolean = false
    var siguiendo: Boolean = false
}

/**
 * El punto azul de "estoy aquí", dibujado a mano para no depender de imágenes.
 * @param conRumbo true dibuja además la flechita que apunta a donde mira el teléfono.
 */
private fun puntoDeUbicacion(context: android.content.Context, conRumbo: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val lado = (d * 30).toInt().coerceAtLeast(24)
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val lienzo = Canvas(bmp)
    val centro = lado / 2f
    val pincel = Paint(Paint.ANTI_ALIAS_FLAG)

    // Halo suave
    pincel.color = 0x332F80ED
    lienzo.drawCircle(centro, centro, d * 12, pincel)
    // Aro blanco
    pincel.color = AColor.WHITE
    lienzo.drawCircle(centro, centro, d * 7.5f, pincel)
    // Punto azul
    pincel.color = 0xFF1A73E8.toInt()
    lienzo.drawCircle(centro, centro, d * 5.5f, pincel)

    if (conRumbo) {
        // Flecha hacia arriba: osmdroid la rota según el rumbo del teléfono.
        val ruta = android.graphics.Path().apply {
            moveTo(centro, d * 1.5f)
            lineTo(centro - d * 4f, d * 8f)
            lineTo(centro + d * 4f, d * 8f)
            close()
        }
        pincel.color = 0xFF1A73E8.toInt()
        lienzo.drawPath(ruta, pincel)
    }
    return bmp
}

private fun markerIcon(map: MapView, pin: MapPin): Drawable? {
    // Las paradas de la ruta se dibujan numeradas y con el color de "ruta", no con el de la
    // categoría: lo importante en el mapa es el ORDEN del viaje. La siguiente parada va en
    // rojo y un poco más grande para que se vea de un vistazo.
    if (pin.isRouteStop) return stopMarkerIcon(map, pin)

    val base = ContextCompat.getDrawable(map.context, ec.travesias.app.R.drawable.ic_map_pin) ?: return null
    val d = DrawableCompat.wrap(base.mutate())
    val color = categoryArgb(pin.category)
    DrawableCompat.setTint(d, if (pin.highlighted) 0xFFC62828.toInt() else color)
    val scale = if (pin.highlighted) 1.25f else 1f
    val size = (map.context.resources.displayMetrics.density * 36 * scale).toInt()
    d.setBounds(0, 0, size, size)
    return d
}

/** Círculo azul (o rojo si es la siguiente parada) con el número del recorrido dentro. */
private fun stopMarkerIcon(map: MapView, pin: MapPin): Drawable {
    val density = map.context.resources.displayMetrics.density
    val size = (density * if (pin.highlighted) 48 else 40).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val centro = size / 2f
    val pincel = Paint(Paint.ANTI_ALIAS_FLAG)

    // Halo suave cuando es la siguiente: llama la atención sin tapar el resto.
    if (pin.highlighted) {
        pincel.color = 0x55FFC501
        canvas.drawCircle(centro, centro, centro, pincel)
    }
    // Aro blanco
    pincel.color = AColor.WHITE
    canvas.drawCircle(centro, centro, centro - density * 1.5f, pincel)
    // Relleno
    pincel.color = if (pin.highlighted) 0xFFC62828.toInt() else 0xFF002F56.toInt()
    canvas.drawCircle(centro, centro, centro - density * 3.5f, pincel)

    // Número del recorrido
    pincel.color = AColor.WHITE
    pincel.textAlign = Paint.Align.CENTER
    pincel.typeface = Typeface.DEFAULT_BOLD
    pincel.textSize = density * if (pin.stopLabel.length > 2) 11f else 15f
    val fm = pincel.fontMetrics
    val baseline = centro - (fm.ascent + fm.descent) / 2f
    canvas.drawText(pin.stopLabel.ifBlank { "•" }, centro, baseline, pincel)

    return BitmapDrawable(map.context.resources, bmp)
}

/** Utilidad para exponer el color de categoría a Compose. */
fun Int.toComposeColor(): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(this)

@Suppress("unused")
private fun argb(a: Int, r: Int, g: Int, b: Int) = AColor.argb(a, r, g, b)

package ec.travesias.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.journeyStops
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.ui.components.AddPlacePage
import ec.travesias.app.ui.components.PlaceDetailSheet
import ec.travesias.app.ui.components.PlaceRouteHoldMenu
import ec.travesias.app.ui.components.categoryColor
import ec.travesias.app.ui.components.categoryIcon
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.ui.map.MapBaseLayer
import ec.travesias.app.ui.map.MapPin
import ec.travesias.app.ui.map.OsmMap
import ec.travesias.app.ui.map.toPin
import ec.travesias.app.ui.theme.*
import ec.travesias.app.ui.util.Intents
import ec.travesias.app.viewmodel.RouteViewModel

/**
 * Mapa: buscador de direcciones, lugares del catálogo, ruta activa, corredor del radar,
 * ubicación real del teléfono y dirección de cualquier punto que mantengas pulsado.
 */
@Composable
fun MapScreen(
    viewModel: RouteViewModel,
    onOpenDiscover: () -> Unit,
    onOpenRoute: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val allPlaces by viewModel.filteredPlaces.collectAsState()
    val route by viewModel.activeRoute.collectAsState()
    val routePlaces by viewModel.activeRoutePlaces.collectAsState()
    val road by viewModel.roadRoute.collectAsState()
    val corridor by viewModel.radarCorridor.collectAsState()
    val radarResults by viewModel.radarResults.collectAsState()
    val selected by viewModel.selectedPlace.collectAsState()
    val focus by viewModel.mapFocus.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val searchArea by viewModel.searchArea.collectAsState()
    val schedule by viewModel.schedule.collectAsState()

    // Mapa base elegido (calles / satélite / topográfico / caminos). Se recuerda.
    val claveMapa by AppSettings.mapLayer.collectAsState()
    val mapaBase = MapBaseLayer.from(claveMapa)

    val showL1 by viewModel.showLayer1.collectAsState()
    val showL2 by viewModel.showLayer2.collectAsState()
    val showL3 by viewModel.showLayer3.collectAsState()
    val l1Places by viewModel.layer1Places.collectAsState()
    val l2Places by viewModel.layer2Places.collectAsState()

    val query by viewModel.addressQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val pin by viewModel.droppedPin.collectAsState()
    val showAddPlace by viewModel.showAddPlace.collectAsState()
    val draft by viewModel.routeDraft.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    var holdPlace by remember { mutableStateOf<Place?>(null) }

    var showOnlyRoute by remember { mutableStateOf(false) }
    var layerMenu by remember { mutableStateOf(false) }
    var legend by remember { mutableStateOf(false) }
    // Botón "ver toda mi ruta": un contador que el mapa escucha para volver a encuadrar.
    var fitSignal by remember { mutableIntStateOf(0) }

    var hasLocation by remember { mutableStateOf(DeviceLocation.hasPermission(context)) }
    // Seguir al GPS como en Google Maps: se enciende con el botón y se apaga solo en cuanto
    // el viajero mueve el mapa con el dedo.
    var siguiendoGps by remember { mutableStateOf(false) }
    var miUbicacion by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result.values.any { it }
        if (hasLocation) siguiendoGps = true
    }

    val stops = route?.stops ?: emptyList()
    val stopIds = stops.map { it.placeId }.toSet()
    // Para el viajero, la salida y la llegada no son "paradas": se excluyen del progreso, de
    // la numeración de los pines y de la siguiente parada.
    val paradas = remember(route) { route?.journeyStops() ?: emptyList() }
    val orderedStops = paradas
    val nextStop = orderedStops.firstOrNull { !it.isDone }
    val hechas = orderedStops.count { it.isDone }
    // El día del viaje en el que cae la siguiente parada: sirve para el horario estimado.
    val nextPlan = schedule.firstOrNull { it.day == nextStop?.dayNumber }
    val nextScheduled = nextPlan?.stops?.firstOrNull { it.stop.id == nextStop?.id }

    val pins: List<MapPin> = remember(allPlaces, stops, routePlaces, radarResults, showOnlyRoute, selected, pin, showL1, showL2, showL3, l1Places, l2Places, corridor, nextStop?.id, searchArea) {
        val stopPins = orderedStops.mapIndexed { i, s ->
            s.toPin(i, routePlaces[s.placeId]?.category ?: PlaceCategory.NATURE)
                .copy(highlighted = s.id == nextStop?.id)
        }
        val others = if (showOnlyRoute) emptyList()
        else {
            val baseList = mutableListOf<Place>()
            if (showL1) baseList.addAll(l1Places)
            if (showL2) baseList.addAll(l2Places)
            if (showL3) baseList.addAll(radarResults)
            // Si no hay ruta dibujada NI zona de búsqueda, mostramos el catálogo entero.
            // Con zona de búsqueda (modo "solo destino") solo se ven los resultados de esa
            // zona: si no, el mapa se llenaba de lugares de todo el país.
            if (corridor.isEmpty() && searchArea == null) {
                baseList.addAll(allPlaces)
            }
            baseList.distinctBy { it.id }
                .filter { it.id !in stopIds }
                .map { it.toPin(highlighted = it.id == selected?.id) }
        }
        val pinned = pin?.let {
            listOf(
                MapPin(
                    id = "dropped-pin", lat = it.latitude, lng = it.longitude,
                    title = it.title, subtitle = it.subtitle,
                    category = PlaceCategory.ALL, highlighted = true
                )
            )
        } ?: emptyList()
        stopPins + others + pinned
    }
    val routePath = remember(road) { road?.legs?.flatMap { it.geometry } ?: emptyList() }

    LaunchedEffect(focus) {
        if (focus != null) {
            kotlinx.coroutines.delay(900)
            viewModel.consumeMapFocus()
        }
    }
    // Si todavía no hay ruta pero sí una zona de búsqueda (modo "solo destino"), el mapa
    // aterriza ahí en vez de quedarse viendo todo el país.
    LaunchedEffect(searchArea?.name, stops.size) {
        val area = searchArea
        if (area != null && stops.isEmpty() && focus == null) {
            viewModel.focusOnMap(area.latitude, area.longitude)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            pins = pins,
            routePath = routePath,
            corridor = corridor,
            searchArea = searchArea,
            focus = focus,
            fitSignal = fitSignal,
            baseLayer = mapaBase,
            fitToPins = focus == null && pin == null && (stops.isNotEmpty() || radarResults.isNotEmpty()),
            showMyLocation = hasLocation,
            followMyLocation = siguiendoGps,
            onFollowChanged = { siguiendoGps = it },
            onMyLocationChanged = { lat, lng -> miUbicacion = lat to lng },
            onPinClick = { p -> if (p.id != "dropped-pin") viewModel.selectPlaceById(p.id) },
            onMapLongClick = { lat, lng -> viewModel.describePoint(lat, lng) }
        )

        // -------------------------------------------------- Buscador + filtros
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp)) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = query,
                        onValueChange = { viewModel.onAddressQueryChanged(it) },
                        placeholder = {
                            Text(
                                if (searchArea != null) "Buscar en ${searchArea?.name}…"
                                else "Buscar dirección, ciudad o lugar",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    if (searching) {
                        CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (query.isNotBlank()) {
                        IconButton(onClick = { viewModel.clearAddressSearch(); focusManager.clearFocus() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = VolcanicGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (results.isNotEmpty()) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
                ) {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(results, key = { it.id }) { r ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { viewModel.openSearchResult(r); focusManager.clearFocus() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (r.placeId != null) Icons.Default.Place else Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (r.placeId != null) EquatorialGold else AndeanBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    if (r.subtitle.isNotBlank()) {
                                        Text(r.subtitle, color = VolcanicGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    items(PlaceCategory.entries.toList()) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { viewModel.onCategorySelected(cat) },
                            label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(categoryIcon(cat), contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SurfaceContainerLowest.copy(alpha = 0.94f),
                                selectedContainerColor = if (cat == PlaceCategory.ALL) BrandFill else categoryColor(cat),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        // -------------------------------------------------- Punto marcado
        pin?.let { p ->
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = StatusClosed)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (p.subtitle.isNotBlank()) {
                                Text(p.subtitle, color = VolcanicGray, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                        IconButton(onClick = { viewModel.clearDroppedPin() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = VolcanicGray)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { Intents.navigateTo(context, p.latitude, p.longitude, p.title) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cómo llegar", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                Intents.shareText(
                                    context,
                                    p.title + "\n" + p.subtitle + "\nhttps://www.openstreetmap.org/?mlat=" + p.latitude + "&mlon=" + p.longitude,
                                    "Compartir ubicación"
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Compartir", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.openAddPlace(p.latitude, p.longitude) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = EquatorialGold)
                        Spacer(Modifier.width(6.dp))
                        Text("Agregar como lugar nuevo", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // -------------------------------------------------- Resumen de la ruta
        // La tarjeta de abajo es el "copiloto": dice dónde estás en el viaje y cuál es la
        // siguiente parada, con un botón para navegar. Antes solo mostraba kilómetros.
        if (pin == null) {
            val r = route
            if (r != null) {
                Card(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest.copy(alpha = 0.97f)),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    "${hechas} de ${paradas.size} paradas hechas · ${r.totalDays} día(s)",
                                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                )
                            }
                            TextButton(onClick = onOpenRoute) {
                                Text("Mi ruta", style = MaterialTheme.typography.labelMedium, color = AndeanBlue)
                            }
                        }
                        if (paradas.isNotEmpty()) {
                            LinearProgressIndicator(
                                progress = { if (paradas.isEmpty()) 0f else hechas.toFloat() / paradas.size },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                color = AndesGreen, trackColor = HairLine
                            )
                        }

                        val sig = nextStop
                        if (sig != null) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EquatorialGold.copy(alpha = 0.16f)).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(BrandFill),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${orderedStops.indexOfFirst { it.id == sig.id } + 1}",
                                        color = BrandYellow, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Siguiente parada", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                                    Text(sig.placeName, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(
                                        buildString {
                                            append("Día ${sig.dayNumber} · parada ${sig.orderInDay + 1}")
                                            val s = nextScheduled
                                            if (s != null && s.travelMinutes > 0) {
                                                append(" · ${s.travelLabel} · ${s.distanceKm.toInt()} km")
                                                append(" · llegada ~${s.arrival}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { Intents.navigateTo(context, sig.latitude, sig.longitude, sig.placeName) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandFill, contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ir", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else if (paradas.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AndesGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("¡Terminaste todas las paradas de esta ruta!", style = MaterialTheme.typography.labelMedium, color = AndesGreen)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val km = road?.totalDistanceKm
                            val min = road?.totalDurationMinutes
                            Icon(Icons.Default.Straighten, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(14.dp))
                            Text(
                                buildString {
                                    append("${paradas.size} parada(s) · ${r.totalDays} día(s)")
                                    if (km != null && km > 0) append(" · ${km.toInt()} km")
                                    if (min != null && min > 0) append(" · ${min / 60}h ${min % 60}m")
                                    if (road?.isEstimate == true) append(" (aprox.)")
                                },
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.requestPlanTab(); onOpenDiscover() }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = GalapagosTeal)
                                Spacer(Modifier.width(3.dp))
                                Text("Planear", style = MaterialTheme.typography.labelSmall, color = GalapagosTeal)
                            }
                        }
                    }
                }
            } else if (searchArea != null) {
                val area = searchArea
                Card(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest.copy(alpha = 0.97f)),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(EquatorialGold.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.LocationSearching, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Buscando alrededor de ${area?.name}", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    "Radio de ${area?.radiusLabel} · ${radarResults.size} lugar(es)",
                                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.requestPlanTab(); onOpenDiscover() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BrandYellow, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Armar una ruta con estos lugares", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest.copy(alpha = 0.97f)),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Tu viaje empieza con un destino", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Text("Dinos a dónde vas y la app arma el mapa y el itinerario.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        }
                        FilledTonalButton(
                            onClick = { viewModel.requestPlanTab(); onOpenDiscover() },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandFill, contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Empezar", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // -------------------------------------------------- Botones flotantes
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .padding(bottom = if (pin != null) 120.dp else if (route != null || searchArea != null) 170.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Encuadrar toda la ruta otra vez. Es el gesto que faltaba cuando el viajero movía
            // el mapa y ya no encontraba sus paradas.
            if (route != null && paradas.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { fitSignal++ },
                    containerColor = SurfaceContainerLowest
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = "Ver toda mi ruta", tint = AndeanBlue)
                }
            }
            SmallFloatingActionButton(
                onClick = {
                    when {
                        !hasLocation -> permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                        // Ya sabemos dónde está: seguirlo (el mapa se mueve con él).
                        miUbicacion != null -> siguiendoGps = !siguiendoGps
                        // Aún no hay señal: se pide una lectura y se avisa si el GPS está apagado.
                        else -> { siguiendoGps = true; viewModel.goToMyLocation() }
                    }
                },
                containerColor = if (siguiendoGps) BrandFill else SurfaceContainerLowest
            ) {
                Icon(
                    if (siguiendoGps) Icons.Default.MyLocation else Icons.Default.LocationSearching,
                    contentDescription = if (siguiendoGps) "Dejar de seguir mi ubicación" else "Mi ubicación",
                    tint = if (siguiendoGps) EquatorialGold else AndeanBlue
                )
            }

            SmallFloatingActionButton(onClick = { viewModel.openAddPlace() }, containerColor = EquatorialGold) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = "Agregar lugar", tint = AndeanBlue)
            }

            SmallFloatingActionButton(onClick = { layerMenu = true }, containerColor = SurfaceContainerLowest) {
                Icon(Icons.Default.Layers, contentDescription = "Capas", tint = AndeanBlue)
            }
            DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                Text(
                    "Mapa base",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                )
                MapBaseLayer.entries.forEach { capa ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(capa.etiqueta, fontWeight = if (capa == mapaBase) FontWeight.Bold else FontWeight.Normal)
                                Text(capa.explicacion, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                            }
                        },
                        leadingIcon = {
                            Icon(
                                when (capa) {
                                    MapBaseLayer.CALLES -> Icons.Default.Map
                                    MapBaseLayer.SATELITE -> Icons.Default.Satellite
                                    MapBaseLayer.TOPO -> Icons.Default.Terrain
                                    MapBaseLayer.CAMINOS -> Icons.Default.Hiking
                                },
                                contentDescription = null,
                                tint = if (capa == mapaBase) AndeanBlue else VolcanicGray
                            )
                        },
                        trailingIcon = {
                            if (capa == mapaBase) Icon(Icons.Default.Check, contentDescription = null, tint = AndeanBlue)
                        },
                        onClick = { AppSettings.setMapLayer(capa.clave); layerMenu = false }
                    )
                }
                HorizontalDivider()
                Text(
                    "Qué se ve encima",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                )
                DropdownMenuItem(
                    text = { Text(if (showL1) "Ocultar mis lugares guardados" else "Mostrar mis lugares guardados") },
                    leadingIcon = { Icon(if (showL1) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) },
                    onClick = { viewModel.toggleLayer1() }
                )
                DropdownMenuItem(
                    text = { Text(if (showL2) "Ocultar catálogo cercano a la vía" else "Mostrar catálogo cercano a la vía") },
                    leadingIcon = { Icon(if (showL2) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) },
                    onClick = { viewModel.toggleLayer2() }
                )
                DropdownMenuItem(
                    text = { Text(if (showL3) "Ocultar lo que encontré en la ruta" else "Mostrar lo que encontré en la ruta") },
                    leadingIcon = { Icon(if (showL3) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) },
                    onClick = { viewModel.toggleLayer3() }
                )
                DropdownMenuItem(
                    text = { Text(if (showOnlyRoute) "Mostrar todos los lugares" else "Solo mi ruta") },
                    leadingIcon = { Icon(if (showOnlyRoute) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) },
                    onClick = { showOnlyRoute = !showOnlyRoute; layerMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Ver toda mi ruta", fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null, tint = AndeanBlue) },
                    onClick = { fitSignal++; layerMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("¿Qué significa cada pin?") },
                    leadingIcon = { Icon(Icons.Default.HelpOutline, contentDescription = null, tint = AndeanBlue) },
                    onClick = { legend = true; layerMenu = false }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Limpiar lo encontrado en la ruta") },
                    leadingIcon = { Icon(Icons.Default.LayersClear, contentDescription = null) },
                    onClick = { viewModel.clearRadar(); layerMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Descargar mapa de mi ruta (offline)") },
                    leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    onClick = { viewModel.downloadRouteMap(); layerMenu = false }
                )
            }
        }

        // -------------------------------------------------- Ayuda
        if (pin == null && results.isEmpty()) {
            Surface(
                color = Color.White.copy(alpha = 0.85f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 116.dp, end = 12.dp)
            ) {
                Text(
                    "Mantén pulsado el mapa para ver la dirección",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (showAddPlace) {
            AddPlacePage(viewModel)
        }
    }

    if (legend) {
        AlertDialog(
            onDismissRequest = { legend = false },
            icon = { Icon(Icons.Default.Map, contentDescription = null, tint = AndeanBlue) },
            title = { Text("Qué significa cada cosa en el mapa", color = AndeanBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeyendaFila(AndeanBlue, "Pin numerado", "Tus paradas, en el orden real del viaje. El rojo es la siguiente.")
                    LeyendaFila(StatusClosed, "Pin rojo", "La parada que toca ahora. Toca la tarjeta de abajo para navegar.")
                    LeyendaFila(EquatorialGold, "Círculo dorado", "Zona donde la app está buscando (cuando no escribiste de dónde sales).")
                    LeyendaFila(SkyAccent, "Línea azul", "Tu ruta por carretera. La línea blanca de debajo la hace visible en cualquier mapa.")
                    LeyendaFila(AndesGreen, "Punto azul", "Tu ubicación real. Toca el botón de GPS para seguirla.")
                    LeyendaFila(VolcanicGray, "Pines de color", "Lugares del catálogo por categoría: comida, naturaleza, playa, cultura…")
                    Text(
                        "Mantén pulsado el mapa para ver la dirección de cualquier punto.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { legend = false }) { Text("Entendido", color = AndeanBlue) }
            }
        )
    }

    selected?.let { place ->
        val comments by viewModel.placeComments.collectAsState()
        PlaceDetailSheet(
            place = place,
            inRoute = place.id in stopIds,
            totalDays = route?.totalDays ?: 1,
            onDismiss = { viewModel.selectPlace(null) },
            onAddToRoute = { day ->
                val extras = routes.filter { it.id != route?.id }
                if (draft != null || extras.isNotEmpty()) {
                    holdPlace = place
                    viewModel.selectPlace(null)
                } else {
                    viewModel.addToRoute(place, day)
                    viewModel.selectPlace(null)
                }
            },
            onToggleSave = { viewModel.toggleSaved(place) },
            onShowOnMap = { viewModel.selectPlace(null); viewModel.focusOnMap(place.latitude, place.longitude) },
            comments = comments,
            onOpenComments = { id -> viewModel.openPlaceComments(id) },
            onCloseComments = { viewModel.closePlaceComments() },
            onAddComment = { text, category, photoUri -> viewModel.addPlaceComment(place.id, text, category, photoUri) }
        )
    }

    holdPlace?.let { p ->
        PlaceRouteHoldMenu(
            place = p,
            activeRoute = route,
            otherRoutes = routes.filter { it.id != route?.id },
            draft = draft,
            inActiveRoute = p.id in stopIds,
            onDismiss = { holdPlace = null },
            onAddToActive = { viewModel.addToRoute(p, 1) },
            onAddToDraft = { viewModel.addPlaceToDraft(p) },
            onAddToRoute = { id, _ -> viewModel.addToRoute(p, 1, id) }
        )
    }
}

@Suppress("unused")
private fun Place.debugLabel() = "$name @ $latitude,$longitude"

/** Una fila de la leyenda del mapa: punto de color + qué es. */
@Composable
private fun LeyendaFila(color: Color, titulo: String, detalle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color)
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Text(detalle, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
        }
    }
}

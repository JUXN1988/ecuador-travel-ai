package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.RadarRequest
import ec.travesias.app.data.model.RoutePlanUiState
import ec.travesias.app.data.model.SearchArea
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TripIntent
import ec.travesias.app.ui.components.AddPlacePage
import ec.travesias.app.ui.components.PlaceDetailSheet
import ec.travesias.app.ui.components.LugarAutocompleteField
import ec.travesias.app.ui.components.PlaceSummaryRow
import ec.travesias.app.ui.components.PlaceRouteHoldMenu
import ec.travesias.app.ui.components.RouteDraftSheet
import ec.travesias.app.ui.components.categoryColor
import ec.travesias.app.ui.components.categoryIcon
import ec.travesias.app.ui.components.platformIcon
import ec.travesias.app.ui.theme.*
import ec.travesias.app.ui.util.Intents
import androidx.compose.foundation.clickable
import ec.travesias.app.viewmodel.RadarUiState
import ec.travesias.app.viewmodel.RouteViewModel
import ec.travesias.app.viewmodel.ScoutUiState

/**
 * Descubrir = Radar IA (paradas a lo largo de tu ruta) + catálogo offline de lugares.
 */
@Composable
fun DiscoverScreen(
    viewModel: RouteViewModel,
    onOpenMap: () -> Unit,
    onOpenCommunityShare: ((placeId: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val places by viewModel.filteredPlaces.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()
    val region by viewModel.selectedRegion.collectAsState()
    val route by viewModel.activeRoute.collectAsState()
    val radarState by viewModel.radarState.collectAsState()
    val aiDiagnosis by viewModel.aiDiagnosis.collectAsState()
    val radarResults by viewModel.radarResults.collectAsState()
    val radarDetours by viewModel.radarDetours.collectAsState()
    val selected by viewModel.selectedPlace.collectAsState()
    val draft by viewModel.routeDraft.collectAsState()
    val planState by viewModel.planState.collectAsState()
    val scoutState by viewModel.scoutState.collectAsState()
    val catalogBusy by viewModel.catalogBusy.collectAsState()
    val catalogPending by viewModel.catalogPending.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshCatalogPending() }

    var tab by remember { mutableIntStateOf(0) }
    val stopIds = route?.stops?.map { it.placeId }?.toSet() ?: emptySet()
    val showAddPlace by viewModel.showAddPlace.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val searchArea by viewModel.searchArea.collectAsState()
    val tripIntent by viewModel.tripIntent.collectAsState()
    // Con una zona de búsqueda activa (modo "solo destino"), el catálogo también se limita a
    // esa zona: si no, el viajero veía lugares de todo el país y creía que eran de su viaje.
    val lugaresVisibles = remember(places, searchArea, query) {
        val area = searchArea
        // Si el viajero escribe una búsqueda concreta, se busca en todo el catálogo; si solo
        // navega, se limita a la zona del destino.
        if (area == null || query.isNotBlank()) places
        else places.filter {
            ec.travesias.app.data.remote.RoutingService.haversineKm(
                area.latitude, area.longitude, it.latitude, it.longitude
            ) <= area.radiusKm
        }
    }
    val openPlanTab by viewModel.openPlanTab.collectAsState()
    // Quien pide "armar con IA" desde otra pantalla aterriza directamente en el formulario.
    LaunchedEffect(openPlanTab) {
        if (openPlanTab) {
            tab = 1
            viewModel.consumePlanTab()
        }
    }
    // Cuando la portada ya lanzó el armado (o llegó una búsqueda), esta pantalla abre en la
    // pestaña que corresponde. Antes el viajero tocaba "Armar mi ruta", aterrizaba en el
    // catálogo y creía que no había pasado nada.
    LaunchedEffect(planState, draft, radarState, scoutState) {
        if (planState is RoutePlanUiState.Loading || planState is RoutePlanUiState.Ready || draft != null ||
            radarState is RadarUiState.Loading || radarState is RadarUiState.Success ||
            scoutState is ScoutUiState.Loading || scoutState is ScoutUiState.Success
        ) tab = 1
    }
    var holdPlace by remember { mutableStateOf<ec.travesias.app.data.model.Place?>(null) }
    val otrasRutas = remember(routes, route) { routes.filter { it.id != route?.id } }
    val elegirDestino: (ec.travesias.app.data.model.Place) -> Unit = { p ->
        if (draft != null || otrasRutas.isNotEmpty()) holdPlace = p
        else viewModel.addToRoute(p, 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(SurfaceLight)) {
        ScrollableTabRow(selectedTabIndex = tab, containerColor = SurfaceLight, contentColor = AndeanBlue, edgePadding = 8.dp, divider = {}) {
            // El catálogo va primero: al entrar en Descubrir lo primero que se ve son los
            // lugares, que es lo que la gente viene a buscar. El planificador IA queda al lado.
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Lugares (${lugaresVisibles.size})", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Planear viaje", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Desde un enlace", fontWeight = if (tab == 2) FontWeight.Bold else FontWeight.Normal) })
        }

        aiDiagnosis?.let { texto ->
            AiDiagnosisDialog(texto = texto, onDismiss = { viewModel.clearAiDiagnosis() })
        }

        if (tab == 1) {
            RadarTab(viewModel, radarState, radarResults, stopIds, route?.totalDays ?: 1, onOpenMap, radarDetours, elegirDestino) { holdPlace = it }
        } else if (tab == 2) {
            LinkTab(viewModel, stopIds, elegirDestino) { holdPlace = it }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Spacer(Modifier.height(8.dp))
                    LugarAutocompleteField(
                        value = query,
                        onValueChange = viewModel::onSearchQueryChanged,
                        label = "Buscar",
                        placeholder = "Provincia, cantón, playa, volcán…",
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AndeanBlue) },
                        trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { viewModel.onSearchQueryChanged("") }) { Icon(Icons.Default.Close, contentDescription = null) } },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        onPicked = { s -> viewModel.onSuggestionPicked(s) }
                    )
                }
                item {
                    // Si hay una búsqueda de zona en curso, el catálogo general no debe
                    // disimularla: se avisa y se ofrece el atajo a los resultados del destino.
                    searchArea?.let { area ->
                        Card(colors = CardDefaults.cardColors(containerColor = GalapagosTeal.copy(alpha = 0.12f)), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationSearching, contentDescription = null, tint = GalapagosTeal)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Buscando alrededor de ${area.name}", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                                    Text("Radio de ${area.radiusLabel}. El catálogo y los resultados se limitan a esta zona.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                                }
                                TextButton(onClick = { tab = 1 }) { Text("Ver IA", color = GalapagosTeal) }
                            }
                        }
                    }
                }
                item {
                    // El catálogo es de todos: lo que agrega cualquiera llega a las demás apps.
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Catálogo de la comunidad", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Text(
                                if (catalogPending > 0) "Tienes " + catalogPending + " aporte(s) por compartir"
                                else "Lo que agrega cualquier viajero llega a todos los teléfonos",
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                        if (catalogBusy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = AndeanBlue, modifier = Modifier.size(18.dp))
                        } else {
                            IconButton(onClick = { viewModel.syncCatalog() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Sync, contentDescription = "Actualizar catálogo", tint = AndeanBlue)
                            }
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = region == null, onClick = { viewModel.onRegionSelected(null) }, label = { Text("Todo Ecuador") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AndeanBlue, selectedLabelColor = Color.White)) }
                        items(EcuadorRegion.entries.toList()) { r ->
                            FilterChip(selected = region == r, onClick = { viewModel.onRegionSelected(if (region == r) null else r) }, label = { Text(r.displayName) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AndeanBlue, selectedLabelColor = Color.White))
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PlaceCategory.entries.toList()) { c ->
                            FilterChip(
                                selected = category == c, onClick = { viewModel.onCategorySelected(c) },
                                label = { Text(c.displayName) }, leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(16.dp)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (c == PlaceCategory.ALL) BrandFill else categoryColor(c), selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White)
                            )
                        }
                    }
                }
                if (lugaresVisibles.isEmpty()) {
                    item {
                        Text(
                            if (searchArea != null) "No hay lugares guardados en esta zona. Toca «Planear viaje» para buscarlos con la IA."
                            else "No hay lugares con esos filtros. Usa el Radar IA o comparte un TikTok/Reel a la app para añadir nuevos.",
                            color = VolcanicGray, modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                items(lugaresVisibles, key = { it.id }) { p ->
                    PlaceSummaryRow(
                        place = p, inRoute = p.id in stopIds,
                        onClick = { viewModel.selectPlace(p) },
                        onAddToRoute = { elegirDestino(p) },
                        onToggleSave = { viewModel.toggleSaved(p) },
                        onHold = { holdPlace = p }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    selected?.let { place ->
        val comments by viewModel.placeComments.collectAsState()
        PlaceDetailSheet(
            place = place, inRoute = place.id in stopIds, totalDays = route?.totalDays ?: 1,
            onDismiss = { viewModel.selectPlace(null) },
            onAddToRoute = { d ->
                if (draft != null || otrasRutas.isNotEmpty()) {
                    holdPlace = place
                    viewModel.selectPlace(null)
                } else {
                    viewModel.addToRoute(place, d)
                    viewModel.selectPlace(null)
                }
            },
            onToggleSave = { viewModel.toggleSaved(place) },
            onShowOnMap = { viewModel.selectPlace(null); viewModel.focusOnMap(place.latitude, place.longitude); onOpenMap() },
            onShareToGroup = onOpenCommunityShare?.let { cb -> { viewModel.selectPlace(null); cb(place.id) } },
            comments = comments,
            onOpenComments = { id -> viewModel.openPlaceComments(id) },
            onCloseComments = { viewModel.closePlaceComments() },
            onAddComment = { text, category, photoUri -> viewModel.addPlaceComment(place.id, text, category, photoUri) }
        )
    }

    // El botón de "agregar lugar" vive en el catálogo, que ahora es la primera pestaña.
    if (tab == 0) {
        FloatingActionButton(
            onClick = { viewModel.openAddPlace() },
            containerColor = EquatorialGold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Default.AddLocationAlt, contentDescription = "Agregar lugar", tint = AndeanBlue)
        }
    }

    if (showAddPlace) {
        AddPlacePage(viewModel)
    }

    holdPlace?.let { p ->
        PlaceRouteHoldMenu(
            place = p,
            activeRoute = route,
            otherRoutes = otrasRutas,
            draft = draft,
            inActiveRoute = p.id in stopIds,
            onDismiss = { holdPlace = null },
            onAddToActive = { viewModel.addToRoute(p, 1) },
            onAddToDraft = { viewModel.addPlaceToDraft(p) },
            onAddToRoute = { id, _ -> viewModel.addToRoute(p, 1, id) }
        )
    }
    }

    // El borrador de la IA se abre encima de todo: aquí se edita y recién luego se guarda.
    draft?.let { d ->
        RouteDraftSheet(
            draft = d,
            onDismiss = { viewModel.discardDraft() },
            onRename = { viewModel.setDraftName(it) },
            onDaysChange = { viewModel.setDraftDays(it) },
            onTravelersChange = { viewModel.setDraftTravelers(it) },
            onTransportChange = { viewModel.setDraftTransport(it) },
            onRemoveStop = { viewModel.removeDraftStop(it) },
            onMoveStop = { id, arriba -> viewModel.moveDraftStop(id, arriba) },
            onStopDayChange = { id, dia -> viewModel.setDraftStopDay(id, dia) },
            onAddNearby = { id, dia -> viewModel.addNearbyToDraft(id, dia) },
            onSave = { viewModel.saveDraftAsRoute() }
        )
    }
}

@Composable
private fun AiDiagnosisDialog(texto: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = AndeanBlue) },
        title = { Text("Prueba de conexión con la IA", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(texto, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                Spacer(Modifier.height(10.dp))
                Text(
                    "La clave se configura en el archivo .env del proyecto (GEMINI_API_KEY) y se obtiene gratis en aistudio.google.com/apikey",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) { Text("Entendido") } }
    )
}

/**
 * **Mi ruta**: el explorador del camino.
 *
 * En qué se diferencia del Radar IA: aquí no se le pregunta a un modelo, se lee el mapa. Se
 * traza la carretera de verdad, se sacan todos los pueblos por los que se pasa y todo lo que
 * hay a la vera de la vía (comida, cascadas, miradores, gasolineras, hospedaje, farmacias),
 * ordenado por el kilómetro del viaje en el que aparece. Sale muchísimo más, con coordenadas
 * exactas, y funciona aunque la IA esté caída. Cuando la IA está disponible se le suma encima
 * lo que se comenta en redes.
 */
/**
 * Explorador OSM del camino (pueblo por pueblo). Se deja en el archivo porque sigue
 * funcionando; la pestaña visible es solo Radar IA para no duplicar el mismo formulario.
 */
@Suppress("unused")
@Composable
private fun RutaTab(
    viewModel: RouteViewModel,
    state: ScoutUiState,
    tramos: List<ec.travesias.app.data.remote.RouteScoutService.Tramo>,
    stopIds: Set<String>,
    onOpenMap: () -> Unit,
    detours: Map<String, Double> = emptyMap()
) {
    val route by viewModel.activeRoute.collectAsState()
    val currentPlace by viewModel.currentPlaceName.collectAsState()
    LaunchedEffect(Unit) { viewModel.detectCurrentPlace() }

    var origin by remember(route?.id) { mutableStateOf(route?.originName ?: "") }
    var destination by remember(route?.id) { mutableStateOf(route?.destinationName ?: "") }
    LaunchedEffect(currentPlace) {
        val donde = currentPlace
        if (!donde.isNullOrBlank() && origin.isBlank()) origin = donde
    }
    var detour by remember { mutableIntStateOf(12) }
    var conIa by remember { mutableStateOf(true) }
    val interestOptions = listOf("comida típica", "naturaleza", "miradores para fotos", "cascadas", "playas", "cultura", "aventura", "hospedaje", "gasolineras y paradas")
    val selectedInterests = remember { mutableStateListOf<String>() }
    val abiertos = remember { mutableStateListOf<String>() }
    val cargando = state is ScoutUiState.Loading

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Todo lo que hay en tu camino", style = MaterialTheme.typography.headlineSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Text(
                "Se traza la carretera de verdad y se recorre pueblo por pueblo: qué hay para comer, " +
                    "qué ver, dónde dormir, dónde tanquear y dónde hay farmacia u hospital, con las " +
                    "coordenadas exactas de OpenStreetMap. No hace falta internet de la IA para esto.",
                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(Modifier.padding(16.dp)) {
                    LugarAutocompleteField(
                        value = origin, onValueChange = { origin = it }, label = "Desde",
                        placeholder = "Ej. Santo Domingo",
                        leadingIcon = { Icon(Icons.Default.TripOrigin, contentDescription = null, tint = StatusNormal) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LugarAutocompleteField(
                        value = destination, onValueChange = { destination = it }, label = "Hasta",
                        placeholder = "Ej. Baños de Agua Santa",
                        leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null, tint = StatusClosed) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("¿Qué buscas? (si no eliges nada, sale todo)", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(interestOptions) { opt ->
                            val sel = opt in selectedInterests
                            FilterChip(
                                selected = sel,
                                onClick = { if (sel) selectedInterests.remove(opt) else selectedInterests.add(opt) },
                                label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EquatorialGold, selectedLabelColor = AndeanBlue)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Cuánto acepto desviarme de la vía: $detour km", style = MaterialTheme.typography.labelMedium, color = VolcanicGray)
                    Slider(
                        value = detour.toFloat(), onValueChange = { detour = it.toInt() },
                        valueRange = 2f..40f, steps = 18,
                        colors = SliderDefaults.colors(thumbColor = AndeanBlue, activeTrackColor = AndeanBlue)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = conIa, onCheckedChange = { conIa = it }, colors = CheckboxDefaults.colors(checkedColor = AndeanBlue))
                        Column {
                            Text("Añadir también lo que se comenta en redes", style = MaterialTheme.typography.labelMedium, color = AndeanBlue)
                            Text("Usa la IA al final, si está disponible. Sin esto la búsqueda igual funciona.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val req = RadarRequest(
                                origin.trim(), destination.trim(),
                                interests = selectedInterests.toList(),
                                maxDetourKm = detour,
                                travelStyle = route?.travelStyle ?: "Moderado"
                            )
                            viewModel.exploreRoute(req, conIa)
                        },
                        enabled = !cargando, modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill), shape = RoundedCornerShape(26.dp)
                    ) {
                        if (cargando) CircularProgressIndicator(color = EquatorialGold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Explore, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cargando) (state as ScoutUiState.Loading).message else "Explorar mi camino",
                            color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        when (state) {
            is ScoutUiState.Error -> item {
                Card(colors = CardDefaults.cardColors(containerColor = StatusClosed.copy(alpha = 0.08f)), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusClosed)
                        Spacer(Modifier.width(8.dp))
                        Text(state.message, color = StatusClosed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is ScoutUiState.Success -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = GalapagosTeal.copy(alpha = 0.14f)), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Route, contentDescription = null, tint = AndeanBlue)
                                Spacer(Modifier.width(6.dp))
                                Text("Tu camino, tramo por tramo", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(state.exploracion.resumen, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = onOpenMap,
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandFill, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ver todo en el mapa")
                            }
                        }
                    }
                }
                tramos.forEach { tramo ->
                    val abierto = tramo.pueblo in abiertos
                    item(key = "tramo_" + tramo.pueblo) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (abierto) abiertos.remove(tramo.pueblo) else abiertos.add(tramo.pueblo)
                            }
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = EquatorialGold.copy(alpha = 0.25f), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        "km " + tramo.kmDelViaje.toInt(),
                                        style = MaterialTheme.typography.labelSmall, color = AndeanBlue,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(tramo.pueblo, color = AndeanBlue, fontWeight = FontWeight.Bold)
                                    Text(
                                        listOfNotNull(
                                            tramo.provincia.takeIf { it.isNotBlank() },
                                            tramo.lugares.size.toString() + " lugares"
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                    )
                                }
                                Icon(
                                    if (abierto) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null, tint = AndeanBlue
                                )
                            }
                        }
                    }
                    if (abierto) {
                        items(tramo.lugares, key = { "ruta_" + it.id }) { p ->
                            PlaceSummaryRow(
                                place = p, inRoute = p.id in stopIds,
                                detourLabel = etiquetaDesvio(detours[p.id], null),
                                onClick = { viewModel.selectPlace(p) },
                                onAddToRoute = { viewModel.addToRoute(p, 1) },
                                onToggleSave = { viewModel.toggleSaved(p) }
                            )
                        }
                    }
                }
            }
            else -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RadarTab(
    viewModel: RouteViewModel,
    state: RadarUiState,
    results: List<ec.travesias.app.data.model.Place>,
    stopIds: Set<String>,
    totalDays: Int,
    onOpenMap: () -> Unit,
    /** Km de desvío por id de lugar, para enseñarlo en cada tarjeta. */
    detours: Map<String, Double> = emptyMap(),
    onAddPlace: (ec.travesias.app.data.model.Place) -> Unit = { viewModel.addToRoute(it, 1) },
    onHoldPlace: (ec.travesias.app.data.model.Place) -> Unit = {}
) {
    val context = LocalContext.current
    val route by viewModel.activeRoute.collectAsState()
    // "Desde" se rellena solo: primero con lo que el viajero escribió en la portada, después
    // con la ruta activa y, si no hay nada, con el lugar donde está según su GPS.
    val currentPlace by viewModel.currentPlaceName.collectAsState()
    val intent by viewModel.tripIntent.collectAsState()
    val searchArea by viewModel.searchArea.collectAsState()
    LaunchedEffect(Unit) { viewModel.detectCurrentPlace() }

    var origin by remember(route?.id, intent.origin) {
        mutableStateOf(intent.origin.ifBlank { route?.originName ?: "" })
    }
    var destination by remember(route?.id, intent.destination) {
        mutableStateOf(intent.destination.ifBlank { route?.destinationName ?: "" })
    }
    // Solo si el viajero no ha escrito nada todavía: nunca le pisamos lo que tecleó.
    LaunchedEffect(currentPlace) {
        val donde = currentPlace
        if (!donde.isNullOrBlank() && origin.isBlank()) origin = donde
    }
    var freeText by remember { mutableStateOf("") }
    var detour by remember { mutableIntStateOf(25) }
    // Los días que eligió el viajero en la portada mandan sobre los de una ruta vieja: si no,
    // planificaba 5 días y el formulario volvía a mostrar 1 al tocar el botón.
    var days by remember(intent.days) { mutableIntStateOf(intent.days) }
    val planState by viewModel.planState.collectAsState()
    val interestOptions = listOf("comida típica", "naturaleza", "miradores para fotos", "aventura", "cultura", "playas", "cascadas", "termas", "huecas virales", "hospedaje económico", "gasolineras y paradas")
    val selectedInterests = remember { mutableStateListOf("comida típica", "naturaleza", "miradores para fotos") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                if (origin.isBlank()) "¿Qué hay alrededor de tu destino?"
                else "¿Por dónde vas a viajar?",
                style = MaterialTheme.typography.headlineSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
            )
            Text(
                if (origin.isBlank())
                    "Escribe solo el destino: la IA busca planes en un radio a la redonda, sin inventarte una carretera ni traerte lugares de otra provincia. Si luego quieres una ruta, escribe también de dónde sales."
                else "Escribe desde dónde y hasta dónde vas: la IA rastrea lo que los viajeros publicaron en TikTok, Instagram, Facebook, YouTube y la web, y te arma el itinerario día por día con los kilómetros que hay que desviarse en cada parada. Lo revisas y lo editas antes de guardarlo.",
                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
            )
        }
        item {
            Column {
                    LugarAutocompleteField(value = origin, onValueChange = { origin = it }, label = "Desde (opcional)", placeholder = "Ej. Santo Domingo. Vacío = solo el destino", leadingIcon = { Icon(Icons.Default.TripOrigin, contentDescription = null, tint = StatusNormal) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    LugarAutocompleteField(value = destination, onValueChange = { destination = it }, label = "¿A dónde vas?", placeholder = "Ej. Baños de Agua Santa", leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null, tint = StatusClosed) }, modifier = Modifier.fillMaxWidth())
                    if (origin.isBlank() && destination.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationSearching, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Sin salida: buscamos solo alrededor de ${destination.trim()}.",
                                style = MaterialTheme.typography.labelSmall, color = GalapagosTeal
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("¿Qué te interesa?", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(interestOptions) { opt ->
                            val sel = opt in selectedInterests
                            FilterChip(selected = sel, onClick = { if (sel) selectedInterests.remove(opt) else selectedInterests.add(opt) }, label = { Text(opt, style = MaterialTheme.typography.labelSmall) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EquatorialGold, selectedLabelColor = AndeanBlue))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (origin.isBlank()) "Buscar en un radio de $detour km alrededor del destino"
                        else "Desvío máximo de la vía: $detour km",
                        style = MaterialTheme.typography.labelMedium, color = VolcanicGray
                    )
                    // En modo destino el máximo real es 25 km (lo que OpenStreetMap cubre de una
                    // vez alrededor de un punto). No se ofrece un control que prometa más de lo
                    // que la app puede buscar.
                    val maxDetour = if (origin.isBlank()) 25 else 80
                    LaunchedEffect(origin.isBlank()) { if (origin.isBlank() && detour > 25) detour = 25 }
                    Slider(
                        value = detour.coerceIn(5, maxDetour).toFloat(),
                        onValueChange = { detour = it.toInt() },
                        valueRange = 5f..maxDetour.toFloat(),
                        steps = if (maxDetour == 25) 3 else 14,
                        colors = SliderDefaults.colors(thumbColor = AndeanBlue, activeTrackColor = AndeanBlue)
                    )
                    OutlinedTextField(value = freeText, onValueChange = { freeText = it }, label = { Text("Algo más (opcional)") }, placeholder = { Text("Ej. viajo con niños, quiero lugares para fotos virales y comida barata") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("¿Cuántos días?", style = MaterialTheme.typography.labelMedium, color = VolcanicGray)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { if (days > 1) days-- }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Menos días", tint = AndeanBlue)
                        }
                        Text(days.toString(), style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { if (days < 30) days++ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = "Más días", tint = AndeanBlue)
                        }
                    }
                    if (searchArea != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Zona de búsqueda: ${searchArea!!.name} · radio ${searchArea!!.radiusLabel}",
                            style = MaterialTheme.typography.labelSmall, color = GalapagosTeal
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val loading = state is RadarUiState.Loading
                    val planning = planState is RoutePlanUiState.Loading
                    val ocupado = loading || planning
                    if (destination.isBlank()) {
                        Text("Escribe el destino para empezar.", style = MaterialTheme.typography.labelSmall, color = StatusRestriction)
                        Spacer(Modifier.height(6.dp))
                    }

                    // Botón principal: la IA arma el ITINERARIO completo y lo deja editable.
                    Button(
                        onClick = {
                            viewModel.updateTripIntent(TripIntent(origin, destination, days, intent.travelers))
                            val req = RadarRequest(origin.trim(), destination.trim(), interests = selectedInterests.toList(), maxDetourKm = detour, travelStyle = route?.travelStyle ?: "Moderado", freeText = freeText.trim())
                            viewModel.planRouteWithAi(req, days, intent.travelers, route?.transportMode ?: TransportMode.BUS)
                        },
                        enabled = !ocupado && destination.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill), shape = RoundedCornerShape(26.dp)
                    ) {
                        if (planning) CircularProgressIndicator(color = EquatorialGold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (planning) (planState as RoutePlanUiState.Loading).message
                            else if (origin.isBlank()) "Armar escapada con IA" else "Armar mi ruta con IA",
                            color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Botón secundario: el Radar de siempre, solo la lista de paradas.
                    OutlinedButton(
                        onClick = {
                            viewModel.updateTripIntent(TripIntent(origin, destination, days, intent.travelers))
                            val req = RadarRequest(origin.trim(), destination.trim(), interests = selectedInterests.toList(), maxDetourKm = detour, travelStyle = route?.travelStyle ?: "Moderado", freeText = freeText.trim())
                            viewModel.runRadar(req)
                        },
                        enabled = !ocupado && destination.isNotBlank(), modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(23.dp)
                    ) {
                        if (loading) CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(Icons.Default.Radar, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (loading) (state as RadarUiState.Loading).message
                            else if (origin.isBlank()) "Ver lugares en la zona" else "Solo descubrir paradas",
                            color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (planState is RoutePlanUiState.Error) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text((planState as RoutePlanUiState.Error).message, style = MaterialTheme.typography.labelMedium, color = StatusClosed)
                        }
                    }
                    if (!viewModel.hasGeminiKey) {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ Falta GEMINI_API_KEY en .env: el radar en vivo está desactivado. El catálogo offline sigue disponible.", style = MaterialTheme.typography.labelSmall, color = StatusRestriction)
                    }
            }
        }

        // Mientras la IA arma el itinerario, el viajero ve que está pasando algo y no un botón
        // apagado sin explicación. El borrador se abre solo cuando está listo.
        if (planState is RoutePlanUiState.Loading) item {
            Card(colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.14f)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("La IA está armando tu viaje", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        Text((planState as RoutePlanUiState.Loading).message, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        Text("Puedes seguir usando la app: el borrador aparecerá para revisarlo.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    }
                }
            }
        }

        when (state) {
            is RadarUiState.Error -> item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusClosed)
                        Spacer(Modifier.width(8.dp))
                        Text(state.message, color = StatusClosed, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { viewModel.diagnoseAi() }) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("¿Por qué falla? Probar la conexión con la IA", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            is RadarUiState.Success -> {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EquatorialGold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (searchArea != null) "Lugares alrededor de ${searchArea?.name}"
                                else "Lo que está de moda en tu ruta",
                                style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
                            )
                            if (state.result.fromCache) { Spacer(Modifier.width(6.dp)); Text("(guardado)", style = MaterialTheme.typography.labelSmall, color = VolcanicGray) }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (searchArea != null) {
                            Text("Buscando en un radio de ${searchArea?.radiusLabel} alrededor del destino.", style = MaterialTheme.typography.labelMedium, color = GalapagosTeal)
                            if (state.result.summary.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(state.result.summary, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                            }
                        } else {
                            Text(state.result.summary, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                        }
                        if (state.result.groundingSources.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.result.groundingSources.take(10)) { s ->
                                    AssistChip(onClick = { Intents.openUrl(context, s.url) }, label = { Text(s.title.ifBlank { s.platform.displayName }.take(28), style = MaterialTheme.typography.labelSmall) }, leadingIcon = { Icon(platformIcon(s.platform), contentDescription = null, modifier = Modifier.size(14.dp)) })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onOpenMap, colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandFill, contentColor = Color.White)) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Ver ${results.size} en el mapa")
                            }
                            TextButton(onClick = {
                                val req = RadarRequest(origin.trim(), destination.trim(), interests = selectedInterests.toList(), maxDetourKm = detour, freeText = freeText.trim())
                                viewModel.runRadar(req, forceRefresh = true)
                            }) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Actualizar") }
                        }
                    }
                }
                items(results, key = { "radar_" + it.id }) { p ->
                    PlaceSummaryRow(
                        place = p, inRoute = p.id in stopIds,
                        detourLabel = etiquetaDesvio(detours[p.id], searchArea),
                        onClick = { viewModel.selectPlace(p) },
                        onAddToRoute = { onAddPlace(p) },
                        onToggleSave = { viewModel.toggleSaved(p) },
                        onHold = { onHoldPlace(p) }
                    )
                }
            }
            else -> {}
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Share, contentDescription = null, tint = AndeanBlue)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Tip: desde TikTok, Instagram, Facebook o YouTube toca 'Compartir' → 'Travesías en Ecuador' y la IA convertirá ese video en una parada lista para tu ruta.",
                    style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Pestaña "Enlace": el viajero pega un TikTok, Reel, video de Facebook o YouTube y la IA
 * lo convierte en una parada. Es la alternativa a "Compartir → Travesías" para cuando la
 * red social no ofrece el menú de compartir o el viajero prefiere copiar el enlace.
 */
@Composable
private fun LinkTab(
    viewModel: RouteViewModel,
    stopIds: Set<String>,
    onAddPlace: (ec.travesias.app.data.model.Place) -> Unit = { viewModel.addToRoute(it, 1) },
    onHoldPlace: (ec.travesias.app.data.model.Place) -> Unit = {}
) {
    val url by viewModel.linkUrl.collectAsState()
    val hint by viewModel.linkHint.collectAsState()
    val state by viewModel.linkState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val cargando = state is RouteViewModel.LinkUi.Loading

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(8.dp))
                        Text("Pega el enlace del video", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "TikTok, Instagram, Facebook, YouTube o cualquier página. La IA abre la publicación, " +
                            "busca el lugar en internet y arma la ficha con coordenadas, costo y cómo llegar.",
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = viewModel::onLinkUrlChanged,
                        label = { Text("Enlace") },
                        placeholder = { Text("https://vm.tiktok.com/…") },
                        singleLine = true,
                        enabled = !cargando,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = AndeanBlue) },
                        trailingIcon = {
                            Row {
                                if (url.isNotBlank()) {
                                    IconButton(onClick = { viewModel.resetLink() }) { Icon(Icons.Default.Close, contentDescription = "Limpiar") }
                                }
                                IconButton(onClick = {
                                    clipboard.getText()?.text?.let { viewModel.onLinkUrlChanged(it.trim()) }
                                }) { Icon(Icons.Default.ContentPaste, contentDescription = "Pegar", tint = AndeanBlue) }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.analyzeLink() },
                        enabled = !cargando && url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        if (cargando) CircularProgressIndicator(color = EquatorialGold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cargando) (state as RouteViewModel.LinkUi.Loading).message else "Analizar y añadir a mi ruta",
                            color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!viewModel.hasGeminiKey) {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ Falta GEMINI_API_KEY: el análisis de enlaces está desactivado.", style = MaterialTheme.typography.labelSmall, color = StatusRestriction)
                    }
            }
        }

        when (val s = state) {
            // --------- Se leyó la publicación pero falta identificar el lugar ---------
            is RouteViewModel.LinkUi.NeedsHint -> item {
                Card(colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.12f)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = EquatorialGold)
                            Spacer(Modifier.width(8.dp))
                            Text("Ayúdame con una pista", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(s.motivo, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)

                        if (s.titulo.isNotBlank() || s.autor.isNotBlank() || s.miniatura.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Esto es lo que sí pude leer:", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                if (s.miniatura.isNotBlank()) {
                                    AsyncImage(
                                        model = s.miniatura, contentDescription = null,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    if (s.titulo.isNotBlank()) Text(s.titulo, style = MaterialTheme.typography.bodySmall, color = VolcanicGray, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    if (s.autor.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text("@" + s.autor + " · " + s.plataforma, style = MaterialTheme.typography.labelSmall, color = AndeanBlue)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = hint,
                            onValueChange = viewModel::onLinkHintChanged,
                            label = { Text("¿Qué lugar sale en el video?") },
                            placeholder = { Text("Ej: la laguna del Quilotoa, o queda en Baños") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.analyzeLink(hint) },
                            enabled = hint.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar con esta pista", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is RouteViewModel.LinkUi.Error -> item {
                Card(colors = CardDefaults.cardColors(containerColor = StatusClosed.copy(alpha = 0.08f)), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusClosed)
                        Spacer(Modifier.width(8.dp))
                        Text(s.message, color = StatusClosed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            is RouteViewModel.LinkUi.Success -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("Lugar identificado y guardado", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    PlaceSummaryRow(
                        place = s.place, inRoute = s.place.id in stopIds,
                        onClick = { viewModel.selectPlace(s.place) },
                        onAddToRoute = { onAddPlace(s.place) },
                        onToggleSave = { viewModel.toggleSaved(s.place) },
                        onHold = { onHoldPlace(s.place) }
                    )
                }
                item {
                    OutlinedButton(onClick = { viewModel.resetLink() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("Analizar otro enlace", color = AndeanBlue)
                    }
                }
            }

            else -> {}
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(10.dp))
                        Text("También puedes compartir directo", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Desde TikTok, Instagram, Facebook o YouTube toca 'Compartir' → 'Travesías en Ecuador' " +
                            "y se abre directo aquí, sin copiar nada.",
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Si Instagram o Facebook no dejan leer el video (pasa con cuentas privadas), " +
                            "escribe tú el nombre del lugar y la IA lo busca en internet igual.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Cómo se lee la distancia de una parada. En modo ruta es el desvío respecto a la carretera;
 * en modo "solo destino" es la distancia a la base. Antes todo decía "de la vía" aunque no
 * existiera una vía, y el viajero no entendía de dónde salía ese número.
 */
private fun etiquetaDesvio(km: Double?, area: SearchArea?): String? {
    if (km == null) return null
    if (area != null) {
        return when {
            km < 1.0 -> "en el centro"
            km < 10.0 -> "a " + String.format(java.util.Locale.US, "%.1f", km) + " km del centro"
            else -> "a " + km.toInt() + " km del centro"
        }
    }
    return when {
        km < 1.0 -> "junto a la vía"
        km < 10.0 -> "a " + String.format(java.util.Locale.US, "%.1f", km) + " km de la vía"
        km > 900.0 -> null
        else -> "a " + km.toInt() + " km de la vía"
    }
}

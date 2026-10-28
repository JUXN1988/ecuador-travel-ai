package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.model.FuelType
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.TripIntent
import ec.travesias.app.data.model.VehicleProfile
import ec.travesias.app.data.model.VehicleType
import ec.travesias.app.data.model.journeyStops
import ec.travesias.app.data.model.stopsInOrder
import ec.travesias.app.data.repository.RouteCoherence
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.data.repository.TripCostEstimator
import ec.travesias.app.ui.components.LugarAutocompleteField
import ec.travesias.app.ui.components.PlaceDetailSheet
import ec.travesias.app.ui.components.categoryColor
import ec.travesias.app.ui.components.categoryIcon
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.theme.*
import ec.travesias.app.ui.util.Intents
import ec.travesias.app.viewmodel.RouteViewModel
import kotlinx.coroutines.launch

/**
 * Mi Ruta — rediseño oct-2026.
 *
 * El itinerario se lee como una LÍNEA DE TIEMPO por día, de arriba abajo:
 *
 *   07:30  ● Sales de Santo Domingo
 *          ┊  carro · 4 h 10 min · 250 km · gasolina $8,40
 *   11:40  ● Lago Agrio            (tarjeta: visita 3 h · sales 14:40)
 *          ┊  carro · 1 h 05 min · 55 km
 *   15:45  ● Reserva Limoncocha
 *   18:45  ☾ Noche en Reserva Limoncocha
 *
 * Arriba: resumen claro (km, horas en camino, costo por persona con entradas + transporte),
 * avisos de lo que no cuadra, y cómo se viaja (con el vehículo y el gasto de combustible).
 */
@Composable
fun RouteScreen(
    viewModel: RouteViewModel,
    onOpenMap: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenPlanner: () -> Unit,
    onShareRouteToGroup: (() -> Unit)? = null,
    /** Manda un mensaje a la Guía IA y abre el chat (para "que la IA lo arregle"). */
    onAskGuide: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route by viewModel.activeRoute.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val places by viewModel.activeRoutePlaces.collectAsState()
    val road by viewModel.roadRoute.collectAsState()
    val isOptimizing by viewModel.isOptimizing.collectAsState()
    val mapDownload by viewModel.routeMapDownload.collectAsState()
    val selected by viewModel.selectedPlace.collectAsState()
    val schedule by viewModel.schedule.collectAsState()
    val cost by viewModel.tripCost.collectAsState()
    val issues by viewModel.routeIssues.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val fuelUpdating by viewModel.fuelUpdating.collectAsState()
    val ends by viewModel.endpoints.collectAsState()
    val lang by AppSettings.language.collectAsState()

    var showRouteEditor by remember { mutableStateOf(false) }
    var editingRoute by remember { mutableStateOf<TravelRoute?>(null) }
    var showRoutesList by remember { mutableStateOf(false) }
    var editingStop by remember { mutableStateOf<RouteStop?>(null) }
    var showAddStop by remember { mutableStateOf(false) }
    var addStopDay by remember { mutableIntStateOf(1) }
    var showVehicle by remember { mutableStateOf(false) }
    var editingStart by remember { mutableStateOf(false) }

    val current = route
    // La siguiente parada sin marcar: la misma que muestra la portada y el mapa. Se excluyen
    // la salida y la llegada (no son visitas). Tenerla aquí arriba convierte la lista en algo
    // accionable ("¿qué toca ahora?") y no solo informativo.
    val nextStop = current?.journeyStops()?.firstOrNull { !it.isDone }
    // La salida y la llegada se guardan como paradas para que el horario sepa de dónde a
    // dónde va el viaje, pero en la línea de tiempo se muestran como extremos, no como
    // visitas que se puedan quitar o reordenar.
    val ordenadasDeLaRuta = current?.stopsInOrder().orEmpty()
    val primeraParadaId = ordenadasDeLaRuta.firstOrNull()?.id
    val ultimaParadaId = ordenadasDeLaRuta.lastOrNull()?.id

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SurfaceLight),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
    ) {
        // ------------------------------------------------------------ cabecera
        item(key = "header") {
            RouteHeader(
                route = current,
                roundTrip = ends.roundTrip,
                onSwitch = { showRoutesList = true },
                onEdit = { editingRoute = current; showRouteEditor = true }
            )
        }

        if (current != null && nextStop != null) {
            item(key = "next_stop") {
                Spacer(Modifier.height(10.dp))
                NextStopBanner(
                    total = current.journeyStops().size,
                    hechas = current.journeyStops().count { it.isDone },
                    next = nextStop,
                    onOpenMap = onOpenMap
                )
            }
        }

        // "No conozco el destino": un toque busca qué visitar allí, aunque la ruta ya esté
        // guardada. Es la app actuando como guía turística, no solo como lista de paradas.
        if (current != null && current.destinationName.isNotBlank()) {
            item(key = "explore_destination") {
                TextButton(
                    onClick = {
                        viewModel.startDestinationDiscovery(
                            TripIntent(
                                origin = "",
                                destination = current.destinationName,
                                days = current.totalDays,
                                travelers = current.travelers
                            )
                        )
                        viewModel.requestPlanTab()
                        onOpenDiscover()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.TravelExplore, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Qué visitar en ${current.destinationName}",
                        color = GalapagosTeal,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (current == null) {
            item(key = "empty_route") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Route, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Aún no tienes una ruta", style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Text(
                        "Dinos a dónde vas y la IA te propone paradas, días y horarios. Tú quitas, mueves y guardas.",
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray, modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.requestPlanTab(); onOpenDiscover() }, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = EquatorialGold)
                        Spacer(Modifier.width(6.dp)); Text("Armar mi ruta con IA")
                    }
                    TextButton(onClick = { editingRoute = null; showRouteEditor = true }) {
                        Text("O créala a mano, paso a paso", color = GalapagosTeal)
                    }
                }
            }
        } else {
            // -------------------------------------------------------- resumen
            item(key = "summary") {
                Spacer(Modifier.height(10.dp))
                TripSummaryCard(
                    route = current,
                    schedule = schedule,
                    cost = cost,
                    roadKm = road?.totalDistanceKm,
                    isOptimizing = isOptimizing,
                    onOpenMap = onOpenMap,
                    onOpenDiscover = onOpenDiscover,
                    onOptimize = { viewModel.optimizeRoute() }
                )
            }

            if (issues.isNotEmpty()) {
                item(key = "issues") {
                    Spacer(Modifier.height(10.dp))
                    IssuesCard(
                        issues = issues,
                        isOptimizing = isOptimizing,
                        canOptimize = current.journeyStops().size >= 2,
                        onOptimize = { viewModel.optimizeRoute() },
                        onAskGuide = onAskGuide?.let { ask ->
                            {
                                ask(
                                    "Revisa mi ruta activa y corrige estos problemas con acciones: " +
                                        issues.joinToString(" | ") { it.text }
                                )
                            }
                        }
                    )
                }
            }

            // -------------------------------------------------------- transporte
            item(key = "transport") {
                Spacer(Modifier.height(10.dp))
                TransportCard(
                    route = current,
                    vehicle = vehicle,
                    cost = cost,
                    lang = lang,
                    fuelUpdating = fuelUpdating,
                    onModeChange = { viewModel.setTransportMode(it) },
                    onEditStart = { editingStart = true },
                    onEditVehicle = { showVehicle = true },
                    onRefreshPrices = { viewModel.refreshFuelPrices() }
                )
            }

            // -------------------------------------------------------- mapa offline
            item(key = "offline") {
                Spacer(Modifier.height(10.dp))
                OfflineMapRow(
                    progress = mapDownload?.progress,
                    running = mapDownload?.isRunning == true,
                    tilesDone = mapDownload?.tilesDone ?: 0,
                    tilesTotal = mapDownload?.tilesTotal ?: 0,
                    error = mapDownload?.error,
                    enabled = current.stops.isNotEmpty(),
                    onDownload = { viewModel.downloadRouteMap() }
                )
            }

            if (current.stops.isEmpty()) {
                item(key = "no_stops") {
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.12f)), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Tu ruta está vacía", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Text(
                                "Añade paradas escribiendo su nombre, búscalas con el Radar IA en Descubrir, o toca un lugar en el Mapa y elige «Añadir a mi ruta».",
                                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { addStopDay = 1; showAddStop = true }, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Añadir una parada")
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------- días en línea de tiempo
            val ultimoDiaConParadas = current.stops.maxOfOrNull { it.dayNumber } ?: 0
            for (day in 1..current.totalDays) {
                val dayStops = current.stopsForDay(day)
                val plan = schedule.firstOrNull { it.day == day }

                item(key = "day_$day") {
                    Spacer(Modifier.height(18.dp))
                    DayHeader(
                        day = day,
                        dateLabel = RouteCoherence.dayLabel(current.startDate, day, lang == AppLanguage.EN),
                        plan = plan,
                        stopsCount = dayStops.count { it.id != primeraParadaId && it.id != ultimaParadaId },
                        entriesUsd = dayStops.sumOf { it.estimatedCostUsd },
                        transportUsd = cost?.day(day)?.totalUsd ?: 0.0,
                        onAdd = { addStopDay = day; showAddStop = true }
                    )
                }

                if (dayStops.isEmpty()) {
                    item(key = "empty_$day") {
                        EmptyDayCard(
                            afterTrip = current.stops.isNotEmpty() && day > ultimoDiaConParadas,
                            onAdd = { addStopDay = day; showAddStop = true }
                        )
                    }
                    continue
                }

                // Salida del día (si el día no arranca ya en la primera parada)
                val lugarSalida = plan?.startPlace?.takeIf { it.isNotBlank() }
                val mostrarSalida = lugarSalida != null && lugarSalida != dayStops.first().placeName
                if (mostrarSalida) item(key = "start_$day") {
                    val lugar = lugarSalida
                    TimelineNode(
                        time = plan?.start ?: current.dailyStartTime,
                        lineTop = false,
                        lineBottom = true,
                        dotColor = BrandFill,
                        icon = if (day == 1) Icons.Default.Home else Icons.Default.WbSunny
                    ) {
                        Text("Sales de $lugar", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        Text(
                            if (day == 1) "Punto de partida del viaje" else "Donde pasaste la noche",
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                        )
                    }
                }

                val programadas = plan?.stops
                dayStops.forEachIndexed { index, stop ->
                    val sch = programadas?.firstOrNull { it.stop.id == stop.id }
                    if (sch != null && sch.travelMinutes > 0) {
                        item(key = "leg_${stop.id}") {
                            LegConnector(
                                transport = sch.transport,
                                minutes = sch.travelMinutes,
                                km = sch.distanceKm,
                                legCost = TripCostEstimator.legCost(sch.transport, sch.distanceKm, vehicle, current.travelers),
                                lang = lang,
                                onTransportChange = { modo -> viewModel.setStopTransport(stop, modo) }
                            )
                        }
                    }
                    item(key = stop.id) {
                        val esSalida = stop.id == primeraParadaId && stop.placeName.equals(current.originName.trim(), true)
                        val esLlegada = stop.id == ultimaParadaId && stop.placeName.equals(current.destinationName.trim(), true)
                        StopNode(
                            stop = stop,
                            place = places[stop.placeId],
                            scheduled = sch,
                            isFirst = index == 0,
                            isLast = index == dayStops.size - 1,
                            // la línea entra por arriba salvo en la primera parada de un día que arranca en ella
                            lineTop = index > 0 || mostrarSalida || (sch?.travelMinutes ?: 0) > 0,
                            totalDays = current.totalDays,
                            isEndpoint = esSalida || esLlegada,
                            endpointLabel = when {
                                esSalida && esLlegada -> "Base en"
                                esSalida -> "Sales de"
                                ends.roundTrip -> "Vuelves a"
                                else -> "Llegas a"
                            },
                            onOpen = { places[stop.placeId]?.let { viewModel.selectPlace(it) } },
                            onMoveUp = { viewModel.moveStop(stop, stop.dayNumber, stop.orderInDay - 1) },
                            onMoveDown = { viewModel.moveStop(stop, stop.dayNumber, stop.orderInDay + 1) },
                            onMoveToDay = { d -> viewModel.moveStop(stop, d, 999) },
                            onRemove = { viewModel.removeStop(stop) },
                            onToggleDone = { viewModel.toggleStopDone(stop) },
                            onEdit = { editingStop = stop },
                            onNavigate = { Intents.navigateTo(context, stop.latitude, stop.longitude, stop.placeName) }
                        )
                    }
                }

                // Cierre del día: llegada final, noche o fin del recorrido
                val finalLeg = plan?.finishLeg
                if (finalLeg != null) {
                    item(key = "fleg_$day") {
                        LegConnector(
                            transport = finalLeg.transport,
                            minutes = finalLeg.travelMinutes,
                            km = finalLeg.distanceKm,
                            legCost = TripCostEstimator.legCost(finalLeg.transport, finalLeg.distanceKm, vehicle, current.travelers),
                            lang = lang,
                            onTransportChange = null
                        )
                    }
                    item(key = "fin_$day") {
                        TimelineNode(
                            time = finalLeg.arrival,
                            lineTop = true, lineBottom = false,
                            dotColor = AndesGreen,
                            icon = if (finalLeg.isReturnHome) Icons.Default.Home else Icons.Default.SportsScore
                        ) {
                            Text(
                                (if (finalLeg.isReturnHome) "De vuelta en " else "Llegas a ") + finalLeg.toName,
                                style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (finalLeg.isReturnHome) "Fin del viaje · regreso a casa" else "Fin del viaje",
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                    }
                } else if (plan != null) {
                    item(key = "night_$day") {
                        val noche = plan.overnight
                        TimelineNode(
                            time = plan.end,
                            lineTop = true, lineBottom = false,
                            dotColor = if (noche != null) AndeanBlueDeep else AndesGreen,
                            icon = if (noche != null) Icons.Default.Bedtime else Icons.Default.SportsScore
                        ) {
                            Text(
                                if (noche != null) "Noche en $noche" else "Fin del recorrido",
                                style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (noche != null) "Busca hospedaje aquí o cerca" else "Última parada del viaje",
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------- compartir
            item(key = "export") {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { Intents.shareText(context, viewModel.exportRouteText(), current.name) } },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(4.dp)); Text("Compartir", color = AndeanBlue, maxLines = 1)
                    }
                    if (onShareRouteToGroup != null) {
                        OutlinedButton(onClick = onShareRouteToGroup, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                            Spacer(Modifier.width(4.dp)); Text("Al grupo", color = AndeanBlue, maxLines = 1)
                        }
                    }
                }
                TextButton(onClick = onOpenPlanner, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Generar itinerario detallado con IA", color = GalapagosTeal)
                }
            }
        }
    }

    // ------------------------------------------------------------ diálogos
    if (showAddStop) {
        AddStopDialog(
            day = addStopDay,
            maxDays = current?.totalDays ?: 1,
            onDismiss = { showAddStop = false },
            onAdd = { nombre, dia -> viewModel.addStopByName(nombre, dia); showAddStop = false }
        )
    }

    if (showRouteEditor) {
        RouteEditorDialog(
            initial = editingRoute,
            onDismiss = { showRouteEditor = false },
            onSave = { name, origin, dest, days, travelers, style, date, transport ->
                val r = editingRoute
                if (r == null) viewModel.createRoute(name, origin, dest, days, travelers, style, date, transport)
                else viewModel.updateRouteInfo(
                    r.copy(
                        name = name.ifBlank { r.name }, originName = origin, destinationName = dest, totalDays = days,
                        travelers = travelers, travelStyle = style, startDate = date, transportMode = transport
                    )
                )
                showRouteEditor = false
            }
        )
    }

    if (showRoutesList) {
        RoutesListDialog(
            routes = routes,
            onPick = { viewModel.setActiveRoute(it); showRoutesList = false },
            onDelete = { viewModel.deleteRoute(it) },
            onNew = { showRoutesList = false; editingRoute = null; showRouteEditor = true },
            onDismiss = { showRoutesList = false }
        )
    }

    editingStop?.let { s ->
        StopEditorDialog(stop = s, onDismiss = { editingStop = null }, onSave = { viewModel.updateStop(it); editingStop = null })
    }

    if (showVehicle) {
        VehicleDialog(
            vehicle = vehicle,
            fuelUpdating = fuelUpdating,
            onRefreshPrices = { viewModel.refreshFuelPrices() },
            onDismiss = { showVehicle = false },
            onSave = { viewModel.saveVehicle(it); showVehicle = false }
        )
    }

    if (editingStart && current != null) {
        StartTimeDialog(
            initial = current.dailyStartTime,
            onDismiss = { editingStart = false },
            onSave = { viewModel.setDailyStart(it); editingStart = false }
        )
    }

    selected?.let { place ->
        val comments by viewModel.placeComments.collectAsState()
        PlaceDetailSheet(
            place = place, inRoute = true, totalDays = current?.totalDays ?: 1,
            onDismiss = { viewModel.selectPlace(null) }, onAddToRoute = { }, onToggleSave = { viewModel.toggleSaved(place) },
            onShowOnMap = { viewModel.selectPlace(null); viewModel.focusOnMap(place.latitude, place.longitude); onOpenMap() },
            comments = comments,
            onOpenComments = { id -> viewModel.openPlaceComments(id) },
            onCloseComments = { viewModel.closePlaceComments() },
            onAddComment = { text, category, photoUri -> viewModel.addPlaceComment(place.id, text, category, photoUri) }
        )
    }
}

// =====================================================================================
//  Cabecera y resumen
// =====================================================================================

/**
 * "¿Qué toca ahora?": la siguiente parada sin completar, con el mapa a un toque. Es la misma
 * información que muestran la portada y el mapa, para que el viajero no tenga que buscarla.
 */
@Composable
private fun NextStopBanner(total: Int, hechas: Int, next: RouteStop, onOpenMap: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(BrandFill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.NearMe, contentDescription = null, tint = BrandYellow, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Siguiente parada", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    Text(next.placeName, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Día ${next.dayNumber} · parada ${next.orderInDay + 1}", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
                TextButton(onClick = onOpenMap) { Text("Ver en el mapa", color = AndeanBlue, style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else hechas.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = AndesGreen, trackColor = HairLine
            )
        }
    }
}

@Composable
private fun RouteHeader(route: TravelRoute?, roundTrip: Boolean, onSwitch: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                route?.name ?: "Mi Ruta",
                style = MaterialTheme.typography.titleLarge, color = AndeanBlue, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 6.dp)
            )
            IconButton(onClick = onSwitch) { Icon(Icons.Default.SwapHoriz, contentDescription = "Mis rutas", tint = AndeanBlue) }
            IconButton(onClick = onEdit) {
                Icon(if (route == null) Icons.Default.Add else Icons.Default.Edit, contentDescription = if (route == null) "Nueva ruta" else "Editar ruta", tint = AndeanBlue)
            }
        }
        if (route != null) {
            val origen = route.originName.trim()
            val destino = route.destinationName.trim()
            if (origen.isNotBlank() || destino.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(if (roundTrip) Icons.Default.Loop else Icons.Default.TripOrigin, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (roundTrip) "$origen · ida y vuelta" else listOf(origen, destino).filter { it.isNotBlank() }.joinToString(" → "),
                        style = MaterialTheme.typography.bodyMedium, color = AndeanBlue, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            val fecha = RouteCoherence.dayLabel(route.startDate, 1)
            Text(
                listOfNotNull(
                    "${route.totalDays} " + (if (route.totalDays == 1) "día" else "días"),
                    "${route.travelers} " + (if (route.travelers == 1) "viajero" else "viajeros"),
                    route.travelStyle.takeIf { it.isNotBlank() },
                    fecha.takeIf { it.isNotBlank() }?.let { "sale el $it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = VolcanicGray,
                modifier = Modifier.padding(top = 2.dp, start = 22.dp)
            )
        }
    }
}

@Composable
private fun TripSummaryCard(
    route: TravelRoute,
    schedule: List<RouteSchedule.DayPlan>,
    cost: TripCostEstimator.TripCost?,
    roadKm: Double?,
    isOptimizing: Boolean,
    onOpenMap: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOptimize: () -> Unit
) {
    val km = cost?.totalKm?.takeIf { it > 0 } ?: roadKm ?: 0.0
    val enCamino = schedule.sumOf { it.travelMinutes }
    // La salida y la llegada no cuentan como paradas visitadas.
    val nParadas = route.journeyStops().size
    Surface(color = BrandFill, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                SummaryStat(Icons.Default.Straighten, if (km > 0) "${km.toInt()} km" else "—", "recorrido total", Modifier.weight(1f))
                SummaryStat(transportIcon(route.transportMode), RouteSchedule.durationLabel(enCamino), "en camino (${route.transportMode.shortName})", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                SummaryStat(
                    Icons.Default.Place, "$nParadas",
                    (if (nParadas == 1) "parada" else "paradas") + " en ${route.totalDays} " + (if (route.totalDays == 1) "día" else "días"),
                    Modifier.weight(1f)
                )
                SummaryStat(
                    Icons.Default.Payments,
                    "$" + TripCostEstimator.fmtUsd(cost?.perPersonUsd ?: route.totalEstimatedCostUsd),
                    "por persona, aprox.",
                    Modifier.weight(1f)
                )
            }
            if (cost != null && (cost.transportUsd > 0 || cost.entriesPerPersonUsd > 0)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Entradas $" + TripCostEstimator.fmtUsd(cost.entriesPerPersonUsd) +
                        " + transporte $" + TripCostEstimator.fmtUsd(cost.transportPerPersonUsd) + " por persona" +
                        (if (cost.travelers > 1) " · total del grupo $" + TripCostEstimator.fmtUsd(cost.groupTotalUsd) else ""),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onOpenMap, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandYellow, contentColor = Color(0xFF002F65))
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Ver mapa", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onOpenDiscover, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Buscar paradas", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOptimize,
                enabled = !isOptimizing && route.journeyStops().size >= 2,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GalapagosTeal, contentColor = Color.White)
            ) {
                if (isOptimizing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isOptimizing) "Ordenando…" else "Ordenar mejor (orden y días)", maxLines = 1)
            }
        }
    }
}

@Composable
private fun SummaryStat(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.White.copy(alpha = 0.12f), shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = BrandYellow, modifier = Modifier.padding(7.dp).size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
private fun IssuesCard(
    issues: List<RouteCoherence.Issue>,
    isOptimizing: Boolean,
    canOptimize: Boolean,
    onOptimize: () -> Unit,
    onAskGuide: (() -> Unit)?
) {
    val serio = issues.any { it.level == RouteCoherence.Level.SERIOUS }
    val tono = if (serio) StatusClosed else StatusRestriction
    Card(
        colors = CardDefaults.cardColors(containerColor = tono.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = tono, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (issues.size == 1) "Algo de tu ruta conviene revisar" else "${issues.size} cosas de tu ruta conviene revisar",
                    style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            issues.take(5).forEach { i ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", color = tono, modifier = Modifier.padding(end = 6.dp))
                    Text(i.text, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canOptimize) {
                    OutlinedButton(onClick = onOptimize, enabled = !isOptimizing) { Text("Ordenar mejor", maxLines = 1) }
                }
                if (onAskGuide != null) {
                    TextButton(onClick = onAskGuide) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Que la Guía IA lo arregle", color = GalapagosTeal, maxLines = 1)
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Transporte y vehículo
// =====================================================================================

/** Icono de cada medio de transporte. */
private fun transportIcon(mode: TransportMode): ImageVector = when (mode) {
    TransportMode.CAR -> Icons.Default.DirectionsCar
    TransportMode.BUS -> Icons.Default.DirectionsBus
    TransportMode.TAXI -> Icons.Default.LocalTaxi
    TransportMode.MOTO -> Icons.Default.TwoWheeler
    TransportMode.TOUR -> Icons.Default.Groups
    TransportMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    TransportMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TransportMode.MIXED -> Icons.Default.SwapHoriz
}

@Composable
private fun TransportCard(
    route: TravelRoute,
    vehicle: VehicleProfile,
    cost: TripCostEstimator.TripCost?,
    lang: AppLanguage,
    fuelUpdating: Boolean,
    onModeChange: (TransportMode) -> Unit,
    onEditStart: () -> Unit,
    onEditVehicle: () -> Unit,
    onRefreshPrices: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t(lang, "¿Cómo viajas?", "How are you travelling?"), style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Text(
                        t(lang, "Con esto calculo tiempos, horas de llegada y gastos.", "Used for travel times, arrival times and costs."),
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                }
                AssistChip(
                    onClick = onEditStart,
                    label = { Text(t(lang, "Salgo ", "Leave ") + route.dailyStartTime) },
                    leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TransportMode.entries.toList()) { m ->
                    FilterChip(
                        selected = route.transportMode == m,
                        onClick = { onModeChange(m) },
                        label = { Text(t(lang, m.displayName, m.displayNameEn), style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Icon(transportIcon(m), contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandFill, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White
                        )
                    )
                }
            }

            val usaVehiculo = route.transportMode == TransportMode.CAR || route.transportMode == TransportMode.MOTO ||
                route.stops.any { it.transportMode == TransportMode.CAR }
            if (usaVehiculo) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = HairLine)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onEditVehicle() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = EquatorialGold.copy(alpha = 0.18f), shape = CircleShape) {
                        Icon(
                            if (vehicle.type == VehicleType.MOTORCYCLE) Icons.Default.TwoWheeler else Icons.Default.DirectionsCar,
                            contentDescription = null, tint = AndeanBlue, modifier = Modifier.padding(8.dp).size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Tu vehículo: " + vehicle.type.displayName,
                            style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold
                        )
                        Text(
                            TripCostEstimator.fmt1(vehicle.kmPerUnit) + " km/" + vehicle.fuel.unit + " · " + vehicle.fuel.displayName +
                                " a $" + TripCostEstimator.fmtUsd(vehicle.pricePerUnit) + "/" + vehicle.fuel.unit,
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                        )
                    }
                    Text("Cambiar", color = GalapagosTeal, style = MaterialTheme.typography.labelLarge)
                }

                if (cost != null && cost.vehicleKm > 0) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = GalapagosTeal.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "$" + TripCostEstimator.fmtUsd(cost.fuelUsd + cost.tollsUsd) + " de combustible y peajes",
                                    style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                cost.vehicleKm.toInt().toString() + " km en tu vehículo → " + TripCostEstimator.fmt1(cost.fuelUnits) + " " +
                                    (if (cost.fuelUnit == "galón") "galones" else cost.fuelUnit) + " ≈ $" + TripCostEstimator.fmtUsd(cost.fuelUsd) +
                                    (if (cost.tollsUsd > 0) " · peajes ≈ $" + TripCostEstimator.fmtUsd(cost.tollsUsd) else ""),
                                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                            )
                            if (cost.travelers > 1) {
                                Text(
                                    "Dividido entre ${cost.travelers}: $" + TripCostEstimator.fmtUsd((cost.fuelUsd + cost.tollsUsd) / cost.travelers) + " cada uno",
                                    style = MaterialTheme.typography.labelMedium, color = GalapagosTeal, fontWeight = FontWeight.Bold
                                )
                            }
                            if (cost.seatsWarning.isNotBlank()) {
                                Text(cost.seatsWarning, style = MaterialTheme.typography.labelSmall, color = StatusRestriction)
                            }
                        }
                    }
                }

                // Precio vigente y aviso si ya pasó la fecha de cambio
                val vencido = vehicle.pricesValidUntil.isNotBlank() && RouteCoherence.today() > vehicle.pricesValidUntil
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (vencido) "Los precios pudieron cambiar. " else "") +
                            "Precio de " + vehicle.pricesSource.ifBlank { "referencia" } +
                            (RouteCoherence.dayLabel(vehicle.pricesValidFrom, 1).takeIf { it.isNotBlank() }?.let { ", desde el $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vencido) StatusRestriction else VolcanicGray,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshPrices, enabled = !fuelUpdating) {
                        if (fuelUpdating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Actualizar", maxLines = 1)
                    }
                }
            } else if (cost != null && cost.faresUsd > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pasajes estimados: $" + TripCostEstimator.fmtUsd(cost.faresUsd / cost.travelers.coerceAtLeast(1)) + " por persona en todo el viaje",
                    style = MaterialTheme.typography.labelMedium, color = GalapagosTeal, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun OfflineMapRow(
    progress: Float?, running: Boolean, tilesDone: Int, tilesTotal: Int, error: String?, enabled: Boolean, onDownload: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (progress == 1f) Icons.Default.CloudDone else Icons.Default.CloudDownload, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Mapa sin señal", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                when {
                    running -> {
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = AndeanBlue)
                        Text("$tilesDone/$tilesTotal partes del mapa", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    }
                    error != null -> Text(error, style = MaterialTheme.typography.labelSmall, color = StatusClosed)
                    progress == 1f -> Text("Listo: funciona sin conexión.", style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                    else -> Text("Descárgalo para navegar sin conexión.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
            }
            if (!running) {
                TextButton(onClick = onDownload, enabled = enabled) { Text(if (progress == 1f) "Actualizar" else "Descargar", maxLines = 1) }
            }
        }
    }
}

// =====================================================================================
//  Línea de tiempo
// =====================================================================================

private val TIME_COL = 50.dp
private val LINE_COL = 34.dp
private val DOT_CENTER = 20.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHeader(
    day: Int,
    dateLabel: String,
    plan: RouteSchedule.DayPlan?,
    stopsCount: Int,
    entriesUsd: Double,
    transportUsd: Double,
    onAdd: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = BrandFill, shape = CircleShape, modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$day", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Día $day" + (if (dateLabel.isNotBlank()) " · $dateLabel" else ""),
                    style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold
                )
                if (plan != null) {
                    val hasta = plan.finishLeg?.toName ?: plan.overnight ?: plan.stops.lastOrNull()?.stop?.placeName
                    if (!hasta.isNullOrBlank() && plan.startPlace.isNotBlank() && hasta != plan.startPlace) {
                        Text(
                            plan.startPlace + " → " + hasta,
                            style = MaterialTheme.typography.labelMedium, color = VolcanicGray, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            FilledTonalIconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = "Añadir parada al día $day", modifier = Modifier.size(18.dp))
            }
        }
        if (plan != null) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(Icons.Default.Schedule, plan.start + " – " + plan.end)
                if (plan.travelMinutes > 0) Pill(Icons.Default.Route, RouteSchedule.durationLabel(plan.travelMinutes) + " · " + plan.distanceKm.toInt() + " km")
                Pill(Icons.Default.Place, "$stopsCount " + if (stopsCount == 1) "parada" else "paradas")
                if (entriesUsd > 0) Pill(Icons.Default.ConfirmationNumber, "$" + TripCostEstimator.fmtUsd(entriesUsd) + "/pers.")
                if (transportUsd > 0) Pill(Icons.Default.LocalGasStation, "$" + TripCostEstimator.fmtUsd(transportUsd) + " transporte")
            }
            when {
                plan.isHeavyTravel -> DayWarning("Mucho tiempo en camino (${RouteSchedule.durationLabel(plan.travelMinutes)}). Parte el trayecto con una noche intermedia.", serious = true)
                plan.isLongDay -> DayWarning("Jornada de ${RouteSchedule.durationLabel(plan.endMinutes - plan.startMinutes)}. Mueve alguna parada a otro día.", serious = false)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String) {
    Surface(color = SurfaceContainerHigh, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DayWarning(text: String, serious: Boolean) {
    val c = if (serious) StatusClosed else StatusRestriction
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = c, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = c)
    }
}

@Composable
private fun EmptyDayCard(afterTrip: Boolean, onAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(start = TIME_COL)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.EventAvailable, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (afterTrip) "Día sin plan" else "Día libre", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                Text(
                    if (afterTrip) "El recorrido ya terminó antes de este día. Añade paradas o baja los días de la ruta."
                    else "Sin paradas. Descansa o añade algo cerca de donde duermes.",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
            }
            TextButton(onClick = onAdd) { Text("Añadir", maxLines = 1) }
        }
    }
}

/**
 * Un punto de la línea de tiempo: hora a la izquierda, círculo con icono sobre la línea vertical
 * y el contenido a la derecha. [lineTop]/[lineBottom] dicen si la línea entra y sale del punto.
 */
@Composable
private fun TimelineNode(
    time: String,
    lineTop: Boolean,
    lineBottom: Boolean,
    dotColor: Color,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val lineColor = AndeanBlue.copy(alpha = 0.25f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            time,
            style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.width(TIME_COL).padding(top = 11.dp, end = 4.dp)
        )
        Box(
            Modifier.width(LINE_COL).fillMaxHeight().drawBehind {
                val x = size.width / 2f
                val cy = DOT_CENTER.toPx()
                val w = 3.dp.toPx()
                if (lineTop) drawLine(lineColor, Offset(x, 0f), Offset(x, cy), strokeWidth = w)
                if (lineBottom) drawLine(lineColor, Offset(x, cy), Offset(x, size.height), strokeWidth = w)
            }
        ) {
            Surface(
                color = dotColor, shape = CircleShape,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = DOT_CENTER - 13.dp).size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, top = 6.dp, bottom = 10.dp)) { content() }
    }
}

/**
 * Tramo entre dos puntos: línea punteada con el transporte, el tiempo, los km y lo que cuesta
 * (gasolina en carro propio o pasaje en bus/taxi). Tocarlo permite cambiar el transporte solo de ese tramo.
 */
@Composable
private fun LegConnector(
    transport: TransportMode,
    minutes: Int,
    km: Double,
    legCost: TripCostEstimator.LegCost,
    lang: AppLanguage,
    onTransportChange: ((TransportMode?) -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    val lineColor = AndeanBlue.copy(alpha = 0.25f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Spacer(Modifier.width(TIME_COL))
        Box(
            Modifier.width(LINE_COL).fillMaxHeight().drawBehind {
                val x = size.width / 2f
                drawLine(
                    lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 9f))
                )
            }
        )
        Box(Modifier.weight(1f).padding(start = 6.dp, top = 2.dp, bottom = 8.dp)) {
            Surface(
                color = GalapagosTeal.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = if (onTransportChange != null) Modifier.clickable { menu = true } else Modifier
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(transportIcon(transport), contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            t(lang, transport.displayName, transport.displayNameEn) + " · " + RouteSchedule.durationLabel(minutes) +
                                (if (km >= 1) " · " + km.toInt() + " km" else ""),
                            style = MaterialTheme.typography.labelMedium, color = GalapagosTeal, fontWeight = FontWeight.Bold
                        )
                        val gasto = when {
                            legCost.fuelUsd > 0 -> "Gasolina ≈ $" + TripCostEstimator.fmtUsd(legCost.fuelUsd)
                            legCost.faresUsd > 0 -> "Pasajes ≈ $" + TripCostEstimator.fmtUsd(legCost.faresUsd)
                            else -> ""
                        }
                        if (gasto.isNotBlank() || onTransportChange != null) {
                            Text(
                                listOf(gasto, if (onTransportChange != null) "toca para cambiar el transporte" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                    }
                }
            }
            if (onTransportChange != null) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Usar el transporte del viaje") }, onClick = { menu = false; onTransportChange(null) })
                    TransportMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(t(lang, m.displayName, m.displayNameEn)) },
                            leadingIcon = { Icon(transportIcon(m), contentDescription = null, tint = AndeanBlue) },
                            onClick = { menu = false; onTransportChange(m) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StopNode(
    stop: RouteStop, place: Place?,
    scheduled: RouteSchedule.ScheduledStop?,
    isFirst: Boolean, isLast: Boolean, lineTop: Boolean, totalDays: Int,
    /** Salida o llegada: no es una visita, es un extremo del viaje. */
    isEndpoint: Boolean = false,
    endpointLabel: String = "",
    onOpen: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onMoveToDay: (Int) -> Unit,
    onRemove: () -> Unit, onToggleDone: () -> Unit, onEdit: () -> Unit, onNavigate: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var verNotas by remember { mutableStateOf(false) }
    val color = place?.let { categoryColor(it.category) } ?: AndeanBlue

    TimelineNode(
        time = scheduled?.arrival ?: stop.plannedTime.ifBlank { "--:--" },
        lineTop = lineTop,
        lineBottom = true,
        dotColor = if (stop.isDone) StatusNormal else color,
        icon = if (stop.isDone) Icons.Default.Check else (place?.let { categoryIcon(it.category) } ?: Icons.Default.Place)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().alpha(if (stop.isDone) 0.7f else 1f)
        ) {
            Column(Modifier.clickable { onOpen() }.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
                Text(
                    if (isEndpoint) "$endpointLabel ${stop.placeName}" else stop.placeName,
                    style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold,
                    textDecoration = if (stop.isDone && !isEndpoint) TextDecoration.LineThrough else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
                // Lo más importante, en una línea legible: cuánto te quedas y a qué hora sigues.
                Text(
                    when {
                        isEndpoint && endpointLabel.startsWith("Base") -> "Sales y vuelves aquí · base del viaje"
                        isEndpoint && endpointLabel.startsWith("Sales") -> "Punto de partida del viaje"
                        isEndpoint && endpointLabel.startsWith("Vuelves") -> "Fin del viaje · regreso a casa"
                        isEndpoint -> "Fin del viaje"
                        scheduled != null -> "Visita de ${scheduled.visitLabel} · sales a las ${scheduled.departure}"
                        else -> "Visita de ${RouteSchedule.durationLabel(stop.durationMinutes)}"
                    },
                    style = MaterialTheme.typography.labelMedium, color = GalapagosTeal, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
                val datos = listOfNotNull(
                    if (stop.estimatedCostUsd > 0) "Entrada ≈ $" + Place.formatUsd(stop.estimatedCostUsd) + " por persona" else "Gratis",
                    place?.openingHours?.takeIf { it.isNotBlank() }?.let { "Horario: " + it.take(40) },
                    if (stop.timeLocked && stop.plannedTime.isNotBlank()) "hora fija ${stop.plannedTime}" else null
                )
                Text(datos.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = VolcanicGray, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                if (stop.notes.isNotBlank()) {
                    Text(
                        stop.notes,
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray,
                        maxLines = if (verNotas) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, end = 8.dp)
                    )
                    if (stop.notes.length > 90) {
                        Text(
                            if (verNotas) "ver menos" else "ver más",
                            style = MaterialTheme.typography.labelSmall, color = GalapagosTeal, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { verNotas = !verNotas }.padding(vertical = 4.dp)
                        )
                    }
                }
                // Acciones: los extremos solo se navegan; no se mueven, no se quitan y no se
                // marcan como "visitados", porque no son visitas.
                if (isEndpoint) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onNavigate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp)); Text("Cómo llegar", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = if (isFirst) SurfaceContainerHighest else AndeanBlue)
                        }
                        IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = if (isLast) SurfaceContainerHighest else AndeanBlue)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onNavigate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp)); Text("Ir", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = onToggleDone, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(if (stop.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (stop.isDone) StatusNormal else AndeanBlue)
                            Spacer(Modifier.width(3.dp)); Text(if (stop.isDone) "Hecho" else "Visitado", style = MaterialTheme.typography.labelMedium)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones", tint = VolcanicGray) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Editar hora, duración, costo y notas") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }, onClick = { menu = false; onEdit() })
                                if (totalDays > 1) {
                                    HorizontalDivider()
                                    (1..totalDays).filter { it != stop.dayNumber }.forEach { d ->
                                        DropdownMenuItem(text = { Text("Mover al día $d") }, leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) }, onClick = { menu = false; onMoveToDay(d) })
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Quitar de la ruta", color = StatusClosed) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusClosed) }, onClick = { menu = false; onRemove() })
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Diálogos
// =====================================================================================

@Composable
private fun RoutesListDialog(
    routes: List<TravelRoute>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    // Borrar una ruta no se puede deshacer: se pide confirmación en la misma fila.
    var borrando by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mis rutas", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (routes.isEmpty()) Text("No hay rutas todavía.", color = VolcanicGray)
                routes.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(r.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (r.isActive) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = AndeanBlue, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${r.totalDays} día(s) · ${r.travelStyle} · ${r.transportMode.shortName}", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        }
                        if (borrando == r.id) {
                            TextButton(onClick = { onDelete(r.id); borrando = null }) { Text("Eliminar", color = StatusClosed) }
                        } else {
                            IconButton(onClick = { borrando = r.id }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = StatusClosed) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("Nueva ruta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun RouteEditorDialog(
    initial: TravelRoute?,
    onDismiss: () -> Unit,
    onSave: (name: String, origin: String, dest: String, days: Int, travelers: Int, style: String, date: String, transport: TransportMode) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var origin by remember { mutableStateOf(initial?.originName ?: "") }
    var dest by remember { mutableStateOf(initial?.destinationName ?: "") }
    var days by remember { mutableIntStateOf(initial?.totalDays ?: 3) }
    var travelers by remember { mutableIntStateOf(initial?.travelers ?: 2) }
    var style by remember { mutableStateOf(initial?.travelStyle ?: "Moderado") }
    var date by remember { mutableStateOf(RouteCoherence.cleanDate(initial?.startDate)) }
    var transport by remember { mutableStateOf(initial?.transportMode ?: TransportMode.BUS) }
    val fechaMala = date.isNotBlank() && !RouteCoherence.isValidDate(date)
    val idaYVuelta = origin.isNotBlank() && origin.trim().equals(dest.trim(), ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva ruta" else "Editar ruta", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LugarAutocompleteField(value = origin, onValueChange = { origin = it }, label = "Sales desde", placeholder = "Ej. Santo Domingo", modifier = Modifier.fillMaxWidth())
                LugarAutocompleteField(value = dest, onValueChange = { dest = it }, label = "Hasta (o el mismo lugar si vuelves)", placeholder = "Ej. Limoncocha", modifier = Modifier.fillMaxWidth())
                if (origin.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = idaYVuelta, onCheckedChange = { marcado -> dest = if (marcado) origin else "" })
                        Text("Ida y vuelta (termino donde empecé)", style = MaterialTheme.typography.bodySmall, color = AndeanBlue)
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Nombre (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = date, onValueChange = { date = it.take(10) },
                    label = { Text("Fecha de salida (opcional)") }, placeholder = { Text("2026-11-15") },
                    isError = fechaMala,
                    supportingText = { Text(if (fechaMala) "Escribe año-mes-día, por ejemplo 2026-11-15" else "Formato año-mes-día. Déjala vacía si aún no sabes.") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Días", color = VolcanicGray, modifier = Modifier.width(70.dp)); Counter(days, 1, 30) { days = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Viajeros", color = VolcanicGray, modifier = Modifier.width(70.dp)); Counter(travelers, 1, 20) { travelers = it }
                }
                Text("¿Cómo viajas?", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TransportMode.entries.toList()) { m ->
                        FilterChip(
                            selected = transport == m, onClick = { transport = m },
                            label = { Text(m.displayName) },
                            leadingIcon = { Icon(transportIcon(m), contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandFill, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White)
                        )
                    }
                }
                Text("Estilo", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("Mochilero", "Moderado", "Aventura", "Familiar", "Lujo")) { s ->
                        FilterChip(selected = style == s, onClick = { style = s }, label = { Text(s) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandFill, selectedLabelColor = Color.White))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), origin.trim(), dest.trim(), days, travelers, style, date.trim(), transport) },
                enabled = !fechaMala,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun Counter(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, contentDescription = "Menos") }
        Text("$value", color = AndeanBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
        FilledTonalIconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, contentDescription = "Más") }
    }
}

@Composable
private fun StopEditorDialog(stop: RouteStop, onDismiss: () -> Unit, onSave: (RouteStop) -> Unit) {
    var time by remember { mutableStateOf(if (stop.timeLocked) stop.plannedTime else "") }
    var duration by remember { mutableStateOf(stop.durationMinutes.toString()) }
    var cost by remember { mutableStateOf(if (stop.estimatedCostUsd > 0) Place.formatUsd(stop.estimatedCostUsd) else "") }
    var notes by remember { mutableStateOf(stop.notes) }
    val horaMala = time.isNotBlank() && !RouteSchedule.isValidHhmm(time)
    val costoMalo = cost.isNotBlank() && cost.replace(",", ".").toDoubleOrNull()?.let { it < 0 || it > 5000 } != false
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stop.placeName, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = duration, onValueChange = { duration = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("¿Cuánto te quedas? (minutos)") },
                    supportingText = { Text("Mirador 30 · almuerzo 60 · cascada 120-180 · tour 240+") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cost, onValueChange = { cost = it.take(8) },
                    label = { Text("Entrada o gasto por persona (USD)") }, isError = costoMalo,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = time, onValueChange = { time = it.take(5) },
                    label = { Text("Hora fija de llegada (opcional)") }, placeholder = { Text("09:30") },
                    isError = horaMala,
                    supportingText = {
                        Text(if (horaMala) "Escribe la hora así: 09:30" else "Solo si hay una hora obligatoria (ferry, tour reservado). Vacía = se calcula sola.")
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(600) }, label = { Text("Notas (reservas, contacto, recordatorios)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !horaMala && !costoMalo,
                onClick = {
                    val horaEscrita = time.trim()
                    onSave(
                        stop.copy(
                            plannedTime = horaEscrita,
                            // Escrita a mano aquí: manda de verdad. Vacía: se calcula según el transporte.
                            timeLocked = horaEscrita.isNotBlank(),
                            durationMinutes = (duration.toIntOrNull() ?: stop.durationMinutes).coerceIn(5, 720),
                            estimatedCostUsd = cost.replace(",", ".").toDoubleOrNull()?.coerceIn(0.0, 5000.0) ?: 0.0,
                            notes = notes.trim()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Añadir una parada sin salir de la ruta: se escribe el nombre y el buscador propone
 * lugares del catálogo, provincias, cantones y direcciones ya conocidas.
 */
@Composable
private fun AddStopDialog(day: Int, maxDays: Int, onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var texto by remember { mutableStateOf("") }
    var diaElegido by remember { mutableIntStateOf(day.coerceIn(1, maxOf(1, maxDays))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir parada", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LugarAutocompleteField(
                    value = texto, onValueChange = { texto = it }, label = "Lugar", placeholder = "Ej. Quilotoa, Mindo, Cuenca…",
                    leadingIcon = { Icon(Icons.Default.Place, contentDescription = null, tint = EquatorialGold) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("¿En qué día?", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..maxOf(1, maxDays)).toList()) { d ->
                        FilterChip(
                            selected = diaElegido == d, onClick = { diaElegido = d }, label = { Text("Día $d") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandFill, selectedLabelColor = Color.White)
                        )
                    }
                }
                Text("Si el lugar no está en el catálogo lo busco en el mapa y lo guardo con sus coordenadas.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(texto, diaElegido) }, enabled = texto.trim().length >= 3, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun StartTimeDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var hora by remember { mutableStateOf(initial) }
    val mala = !RouteSchedule.isValidHhmm(hora)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿A qué hora sales cada día?", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                FlowRowHoras(onPick = { hora = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hora, onValueChange = { hora = it.take(5) },
                    label = { Text("Hora de salida") }, placeholder = { Text("07:30") },
                    isError = mala && hora.isNotBlank(),
                    supportingText = { Text(if (mala) "Escribe la hora así: 07:30" else "En la sierra conviene salir temprano: los volcanes se despejan entre 6 y 9 de la mañana.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(hora.trim()) }, enabled = !mala, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowHoras(onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("05:30", "06:30", "07:00", "07:30", "08:00", "09:00").forEach { h ->
            SuggestionChip(onClick = { onPick(h) }, label = { Text(h) })
        }
    }
}

/**
 * El vehículo del viajero: tipo, combustible, rendimiento, extra por montaña y peaje.
 * Con esto se calcula cuánto cuesta la gasolina de la ruta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleDialog(
    vehicle: VehicleProfile,
    fuelUpdating: Boolean,
    onRefreshPrices: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (VehicleProfile) -> Unit
) {
    var tipo by remember { mutableStateOf(vehicle.type) }
    var combustible by remember { mutableStateOf(vehicle.fuel) }
    var rendimiento by remember { mutableStateOf(TripCostEstimator.fmt1(vehicle.kmPerUnit).replace(",", ".")) }
    var extra by remember { mutableIntStateOf(vehicle.extraConsumptionPct) }
    var peaje by remember { mutableStateOf(TripCostEstimator.fmtUsd(vehicle.tollUsd).replace(",", ".")) }
    var precio by remember { mutableStateOf(TripCostEstimator.fmtUsd(vehicle.prices[vehicle.fuel] ?: vehicle.fuel.defaultPrice).replace(",", ".")) }

    // Si llegan precios nuevos de internet mientras el diálogo está abierto, se reflejan.
    LaunchedEffect(vehicle.prices, vehicle.pricesValidFrom, combustible) {
        precio = TripCostEstimator.fmtUsd(vehicle.prices[combustible] ?: combustible.defaultPrice).replace(",", ".")
    }

    val rendimientoNum = rendimiento.replace(",", ".").toDoubleOrNull()
    val precioNum = precio.replace(",", ".").toDoubleOrNull()
    val peajeNum = peaje.replace(",", ".").toDoubleOrNull()
    val rendimientoMalo = rendimientoNum == null || rendimientoNum !in 1.0..400.0
    val precioMalo = precioNum == null || precioNum !in combustible.validRange
    val peajeMalo = peajeNum == null || peajeNum !in 0.0..20.0
    val unidad = combustible.unit

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tu vehículo", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tipo", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                VehicleType.entries.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            tipo = v
                            combustible = v.defaultFuel
                            rendimiento = TripCostEstimator.fmt1(v.kmPerUnit).replace(",", ".")
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = tipo == v, onClick = {
                            tipo = v; combustible = v.defaultFuel; rendimiento = TripCostEstimator.fmt1(v.kmPerUnit).replace(",", ".")
                        })
                        Column(Modifier.weight(1f)) {
                            Text(v.displayName, style = MaterialTheme.typography.bodyMedium, color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                            Text(v.examples + " · ≈ " + v.kmPerUnit.toInt() + " km/" + v.defaultFuel.unit, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        }
                    }
                }
                Text("Combustible", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FuelType.entries.forEach { f ->
                        FilterChip(
                            selected = combustible == f, onClick = { combustible = f },
                            label = { Text(f.displayName) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandFill, selectedLabelColor = Color.White)
                        )
                    }
                }
                OutlinedTextField(
                    value = rendimiento, onValueChange = { rendimiento = it.take(6) },
                    label = { Text("Rendimiento (km por $unidad)") }, isError = rendimientoMalo,
                    supportingText = {
                        Text(
                            if (rendimientoMalo) "Escribe un número, por ejemplo 40"
                            else if (combustible != FuelType.ELECTRIC && rendimientoNum != null) "≈ " + TripCostEstimator.fmt1(100.0 / (rendimientoNum / 3.785)) + " L/100 km. Si no lo sabes, deja el sugerido."
                            else "Si no lo sabes, deja el sugerido."
                        )
                    },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = precio, onValueChange = { precio = it.take(6) },
                    label = { Text("Precio del $unidad de ${combustible.displayName} (USD)") }, isError = precioMalo,
                    supportingText = {
                        Text(
                            "Fuente: " + vehicle.pricesSource.ifBlank { "—" } +
                                (RouteCoherence.dayLabel(vehicle.pricesValidFrom, 1).takeIf { it.isNotBlank() }?.let { " · desde el $it" } ?: "") +
                                (if (combustible == FuelType.SUPER) ". La Súper es precio libre: cada gasolinera pone el suyo." else "")
                        )
                    },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onRefreshPrices, enabled = !fuelUpdating) {
                    if (fuelUpdating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Buscar el precio vigente en internet")
                }
                Text("Montaña, aire acondicionado y carga", style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to "Plano, carro liviano", 10 to "Normal (+10 %)", 20 to "Mucha montaña o carga (+20 %)").forEach { (pct, txt) ->
                        FilterChip(
                            selected = extra == pct, onClick = { extra = pct }, label = { Text(txt) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandFill, selectedLabelColor = Color.White)
                        )
                    }
                }
                OutlinedTextField(
                    value = peaje, onValueChange = { peaje = it.take(5) },
                    label = { Text("Valor de cada peaje (USD)") }, isError = peajeMalo,
                    supportingText = { Text("Vehículo liviano ≈ $1. Se estima un peaje cada ~90 km de vía principal.") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !rendimientoMalo && !precioMalo && !peajeMalo,
                onClick = {
                    val nuevoPrecio = precioNum ?: combustible.defaultPrice
                    val original = vehicle.prices[combustible] ?: combustible.defaultPrice
                    val cambioPrecio = kotlin.math.abs(nuevoPrecio - original) > 0.004
                    onSave(
                        vehicle.copy(
                            type = tipo,
                            fuel = combustible,
                            kmPerUnit = rendimientoNum ?: tipo.kmPerUnit,
                            extraConsumptionPct = extra,
                            tollUsd = peajeNum ?: 1.0,
                            prices = vehicle.prices + (combustible to nuevoPrecio),
                            pricesValidFrom = if (cambioPrecio) RouteCoherence.today() else vehicle.pricesValidFrom,
                            pricesValidUntil = if (cambioPrecio) "" else vehicle.pricesValidUntil,
                            pricesSource = if (cambioPrecio) "escrito por ti" else vehicle.pricesSource
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

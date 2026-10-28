package ec.travesias.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.local.TemaApp
import ec.travesias.app.data.model.RoutePlanUiState
import ec.travesias.app.data.model.TripIntent
import ec.travesias.app.data.model.journeyStops
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.ui.auth.BrandMark
import ec.travesias.app.ui.auth.FondoTravesias
import ec.travesias.app.ui.auth.PanelVidrio
import ec.travesias.app.ui.components.LugarAutocompleteField
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.AndesGreen
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.BrandYellow
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.GlassChip
import ec.travesias.app.ui.theme.HairLine
import ec.travesias.app.ui.theme.OnBrand
import ec.travesias.app.ui.theme.SkyAccent
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.VolcanicGray
import ec.travesias.app.viewmodel.RouteViewModel
import kotlinx.coroutines.delay

/**
 * Portada de la app: UNA sola pantalla y UNA sola pregunta.
 *
 * QUÉ CAMBIÓ (oct-2026)
 * La versión anterior era larga: formulario + "Así funciona" + "Más herramientas" + cuenta.
 * Ahora la portada es corta y directa:
 *
 *   1. ¿A dónde vas?  (destino obligatorio, salida opcional)
 *   2. Días y viajeros en una fila compacta.
 *   3. Botón grande "Armar mi ruta con IA".
 *   4. Si ya hay viaje, una tarjeta corta con la siguiente parada.
 *   5. Una fila de accesos rápidos (lo demás vive dentro de la app).
 *
 * Si el viajero deja "Sales desde" vacío, NO se inventa una ruta: se busca alrededor del
 * destino. Esa explicación va en una sola línea, no en un párrafo.
 */
@Composable
fun HomeHubScreen(
    espacioAbajo: Dp = 0.dp,
    onOpenDiscover: () -> Unit,
    onOpenRoute: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenKit: () -> Unit,
    routeVm: RouteViewModel
) {
    val lang by AppSettings.language.collectAsState()
    val ruta by routeVm.activeRoute.collectAsState()
    val intent by routeVm.tripIntent.collectAsState()
    val planState by routeVm.planState.collectAsState()
    val draft by routeVm.routeDraft.collectAsState()
    val currentPlace by routeVm.currentPlaceName.collectAsState()

    var destination by rememberSaveable { mutableStateOf(intent.destination) }
    var origin by rememberSaveable { mutableStateOf(intent.origin) }
    var days by rememberSaveable { mutableIntStateOf(intent.days) }
    var travelers by rememberSaveable { mutableIntStateOf(intent.travelers) }
    var esperandoUbicacion by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            esperandoUbicacion = true
            routeVm.useCurrentPlaceAsOrigin()
        }
    }
    val pedirUbicacion = {
        if (DeviceLocation.hasPermission(context)) {
            esperandoUbicacion = true
            routeVm.useCurrentPlaceAsOrigin()
        } else {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(intent.origin) {
        if (esperandoUbicacion && intent.origin.isNotBlank()) {
            origin = intent.origin
            esperandoUbicacion = false
        } else if (origin.isBlank() && intent.origin.isNotBlank()) {
            origin = intent.origin
        }
    }
    LaunchedEffect(intent.destination) {
        if (destination.isBlank() && intent.destination.isNotBlank()) destination = intent.destination
    }
    // Al volver a la portada desde otra pantalla (por ejemplo "Qué visitar en Loja"), la
    // pregunta se sincroniza con lo último que hizo la app. No se usa como efecto continuo
    // para no borrar lo que el viajero está escribiendo.
    LaunchedEffect(Unit) {
        destination = intent.destination
        origin = intent.origin
        days = intent.days
        travelers = intent.travelers
    }
    LaunchedEffect(esperandoUbicacion) {
        if (esperandoUbicacion) {
            delay(7000)
            esperandoUbicacion = false
        }
    }
    LaunchedEffect(Unit) { routeVm.detectCurrentPlace() }
    // Recuerda lo último escrito sin tocar el disco en cada tecla.
    LaunchedEffect(origin, destination, days, travelers) {
        if (destination.isNotBlank()) {
            delay(1200)
            AppSettings.setLastTrip(TripIntent(origin, destination, days, travelers))
        }
    }

    val trip = TripIntent(origin, destination, days, travelers)
    val planLoading = planState as? RoutePlanUiState.Loading
    val planError = planState as? RoutePlanUiState.Error

    Box(Modifier.fillMaxSize()) {
        FondoTravesias(Modifier.fillMaxSize(), conMarca = false)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BotonTema()
                Spacer(Modifier.width(8.dp))
                SelectorIdioma(lang)
            }

            BrandMark(escala = 0.42f)
            Spacer(Modifier.height(8.dp))

            // -------------------------------------------------- La pregunta
            PanelVidrio(opaco = true, relleno = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(EquatorialGold.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t(lang, "¿A dónde vas?", "Where are you going?"),
                            color = AndeanBlue,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            t(lang, "La IA arma; tú eliges antes de guardar.", "The AI builds; you choose before saving."),
                            color = VolcanicGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                LugarAutocompleteField(
                    value = destination,
                    onValueChange = {
                        destination = it
                        routeVm.updateTripIntent(trip.copy(destination = it), persist = false)
                    },
                    label = t(lang, "¿A dónde vas?", "Where to?"),
                    placeholder = t(lang, "Ej. Loja, Baños, Cuenca…", "E.g. Loja, Baños, Cuenca…"),
                    leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null, tint = StatusClosed) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LugarAutocompleteField(
                    value = origin,
                    onValueChange = {
                        origin = it
                        routeVm.updateTripIntent(trip.copy(origin = it), persist = false)
                    },
                    label = t(lang, "Sales desde (opcional)", "Leaving from (optional)"),
                    placeholder = t(lang, "Ej. Santo Domingo", "E.g. Santo Domingo"),
                    leadingIcon = { Icon(Icons.Default.TripOrigin, contentDescription = null, tint = AndesGreen) },
                    trailingIcon = {
                        IconButton(onClick = pedirUbicacion) {
                            if (esperandoUbicacion) CircularProgressIndicator(strokeWidth = 2.dp, color = AndeanBlue, modifier = Modifier.size(18.dp))
                            else Icon(Icons.Default.MyLocation, contentDescription = t(lang, "Usar mi ubicación", "Use my location"), tint = AndeanBlue)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        t(
                            lang,
                            "Sin salida, buscamos solo alrededor del destino.",
                            "Without an origin, we search only around the destination."
                        ),
                        color = VolcanicGray,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (origin.isBlank() && !currentPlace.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(AndesGreen.copy(alpha = 0.12f))
                            .clickable {
                                val nuevo = currentPlace.orEmpty()
                                origin = nuevo
                                routeVm.updateTripIntent(trip.copy(origin = nuevo), persist = false)
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = AndesGreen, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            t(lang, "Estás en $currentPlace", "You're in $currentPlace"),
                            color = AndesGreen,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t(lang, "Días", "Days"),
                        color = AndeanBlue,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(5.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        items(listOf(1, 2, 3, 4, 5, 7)) { d ->
                            FilterChip(
                                selected = days == d,
                                onClick = {
                                    days = d
                                    routeVm.updateTripIntent(trip.copy(days = d), persist = false)
                                },
                                label = { Text("$d", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BrandFill,
                                    selectedLabelColor = OnBrand
                                ),
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Groups, contentDescription = t(lang, "Viajeros", "Travelers"), tint = AndeanBlue, modifier = Modifier.size(15.dp))
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).clickable {
                            if (travelers > 1) {
                                travelers--
                                routeVm.updateTripIntent(trip.copy(travelers = travelers), persist = false)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = t(lang, "Menos", "Fewer"), tint = AndeanBlue, modifier = Modifier.size(17.dp))
                    }
                    Text("$travelers", color = AndeanBlue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).clickable {
                            if (travelers < 20) {
                                travelers++
                                routeVm.updateTripIntent(trip.copy(travelers = travelers), persist = false)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = t(lang, "Más", "More"), tint = AndeanBlue, modifier = Modifier.size(17.dp))
                    }
                }

                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        routeVm.requestPlanTab()
                        routeVm.startTripPlan(trip)
                        onOpenDiscover()
                    },
                    enabled = destination.isNotBlank() && planLoading == null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandFill, disabledContainerColor = BrandFill.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BrandYellow, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (planLoading != null) t(lang, "Armando tu viaje…", "Building your trip…")
                        else t(lang, "Armar mi ruta con IA", "Build my route with AI"),
                        color = OnBrand,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(
                    onClick = {
                        routeVm.startDestinationDiscovery(trip)
                        onOpenDiscover()
                    },
                    enabled = destination.isNotBlank() && planLoading == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        t(lang, "Solo ver lugares", "Just see places"),
                        color = GalapagosTeal,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // -------------------------------------------------- Plan en curso / listo / error
            if (planLoading != null) {
                Spacer(Modifier.height(9.dp))
                PanelVidrio {
                    EstadoRow(
                        icon = Icons.Default.AutoAwesome,
                        tint = EquatorialGold,
                        title = t(lang, "La IA está armando tu viaje", "The AI is building your trip"),
                        detail = planLoading.message
                    )
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = EquatorialGold,
                        trackColor = HairLine
                    )
                }
            } else if (planError != null) {
                Spacer(Modifier.height(9.dp))
                PanelVidrio {
                    EstadoRow(
                        icon = Icons.Default.ErrorOutline,
                        tint = StatusClosed,
                        title = t(lang, "No se pudo armar el viaje", "Could not build the trip"),
                        detail = planError.message
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { routeVm.startTripPlan(trip); onOpenDiscover() }) {
                            Text(t(lang, "Reintentar", "Try again"), color = AndeanBlue, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { routeVm.discardDraft() }) {
                            Text(t(lang, "Cerrar", "Close"), color = VolcanicGray)
                        }
                    }
                }
            } else if (draft != null) {
                Spacer(Modifier.height(9.dp))
                PanelVidrio(opaco = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AndesGreen, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t(lang, "Tu itinerario está listo", "Your itinerary is ready"),
                                color = AndeanBlue, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${draft?.discoveredCount ?: 0} " + t(lang, "paradas y sugerencias para elegir", "stops and suggestions to choose"),
                                color = VolcanicGray, style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = onOpenDiscover) { Text(t(lang, "Revisar", "Review"), color = GalapagosTeal, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // -------------------------------------------------- Viaje en curso (compacto)
            ruta?.let { r ->
                val visitas = r.journeyStops()
                val hechas = visitas.count { it.isDone }
                val siguiente = visitas.firstOrNull { !it.isDone }
                Spacer(Modifier.height(9.dp))
                PanelVidrio(opaco = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(EquatorialGold.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Route, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t(lang, "Tu viaje", "Your trip"),
                                color = GalapagosTeal, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                            )
                            Text(
                                r.name, color = AndeanBlue, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    siguiente != null -> t(
                                        lang,
                                        "Siguiente: ${siguiente.placeName} · día ${siguiente.dayNumber}",
                                        "Next: ${siguiente.placeName} · day ${siguiente.dayNumber}"
                                    )
                                    visitas.isNotEmpty() -> t(lang, "Todas las paradas completadas", "All stops completed")
                                    else -> t(lang, "Todavía sin paradas", "No stops yet")
                                },
                                color = VolcanicGray, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = onOpenRoute) { Text(t(lang, "Ver", "View"), color = AndeanBlue, fontWeight = FontWeight.Bold) }
                        IconButton(onClick = onOpenMap, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Map, contentDescription = t(lang, "Mapa", "Map"), tint = AndeanBlue, modifier = Modifier.size(19.dp))
                        }
                    }
                    if (visitas.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { hechas.toFloat() / visitas.size },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = AndesGreen, trackColor = HairLine
                        )
                    }
                }
            }

            // -------------------------------------------------- Accesos rápidos (una fila)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(GlassChip)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val accesos = listOf(
                    Acceso(Icons.Default.Explore, AndesGreen, t(lang, "Descubrir", "Discover"), onOpenDiscover),
                    Acceso(Icons.Default.Route, SkyAccent, t(lang, "Mi Ruta", "Route"), onOpenRoute),
                    Acceso(Icons.Default.Map, AndeanBlue, t(lang, "Mapa", "Map"), onOpenMap),
                    Acceso(Icons.Default.Star, EquatorialGold, t(lang, "Guía IA", "AI Guide"), onOpenChat),
                    Acceso(Icons.Default.Groups, SkyAccent, t(lang, "Viajeros", "Travelers"), onOpenCommunity),
                    Acceso(Icons.Default.HealthAndSafety, AndesGreen, t(lang, "Kit", "Kit"), onOpenKit)
                )
                accesos.forEach { acceso -> AccesoMini(acceso, Modifier.weight(1f)) }
            }

            Spacer(Modifier.height(espacioAbajo + 14.dp))
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun EstadoRow(icon: ImageVector, tint: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AndeanBlue, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) {
                Text(detail, color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class Acceso(
    val icono: ImageVector,
    val color: Color,
    val titulo: String,
    val onClick: () -> Unit
)

/** Acceso compacto: círculo + una palabra. Cabe en una sola fila de seis. */
@Composable
private fun AccesoMini(acceso: Acceso, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = acceso.onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(acceso.color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(acceso.icono, contentDescription = null, tint = acceso.color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            acceso.titulo,
            color = AndeanBlue,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Claro / oscuro / como el teléfono, en un solo botón que va rotando.
 * Se guarda en el teléfono y pinta la app entera al instante.
 */
@Composable
fun BotonTema() {
    val tema by AppSettings.theme.collectAsState()
    val lang by AppSettings.language.collectAsState()
    val (icono, etiqueta) = when (tema) {
        TemaApp.SISTEMA -> Icons.Default.BrightnessAuto to t(lang, "Auto", "Auto")
        TemaApp.CLARO -> Icons.Default.LightMode to t(lang, "Claro", "Light")
        TemaApp.OSCURO -> Icons.Default.DarkMode to t(lang, "Oscuro", "Dark")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassChip)
            .clickable {
                AppSettings.setTheme(
                    when (tema) {
                        TemaApp.SISTEMA -> TemaApp.CLARO
                        TemaApp.CLARO -> TemaApp.OSCURO
                        TemaApp.OSCURO -> TemaApp.SISTEMA
                    }
                )
            }
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, contentDescription = etiqueta, tint = AndeanBlue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(etiqueta, color = AndeanBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

/** Español / English, en la propia portada. Se guarda en el teléfono. */
@Composable
fun SelectorIdioma(actual: AppLanguage, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassChip)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLanguage.entries.forEach { idioma ->
            val activo = idioma == actual
            Text(
                "${idioma.flag}  ${idioma.label}",
                color = if (activo) OnBrand else AndeanBlue,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (activo) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (activo) BrandFill else Color.Transparent)
                    .clickable { AppSettings.setLanguage(idioma) }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            )
        }
    }
}

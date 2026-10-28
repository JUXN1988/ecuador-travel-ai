package ec.travesias.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ec.travesias.app.data.model.DraftStop
import ec.travesias.app.data.model.DraftStopKind
import ec.travesias.app.data.model.RouteDraft
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.ui.theme.*
import ec.travesias.app.ui.util.Intents

/**
 * BORRADOR de la ruta que armó la IA, antes de guardarla.
 *
 * Aquí el viajero manda: quita paradas (vuelven a "cerca de tu ruta", no se pierden),
 * las sube y baja, las cambia de día, mete las que quedaron cerca de la vía y ajusta
 * nombre, días, viajeros y transporte. Recién al tocar "Guardar ruta" se crea de verdad.
 */
@Composable
fun RouteDraftSheet(
    draft: RouteDraft,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDaysChange: (Int) -> Unit,
    onTravelersChange: (Int) -> Unit,
    onTransportChange: (TransportMode) -> Unit,
    onRemoveStop: (String) -> Unit,
    onMoveStop: (String, Boolean) -> Unit,
    onStopDayChange: (String, Int) -> Unit,
    onAddNearby: (String, Int) -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceLight, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {

                // ---------- Cabecera ----------
                Surface(color = BrandFill, shadowElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Tu ruta propuesta", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (draft.originName.isBlank())
                                    "Alrededor de " + draft.destinationName + " · sales y vuelves a la base"
                                else draft.originName + " → " + draft.destinationName,
                                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White) }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(Modifier.height(6.dp)) }

                    // ---------- Datos del viaje ----------
                    item { AjustesDelViaje(draft, onRename, onDaysChange, onTravelersChange, onTransportChange) }

                    // Si no hubo salida, se explica de dónde salieron estos lugares: no es una
                    // ruta inventada, es una base con un radio de búsqueda.
                    draft.area?.let { area ->
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = GalapagosTeal.copy(alpha = 0.12f)), shape = RoundedCornerShape(12.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocationSearching, contentDescription = null, tint = GalapagosTeal)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Buscamos en ${area.radiusLabel} a la redonda de ${area.name}", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                                        Text(
                                            "No escribiste de dónde sales, así que la app no inventó una carretera: el viaje empieza y termina en esta base.",
                                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- Resumen de la IA ----------
                    if (draft.summary.isNotBlank() || draft.sources.isNotEmpty()) {
                        item { ResumenDeLaIa(draft, onOpenUrl = { Intents.openUrl(context, it) }) }
                    }

                    if (draft.warning.isNotBlank()) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = StatusRestriction.copy(alpha = 0.12f)), shape = RoundedCornerShape(12.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusRestriction)
                                    Spacer(Modifier.width(8.dp))
                                    Text(draft.warning, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                                }
                            }
                        }
                    }

                    // ---------- Itinerario, día por día ----------
                    for (dia in 1..draft.days) {
                        val delDia = draft.stopsForDay(dia)
                        item(key = "cab_$dia") { CabeceraDeDia(dia, delDia) }
                        if (delDia.isEmpty()) {
                            item(key = "vacio_$dia") {
                                Text(
                                    "Día libre. Baja una parada desde el día anterior o añade una de las de abajo.",
                                    style = MaterialTheme.typography.bodySmall, color = VolcanicGray,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                        items(delDia, key = { it.id }) { parada ->
                            TarjetaDeParada(
                                parada = parada,
                                dias = draft.days,
                                onRemove = { onRemoveStop(parada.id) },
                                onMove = { arriba -> onMoveStop(parada.id, arriba) },
                                onDayChange = { d -> onStopDayChange(parada.id, d) },
                                onOpenUrl = { Intents.openUrl(context, it) }
                            )
                        }
                    }

                    // ---------- Sugerencias de la vía y del destino ----------
                    if (draft.nearby.isNotEmpty()) {
                        item(key = "cerca_cab") {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NearMe, contentDescription = null, tint = GalapagosTeal)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Sugerencias para tu viaje (" + draft.nearby.size + ")", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                                    Text("Lugares reales de la vía y del destino. Añádelos antes de guardar la ruta.", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                                }
                            }
                        }
                        items(draft.nearby, key = { "cerca_" + it.id }) { cercano ->
                            TarjetaCercana(
                                parada = cercano,
                                dias = draft.days,
                                onAdd = { dia -> onAddNearby(cercano.id, dia) }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(20.dp)) }
                }

                // ---------- Barra de guardar ----------
                Surface(color = SurfaceContainerLowest, shadowElevation = 12.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            draft.discoveredCount.toString() + " paradas · " + draft.totalKm.toInt() + " km · " +
                                RouteSchedule.durationLabel(draft.totalTravelMinutes) + " en carretera",
                            style = MaterialTheme.typography.labelMedium, color = VolcanicGray
                        )
                        if (draft.nearby.isNotEmpty()) {
                            Text(
                                draft.nearby.size.toString() + " sugerencias más abajo · puedes añadirlas antes de guardar",
                                style = MaterialTheme.typography.labelSmall, color = GalapagosTeal, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp)) {
                                Text("Descartar", color = VolcanicGray)
                            }
                            Button(
                                onClick = onSave,
                                modifier = Modifier.weight(1.6f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = EquatorialGold)
                                Spacer(Modifier.width(8.dp))
                                Text("Guardar ruta", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================
//  Piezas
// ======================================================================

@Composable
private fun AjustesDelViaje(
    draft: RouteDraft,
    onRename: (String) -> Unit,
    onDaysChange: (Int) -> Unit,
    onTravelersChange: (Int) -> Unit,
    onTransportChange: (TransportMode) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onRename,
                label = { Text("Nombre de la ruta") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Contador("Días", draft.days, Icons.Default.CalendarMonth, onMenos = { onDaysChange(draft.days - 1) }, onMas = { onDaysChange(draft.days + 1) })
                Spacer(Modifier.width(12.dp))
                Contador("Viajeros", draft.travelers, Icons.Default.Group, onMenos = { onTravelersChange(draft.travelers - 1) }, onMas = { onTravelersChange(draft.travelers + 1) })
            }
            Spacer(Modifier.height(10.dp))
            Text("¿Cómo se van a mover?", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TransportMode.entries.toList()) { modo ->
                    FilterChip(
                        selected = draft.transport == modo,
                        onClick = { onTransportChange(modo) },
                        label = { Text(modo.displayName, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AndeanBlue, selectedLabelColor = Color.White)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cambiar el transporte recalcula los tiempos de todos los tramos.",
                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
            )
        }
    }
}

@Composable
private fun Contador(
    etiqueta: String,
    valor: Int,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    onMenos: () -> Unit,
    onMas: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(etiqueta, style = MaterialTheme.typography.labelMedium, color = VolcanicGray)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenos, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Menos", tint = AndeanBlue)
            }
            Text(valor.toString(), style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
            IconButton(onClick = onMas, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = "Más", tint = AndeanBlue)
            }
        }
    }
}

@Composable
private fun ResumenDeLaIa(draft: RouteDraft, onOpenUrl: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.12f)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, contentDescription = null, tint = EquatorialGold)
                Spacer(Modifier.width(6.dp))
                Text("Lo que se dice de esta ruta", style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
            }
            if (draft.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(draft.summary, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
            }
            if (draft.corridorKm > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Trayecto principal: " + draft.corridorKm.toInt() + " km · " + RouteSchedule.durationLabel(draft.corridorMinutes) + " sin contar paradas",
                    style = MaterialTheme.typography.labelMedium, color = AndeanBlue
                )
            }
            if (draft.sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(draft.sources.take(12)) { s ->
                        AssistChip(
                            onClick = { onOpenUrl(s.url) },
                            label = { Text(s.title.ifBlank { s.platform.displayName }.take(26), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(platformIcon(s.platform), contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CabeceraDeDia(dia: Int, paradas: List<DraftStop>) {
    val km = paradas.sumOf { it.legKm }
    val minutos = paradas.sumOf { it.legMinutes }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(BrandFill),
            contentAlignment = Alignment.Center
        ) { Text(dia.toString(), color = EquatorialGold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Día " + dia, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Text(
                paradas.size.toString() + " paradas" + if (km > 0) " · " + km.toInt() + " km · " + RouteSchedule.durationLabel(minutos) else "",
                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
            )
        }
    }
}

@Composable
private fun TarjetaDeParada(
    parada: DraftStop,
    dias: Int,
    onRemove: () -> Unit,
    onMove: (Boolean) -> Unit,
    onDayChange: (Int) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var abierta by remember(parada.id) { mutableStateOf(false) }
    val p: Place = parada.place

    Column {
        // Tramo desde la parada anterior
        if (parada.legMinutes > 0 || parada.legKm > 0.5) {
            Row(Modifier.padding(start = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SouthEast, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    RouteSchedule.durationLabel(parada.legMinutes) + " · " + parada.legKm.toInt() + " km",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = if (parada.isAnchor) SurfaceContainerLow else SurfaceContainerLowest),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                            .background(if (parada.isAnchor) BrandFill else categoryColor(p.category).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (parada.kind) {
                                DraftStopKind.ORIGIN -> Icons.Default.TripOrigin
                                DraftStopKind.DESTINATION -> Icons.Default.Flag
                                else -> categoryIcon(p.category)
                            },
                            contentDescription = null,
                            tint = if (parada.isAnchor) EquatorialGold else categoryColor(p.category),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            p.locationLabel + if (!parada.isAnchor && parada.detourLabel.isNotBlank()) " · " + parada.detourLabel else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (parada.detourKm > 15 && !parada.isAnchor) StatusRestriction else VolcanicGray
                        )
                    }
                    IconButton(onClick = { abierta = !abierta }, modifier = Modifier.size(30.dp)) {
                        Icon(if (abierta) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Detalles", tint = AndeanBlue)
                    }
                }

                if (parada.why.isNotBlank() && !parada.isAnchor) {
                    Spacer(Modifier.height(6.dp))
                    Text(parada.why, style = MaterialTheme.typography.bodySmall, color = VolcanicGray, maxLines = if (abierta) 10 else 2, overflow = TextOverflow.Ellipsis)
                }

                if (abierta) {
                    Spacer(Modifier.height(8.dp))
                    if (p.costMinUsd > 0 || p.costMaxUsd > 0) DetalleLinea(Icons.Default.Payments, p.costLabel)
                    if (p.openingHours.isNotBlank()) DetalleLinea(Icons.Default.Schedule, p.openingHours)
                    if (p.howToGetThere.isNotBlank()) DetalleLinea(Icons.Default.Directions, p.howToGetThere)
                    if (p.sources.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(p.sources.take(6)) { s ->
                                AssistChip(
                                    onClick = { onOpenUrl(s.url) },
                                    label = { Text(s.title.ifBlank { s.platform.displayName }.take(22), style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = { Icon(platformIcon(s.platform), contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }

                if (!parada.isAnchor) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMove(true) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = AndeanBlue)
                        }
                        IconButton(onClick = { onMove(false) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = AndeanBlue)
                        }
                        Spacer(Modifier.width(4.dp))
                        SelectorDeDia(dias = dias, actual = parada.day, etiqueta = "Día " + parada.day, onPick = onDayChange)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRemove) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusClosed)
                            Spacer(Modifier.width(4.dp))
                            Text("Quitar", color = StatusClosed, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaCercana(parada: DraftStop, dias: Int, onAdd: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = GalapagosTeal.copy(alpha = 0.07f)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(parada.place.category), contentDescription = null, tint = categoryColor(parada.place.category), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(parada.place.name, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    parada.place.locationLabel + " · " + parada.detourLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (parada.detourKm > 25) StatusRestriction else VolcanicGray
                )
                if (parada.why.isNotBlank()) {
                    Text(parada.why, style = MaterialTheme.typography.labelSmall, color = VolcanicGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            SelectorDeDia(dias = dias, actual = parada.day, etiqueta = "Añadir", icono = Icons.Default.Add, onPick = onAdd)
        }
    }
}

/** Menú para elegir a qué día va una parada. Se usa para mover y para añadir. */
@Composable
private fun SelectorDeDia(
    dias: Int,
    actual: Int,
    etiqueta: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.CalendarMonth,
    onPick: (Int) -> Unit
) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { if (dias <= 1) onPick(1) else abierto = true },
            label = { Text(etiqueta, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(icono, contentDescription = null, modifier = Modifier.size(14.dp), tint = AndeanBlue) }
        )
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            for (d in 1..dias) {
                DropdownMenuItem(
                    text = { Text(if (d == actual) "Día " + d + " (actual)" else "Día " + d) },
                    onClick = { abierto = false; onPick(d) }
                )
            }
        }
    }
}

@Composable
private fun DetalleLinea(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icono, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(texto, style = MaterialTheme.typography.labelMedium, color = VolcanicGray)
    }
}

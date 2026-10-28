package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.ItineraryPlan
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.TravelViewModel

@Composable
fun TripsScreen(
    viewModel: TravelViewModel,
    onNavigateToPlanner: () -> Unit = {}
) {
    val itinerary by viewModel.currentItinerary.collectAsState()
    val planAlt by viewModel.selectedPlanAlternative.collectAsState()

    var completedActivities by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .background(SurfaceLight)
    ) {
        val plan = itinerary
        if (plan == null) {
            // Vacío de verdad: no se enseña el demo de Quito–Baños.
            Column(
                    modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Map,
                        contentDescription = null,
                        tint = AndeanBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No tienes un itinerario activo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AndeanBlue
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Usa el Planificador IA para generar una ruta personalizada por Ecuador.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToPlanner,
                        colors = ButtonDefaults.buttonColors(containerColor = EquatorialGold)
                    ) {
                        Text("Crear Itinerario con IA", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
        } else {
            val dia = plan.days.firstOrNull()
            Column(modifier = Modifier.fillMaxWidth()) {
                    Text(plan.title, style = MaterialTheme.typography.headlineSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Text(plan.summary, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = StatusNormal)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (dia != null) "${dia.temperatureC}°C • ${dia.weatherCondition}" else plan.targetRegion.displayName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            "Día ${dia?.dayNumber ?: 1} de ${plan.totalDays}",
                            color = VolcanicGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PRESUPUESTO ESTIMADO RESTANTE", style = MaterialTheme.typography.labelSmall, color = VolcanicGray, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = StatusNormal)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(String.format("$%.2f USD", plan.remainingBudgetUsd), style = MaterialTheme.typography.headlineSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    val denom = plan.totalBudgetUsd.takeIf { it > 0.0 } ?: 1.0
                    val progress = (plan.remainingBudgetUsd / denom).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AndeanBlue,
                        trackColor = SurfaceContainerHighest
                    )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                if (dia != null) "CRONOGRAMA · ${dia.title}" else "CRONOGRAMA DEL DÍA",
                style = MaterialTheme.typography.labelSmall,
                color = VolcanicGray,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            val actividades = dia?.activities.orEmpty()
            if (actividades.isEmpty()) {
                Text(
                    "Este itinerario aún no tiene actividades detalladas para el primer día.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VolcanicGray
                )
            } else {
                actividades.forEachIndexed { idx, act ->
                    val isDone = act.isCompleted || completedActivities.contains(act.id)
                    val hasAlt = !act.alternativePlan.isNullOrBlank()
                    if (hasAlt) {
                        TimelineAiAdaptiveNode(
                            time = act.time,
                            titlePlanA = act.title,
                            titlePlanB = act.alternativePlan.orEmpty(),
                            selectedPlan = planAlt,
                            onSelectPlan = { viewModel.setPlanAlternative(it) }
                        )
                    } else if (act.hasWeatherAlert) {
                        TimelineAlertNode(
                            title = act.title,
                            desc = act.description,
                            icon = Icons.Default.Warning,
                            iconBg = StatusRestriction
                        )
                    } else {
                        TimelineItem(
                            time = act.time,
                            title = act.title,
                            desc = act.description.ifBlank { act.location },
                            icon = if (isDone) Icons.Default.Check else Icons.Default.LocationOn,
                            iconBg = when {
                                isDone -> StatusNormal
                                idx == 0 -> EquatorialGold
                                else -> VolcanicGray.copy(alpha = 0.5f)
                            },
                            isNext = !isDone && idx == actividades.indexOfFirst { !it.isCompleted && !completedActivities.contains(it.id) },
                            isCompleted = isDone,
                            onToggleComplete = {
                                completedActivities = if (isDone) completedActivities - act.id else completedActivities + act.id
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun TimelineItem(
    time: String,
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    isNext: Boolean = false,
    isCompleted: Boolean = false,
    onToggleComplete: () -> Unit
) {
    Row(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBg, RoundedCornerShape(18.dp))
                .clickable { onToggleComplete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { onToggleComplete() }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = if (isNext) EquatorialGold else VolcanicGray, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AndeanBlue,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
            }
        }
    }
}

@Composable
fun TimelineAlertNode(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconBg: Color) {
    Row(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            modifier = Modifier.size(36.dp).background(iconBg, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = VolcanicGray),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = StatusRestriction, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
fun TimelineAiAdaptiveNode(
    time: String,
    titlePlanA: String,
    titlePlanB: String,
    selectedPlan: String,
    onSelectPlan: (String) -> Unit
) {
    Row(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            modifier = Modifier.size(36.dp).background(AndeanBlue, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFD2E4FF).copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("INTERVENCIÓN IA POR CLIMA", style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall, color = AndeanBlue)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (selectedPlan == "A") titlePlanA else titlePlanB,
                    style = MaterialTheme.typography.titleMedium,
                    color = AndeanBlue,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSelectPlan("A") },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedPlan == "A") BrandFill else SurfaceContainerHighest),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Plan A (Exterior)", color = if (selectedPlan == "A") Color.White else AndeanBlue, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onSelectPlan("B") },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedPlan == "B") BrandFill else SurfaceContainerHighest),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Plan B (Cubierto / Termas)", color = if (selectedPlan == "B") Color.White else AndeanBlue, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

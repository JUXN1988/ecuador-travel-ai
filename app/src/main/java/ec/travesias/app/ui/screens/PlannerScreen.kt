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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.TravelViewModel

@Composable
fun PlannerScreen(
    viewModel: TravelViewModel,
    onNavigateToTrips: () -> Unit = {}
) {
    var selectedRegion by remember { mutableStateOf(EcuadorRegion.ANDES) }
    var travelDays by remember { mutableIntStateOf(5) }
    var travelersCount by remember { mutableIntStateOf(2) }
    var budgetStyle by remember { mutableStateOf("Moderado") }
    var customPrompt by remember { mutableStateOf("") }

    val isGenerating by viewModel.isGeneratingPlan.collectAsState()
    val currentPlan by viewModel.currentItinerary.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .background(SurfaceLight)
    ) {
        Text("Diseña tu Viaje con IA", style = MaterialTheme.typography.headlineMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Cuéntanos tus intereses y nuestra IA armará un itinerario inteligente optimizado para Ecuador.", style = MaterialTheme.typography.bodyMedium, color = VolcanicGray)

        Spacer(modifier = Modifier.height(20.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
                Text("¿Qué experiencia buscas?", fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    placeholder = { Text("Ej. Quiero una aventura de 5 días en los Andes con senderismo ligero, degustación de café y termas.") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }

        Spacer(modifier = Modifier.height(20.dp))

        // Settings Grid
        Text("CONFIGURACIÓN DEL VIAJE", style = MaterialTheme.typography.labelSmall, color = VolcanicGray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
                Text("Región Destino", fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EcuadorRegion.entries.forEach { reg ->
                        val isSelected = selectedRegion == reg
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) BrandFill else SurfaceContainerHigh,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedRegion = reg }
                        ) {
                            Text(
                                when (reg) {
                                    EcuadorRegion.ANDES -> "Sierra"
                                    EcuadorRegion.COAST -> "Costa"
                                    EcuadorRegion.AMAZON -> "Oriente"
                                    EcuadorRegion.GALAPAGOS -> "Galápagos"
                                },
                                color = if (isSelected) Color.White else VolcanicGray,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

        Spacer(modifier = Modifier.height(12.dp))

        // Days and Travelers row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                    Text("Duración: $travelDays días", fontWeight = FontWeight.SemiBold, color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = travelDays.toFloat(),
                        onValueChange = { travelDays = it.toInt() },
                        valueRange = 1f..14f,
                        steps = 12,
                        colors = SliderDefaults.colors(thumbColor = EquatorialGold, activeTrackColor = AndeanBlue)
                    )
                }

            Column(modifier = Modifier.weight(1f)) {
                    Text("Viajeros: $travelersCount", fontWeight = FontWeight.SemiBold, color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = travelersCount.toFloat(),
                        onValueChange = { travelersCount = it.toInt() },
                        valueRange = 1f..6f,
                        steps = 4,
                        colors = SliderDefaults.colors(thumbColor = EquatorialGold, activeTrackColor = AndeanBlue)
                    )
                }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Budget style selector
        Column(modifier = Modifier.fillMaxWidth()) {
                Text("Estilo de Presupuesto", fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Mochilero", "Moderado", "Lujo").forEach { style ->
                        val isSelected = budgetStyle == style
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) EquatorialGold else SurfaceContainerHigh,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { budgetStyle = style }
                        ) {
                            Text(
                                style,
                                color = if (isSelected) Color.White else VolcanicGray,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

        Spacer(modifier = Modifier.height(24.dp))

        // Submit Button
        Button(
            onClick = {
                viewModel.generateItineraryWithAi(
                    region = selectedRegion,
                    days = travelDays,
                    budgetStyle = budgetStyle,
                    travelers = travelersCount,
                    prompt = customPrompt.ifBlank { "Quiero conocer lo más destacado de ${selectedRegion.displayName}" }
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EquatorialGold, contentColor = Color.White),
            shape = RoundedCornerShape(28.dp),
            enabled = !isGenerating
        ) {
            if (isGenerating) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Gemini IA generando tu ruta...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generar Itinerario con IA", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }

        // Generated Plan Result Alert Card
        currentPlan?.let { plan ->
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandFill),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusNormal)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("¡Itinerario Generado con Éxito!", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(plan.title, color = EquatorialGold, fontWeight = FontWeight.Bold)
                    Text(plan.summary, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Presupuesto Estimado: \$${plan.totalBudgetUsd.toInt()} USD", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("${plan.days.size} Días", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onNavigateToTrips,
                        colors = ButtonDefaults.buttonColors(containerColor = EquatorialGold),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ver Timeline Completo en Viajes →", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Inspiration Routes
        Text("Inspiración & Rutas Emblemáticas", style = MaterialTheme.typography.titleLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        InspirationCard(
            title = "Avenida de los Volcanes & Páramos",
            regionName = "Ruta Andina",
            imageUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuCw9Ln67RHpf3UV8dCJ5Vwgo5QGP9tJOE-TNiu8OPjiB4EVZ1ZFUz398xVTaPw_wB0bjWQkgjppYet6NeNvcyMccQAxahwMhURW7VKT1oG_VynAeH_ydQB7dVBIskPDzHU75e4kvVUFLGTkFykqtjHSiT7zT1cNUo1vgeeKMmPR_5yh1WKYHZNTcm_ldyDiuzKQTnoTWvrP8xUw2Rpwvd6VvLaSsu7DMQbK3VTXclr7OJ12KZTcW4Wr",
            onClick = {
                selectedRegion = EcuadorRegion.ANDES
                customPrompt = "Ruta por la Avenida de los Volcanes: Cotopaxi, Quilotoa y Baños de Agua Santa."
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                InspirationCard(
                    title = "Islas Galápagos",
                    regionName = "Galápagos",
                    imageUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuD2rly_j9lNqUo3YDKJkTTW8iSpCx6_ovEuBb08THJJGw7j0dLAKY5hrXrxnCzY7ZTYetwnSCwQcHMtnPdQM1vUWhsu9UaGHs9AcUPTXU2kqEAE2Ymd3q6NN5vhOW9AXpSJ3Lu9LDVMoDWRYfA4QwDm5ZFg4BYqFtpEfCXntdDR25oR4FQ2CGfBH9EXl3eJmUe1TnaUFpp1DYr5uHyxVkKtpL7fVRdmSDuAVzaVzAqnpll3badqepeA",
                    isCompact = true,
                    onClick = {
                        selectedRegion = EcuadorRegion.GALAPAGOS
                        customPrompt = "Snorkel y fauna endémica en Galápagos: tortugas gigantes y piqueros."
                    }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                InspirationCard(
                    title = "Selva Amazónica",
                    regionName = "Oriente",
                    imageUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuCpAJuLOto5WZ3ngjKseBV8Tr77IOypsd_6giki5l80aeLAstdBY23e6m49pbZU6L1SvTlmvREmHbl2Dmev7mQxemC-vylBXvHP0ewBJ_Ig7ciZLm6BGGAkuU0TPlNn-iFznEe9qYM89UvmeEcvcMMGu8uvJ66BNoGnucGCvM0gCK1AFrPf8iXpXIhB5U88o0rUXbwyNqXWVjNjqK3sraqlMtwN36xSU_kHQC5cM5OsrSVQwEGiR0WF",
                    isCompact = true,
                    onClick = {
                        selectedRegion = EcuadorRegion.AMAZON
                        customPrompt = "Inmersión en la selva del Napo con comunidades indígenas y rafting."
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun InspirationCard(
    title: String,
    regionName: String,
    imageUrl: String,
    isCompact: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 140.dp else 180.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Surface(color = EquatorialGold, shape = RoundedCornerShape(4.dp)) {
                    Text(regionName, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(title, color = Color.White, style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

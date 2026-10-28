package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.model.AltitudeGuide
import ec.travesias.app.data.model.EmergencyContact
import ec.travesias.app.data.notify.NotificationHelper
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.service.TravesiasBackgroundService
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.TravelViewModel

@Composable
fun ToolsScreen(viewModel: TravelViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val lang by AppSettings.language.collectAsState()
    val tabs = listOf(
        t(lang, "Mapas Offline", "Offline Maps"),
        t(lang, "Emergencias", "Emergency"),
        t(lang, "Altitud / Soroche", "Altitude"),
        t(lang, "Equipaje", "Packing"),
        t(lang, "Calculadora", "Calculator"),
        t(lang, "Ajustes", "Settings")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    t(lang, "Kit de Viaje", "Travel Kit"),
                    style = MaterialTheme.typography.titleLarge,
                    color = AndeanBlue,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    t(lang, "Mapas, emergencias, altitud, equipaje y ajustes",
                        "Maps, emergency, altitude, packing and settings"),
                    style = MaterialTheme.typography.bodySmall,
                    color = VolcanicGray
                )
            }

        // Tab Row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = SurfaceLight,
            contentColor = AndeanBlue,
            edgePadding = 16.dp,
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Tab Content
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTab) {
                0 -> OfflineScreen(viewModel)
                1 -> EmergenciesSection(viewModel.emergencyContacts)
                2 -> AltitudeSection(viewModel.altitudeGuides)
                3 -> PackingSection(viewModel)
                4 -> BudgetCalculatorSection()
                5 -> SettingsSection()
            }
        }
    }
}

@Composable
fun SettingsSection() {
    val context = LocalContext.current
    val lang by AppSettings.language.collectAsState()
    val backgroundOn by AppSettings.backgroundTracking.collectAsState()

    var hasNotif by remember { mutableStateOf(NotificationHelper.hasPermission(context)) }
    var hasLoc by remember { mutableStateOf(DeviceLocation.hasPermission(context)) }
    // Tener el permiso no basta: se pueden haber apagado desde los ajustes del telefono,
    // o haber dejado mudo el canal de mensajes. Eso hay que DECIRLO, porque si no la app
    // parece rota y en realidad esta callada por orden del propio telefono.
    var apagadasFuera by remember { mutableStateOf(NotificationHelper.apagadasEnElSistema(context)) }
    var canalMudo by remember { mutableStateOf(NotificationHelper.canalDeMensajesMudo(context)) }
    var avisoPrueba by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotif = granted
        apagadasFuera = NotificationHelper.apagadasEnElSistema(context)
        canalMudo = NotificationHelper.canalDeMensajesMudo(context)
        if (granted && backgroundOn) TravesiasBackgroundService.start(context)
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLoc = result.values.any { it }
        if (backgroundOn) TravesiasBackgroundService.start(context)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "Idioma de la app", "App language"), fontWeight = FontWeight.Bold, color = AndeanBlue)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t(lang, "Cambia el idioma de la interfaz y de las respuestas del Guía IA y el Radar IA.",
                            "Changes the interface language and the language the AI Guide and AI Radar reply in."),
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppLanguage.entries.forEach { opcion ->
                            FilterChip(
                                selected = lang == opcion,
                                onClick = { AppSettings.setLanguage(opcion) },
                                label = { Text(opcion.flag + " " + opcion.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AndeanBlue, selectedLabelColor = Color.White)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "Segundo plano y notificaciones", "Background & notifications"), fontWeight = FontWeight.Bold, color = AndeanBlue)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t(lang, "Para que los mensajes suenen con la app cerrada, Android obliga a dejar un aviso fijo en la barra. No hay otra forma sin un servidor de pago. Ese aviso va sin sonido y al final de la lista. Si lo apagas, los mensajes solo llegarán mientras tengas la app abierta.",
                            "For messages to ring while the app is closed, Android requires a permanent notice in the tray. There is no other way without a paid server. That notice is silent and sits at the bottom. If you turn it off, messages will only arrive while the app is open."),
                        style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t(lang, "Avisarme con la app cerrada", "Alert me while the app is closed"), fontWeight = FontWeight.Medium, color = AndeanBlue)
                            Text(
                                t(lang, "Mensajes y avisos de ruta. Deja un aviso fijo, mudo, en la barra", "Messages and route alerts. Leaves a silent, permanent notice in the tray"),
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                        Switch(
                            checked = backgroundOn,
                            onCheckedChange = { activar ->
                                AppSettings.setBackgroundTracking(activar)
                                if (activar) TravesiasBackgroundService.start(context) else TravesiasBackgroundService.stop(context)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = AndeanBlue, checkedTrackColor = AndeanBlue.copy(alpha = 0.4f))
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))

                    PermisoRow(
                        titulo = t(lang, "Notificaciones", "Notifications"),
                        concedido = hasNotif,
                        lang = lang,
                        onSolicitar = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                    if (hasNotif && apagadasFuera) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(lang, "⚠️  Las notificaciones de Travesías están apagadas en los ajustes del teléfono. Por eso no suena nada.",
                                "⚠️  Travesías notifications are turned off in the phone settings. That is why nothing rings."),
                            style = MaterialTheme.typography.bodySmall, color = StatusCaution
                        )
                        TextButton(onClick = { NotificationHelper.abrirAjustes(context) }) {
                            Text(t(lang, "Abrir los ajustes del teléfono", "Open phone settings"), color = AndeanBlue)
                        }
                    } else if (hasNotif && canalMudo) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(lang, "⚠️  El canal \"Mensajes de viajeros\" está silenciado en el teléfono: llegan, pero sin sonido.",
                                "⚠️  The \"Traveler messages\" channel is silenced on the phone: they arrive, but without sound."),
                            style = MaterialTheme.typography.bodySmall, color = StatusCaution
                        )
                        TextButton(onClick = { NotificationHelper.abrirAjustes(context) }) {
                            Text(t(lang, "Abrir los ajustes del teléfono", "Open phone settings"), color = AndeanBlue)
                        }
                    }
                    if (hasNotif) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                NotificationHelper.notifyPrueba(context)
                                apagadasFuera = NotificationHelper.apagadasEnElSistema(context)
                                canalMudo = NotificationHelper.canalDeMensajesMudo(context)
                                avisoPrueba = t(lang, "Enviada. Mira la barra de notificaciones.", "Sent. Check your notification tray.")
                            }) {
                                Text(t(lang, "Probar el sonido ahora", "Test the sound now"), color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                            }
                            if (avisoPrueba.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(avisoPrueba, style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PermisoRow(
                        titulo = t(lang, "Ubicación real", "Real location"),
                        concedido = hasLoc,
                        lang = lang,
                        onSolicitar = {
                            locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermisoRow(titulo: String, concedido: Boolean, lang: AppLanguage, onSolicitar: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (concedido) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (concedido) StatusNormal else StatusCaution,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(titulo, fontWeight = FontWeight.Medium, color = AndeanBlue)
                Text(
                    if (concedido) t(lang, "Concedido", "Granted") else t(lang, "No concedido", "Not granted"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (concedido) StatusNormal else StatusCaution
                )
            }
        }
        if (!concedido) {
            Button(onClick = onSolicitar, colors = ButtonDefaults.buttonColors(containerColor = BrandFill), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Text(t(lang, "Activar", "Enable"), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun EmergenciesSection(contacts: List<EmergencyContact>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StatusClosed.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Servicio Integrado ECU 911", fontWeight = FontWeight.Bold, color = StatusClosed)
                        Text("Llamada gratuita nacional desde cualquier teléfono o SIM internacional.", style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                    }
                }
            }
        }

        items(contacts) { contact ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(contact.title, fontWeight = FontWeight.Bold, color = AndeanBlue)
                        Surface(
                            color = AndeanBlue,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { ec.travesias.app.ui.util.Intents.dial(context, contact.number) }
                        ) {
                            Text(
                                "📞 " + contact.number,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(contact.description, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                }
            }
        }
    }
}

@Composable
fun AltitudeSection(guides: List<AltitudeGuide>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terrain, contentDescription = null, tint = AndeanBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Regla de Oro en los Andes", fontWeight = FontWeight.Bold, color = AndeanBlue)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Al subir de 0 a más de 2.500 msnm (ej. de Guayaquil a Quito o Cotopaxi), mantén una hidratación constante (mínimo 2.5L de agua al día), consume infusiones de coca y realiza caminatas lentas el primer día.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray
                    )
                }
            }
        }

        items(guides) { guide ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(guide.cityOrMountain, fontWeight = FontWeight.Bold, color = AndeanBlue)
                            Text("${guide.altitudeMeters} msnm", style = MaterialTheme.typography.labelSmall, color = EquatorialGold, fontWeight = FontWeight.SemiBold)
                        }
                        val badgeColor = when (guide.riskLevel) {
                            "Bajo" -> StatusNormal
                            "Bajo / Moderado", "Moderado" -> StatusCaution
                            "Moderado / Alto", "Alto" -> StatusRestriction
                            else -> StatusClosed
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "Riesgo: ${guide.riskLevel}",
                                color = badgeColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    guide.recommendations.forEach { rec ->
                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Text("• ", color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Text(rec, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PackingSection(viewModel: TravelViewModel) {
    val items by viewModel.packingItems.collectAsState()
    var newItemText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("General") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Añadir al Equipaje", fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        placeholder = { Text("Ej. Protector labial térmico", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newItemText.isNotBlank()) {
                                viewModel.addPackingItem(newItemText, selectedCategory)
                                newItemText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Agregar")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.isChecked,
                            onCheckedChange = { viewModel.togglePackingCheck(item) },
                            colors = CheckboxDefaults.colors(checkedColor = AndeanBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.name,
                                fontWeight = FontWeight.Medium,
                                color = if (item.isChecked) VolcanicGray.copy(alpha = 0.5f) else AndeanBlue,
                                textDecoration = if (item.isChecked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                            )
                            Text(item.category, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BudgetCalculatorSection() {
    var expenseAmount by remember { mutableStateOf("") }
    var tipPercent by remember { mutableFloatStateOf(10f) }

    val amount = expenseAmount.toDoubleOrNull() ?: 0.0
    val tipAmount = amount * (tipPercent / 100.0)
    val totalAmount = amount + tipAmount

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Calculadora de Propinas & Presupuesto USD", fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("En restaurantes de Ecuador el 10% de servicio suele incluirse en la factura, pero la propina voluntaria en efectivo es muy apreciada.", style = MaterialTheme.typography.bodySmall, color = VolcanicGray)

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = expenseAmount,
                    onValueChange = { expenseAmount = it },
                    label = { Text("Monto del Consumo (\$ USD)") },
                    leadingIcon = { Text("\$", fontWeight = FontWeight.Bold, color = AndeanBlue) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Propina sugerida: ${tipPercent.toInt()}%", fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                Slider(
                    value = tipPercent,
                    onValueChange = { tipPercent = it },
                    valueRange = 0f..20f,
                    steps = 3,
                    colors = SliderDefaults.colors(thumbColor = EquatorialGold, activeTrackColor = AndeanBlue)
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Propina:", color = VolcanicGray)
                    Text(String.format("$%.2f USD", tipAmount), fontWeight = FontWeight.Bold, color = AndeanBlue)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total a Pagar:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AndeanBlue)
                    Text(String.format("$%.2f USD", totalAmount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = StatusNormal)
                }
            }
        }
    }
}

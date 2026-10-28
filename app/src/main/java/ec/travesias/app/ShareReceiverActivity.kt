package ec.travesias.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import ec.travesias.app.data.model.LinkExtractionState
import ec.travesias.app.data.remote.LinkExtractorService
import ec.travesias.app.data.model.Place
import ec.travesias.app.ui.components.PlaceDetailContent
import ec.travesias.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recibe enlaces compartidos desde TikTok, Instagram, Facebook, YouTube o cualquier app
 * ("Compartir → Travesías en Ecuador") y los convierte en una parada lista para la ruta.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cada red comparte de una forma distinta: TikTok manda el enlace en EXTRA_TEXT,
        // Facebook a veces solo el asunto, y al abrir un enlace directo llega en el data.
        val sharedText = listOfNotNull(
            intent?.getStringExtra(Intent.EXTRA_TEXT),
            intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            intent?.dataString,
            intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        ).firstOrNull { it.isNotBlank() } ?: ""
        // Una invitación a un grupo no es un lugar: se reconoce y se trata aparte, para que
        // entrar sea un toque desde WhatsApp y nadie tenga que teclear un código largo.
        val invitacion = sharedText.contains("travesias.ec/g/", ignoreCase = true) ||
            sharedText.trim().uppercase().startsWith("TRV1-")
        setContent {
            AppTheme {
                if (invitacion) InvitacionScreen(sharedText.trim()) else ShareScreen(sharedText)
            }
        }
    }

    /**
     * Alguien te pasó una invitación a un grupo por WhatsApp. Aquí se abre de un toque:
     * la llave del grupo va DENTRO del enlace, así que ni Firebase ni nosotros la vemos.
     */
    @Composable
    private fun InvitacionScreen(texto: String) {
        val container = TravesiasApp.from(this)
        val scope = rememberCoroutineScope()
        var estado by remember { mutableStateOf("Abriendo la invitación…") }
        var listo by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(false) }

        LaunchedEffect(texto) {
            estado = "Abriendo la invitación…"
            val r = runCatching { withContext(Dispatchers.IO) { container.community.joinByLinkOrCode(texto) } }
            r.onSuccess {
                estado = "Ya estás dentro de \"" + it.name.ifBlank { "el grupo" } + "\". Ábrelo en Comunidad."
                listo = true
            }.onFailure {
                estado = it.message ?: "No se pudo abrir la invitación."
                error = true
            }
        }

        Box(Modifier.fillMaxSize().background(SurfaceLight), contentAlignment = Alignment.Center) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (error) Icons.Default.ErrorOutline else Icons.Default.Groups,
                        contentDescription = null,
                        tint = if (error) StatusClosed else AndeanBlue,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Invitación a un grupo de viaje",
                        style = MaterialTheme.typography.titleMedium,
                        color = AndeanBlue, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(estado, color = VolcanicGray, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    if (!listo && !error) {
                        CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    } else {
                        Button(
                            onClick = {
                                startActivity(
                                    Intent(this@ShareReceiverActivity, MainActivity::class.java)
                                        .putExtra(MainActivity.EXTRA_OPEN_ROUTE, "community")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                )
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
                        ) { Text(if (error) "Abrir la app" else "Ir al grupo") }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShareScreen(sharedText: String) {
        val container = TravesiasApp.from(this)
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf<LinkExtractionState>(LinkExtractionState.Idle) }
        var saved by remember { mutableStateOf(false) }
        var totalDays by remember { mutableIntStateOf(1) }
        // Cuando la red no deja leer el video, se le pide una pista al viajero y se reintenta.
        var needsHint by remember { mutableStateOf<LinkExtractorService.NeedsHint?>(null) }
        var hint by remember { mutableStateOf("") }
        var intento by remember { mutableIntStateOf(0) }

        LaunchedEffect(sharedText, intento) {
            if (sharedText.isBlank()) {
                state = LinkExtractionState.Error("No se recibió ningún enlace o texto.")
                return@LaunchedEffect
            }
            needsHint = null
            state = LinkExtractionState.Loading("Analizando la publicación…")
            try {
                totalDays = container.routeRepository.activeRoute.first()?.totalDays ?: 1
                val pista = hint.trim()
                val (place, source) = withContext(Dispatchers.IO) {
                    container.linkExtractor.extractPlace(sharedText, pista) { msg ->
                        // Compose State solo se toca en el hilo principal.
                        scope.launch { state = LinkExtractionState.Loading(msg) }
                    }
                }
                container.routeRepository.upsertPlace(place, share = true)
                state = LinkExtractionState.Success(place, source)
            } catch (e: LinkExtractorService.NeedsHint) {
                needsHint = e
                state = LinkExtractionState.Idle
            } catch (e: Exception) {
                state = LinkExtractionState.Error(
                    when {
                        e.message?.contains("GEMINI_API_KEY") == true -> "Configura GEMINI_API_KEY en .env para analizar publicaciones con IA."
                        e.message?.contains("Unable to resolve host") == true -> "Sin conexión a internet. Inténtalo cuando tengas señal."
                        else -> e.message ?: "No se pudo analizar la publicación."
                    }
                )
            }
        }

        Surface(color = SurfaceLight, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = BrandFill) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, tint = EquatorialGold)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Añadir a Travesías en Ecuador", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("De una publicación a una parada en tu ruta", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White) }
                    }
                }

                val pendiente = needsHint
                if (pendiente != null) {
                    HintForm(
                        motivo = pendiente.motivo,
                        titulo = pendiente.meta.title,
                        autor = pendiente.meta.author,
                        plataforma = pendiente.meta.platform.displayName,
                        miniatura = pendiente.meta.thumbnail,
                        hint = hint,
                        onHintChange = { hint = it },
                        onRetry = { intento++ },
                        onClose = { finish() }
                    )
                    return@Column
                }

                when (val s = state) {
                    is LinkExtractionState.Idle, is LinkExtractionState.Loading -> {
                        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = AndeanBlue)
                            Spacer(Modifier.height(16.dp))
                            Text((s as? LinkExtractionState.Loading)?.message ?: "Preparando…", color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(sharedText.take(140), color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is LinkExtractionState.Error -> {
                        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(s.message, color = StatusClosed, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { finish() }, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) { Text("Cerrar") }
                        }
                    }
                    is LinkExtractionState.Success -> {
                        var bookmarked by remember(s.place.id) { mutableStateOf(s.place.isSaved) }
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                PlaceDetailContent(
                                    place = s.place.copy(isSaved = bookmarked), inRoute = saved, totalDays = totalDays,
                                    onAddToRoute = { day ->
                                        scope.launch {
                                            container.routeRepository.addStop(s.place, day)
                                            saved = true
                                        }
                                    },
                                    onToggleSave = {
                                        scope.launch {
                                            val siguiente = !bookmarked
                                            container.routeRepository.setSaved(s.place.id, siguiente)
                                            bookmarked = siguiente
                                        }
                                    },
                                    onShowOnMap = { openInApp(s.place, Routes.MAP) },
                                    showMiniMap = true
                                )
                            }
                            Surface(color = SurfaceContainerLowest, tonalElevation = 3.dp) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(if (saved) "Listo" else "Solo guardar", color = AndeanBlue) }
                                    Button(onClick = { openInApp(s.place, Routes.ROUTE) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = BrandFill), shape = RoundedCornerShape(12.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Abrir la app")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * La red social no dejó leer el video (pasa mucho con Instagram y Facebook, y con
     * cuentas privadas de TikTok). En vez de rendirse, se le enseña al viajero lo que SÍ
     * se pudo leer y se le pide el nombre del lugar para volver a buscar en internet.
     */
    @Composable
    private fun HintForm(
        motivo: String,
        titulo: String,
        autor: String,
        plataforma: String,
        miniatura: String,
        hint: String,
        onHintChange: (String) -> Unit,
        onRetry: () -> Unit,
        onClose: () -> Unit
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = EquatorialGold)
                Spacer(Modifier.width(8.dp))
                Text("Ayúdame con una pista", color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(motivo, color = VolcanicGray, style = MaterialTheme.typography.bodyMedium)

            if (titulo.isNotBlank() || autor.isNotBlank() || miniatura.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("Esto es lo que sí pude leer:", color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    if (miniatura.isNotBlank()) {
                        AsyncImage(
                            model = miniatura, contentDescription = null,
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        if (titulo.isNotBlank()) {
                            Text(titulo, color = VolcanicGray, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                        if (autor.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("@" + autor + " · " + plataforma, color = AndeanBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = onHintChange,
                label = { Text("¿Qué lugar sale en el video?") },
                placeholder = { Text("Ej: la laguna del Quilotoa, o queda en Baños") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Con el nombre o la ciudad me basta: yo busco en internet las coordenadas, el costo, el horario y cómo llegar.",
                color = VolcanicGray, style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                enabled = hint.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                shape = RoundedCornerShape(25.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = EquatorialGold)
                Spacer(Modifier.width(8.dp))
                Text("Buscar con esta pista", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar", color = VolcanicGray)
            }
        }
    }

    private fun openInApp(place: Place, route: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLACE_ID, place.id)
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}

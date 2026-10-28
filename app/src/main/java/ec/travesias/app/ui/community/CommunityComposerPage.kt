package ec.travesias.app.ui.community

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.data.social.LugarDeLaFoto
import ec.travesias.app.ui.theme.AndesGreen
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.SkyAccent
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Publicar fotos a pantalla completa (pie, lugar y menciones).
 * Sustituye el diálogo CommunityComposer; la lógica de publicación no cambia.
 */
@Composable
fun CommunityComposerPage(
    uris: List<Uri>,
    busy: Boolean,
    lugaresSugeridos: List<Pair<String, String>>,
    contactos: List<String>,
    onDismiss: () -> Unit,
    onPublicar: (caption: String, placeName: String, placeId: String, tagged: List<String>) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    var placeName by remember { mutableStateOf("") }
    var placeId by remember { mutableStateOf("") }
    var mentionInput by remember { mutableStateOf("") }
    val tagged = remember { mutableStateListOf<String>() }
    val sugerenciasLugar = remember(placeName, lugaresSugeridos) {
        val q = placeName.trim().lowercase()
        if (q.length < 2) lugaresSugeridos.take(6)
        else lugaresSugeridos.filter { it.second.lowercase().contains(q) }.take(8)
    }

    // ---- De dónde salió la foto, sin que el viajero tenga que escribirlo ----
    // 1) las coordenadas que la cámara dejó DENTRO de la foto; 2) si no las trae,
    // el GPS del teléfono; 3) si tampoco, se escribe a mano como siempre.
    val contexto = LocalContext.current
    var hallazgo by remember { mutableStateOf<LugarDeLaFoto.Hallazgo?>(null) }
    var buscandoLugar by remember { mutableStateOf(false) }
    var yaSeSugirio by remember { mutableStateOf(false) }
    val pedirGps = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) yaSeSugirio = false
    }

    LaunchedEffect(uris.firstOrNull(), yaSeSugirio) {
        val primera = uris.firstOrNull()
        if (primera == null || yaSeSugirio) return@LaunchedEffect
        yaSeSugirio = true
        buscandoLugar = true
        hallazgo = runCatching { LugarDeLaFoto.detectar(contexto, primera) }.getOrNull()
        buscandoLugar = false
        // Solo se rellena si el viajero no escribió nada: nunca se pisa lo suyo.
        val h = hallazgo
        if (h != null && placeName.isBlank()) placeName = h.nombre
    }

    BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(SurfaceLight)) {
        CommunityPageBar(
            title = if (uris.size > 1) "Nueva publicación · ${uris.size} fotos" else "Nueva publicación",
            onBack = onDismiss
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(uris, key = { it.toString() }) { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Foto a publicar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp)
                    )
                }
            }
            OutlinedTextField(
                value = placeName,
                onValueChange = { placeName = it.take(80); if (placeId.isNotBlank()) placeId = "" },
                label = { Text("Lugar") },
                placeholder = { Text("Baños, Cotopaxi…") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Default.LocationOn, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Aviso de dónde salió el lugar, y salida manual si no se pudo saber.
            when {
                buscandoLugar -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = SkyAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscando dónde se tomó la foto…", style = MaterialTheme.typography.labelSmall, color = MutedGray)
                }
                hallazgo != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        if (hallazgo!!.origen == LugarDeLaFoto.Origen.FOTO) Icons.Default.PhotoCamera else Icons.Default.MyLocation,
                        contentDescription = null, tint = AndesGreen, modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        hallazgo!!.explicacion,
                        style = MaterialTheme.typography.labelSmall,
                        color = AndesGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "La foto no dice dónde se tomó.",
                        style = MaterialTheme.typography.labelSmall, color = MutedGray
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            if (DeviceLocation.hasPermission(contexto)) yaSeSugirio = false
                            else pedirGps.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Usar dónde estoy", style = MaterialTheme.typography.labelSmall, color = SkyAccent)
                    }
                }
            }
            if (sugerenciasLugar.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sugerenciasLugar, key = { it.first }) { (id, nombre) ->
                        FilterChip(
                            selected = placeId == id,
                            onClick = { placeId = id; placeName = nombre },
                            label = { Text(nombre, maxLines = 1) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it.take(180) },
                label = { Text("Pie de foto") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            OutlinedTextField(
                value = mentionInput,
                onValueChange = { mentionInput = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                label = { Text("Mencionar") },
                prefix = { Text("@", color = VolcanicGray) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            val u = mentionInput.trim()
                            if (u.length >= 3 && u !in tagged) tagged.add(u)
                            mentionInput = ""
                        },
                        enabled = mentionInput.length >= 3
                    ) { Text("Añadir") }
                }
            )
            if (contactos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(contactos.take(12)) { user ->
                        val sel = user in tagged
                        FilterChip(
                            selected = sel,
                            onClick = { if (sel) tagged.remove(user) else tagged.add(user) },
                            label = { Text("@$user") }
                        )
                    }
                }
            }
            if (tagged.isNotEmpty()) {
                Text("Con " + tagged.joinToString(" ") { "@$it" }, style = MaterialTheme.typography.labelSmall, color = AndeanBlue)
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { onPublicar(caption, placeName, placeId, tagged.toList()) },
            enabled = !busy && uris.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) { Text("Compartir", fontWeight = FontWeight.Bold) }
    }
}

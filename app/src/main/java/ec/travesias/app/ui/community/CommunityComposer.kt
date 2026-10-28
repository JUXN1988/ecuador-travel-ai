package ec.travesias.app.ui.community

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
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
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Publicar una o varias fotos: pie, lugar y menciones.
 * Sin recuadros extra; solo el diálogo y las miniaturas en fila.
 */
@Composable
fun CommunityComposer(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (uris.size > 1) "Nueva publicación · ${uris.size} fotos" else "Nueva publicación",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(uris, key = { it.toString() }) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp)
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
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPublicar(caption, placeName, placeId, tagged.toList()) },
                enabled = !busy && uris.isNotEmpty()
            ) { Text("Compartir", fontWeight = FontWeight.Bold, color = AndeanBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

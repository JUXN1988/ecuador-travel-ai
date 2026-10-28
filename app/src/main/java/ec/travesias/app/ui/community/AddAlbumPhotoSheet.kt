package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Hoja para publicar una foto en el álbum: lugar visitado y menciones a compañeros
 * por @usuario. Eligió la imagen antes de abrir esto.
 */
@Composable
fun AddAlbumPhotoSheet(
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
        if (q.length < 2) lugaresSugeridos.take(8)
        else lugaresSugeridos.filter { it.second.lowercase().contains(q) }.take(10)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Foto de viaje", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "La foto se ve grande en tu perfil. Etiqueta el lugar y menciona a quien viajó contigo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VolcanicGray
                )
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it.take(80); if (placeId.isNotBlank()) placeId = "" },
                    label = { Text("Lugar visitado") },
                    placeholder = { Text("Ej. Baños de Agua Santa") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = AndeanBlue) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (sugerenciasLugar.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sugerenciasLugar, key = { it.first }) { (id, nombre) ->
                            FilterChip(
                                selected = placeId == id,
                                onClick = { placeId = id; placeName = nombre },
                                label = { Text(nombre, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EquatorialGold,
                                    selectedLabelColor = AndeanBlue
                                )
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(180) },
                    label = { Text("Pie de foto (opcional)") },
                    placeholder = { Text("Un día en la ruta…") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                OutlinedTextField(
                    value = mentionInput,
                    onValueChange = { mentionInput = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                    label = { Text("Mencionar compañero") },
                    placeholder = { Text("usuario") },
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
                        ) { Text("Añadir", color = AndeanBlue) }
                    }
                )
                if (contactos.isNotEmpty()) {
                    Text("Tu gente", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(contactos.take(12)) { user ->
                            val sel = user in tagged
                            FilterChip(
                                selected = sel,
                                onClick = { if (sel) tagged.remove(user) else tagged.add(user) },
                                label = { Text("@$user") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AndeanBlue,
                                    selectedLabelColor = androidx.compose.ui.graphics.Color.White
                                )
                            )
                        }
                    }
                }
                if (tagged.isNotEmpty()) {
                    Surface(color = SurfaceContainerLow, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Viajaron: " + tagged.joinToString(" ") { "@$it" },
                            color = AndeanBlue,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
            }
        },
        confirmButton = {
            Button(
                onClick = { onPublicar(caption, placeName, placeId, tagged.toList()) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Publicar en mi álbum")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

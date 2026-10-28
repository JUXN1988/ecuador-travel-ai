package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.SurfaceLight

/**
 * Barra para escribir en el chat. Extraída para reutilizarla tanto en chats de amigos
 * como cuando alguien responde una solicitud sin haberse agregado todavía.
 */
@Composable
fun ChatComposerBar(
    placeholder: String,
    onSend: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onShareLocation: () -> Unit,
    onShareRoute: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(false) }
    var emojis by remember { mutableStateOf(false) }
    Surface(color = SurfaceLight, shadowElevation = 6.dp) {
      Column {
        // El panel de emojis va ENCIMA de la barra: así el teclado no lo tapa.
        if (emojis) SelectorEmojis(onElegir = { text += it })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { attach = true }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Adjuntar", tint = AndeanBlue)
                }
                DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                    DropdownMenuItem(
                        text = { Text("Enviar una foto") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, tint = AndeanBlue) },
                        onClick = { attach = false; onPickPhoto() }
                    )
                    DropdownMenuItem(
                        text = { Text("Enviar un video") },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, tint = AndeanBlue) },
                        onClick = { attach = false; onPickVideo() }
                    )
                    DropdownMenuItem(
                        text = { Text("Enviar un archivo") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = AndeanBlue) },
                        onClick = { attach = false; onPickFile() }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir mi ubicación") },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = AndeanBlue) },
                        onClick = { attach = false; onShareLocation() }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir mi ruta") },
                        leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, tint = AndeanBlue) },
                        onClick = { attach = false; onShareRoute() }
                    )
                }
            }
            IconButton(onClick = { emojis = !emojis }) {
                Icon(
                    if (emojis) Icons.Default.KeyboardAlt else Icons.Default.EmojiEmotions,
                    contentDescription = if (emojis) "Cerrar los emojis" else "Emojis",
                    tint = AndeanBlue
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                        emojis = false
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrandFill)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
      }
    }
}

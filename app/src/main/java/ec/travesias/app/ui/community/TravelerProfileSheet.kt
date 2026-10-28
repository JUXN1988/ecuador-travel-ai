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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Perfil de otro viajero: álbum de fotos grande, agregar amigo, escribir y ver datos
 * que él mismo publicó. No sustituye la ficha anterior: es el módulo nuevo del recorte.
 */
@Composable
fun TravelerProfileSheet(
    card: TravelerCard,
    album: List<AlbumPhoto>,
    cargando: Boolean,
    misIntereses: List<String>,
    onCerrar: () -> Unit,
    onEscribir: () -> Unit,
    onAgregarAmigo: () -> Unit,
    onBloquear: () -> Unit,
    onDesbloquear: () -> Unit,
    onNota: (String) -> Unit,
    onInvitarAGrupo: () -> Unit,
    avatar: @Composable (thumb: String, initials: String) -> Unit,
    datoChip: @Composable (icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String) -> Unit,
    tiempo: (Long) -> String,
    onNeedPhoto: (AlbumPhoto) -> Unit = {}
) {
    var nota by remember(card.fingerprint) { mutableStateOf("") }
    var editandoNota by remember(card.fingerprint) { mutableStateOf(false) }
    val enComun = remember(card, misIntereses) {
        card.inCommon.ifEmpty { card.interests.filter { i -> misIntereses.any { it.equals(i, true) } } }
    }
    val yaEsAmigo = card.state == ContactState.CONTACTO
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar(card.thumb, card.initials)
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("@" + card.username.ifBlank { card.fingerprint.take(10) }, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        if (card.verified) {
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Default.VerifiedUser, contentDescription = "Firma verificada", tint = StatusNormal, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(
                        when {
                            card.isBlocked -> "Bloqueado"
                            !card.canViewProfile && card.visibility == ProfileVisibility.PRIVATE -> "Perfil privado"
                            !card.canViewProfile -> "Perfil solo para amigos"
                            yaEsAmigo -> "Es tu amigo"
                            card.showcase -> "Está buscando compañeros de viaje"
                            else -> "Aún no es tu amigo"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (card.isBlocked) StatusClosed else VolcanicGray
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (cargando) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
                    Spacer(Modifier.height(8.dp))
                }
                if (!card.canViewProfile) {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = EquatorialGold.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (card.visibility == ProfileVisibility.PRIVATE)
                                    "Este perfil es privado. No se ven fotos ni datos de viaje."
                                else
                                    "Solo sus amigos pueden ver el perfil y el álbum. Agrégalo y, cuando él te agregue, lo verás.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VolcanicGray
                            )
                        }
                    }
                } else {
                if (card.displayName.isNotBlank() && !card.displayName.equals(card.username, true)) {
                    Text(card.displayName, color = AndeanBlue, fontWeight = FontWeight.Bold)
                }
                if (card.bio.isNotBlank()) {
                    Text(card.bio, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    datoChip(Icons.Default.Forum, if (card.messagesSeen > 0) card.messagesSeen.toString() + " mensajes" else "sin mensajes")
                    if (card.knownSince > 0) datoChip(Icons.Default.Schedule, "desde " + tiempo(card.knownSince))
                    if (card.contributions > 0) datoChip(Icons.Default.Public, card.contributions.toString() + " aportes")
                }
                if (card.region.isNotBlank() || card.travelMonth.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOfNotNull(
                            card.region.takeIf { it.isNotBlank() }?.let { "Viaja a $it" },
                            card.travelMonth.takeIf { it.isNotBlank() }?.let { "en $it" }
                        ).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium,
                        color = AndeanBlue
                    )
                }
                if (card.routeLabel.isNotBlank()) {
                    Text(card.routeLabel, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
                if (enComun.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Les gusta lo mismo: " + enComun.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                }
                if (card.interests.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(card.interests) { i ->
                            Surface(color = EquatorialGold.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                                Text(i, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TravelerAlbumGallery(
                    fotos = album.ifEmpty { card.album },
                    esMio = false,
                    altura = 280,
                    onNeedFull = onNeedPhoto
                )
                } // canViewProfile
                Spacer(Modifier.height(10.dp))
                if (editandoNota) {
                    OutlinedTextField(
                        value = nota, onValueChange = { nota = it.take(140) },
                        label = { Text("Nota privada") },
                        placeholder = { Text("Ej. el de la camioneta que va a Baños") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { onNota(nota); editandoNota = false }) { Text("Guardar nota", color = AndeanBlue) }
                } else {
                    TextButton(onClick = { editandoNota = true }) {
                        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Ponerle una nota que solo veas tú", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!card.isBlocked && card.username.length >= 3) {
                    OutlinedButton(onClick = onInvitarAGrupo, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("Meter en un grupo de viaje", color = AndeanBlue)
                    }
                }
                if (!card.verified && card.messagesSeen > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GppMaybe, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Alguno de sus mensajes no se pudo verificar. Desconfía si te pide dinero o datos.",
                            style = MaterialTheme.typography.labelSmall, color = StatusClosed
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (card.isBlocked) {
                Button(onClick = onDesbloquear, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                    Text("Desbloquear")
                }
            } else {
                Column {
                    if (!yaEsAmigo) {
                        Button(
                            onClick = onAgregarAmigo,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusNormal),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Agregar amigo")
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Button(onClick = onEscribir, colors = ButtonDefaults.buttonColors(containerColor = BrandFill), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (yaEsAmigo) "Chatear" else "Responder / escribir")
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (!card.isBlocked) {
                    TextButton(onClick = onBloquear) { Text("Bloquear", color = StatusClosed) }
                }
                TextButton(onClick = onCerrar) { Text("Cerrar") }
            }
        }
    )
}

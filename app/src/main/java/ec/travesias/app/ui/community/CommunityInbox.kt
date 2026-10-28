package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.esConversacionConmigo
import ec.travesias.app.data.social.nombreDelInterlocutor
import ec.travesias.app.ui.components.holdToMenu
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Bandeja: solicitudes arriba, chats debajo. Filas planas, sin cards.
 * Responder no implica agregar amigo; eso sigue en un toque aparte.
 */
@Composable
fun CommunityInbox(
    chats: List<TravelGroup>,
    solicitudes: List<TravelGroup>,
    yo: String?,
    esAmigo: (String?) -> Boolean,
    avatarDe: (String?) -> String = { "" },
    onEscribirUsuario: () -> Unit,
    onAbrirChat: (String) -> Unit,
    onVerPerfil: (String, String) -> Unit,
    onResponder: (TravelGroup) -> Unit,
    onAgregarAmigo: (TravelGroup) -> Unit,
    onRechazar: (TravelGroup) -> Unit,
    onAceptarGrupo: (TravelGroup) -> Unit,
    onHoldChat: (TravelGroup) -> Unit = {},
    miUsuario: String? = null,
    yoUid: String? = null,
    modifier: Modifier = Modifier
) {
    // No listar un chat cuyo interlocutor soy yo.
    val chatsVisibles = remember(chats, yo, miUsuario, yoUid) {
        chats.filter { !it.esConversacionConmigo(yo, miUsuario, yoUid) }
    }
    val solicitudesVisibles = remember(solicitudes, yo, miUsuario, yoUid) {
        solicitudes.filter { !it.esConversacionConmigo(yo, miUsuario, yoUid) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            TextButton(onClick = onEscribirUsuario, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Default.AlternateEmail, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                Spacer(Modifier.width(6.dp))
                Text("Escribir a @usuario", color = AndeanBlue, fontWeight = FontWeight.SemiBold)
            }
        }
        if (solicitudesVisibles.isNotEmpty()) {
            item {
                Text(
                    "📬  Solicitudes",
                    fontWeight = FontWeight.SemiBold,
                    color = AndeanBlue,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(solicitudesVisibles, key = { "r_" + it.id }) { g ->
                val quien = g.nombreDelInterlocutor(yo, miUsuario)
                val otro = g.otherMember(yo) ?: g.invitedBy
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        if (g.direct) "@$quien te escribió" else "Te invitaron a ${g.name}",
                        fontWeight = FontWeight.SemiBold,
                        color = AndeanBlue
                    )
                    if (g.lastMessagePreview.isNotBlank()) {
                        Text(
                            g.lastMessagePreview,
                            color = VolcanicGray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            if (g.direct) onResponder(g) else onAceptarGrupo(g)
                        }) { Text(if (g.direct) "Responder" else "Aceptar", fontWeight = FontWeight.Bold) }
                        if (g.direct && !esAmigo(otro)) {
                            TextButton(onClick = { onAgregarAmigo(g) }) { Text("Amigo") }
                        }
                        TextButton(onClick = { onVerPerfil(otro, quien) }) { Text("Perfil") }
                        TextButton(onClick = { onRechazar(g) }) { Text("No", color = VolcanicGray) }
                    }
                }
                HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
            }
        }
        item {
            Text(
                    "💬  Mensajes",
                fontWeight = FontWeight.SemiBold,
                color = AndeanBlue,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (chatsVisibles.isEmpty()) {
            item {
                Text(
                    "Todavía no hay conversaciones. Escríbele a alguien por su @usuario.",
                    color = VolcanicGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        items(chatsVisibles, key = { it.id }) { g ->
            InboxChatRow(
                group = g,
                yo = yo,
                miUsuario = miUsuario,
                thumb = avatarDe(g.otherMember(yo)),
                onOpen = { onAbrirChat(g.id) },
                onProfile = onVerPerfil,
                onHold = { onHoldChat(g) }
            )
            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun InboxChatRow(
    group: TravelGroup,
    yo: String?,
    miUsuario: String?,
    thumb: String,
    onOpen: () -> Unit,
    onProfile: (String, String) -> Unit,
    onHold: () -> Unit
) {
    val otro = group.otherMember(yo)
    val nombre = group.nombreDelInterlocutor(yo, miUsuario)
    val esperando = group.waitingForOther(yo)
    Row(
        Modifier
            .fillMaxWidth()
            .holdToMenu(onClick = onOpen, onHold = onHold)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommunityMiniAvatar(
            thumb = thumb,
            initials = nombre.take(2).uppercase(),
            size = 48.dp,
            onClick = { if (otro != null) onProfile(otro, nombre) }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nombre, fontWeight = FontWeight.SemiBold, color = AndeanBlue, maxLines = 1)
                if (group.muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(13.dp))
                }
            }
            Text(
                when {
                    esperando -> "Esperando respuesta"
                    group.lastMessagePreview.isNotBlank() -> group.lastMessagePreview
                    else -> "Sin mensajes"
                },
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (group.lastMessageAt > 0) {
            Text(communityTimeAgo(group.lastMessageAt), style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
        }
    }
}

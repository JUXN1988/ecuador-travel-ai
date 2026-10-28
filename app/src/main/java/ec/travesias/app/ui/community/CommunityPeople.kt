package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import ec.travesias.app.data.social.BuddyMatch
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.data.social.esYo
import ec.travesias.app.ui.components.holdToMenu
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Amigos y viajeros afines en listas planas. El sistema de amigos no cambia:
 * CONTACTO = amigo; aquí solo se ve más claro.
 */
@Composable
fun CommunityPeople(
    contactos: List<TravelerCard>,
    buddies: List<BuddyMatch>,
    bloqueados: List<TravelerCard>,
    busy: Boolean,
    onEscribirUsuario: () -> Unit,
    onBuscarAfines: () -> Unit,
    onPerfil: (fingerprint: String, username: String) -> Unit,
    onEscribir: (username: String) -> Unit,
    onInvitar: (TravelerCard) -> Unit,
    onContactarBuddy: (uid: String, username: String) -> Unit,
    onDesbloquear: (fingerprint: String) -> Unit,
    onHoldPerson: (TravelerCard) -> Unit = {},
    yoFingerprint: String? = null,
    miUsuario: String? = null,
    yoUid: String? = null,
    modifier: Modifier = Modifier
) {
    // Cinturón: aunque llegue la ficha propia, no se lista como amigo ni afín.
    val amigosVisibles = remember(contactos, yoFingerprint, miUsuario, yoUid) {
        contactos.filter { !it.esYo(yoFingerprint, miUsuario, yoUid) }
    }
    val afinesVisibles = remember(buddies, yoFingerprint, miUsuario, yoUid) {
        buddies.filter { !it.esYo(yoFingerprint, miUsuario, yoUid) }
    }
    val bloqueadosVisibles = remember(bloqueados, yoFingerprint, miUsuario, yoUid) {
        bloqueados.filter { !it.esYo(yoFingerprint, miUsuario, yoUid) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEscribirUsuario) {
                    Icon(Icons.Default.AlternateEmail, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Agregar", color = AndeanBlue)
                }
                TextButton(onClick = onBuscarAfines) {
                    Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Afines", color = AndeanBlue)
                }
            }
        }
        item {
            Text(
                    "🤝  Amigos" + if (amigosVisibles.isNotEmpty()) "  ${amigosVisibles.size}" else "",
                fontWeight = FontWeight.SemiBold,
                color = AndeanBlue,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (amigosVisibles.isEmpty()) {
            item {
                Text(
                    "Cuando agregues a alguien y te agregue, aparece aquí. Puedes chatear antes, si la otra persona responde.",
                    color = VolcanicGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        items(amigosVisibles, key = { "c_" + it.fingerprint }) { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .holdToMenu(
                        onClick = { onPerfil(c.fingerprint, c.username) },
                        onHold = { onHoldPerson(c) }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CommunityMiniAvatar(thumb = c.thumb, initials = c.initials, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@" + c.username.ifBlank { c.fingerprint.take(8) }, fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                    if (c.bio.isNotBlank()) {
                        Text(c.bio, style = MaterialTheme.typography.bodySmall, color = VolcanicGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { onInvitar(c) }) {
                    Icon(Icons.Default.GroupAdd, contentDescription = "Meter en un grupo", tint = AndeanBlue)
                }
                IconButton(onClick = { onEscribir(c.username) }) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Escribir", tint = AndeanBlue)
                }
            }
            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
        }
        item {
            Text(
                "🧭  Viajeros afines",
                fontWeight = FontWeight.SemiBold,
                color = AndeanBlue,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        if (busy) {
            item {
                Text("Buscando…", color = VolcanicGray, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!busy && afinesVisibles.isEmpty()) {
            item {
                Text(
                    "Completa gustos y ruta en tu perfil 🧭, y enciende la vitrina, para que coincidan contigo.",
                    color = VolcanicGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        items(afinesVisibles, key = { "b_" + it.profile.uid }) { b ->
            Row(
                Modifier.fillMaxWidth().clickable { onPerfil(b.profile.uid, b.profile.username) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CommunityMiniAvatar(thumb = b.profile.avatarThumb, initials = b.profile.initials, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@" + b.profile.username, fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                    Text(
                        b.reasons.take(2).joinToString(" · ").ifBlank { "${b.score} pts" },
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { onContactarBuddy(b.profile.uid, b.profile.username) }) {
                    Text("Escribir", fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
        }
        if (bloqueadosVisibles.isNotEmpty()) {
            item {
                Text("Bloqueados", fontWeight = FontWeight.SemiBold, color = StatusClosed, modifier = Modifier.padding(16.dp))
            }
            items(bloqueadosVisibles, key = { "x_" + it.fingerprint }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("@" + c.username.ifBlank { c.fingerprint.take(8) }, color = VolcanicGray, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDesbloquear(c.fingerprint) }) { Text("Desbloquear", color = AndeanBlue) }
                }
            }
        }
    }
}

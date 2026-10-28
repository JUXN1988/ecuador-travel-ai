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
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.ui.components.holdToMenu
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Grupos de viaje en filas simples: abrir chat e invitar amigos.
 * El cifrado y las invitaciones siguen igual; solo se aligera la vista.
 */
@Composable
fun CommunityGroupsPanel(
    grupos: List<TravelGroup>,
    onCrear: () -> Unit,
    onUnirme: () -> Unit,
    onAbrir: (String) -> Unit,
    onInvitar: (String) -> Unit,
    onHoldGroup: (TravelGroup) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCrear) {
                    Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Crear", color = AndeanBlue)
                }
                TextButton(onClick = onUnirme) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Tengo invitación", color = AndeanBlue)
                }
            }
        }
        if (grupos.isEmpty()) {
            item {
                Text(
                    "Crea un grupo para el viaje e invita a tus amigos por @usuario o con el enlace. 🚌",
                    color = VolcanicGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        items(grupos, key = { it.id }) { g ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .holdToMenu(onClick = { onAbrir(g.id) }, onHold = { onHoldGroup(g) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontWeight = FontWeight.SemiBold, color = AndeanBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        g.lastMessagePreview.ifBlank { "${g.memberUids.size} miembros" },
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onInvitar(g.id) }) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Invitar", tint = AndeanBlue)
                }
            }
            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
        }
    }
}

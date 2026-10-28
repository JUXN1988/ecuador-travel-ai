package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Ajustes de visibilidad del perfil: público, solo amigos o privado.
 * Módulo aparte para no mezclarlo con el resto de la ficha de edición.
 */
@Composable
fun ProfilePrivacySettings(
    actual: ProfileVisibility,
    onCambiar: (ProfileVisibility) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Quién puede ver tu perfil",
            style = MaterialTheme.typography.labelLarge,
            color = AndeanBlue,
            fontWeight = FontWeight.Bold
        )
        Text(
            "El chat sigue siendo solo entre ustedes, cifrado. Esto controla el perfil y el álbum de fotos.",
            style = MaterialTheme.typography.labelSmall,
            color = VolcanicGray
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipVis(
                vis = ProfileVisibility.PUBLIC,
                icono = Icons.Default.Public,
                seleccionado = actual == ProfileVisibility.PUBLIC,
                modifier = Modifier.weight(1f),
                onClick = { onCambiar(ProfileVisibility.PUBLIC) }
            )
            ChipVis(
                vis = ProfileVisibility.FRIENDS,
                icono = Icons.Default.People,
                seleccionado = actual == ProfileVisibility.FRIENDS,
                modifier = Modifier.weight(1f),
                onClick = { onCambiar(ProfileVisibility.FRIENDS) }
            )
            ChipVis(
                vis = ProfileVisibility.PRIVATE,
                icono = Icons.Default.Lock,
                seleccionado = actual == ProfileVisibility.PRIVATE,
                modifier = Modifier.weight(1f),
                onClick = { onCambiar(ProfileVisibility.PRIVATE) }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(actual.detalle, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
    }
}

@Composable
private fun ChipVis(
    vis: ProfileVisibility,
    icono: ImageVector,
    seleccionado: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = seleccionado,
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icono, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(vis.titulo, maxLines = 1)
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = EquatorialGold,
            selectedLabelColor = AndeanBlue
        )
    )
}

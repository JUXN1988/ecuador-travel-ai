package ec.travesias.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.model.Place
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.SurfaceContainerHigh
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Lugar en lista a todo el ancho, sin tarjeta ni sombra.
 * Misma información y acciones que PlaceSummaryCard; el diálogo de detalle no cambia.
 */
@Composable
fun PlaceSummaryRow(
    place: Place,
    modifier: Modifier = Modifier,
    inRoute: Boolean = false,
    onClick: () -> Unit,
    onAddToRoute: (() -> Unit)? = null,
    onToggleSave: (() -> Unit)? = null,
    onHold: (() -> Unit)? = null,
    detourLabel: String? = null
) {
    val rowMod = if (onHold != null) {
        modifier.fillMaxWidth().holdToMenu(onClick = onClick, onHold = onHold)
    } else {
        modifier.fillMaxWidth().clickable { onClick() }
    }
    Column(rowMod) {
            Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaceImageOrPlaceholder(
                place,
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AndeanBlue,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    place.locationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = VolcanicGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!detourLabel.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(detourLabel, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = StatusNormal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(place.costLabel, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                    place.rating?.let { r ->
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(12.dp))
                        Text(String.format(java.util.Locale.US, " %.1f", r), style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (onToggleSave != null) {
                    IconButton(onClick = onToggleSave, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (place.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Guardar",
                            tint = if (place.isSaved) EquatorialGold else VolcanicGray
                        )
                    }
                }
                if (onAddToRoute != null) {
                    FilledTonalButton(
                        onClick = onAddToRoute,
                        enabled = !inRoute,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (inRoute) SurfaceContainerHigh else AndeanBlue,
                            contentColor = if (inRoute) VolcanicGray else Color.White
                        )
                    ) {
                        Icon(if (inRoute) Icons.Default.Check else Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (inRoute) "En ruta" else "Añadir", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f))
    }
}

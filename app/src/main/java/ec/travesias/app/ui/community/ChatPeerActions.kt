package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.StatusNormal

/**
 * Acciones que siempre se ven en un chat de dos: agregar como amigo (si aún no lo es)
 * y ver el perfil cuando uno quiera.
 */
@Composable
fun ChatPeerActions(
    yaEsAmigo: Boolean,
    nombre: String,
    onAgregarAmigo: () -> Unit,
    onVerPerfil: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!yaEsAmigo) {
            Button(
                onClick = onAgregarAmigo,
                colors = ButtonDefaults.buttonColors(containerColor = StatusNormal),
                shape = RoundedCornerShape(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Agregar amigo", fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedButton(
            onClick = onVerPerfil,
            shape = RoundedCornerShape(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = AndeanBlue)
            Spacer(Modifier.width(4.dp))
            Text("Ver perfil de @$nombre", color = AndeanBlue, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        }
    }
}

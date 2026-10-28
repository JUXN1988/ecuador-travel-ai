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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Acciones al recibir un primer mensaje: responder (chat sin ser amigos),
 * agregar como amigo y ver el perfil. Cada botón vive aquí para no inflar CommunityScreen.
 */
@Composable
fun IncomingRequestActions(
    nombre: String,
    esDirecto: Boolean,
    yaEsAmigo: Boolean,
    onResponder: () -> Unit,
    onAgregarAmigo: () -> Unit,
    onVerPerfil: () -> Unit,
    onRechazar: (() -> Unit)? = null,
    mostrarResponder: Boolean = true
) {
    Surface(color = EquatorialGold.copy(alpha = 0.16f), shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                if (esDirecto) "@$nombre te escribió" else "Te invitaron a este grupo",
                color = AndeanBlue,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (esDirecto)
                    "Puedes responder sin ser amigos: cuando contestes, los dos podrán seguir chateando. Agrégalo cuando quieras."
                else
                    "Hasta que aceptes no formas parte del grupo. Puedes ver quién te invitó antes de decidir.",
                style = MaterialTheme.typography.labelSmall,
                color = VolcanicGray
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mostrarResponder) {
                    Button(
                        onClick = onResponder,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (esDirecto) "Responder" else "Aceptar")
                    }
                }
                if (esDirecto && !yaEsAmigo) {
                    Button(
                        onClick = onAgregarAmigo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusNormal),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Agregar")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onVerPerfil) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Ver perfil", color = AndeanBlue)
                }
                if (onRechazar != null) {
                    TextButton(onClick = onRechazar) {
                        Text("Rechazar", color = VolcanicGray)
                    }
                }
            }
        }
    }
}

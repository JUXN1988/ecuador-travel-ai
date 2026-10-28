package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.ui.theme.VolcanicGray
import ec.travesias.app.ui.util.Intents

/**
 * Invitar amigos a un grupo de viaje **sin códigos largos**.
 *
 * Tres caminos, todos de un toque:
 *  1. Elegir a alguien de “tu gente”.
 *  2. Escribir su @usuario.
 *  3. Mandar el enlace por WhatsApp (el código TRV1 queda dentro del link, no se teclea).
 *
 * Si [invitadoFijo] viene informado, en vez de pedir usuario se elige A QUÉ grupo meterlo.
 */
@Composable
fun InviteFriendsSheet(
    busy: Boolean,
    nombreGrupo: String,
    enlace: String,
    contactos: List<TravelerCard>,
    gruposViaje: List<TravelGroup> = emptyList(),
    invitadoFijo: TravelerCard? = null,
    onDismiss: () -> Unit,
    onInvitarUsuario: (String) -> Unit,
    onInvitarAGrupo: (groupId: String, usuario: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var usuario by remember { mutableStateOf(invitadoFijo?.username.orEmpty()) }
    val titulo = if (invitadoFijo != null) {
        "Meter a @" + invitadoFijo.username + " en un grupo"
    } else {
        "Invitar a " + nombreGrupo.ifBlank { "tu grupo" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titulo, color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (invitadoFijo != null) {
                    Text(
                        "Elige el grupo de viaje. Le llega como solicitud: entra cuando acepte, sin códigos.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(10.dp))
                    val grupos = gruposViaje.filter { !it.direct }
                    if (grupos.isEmpty()) {
                        Text(
                            "Todavía no tienes un grupo de viaje. Créalo en la pestaña Grupos y vuelve.",
                            style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                        )
                    } else {
                        grupos.forEach { g ->
                            Surface(
                                color = SurfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable(enabled = !busy && invitadoFijo.username.length >= 3) {
                                        onInvitarAGrupo(g.id, invitadoFijo.username)
                                    }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(g.name, color = AndeanBlue, fontWeight = FontWeight.Bold)
                                        Text(
                                            g.memberUids.size.toString() + " miembro(s)",
                                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "Tus compañeros entran por su @usuario o tocando el enlace. Nadie teclea códigos.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                    val gente = contactos.filter { it.username.isNotBlank() && !it.isBlocked }
                    if (gente.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Tu gente", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        gente.take(12).forEach { c ->
                            Surface(
                                color = SurfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clickable(enabled = !busy) { onInvitarUsuario(c.username) }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("@" + c.username, color = AndeanBlue, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("Invitar", color = StatusNormal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = usuario,
                        onValueChange = { usuario = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                        label = { Text("O escribe su @usuario") },
                        placeholder = { Text("ej. gabriel_ec") },
                        prefix = { Text("@", color = VolcanicGray) },
                        leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = AndeanBlue) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Le llega como solicitud. Entra al grupo cuando acepte.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )

                    if (enlace.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))
                        Spacer(Modifier.height(10.dp))
                        Text("Mandar por WhatsApp", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        Text(
                            "Un toque y adentro. El enlace lleva la llave del grupo: compártelo solo con quien quieras dentro.",
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    Intents.shareText(
                                        context,
                                        "Te invito a mi grupo \"" + nombreGrupo + "\" en Travesías en Ecuador.\n\nToca para entrar (sin códigos):\n" + enlace,
                                        "Invitación"
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = StatusNormal)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Compartir")
                            }
                            OutlinedButton(
                                onClick = { Intents.copyToClipboard(context, "Invitación", enlace) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                                Spacer(Modifier.size(6.dp))
                                Text("Copiar enlace", color = AndeanBlue)
                            }
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
                }
            }
        },
        confirmButton = {
            if (invitadoFijo == null) {
                Button(
                    onClick = { if (usuario.length >= 3) onInvitarUsuario(usuario) },
                    enabled = usuario.length >= 3 && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
                ) { Text("Invitar") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

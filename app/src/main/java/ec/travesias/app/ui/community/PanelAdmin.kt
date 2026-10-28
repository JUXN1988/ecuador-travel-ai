package ec.travesias.app.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.AdminService
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.HairLine
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.OnBrand
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.viewmodel.CommunityViewModel

/**
 * Fila del perfil con la huella propia. Es lo que hay que copiar en la consola de Firebase
 * para nombrar a alguien administrador (colección `admins`, un documento con esa huella).
 */
@Composable
fun FilaMiHuella(community: CommunityViewModel) {
    val portapapeles = LocalClipboardManager.current
    val huella = remember { community.miHuella() }
    if (huella.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceContainerLow)
            .clickable { portapapeles.setText(AnnotatedString(huella)); community.avisar("Huella copiada") }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mi huella", color = AndeanBlue, style = MaterialTheme.typography.titleSmall)
            Text(huella, color = MutedGray, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = MutedGray, modifier = Modifier.size(18.dp))
    }
}

/**
 * Herramientas de administrador. Solo aparece si la huella está dada de alta en `admins`.
 *
 * Qué hace: encontrar CUENTAS HUÉRFANAS —registros que nunca se llegaron a usar y que están
 * ocupando un @usuario— y borrarlas. No lee mensajes, ni fotos, ni cofres: todo eso va
 * cifrado con llaves que un administrador no tiene.
 */
@Composable
fun PanelAdmin(community: CommunityViewModel) {
    val esAdmin by community.soyAdmin.collectAsState()
    val busy by community.busy.collectAsState()
    var abierto by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { community.comprobarSiSoyAdmin() }
    if (!esAdmin) return

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StatusClosed.copy(alpha = 0.10f))
            .clickable { abierto = true; community.cargarHuerfanas() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Herramientas de administrador", color = AndeanBlue, style = MaterialTheme.typography.titleSmall)
            Text(
                "Limpiar cuentas que nunca se usaron",
                color = MutedGray, style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (abierto) DialogoHuerfanas(community, busy) { abierto = false }
}

@Composable
private fun DialogoHuerfanas(community: CommunityViewModel, busy: Boolean, onCerrar: () -> Unit) {
    val lista by community.huerfanas.collectAsState()
    var confirmar by remember { mutableStateOf<AdminService.Candidata?>(null) }

    AlertDialog(
        onDismissRequest = onCerrar,
        icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusClosed) },
        title = { Text("Cuentas sin usar", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Registros de más de 30 días que nunca se conectaron. Borrarlos libera su " +
                        "@usuario. No se toca ningún mensaje: los de los grupos son de los grupos.",
                    color = MutedGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                if (lista.isEmpty()) {
                    Text(
                        if (busy) "Revisando…" else "No hay ninguna. Todo limpio.",
                        color = MutedGray, style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(lista, key = { it.huella }) { c ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("@" + c.usuario, color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                                    Text(c.motivo, color = MutedGray, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(enabled = !busy, onClick = { confirmar = c }) {
                                    Text("Borrar", color = StatusClosed)
                                }
                            }
                            Spacer(Modifier.fillMaxWidth().height(1.dp).background(HairLine))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { community.cargarHuerfanas() }) {
                Text("Revisar de nuevo", color = AndeanBlue)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar", color = MutedGray) } }
    )

    // Borrar una cuenta no tiene vuelta atrás: se pregunta con el nombre delante.
    val aBorrar = confirmar
    if (aBorrar != null) {
        AlertDialog(
            onDismissRequest = { confirmar = null },
            title = { Text("¿Borrar @" + aBorrar.usuario + "?", color = AndeanBlue, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Se borra su ficha, su vitrina y su cofre. El @usuario queda libre para otro " +
                        "viajero. Esto no se puede deshacer.",
                    color = MutedGray, style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = { community.borrarHuerfana(aBorrar); confirmar = null },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusClosed, contentColor = OnBrand)
                ) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = null }) { Text("No", color = BrandFill) }
            }
        )
    }
}

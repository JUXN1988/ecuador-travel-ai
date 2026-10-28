package ec.travesias.app.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.social.HuellaBoveda
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.SkyAccent
import ec.travesias.app.ui.theme.StatusNormal

// ---------------------------------------------------------------------------
//  Entrar y recordar la contraseña con la huella (o el patrón / PIN del teléfono).
//  La contraseña es lo único que abre la cuenta en otro teléfono: si se olvida,
//  se pierde la cuenta. Por eso aquí se puede guardar y volver a verla.
// ---------------------------------------------------------------------------

/** Saca la FragmentActivity del contexto: BiometricPrompt no funciona sin ella. */
fun actividadDe(contexto: Context): FragmentActivity? {
    var c: Context? = contexto
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** ¿Tiene sentido ofrecer la huella en este teléfono? */
@Composable
fun huellaDisponible(): Boolean {
    val ctx = LocalContext.current
    return remember { HuellaBoveda.estado(ctx) == HuellaBoveda.Estado.LISTO && actividadDe(ctx) != null }
}

/**
 * Botón "Entrar con mi huella". Solo aparece si en este teléfono hay una contraseña guardada.
 * Al pasar el bloqueo devuelve usuario y contraseña para entrar sin teclear nada.
 */
@Composable
fun BotonEntrarConHuella(
    onEntrar: (usuario: String, clave: String) -> Unit,
    onAviso: (String) -> Unit
) {
    val lang by AppSettings.language.collectAsState()
    val ctx = LocalContext.current
    val hay = remember { HuellaBoveda.hayCredencial(ctx) }
    if (!hay || !huellaDisponible()) return

    OutlinedButton(
        onClick = {
            val act = actividadDe(ctx) ?: return@OutlinedButton
            HuellaBoveda.pedir(
                actividad = act,
                titulo = t(lang, "Entrar en Travesías", "Sign in to Travesías"),
                subtitulo = t(lang, "Usa tu huella, tu patrón o tu PIN", "Use your fingerprint, pattern or PIN"),
                alPasar = {
                    val cred = HuellaBoveda.leer(ctx)
                    if (cred == null) onAviso(t(lang, "No se pudo leer la contraseña guardada", "Couldn't read the saved password"))
                    else onEntrar(cred.first, cred.second)
                },
                alFallar = onAviso
            )
        },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(25.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AndeanBlue)
    ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(8.dp))
        Text(t(lang, "Entrar con mi huella", "Sign in with my fingerprint"), fontWeight = FontWeight.Bold)
    }
}

/**
 * Se ofrece una sola vez, justo después de entrar: guardar la contraseña detrás del
 * bloqueo del teléfono. Es el seguro contra "se me olvidó la contraseña".
 */
@Composable
fun DialogoGuardarHuella(
    usuario: String,
    clave: String,
    onCerrar: () -> Unit,
    onAviso: (String) -> Unit
) {
    val lang by AppSettings.language.collectAsState()
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onCerrar,
        icon = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = SkyAccent, modifier = Modifier.size(34.dp)) },
        title = {
            Text(
                t(lang, "¿Guardar tu contraseña con la huella?", "Save your password with your fingerprint?"),
                color = AndeanBlue, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                t(
                    lang,
                    "Tu contraseña es lo único que abre tu cuenta en otro teléfono. Si la guardas aquí, " +
                        "podrás entrar con la huella y volver a verla cuando la necesites. Queda cifrada en " +
                        "este teléfono y no se envía a ningún lado.",
                    "Your password is the only thing that opens your account on another phone. Save it here " +
                        "and you can sign in with your fingerprint and read it back whenever you need it. It stays " +
                        "encrypted on this phone and is never sent anywhere."
                ),
                color = MutedGray, style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val act = actividadDe(ctx)
                if (act == null) { onCerrar(); return@TextButton }
                HuellaBoveda.pedir(
                    actividad = act,
                    titulo = t(lang, "Guardar tu contraseña", "Save your password"),
                    subtitulo = t(lang, "Confirma con tu huella, patrón o PIN", "Confirm with fingerprint, pattern or PIN"),
                    alPasar = {
                        HuellaBoveda.guardar(ctx, usuario, clave)
                        onAviso(t(lang, "Listo: ya puedes entrar con la huella", "Done: you can now sign in with your fingerprint"))
                        onCerrar()
                    },
                    alFallar = { onAviso(it); onCerrar() }
                )
            }) { Text(t(lang, "Guardar", "Save"), color = AndeanBlue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text(t(lang, "Ahora no", "Not now"), color = MutedGray) }
        }
    )
}

/**
 * Fila del perfil: ver la contraseña guardada (tras pasar el bloqueo) o dejar de guardarla.
 * Sirve para copiarla antes de cambiar de teléfono.
 */
@Composable
fun FilaContrasenaGuardada(onAviso: (String) -> Unit) {
    val lang by AppSettings.language.collectAsState()
    val ctx = LocalContext.current
    var guardada by remember { mutableStateOf(HuellaBoveda.hayCredencial(ctx)) }
    var aLaVista by remember { mutableStateOf<String?>(null) }
    if (!huellaDisponible()) return

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SkyAccent.copy(alpha = 0.08f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(SkyAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = SkyAccent, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t(lang, "Mi contraseña", "My password"),
                    color = AndeanBlue, style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (guardada) t(lang, "Guardada con tu huella en este teléfono", "Saved with your fingerprint on this phone")
                    else t(lang, "No está guardada: si la olvidas, se pierde la cuenta", "Not saved: if you forget it, the account is gone"),
                    color = if (guardada) StatusNormal else MutedGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (guardada) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val act = actividadDe(ctx) ?: return@TextButton
                    HuellaBoveda.pedir(
                        actividad = act,
                        titulo = t(lang, "Ver mi contraseña", "Show my password"),
                        subtitulo = t(lang, "Confirma que eres tú", "Confirm it's you"),
                        alPasar = { aLaVista = HuellaBoveda.leer(ctx)?.second },
                        alFallar = onAviso
                    )
                }) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = SkyAccent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(t(lang, "Verla", "Show it"), color = SkyAccent)
                }
                TextButton(onClick = {
                    HuellaBoveda.olvidar(ctx)
                    guardada = false
                    onAviso(t(lang, "Se quitó la contraseña guardada", "Saved password removed"))
                }) { Text(t(lang, "Dejar de guardarla", "Stop saving it"), color = MutedGray) }
            }
        }
    }

    val texto = aLaVista
    if (texto != null) {
        AlertDialog(
            onDismissRequest = { aLaVista = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null, tint = AndeanBlue) },
            title = { Text(t(lang, "Tu contraseña", "Your password"), color = AndeanBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(texto, color = AndeanBlue, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t(lang, "Anótala en un lugar seguro. Con tu @usuario y esta contraseña recuperas la cuenta en cualquier teléfono.",
                            "Write it down somewhere safe. With your @username and this password you get the account back on any phone."),
                        color = MutedGray, style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { aLaVista = null }) { Text(t(lang, "Listo", "Done"), color = AndeanBlue) } }
        )
    }
}

package ec.travesias.app.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Abrir un chat directo con un @usuario, a pantalla completa (no diálogo).
 */
@Composable
fun CommunityWriteUserPage(
    busy: Boolean,
    onDismiss: () -> Unit,
    onEscribir: (usuario: String) -> Unit
) {
    var usuario by remember { mutableStateOf("") }

    BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(SurfaceLight)) {
        CommunityPageBar(title = "Escribir a un viajero", onBack = onDismiss)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Se abre un chat solo entre ustedes dos, cifrado de punta a punta. Tu usuario no aparece en ninguna lista: solo te encuentra quien lo sepa escribir tal cual.",
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray
            )
            OutlinedTextField(
                value = usuario,
                onValueChange = { usuario = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                label = { Text("Nombre de usuario") },
                placeholder = { Text("ej. gabriel_ec") },
                prefix = { Text("@", color = VolcanicGray) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = AndeanBlue)
            }
        }
        Button(
            onClick = { if (usuario.length >= 3) onEscribir(usuario) },
            enabled = usuario.length >= 3 && !busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) { Text("Escribirle", fontWeight = FontWeight.Bold) }
    }
}

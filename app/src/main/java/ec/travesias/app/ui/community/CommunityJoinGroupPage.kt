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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Unirse a un grupo pegando el enlace, a pantalla completa (no diálogo).
 */
@Composable
fun CommunityJoinGroupPage(
    onDismiss: () -> Unit,
    onUnirme: (codigo: String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(SurfaceLight)) {
        CommunityPageBar(title = "Unirme a un grupo", onBack = onDismiss)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Pega el enlace que te mandaron por WhatsApp. No hace falta copiar códigos largos. Hace falta internet.",
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim().take(200) },
                label = { Text("Enlace o invitación") },
                placeholder = { Text("Pega el enlace (travesias.ec/g/…)") },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = { if (code.isNotBlank()) onUnirme(code) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) { Text("Unirme", fontWeight = FontWeight.Bold) }
    }
}

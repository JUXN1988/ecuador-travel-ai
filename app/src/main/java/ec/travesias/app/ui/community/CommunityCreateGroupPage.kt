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
 * Crear un grupo de viaje a pantalla completa (no diálogo).
 */
@Composable
fun CommunityCreateGroupPage(
    onDismiss: () -> Unit,
    onConfirm: (nombre: String, descripcion: String) -> Unit
) {
    var nombre by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }

    BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(SurfaceLight)) {
        CommunityPageBar(title = "Nuevo grupo de viaje", onBack = onDismiss)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Un grupo sirve para armar el viaje entre varios: chat cifrado, fotos y la ruta compartida.",
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray
            )
            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it.take(60) },
                label = { Text("Nombre del grupo") },
                placeholder = { Text("Ej. Ruta Costa 2026") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it.take(160) },
                label = { Text("Descripción (opcional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = { if (nombre.isNotBlank()) onConfirm(nombre.trim(), descripcion.trim()) },
            enabled = nombre.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) { Text("Crear", fontWeight = FontWeight.Bold) }
    }
}

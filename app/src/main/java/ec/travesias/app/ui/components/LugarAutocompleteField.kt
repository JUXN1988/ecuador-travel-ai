package ec.travesias.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.TravesiasApp
import ec.travesias.app.data.local.GazetteerData
import ec.travesias.app.data.repository.PlaceIndexRepository
import ec.travesias.app.data.repository.PlaceSuggestion
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.SurfaceContainerLowest
import ec.travesias.app.ui.theme.VolcanicGray
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Campo de texto con autocompletado de lugares del Ecuador.
 *
 * Propone provincias, cantones, localidades turísticas, los lugares del catálogo y las
 * direcciones ya buscadas antes. Todo sale de la base local del teléfono, así que responde
 * al instante y funciona sin internet.
 */
@Composable
fun LugarAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    maxSuggestions: Int = 6,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    onPicked: (PlaceSuggestion) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val index = remember { runCatching { TravesiasApp.from(context).placeIndex }.getOrNull() }

    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }
    var justPicked by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (index == null || !focused || justPicked || value.trim().length < 2) {
            suggestions = emptyList()
            justPicked = false
            return@LaunchedEffect
        }
        delay(160)
        suggestions = runCatching { index.suggest(value, maxSuggestions) }.getOrDefault(emptyList())
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { justPicked = false; onValueChange(it) },
            label = { Text(label) },
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = true,
            shape = shape,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceContainerLowest,
                unfocusedContainerColor = SurfaceContainerLowest
            )
        )

        if (suggestions.isNotEmpty()) {
            Surface(
                color = SurfaceContainerLowest,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    suggestions.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    justPicked = true
                                    suggestions = emptyList()
                                    onValueChange(s.name)
                                    scope.launch { runCatching { index?.markUsed(s) } }
                                    onPicked(s)
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(iconFor(s.kind), contentDescription = null, tint = tintFor(s.kind), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    s.name, color = AndeanBlue, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1
                                )
                                Text(s.subtitle, color = VolcanicGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        if (i < suggestions.size - 1) {
                            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.10f))
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    GazetteerData.KIND_PROVINCE -> Icons.Default.Public
    GazetteerData.KIND_CANTON -> Icons.Default.LocationCity
    PlaceIndexRepository.KIND_PLACE -> Icons.Default.Place
    else -> Icons.Default.LocationOn
}

private fun tintFor(kind: String) = when (kind) {
    GazetteerData.KIND_PROVINCE -> AndeanBlue
    GazetteerData.KIND_CANTON -> GalapagosTeal
    PlaceIndexRepository.KIND_PLACE -> EquatorialGold
    else -> VolcanicGray
}

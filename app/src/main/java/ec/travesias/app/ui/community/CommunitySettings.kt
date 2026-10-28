package ec.travesias.app.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.TravelerProfile
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Ajustes de Comunidad como pantalla de la app (no diálogo ni tarjeta flotante).
 * Mismos datos que el editor anterior: bio, gustos, ruta, privacidad y sesión.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunitySettings(
    profile: TravelerProfile,
    options: List<String>,
    tagOptions: List<String>,
    conCuenta: Boolean,
    onCerrar: () -> Unit,
    onShowcase: (Boolean) -> Unit,
    onVisibility: (ProfileVisibility) -> Unit,
    onSignOut: () -> Unit,
    onProtegerCuenta: () -> Unit,
    onSave: (TravelerProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    var bio by remember { mutableStateOf(profile.bio) }
    var region by remember { mutableStateOf(profile.region) }
    var origin by remember { mutableStateOf(profile.routeOrigin) }
    var dest by remember { mutableStateOf(profile.routeDestination) }
    var month by remember { mutableStateOf(profile.travelMonth) }
    val interests = remember { mutableStateListOf<String>().apply { addAll(profile.interests) } }
    val travelTags = remember { mutableStateListOf<String>().apply { addAll(profile.travelTags) } }
    var vis by remember { mutableStateOf(profile.visibility) }

    // Atrás del sistema cierra esta pantalla, no la pestaña Comunidad.
    BackHandler(onBack = onCerrar)

    Column(modifier.fillMaxSize().background(SurfaceLight)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCerrar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = AndeanBlue)
            }
            Text(
                "⚙️  Ajustes de comunidad",
                color = AndeanBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Tu perfil de viajero. Quien te encuentre ve lo que elijas abajo.",
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray
            )

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it.take(120) },
                label = { Text("Sobre mí") },
                placeholder = { Text("Ej. Me encanta la Sierra y el ceviche 🌋🍤") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            Seccion("💚  ¿Qué te gusta?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                options.forEach { opt ->
                    val sel = interests.contains(opt)
                    FilterChip(
                        selected = sel,
                        onClick = { if (sel) interests.remove(opt) else interests.add(opt) },
                        label = { Text(opt) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EquatorialGold,
                            selectedLabelColor = AndeanBlue
                        )
                    )
                }
            }

            Seccion("🗺️  Próxima región")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EcuadorRegion.entries.forEach { r ->
                    FilterChip(
                        selected = region == r.displayName,
                        onClick = { region = if (region == r.displayName) "" else r.displayName },
                        label = { Text(r.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AndeanBlue,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }
            }

            OutlinedTextField(
                value = origin,
                onValueChange = { origin = it },
                label = { Text("Mi ruta: desde") },
                placeholder = { Text("Quito") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = dest,
                onValueChange = { dest = it },
                label = { Text("Mi ruta: hasta") },
                placeholder = { Text("Baños") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = month,
                onValueChange = { month = it },
                label = { Text("Mes de viaje (AAAA-MM)") },
                placeholder = { Text("2026-10") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (tagOptions.isNotEmpty()) {
                Seccion("🎒  Cómo viajas")
                Text(
                    "Para encontrar compañeros, aventón o mochileros.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VolcanicGray
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tagOptions.forEach { tag ->
                        val sel = travelTags.contains(tag)
                        FilterChip(
                            selected = sel,
                            onClick = { if (sel) travelTags.remove(tag) else travelTags.add(tag) },
                            label = { Text(tag) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EquatorialGold,
                                selectedLabelColor = AndeanBlue
                            )
                        )
                    }
                }
            }

            if (conCuenta) {
                HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))
                Seccion("👀  Quién puede verte")
                Text(
                    "El chat sigue cifrado entre ustedes. Esto solo cubre perfil y álbum.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VolcanicGray
                )
                VisRow(
                    vis = ProfileVisibility.PUBLIC,
                    icono = Icons.Default.Public,
                    emoji = "🌍",
                    seleccionado = vis == ProfileVisibility.PUBLIC,
                    onClick = {
                        vis = ProfileVisibility.PUBLIC
                        onVisibility(ProfileVisibility.PUBLIC)
                        onShowcase(true)
                    }
                )
                VisRow(
                    vis = ProfileVisibility.FRIENDS,
                    icono = Icons.Default.People,
                    emoji = "🤝",
                    seleccionado = vis == ProfileVisibility.FRIENDS,
                    onClick = {
                        vis = ProfileVisibility.FRIENDS
                        onVisibility(ProfileVisibility.FRIENDS)
                        onShowcase(false)
                    }
                )
                VisRow(
                    vis = ProfileVisibility.PRIVATE,
                    icono = Icons.Default.Lock,
                    emoji = "🔒",
                    seleccionado = vis == ProfileVisibility.PRIVATE,
                    onClick = {
                        vis = ProfileVisibility.PRIVATE
                        onVisibility(ProfileVisibility.PRIVATE)
                        onShowcase(false)
                    }
                )
                Text(vis.detalle, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)

                TextButton(onClick = onSignOut, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusClosed)
                    Spacer(Modifier.width(6.dp))
                    Text("Cerrar sesión en este teléfono", color = StatusClosed)
                }
            } else {
                TextButton(onClick = onProtegerCuenta) {
                    Text("Proteger cuenta con contraseña", color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                onSave(
                    profile.copy(
                        bio = bio.trim(),
                        interests = interests.toList(),
                        region = region,
                        routeOrigin = origin.trim(),
                        routeDestination = dest.trim(),
                        travelMonth = month.trim(),
                        travelTags = travelTags.toList(),
                        visibility = vis,
                        showcase = vis == ProfileVisibility.PUBLIC
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text("Guardar cambios", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(titulo, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
}

/** Una opción de visibilidad en fila, pegada a la pantalla (sin tarjeta). */
@Composable
private fun VisRow(
    vis: ProfileVisibility,
    icono: ImageVector,
    emoji: String,
    seleccionado: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = seleccionado,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AndeanBlue)
        )
        Icon(icono, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(emoji + "  " + vis.titulo, color = AndeanBlue, fontWeight = if (seleccionado) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

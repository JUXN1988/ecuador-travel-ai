package ec.travesias.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.RouteViewModel

/**
 * Formulario para que un viajero agregue un lugar nuevo al catálogo: nombre, categoría,
 * coordenadas (GPS real, buscador o punto marcado en el mapa), provincia/cantón y fotos propias.
 * Si no está seguro de la ubicación exacta, lo marca para que la comunidad ayude a confirmarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlaceDialog(viewModel: RouteViewModel) {
    val draft by viewModel.newPlaceDraft.collectAsState()

    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PlaceCategory.NATURE) }
    var description by remember { mutableStateOf("") }
    var province by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var approximate by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<Uri>() }

    LaunchedEffect(draft.province, draft.city) {
        if (province.isBlank()) province = draft.province
        if (city.isBlank()) city = draft.city
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && photos.size < 6) photos.add(uri)
    }

    AlertDialog(
        onDismissRequest = { viewModel.closeAddPlace() },
        title = { Text("Agregar un lugar nuevo", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Ayuda a otros viajeros: agrega un sitio que no esté en el catálogo.",
                    style = MaterialTheme.typography.bodySmall, color = VolcanicGray
                )

                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(80) },
                    label = { Text("Nombre del lugar *") },
                    placeholder = { Text("Ej. Cascada El Escondite") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text("Categoría", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PlaceCategory.entries.filter { it != PlaceCategory.ALL }) { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c.displayName, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = categoryColor(c), selectedLabelColor = androidx.compose.ui.graphics.Color.White, selectedLeadingIconColor = androidx.compose.ui.graphics.Color.White)
                        )
                    }
                }

                HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))

                Text("Ubicación *", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                Text(
                    "La forma más fácil: si ya estás en el lugar, usa tu GPS. Si no, búscalo o marca el punto en el mapa.",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.useMyLocationForNewPlace() },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Usar mi GPS", style = MaterialTheme.typography.labelMedium)
                    }
                    if (draft.resolvingLocation) {
                        CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp).align(Alignment.CenterVertically))
                    }
                }
                LugarAutocompleteField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "O buscar el lugar",
                    placeholder = "Provincia, cantón, referencia cercana…",
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AndeanBlue) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onPicked = { s -> searchQuery = s.name; viewModel.setNewPlaceFromSuggestion(s) }
                )
                if (draft.hasCoords) {
                    Surface(color = StatusNormal.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusNormal, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                String.format(java.util.Locale.US, "%.5f, %.5f", draft.latitude, draft.longitude),
                                style = MaterialTheme.typography.labelSmall, color = AndeanBlue
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = province, onValueChange = { province = it },
                    label = { Text("Provincia") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (province.isNotBlank()) {
                    val cantones = remember(province) { viewModel.cantonsForNewPlaceProvince(province) }
                    if (cantones.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(cantones) { canton ->
                                FilterChip(selected = city == canton, onClick = { city = canton }, label = { Text(canton, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = city, onValueChange = { city = it },
                    label = { Text("Cantón / ciudad") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = approximate, onCheckedChange = { approximate = it })
                    Text("No estoy seguro/a de la ubicación exacta", style = MaterialTheme.typography.bodySmall, color = VolcanicGray, modifier = Modifier.weight(1f))
                }
                if (approximate) {
                    Text(
                        "Otros viajeros podrán confirmarla con un comentario. Si además buscas compañía " +
                            "o aventón para llegar, actívalo en Comunidad → tu perfil → Etiquetas de viaje.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                }

                HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))

                OutlinedTextField(
                    value = description, onValueChange = { description = it.take(300) },
                    label = { Text("Descripción (opcional)") },
                    placeholder = { Text("Cómo es, cómo llegar, qué llevar…") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )

                Text("Fotos", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos) { uri ->
                        Box {
                            AsyncImage(
                                model = uri, contentDescription = "Foto", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(onClick = { photos.remove(uri) }, modifier = Modifier.size(20.dp).align(Alignment.TopEnd)) {
                                Icon(Icons.Default.Cancel, contentDescription = "Quitar", tint = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                    if (photos.size < 6) {
                        item {
                            Surface(
                                color = SurfaceContainerLowest, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    IconButton(onClick = { photoPicker.launch("image/*") }) {
                                        Icon(Icons.Default.AddAPhoto, contentDescription = "Agregar foto", tint = AndeanBlue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.submitNewPlace(name, category, province, city, description, photos.toList(), approximate)
                },
                enabled = name.isNotBlank() && draft.hasCoords,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Agregar lugar") }
        },
        dismissButton = { TextButton(onClick = { viewModel.closeAddPlace() }) { Text("Cancelar") } }
    )
}

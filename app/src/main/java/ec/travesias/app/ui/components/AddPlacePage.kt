package ec.travesias.app.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray
import ec.travesias.app.viewmodel.RouteViewModel

/**
 * Agregar un lugar al catálogo a pantalla completa (no diálogo).
 * Misma lógica que AddPlaceDialog: GPS, búsqueda, fotos y envío al ViewModel.
 */
@Composable
fun AddPlacePage(viewModel: RouteViewModel) {
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

    BackHandler { viewModel.closeAddPlace() }

    Column(Modifier.fillMaxSize().background(SurfaceLight)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeAddPlace() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = AndeanBlue)
            }
            Text(
                "Agregar un lugar",
                color = AndeanBlue,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Ayuda a otros viajeros: agrega un sitio que no esté en el catálogo.",
                style = MaterialTheme.typography.bodySmall,
                color = VolcanicGray
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
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = categoryColor(c),
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                            selectedLeadingIconColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }
            }

            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))

            Text("Ubicación *", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Text(
                "Si ya estás en el lugar, usa tu GPS. Si no, búscalo o márcalo en el mapa.",
                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    CircularProgressIndicator(color = AndeanBlue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusNormal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        String.format(java.util.Locale.US, "%.5f, %.5f", draft.latitude, draft.longitude),
                        style = MaterialTheme.typography.labelSmall, color = AndeanBlue
                    )
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
                    "Otros viajeros podrán confirmarla. Si buscas compañía, actívalo en Comunidad → perfil.",
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
                        IconButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.size(72.dp)) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = "Agregar foto", tint = AndeanBlue)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                viewModel.submitNewPlace(name, category, province, city, description, photos.toList(), approximate)
            },
            enabled = name.isNotBlank() && draft.hasCoords,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
            shape = RoundedCornerShape(25.dp)
        ) { Text("Agregar lugar", fontWeight = FontWeight.Bold) }
    }
}

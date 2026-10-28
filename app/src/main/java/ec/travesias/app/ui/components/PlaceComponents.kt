package ec.travesias.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ec.travesias.app.data.model.*
import ec.travesias.app.data.social.CommentCategory
import ec.travesias.app.data.social.PhotoStore
import ec.travesias.app.data.social.PlaceComment
import ec.travesias.app.ui.map.OsmMap
import ec.travesias.app.ui.map.categoryArgb
import ec.travesias.app.ui.map.toComposeColor
import ec.travesias.app.ui.map.toPin
import ec.travesias.app.ui.theme.*
import ec.travesias.app.ui.util.Intents

fun categoryIcon(category: PlaceCategory): ImageVector = when (category) {
    PlaceCategory.ALL -> Icons.Default.Explore
    PlaceCategory.NATURE -> Icons.Default.Landscape
    PlaceCategory.FOOD -> Icons.Default.Restaurant
    PlaceCategory.CULTURE -> Icons.Default.Museum
    PlaceCategory.ADVENTURE -> Icons.Default.Hiking
    PlaceCategory.BEACH -> Icons.Default.BeachAccess
    PlaceCategory.VIEWPOINT -> Icons.Default.PhotoCamera
    PlaceCategory.LODGING -> Icons.Default.Hotel
    PlaceCategory.SERVICES -> Icons.Default.LocalGasStation
}

fun categoryColor(category: PlaceCategory): Color = categoryArgb(category).toComposeColor()

fun platformIcon(p: SourcePlatform): ImageVector = when (p) {
    SourcePlatform.YOUTUBE -> Icons.Default.PlayCircle
    SourcePlatform.TIKTOK -> Icons.Default.MusicNote
    SourcePlatform.INSTAGRAM -> Icons.Default.CameraAlt
    SourcePlatform.FACEBOOK -> Icons.Default.ThumbUp
    SourcePlatform.GOOGLE_PLACES -> Icons.Default.Place
    else -> Icons.Default.Language
}

/**
 * Muestra la foto del propio viajero si el lugar tiene una (vive en este teléfono);
 * si no, la foto del catálogo; si no hay ninguna, un degradado con el ícono de la categoría.
 */
@Composable
fun PlaceImageOrPlaceholder(place: Place, modifier: Modifier = Modifier) {
    val localBitmap = remember(place.localPhotoPaths) {
        place.localPhotoPaths.firstOrNull()?.let { PhotoStore.decodeLocal(it, 900) }
    }
    when {
        localBitmap != null -> Image(
            bitmap = localBitmap.asImageBitmap(),
            contentDescription = place.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        place.primaryImage != null -> AsyncImage(
            model = place.primaryImage,
            contentDescription = place.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        else -> Box(
            modifier = modifier.background(
                Brush.linearGradient(listOf(categoryColor(place.category).copy(alpha = 0.85f), AndeanBlue))
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(categoryIcon(place.category), contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
fun VerificationChip(level: VerificationLevel, mentions: Int = 0) {
    val (color, label, icon) = when (level) {
        VerificationLevel.CURATED -> Triple(StatusNormal, "Curado", Icons.Default.Verified)
        VerificationLevel.VERIFIED -> Triple(GalapagosTeal, "Verificado IA", Icons.Default.CheckCircle)
        VerificationLevel.AI_SUGGESTED -> Triple(StatusCaution, "Sugerido IA", Icons.Default.AutoAwesome)
        VerificationLevel.USER -> Triple(AndeanBlue, "Desde tu enlace", Icons.Default.Link)
        VerificationLevel.SHARED -> Triple(EquatorialGold, "De un compañero", Icons.Default.Group)
        VerificationLevel.OSM -> Triple(VolcanicGray, "Del mapa", Icons.Default.Map)
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
            if (mentions > 0) {
                Text(" · $mentions menciones", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/**
 * Tarjeta compacta de un lugar para listas (Descubrir, Radar, Comunidad).
 */
@Composable
fun PlaceSummaryCard(
    place: Place,
    modifier: Modifier = Modifier,
    inRoute: Boolean = false,
    onClick: () -> Unit,
    onAddToRoute: (() -> Unit)? = null,
    onToggleSave: (() -> Unit)? = null,
    /** "a 12 km de la vía": lo primero que mira el viajero para decidir si para o no. */
    detourLabel: String? = null
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                PlaceImageOrPlaceholder(place, modifier = Modifier.fillMaxSize())
                Surface(color = AndeanBlue.copy(alpha = 0.88f), shape = RoundedCornerShape(bottomEnd = 12.dp), modifier = Modifier.align(Alignment.TopStart)) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(place.locationLabel, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Cuánto hay que salirse de la vía: se pone abajo a la izquierda, sobre la foto,
                // porque es el dato que decide si el viajero para o sigue de largo.
                if (!detourLabel.isNullOrBlank()) {
                    Surface(
                        color = EquatorialGold.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(topEnd = 12.dp),
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(detourLabel, color = AndeanBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                Surface(color = categoryColor(place.category), shape = RoundedCornerShape(bottomStart = 12.dp), modifier = Modifier.align(Alignment.TopEnd)) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(place.category), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(place.category.displayName, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                if (onToggleSave != null) {
                    IconButton(onClick = onToggleSave, modifier = Modifier.align(Alignment.BottomEnd)) {
                        Icon(
                            if (place.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Guardar", tint = if (place.isSaved) EquatorialGold else Color.White
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(14.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                VerificationChip(place.verification, place.socialMentions)
                if (place.shortDescription.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(place.shortDescription, style = MaterialTheme.typography.bodySmall, color = VolcanicGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Payments, contentDescription = null, tint = StatusNormal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(place.costLabel, style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.SemiBold)
                        place.rating?.let { r ->
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Default.Star, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(14.dp))
                            Text(String.format(java.util.Locale.US, " %.1f", r), style = MaterialTheme.typography.labelMedium, color = VolcanicGray)
                        }
                    }
                    if (onAddToRoute != null) {
                        FilledTonalButton(
                            onClick = onAddToRoute,
                            enabled = !inRoute,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (inRoute) SurfaceContainerHigh else AndeanBlue, contentColor = if (inRoute) VolcanicGray else Color.White)
                        ) {
                            Icon(if (inRoute) Icons.Default.Check else Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (inRoute) "En tu ruta" else "Añadir", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hoja inferior con la tarjeta de presentación completa del lugar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailSheet(
    place: Place,
    inRoute: Boolean,
    totalDays: Int,
    onDismiss: () -> Unit,
    onAddToRoute: (day: Int) -> Unit,
    onToggleSave: () -> Unit,
    onShowOnMap: (() -> Unit)? = null,
    onShareToGroup: (() -> Unit)? = null,
    comments: List<PlaceComment> = emptyList(),
    onOpenComments: ((placeId: String) -> Unit)? = null,
    onCloseComments: (() -> Unit)? = null,
    onAddComment: ((text: String, category: CommentCategory, photoUri: Uri?) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(place.id) { onOpenComments?.invoke(place.id) }
    DisposableEffect(Unit) { onDispose { onCloseComments?.invoke() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceLight) {
        PlaceDetailContent(
            place, inRoute, totalDays, onAddToRoute, onToggleSave, onShowOnMap, onShareToGroup,
            comments = comments, onAddComment = onAddComment
        )
    }
}

@Composable
fun PlaceDetailContent(
    place: Place,
    inRoute: Boolean,
    totalDays: Int,
    onAddToRoute: (day: Int) -> Unit,
    onToggleSave: () -> Unit,
    onShowOnMap: (() -> Unit)? = null,
    onShareToGroup: (() -> Unit)? = null,
    showMiniMap: Boolean = true,
    comments: List<PlaceComment> = emptyList(),
    onAddComment: ((text: String, category: CommentCategory, photoUri: Uri?) -> Unit)? = null
) {
    val context = LocalContext.current
    var showDayPicker by remember { mutableStateOf(false) }
    var showAddComment by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        // Imagen / cabecera
        Box(modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp))) {
            PlaceImageOrPlaceholder(place, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)), startY = 120f)))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(place.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = EquatorialGold, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${place.locationLabel} · ${place.region.displayName}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onToggleSave, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(if (place.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "Guardar", tint = if (place.isSaved) EquatorialGold else Color.White)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VerificationChip(place.verification, place.socialMentions)
            Surface(color = categoryColor(place.category).copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(place.category), contentDescription = null, tint = categoryColor(place.category), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(place.category.displayName, style = MaterialTheme.typography.labelSmall, color = categoryColor(place.category), fontWeight = FontWeight.Bold)
                }
            }
        }

        if (place.locationApproximate) {
            Spacer(Modifier.height(10.dp))
            Surface(color = StatusCaution.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PinDrop, contentDescription = null, tint = StatusCaution, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ubicación aproximada: quien la agregó no estaba seguro del punto exacto. " +
                            "Si conoces este lugar, ayuda a confirmarla con un comentario.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                }
            }
        }

        // Acciones principales
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (totalDays <= 1) onAddToRoute(1) else showDayPicker = true },
                enabled = !inRoute,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (inRoute) Icons.Default.Check else Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (inRoute) "Ya en tu ruta" else "Añadir a mi ruta")
            }
            OutlinedButton(
                onClick = { Intents.navigateTo(context, place.latitude, place.longitude, place.name) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp), tint = AndeanBlue)
                Spacer(Modifier.width(6.dp))
                Text("Navegar", color = AndeanBlue)
            }
        }

        // Datos clave
        Spacer(Modifier.height(16.dp))
        InfoGrid(place)

        // Descripción
        if (place.fullDescription.isNotBlank() || place.shortDescription.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.Info, "Acerca del lugar")
            Text(place.fullDescription.ifBlank { place.shortDescription }, style = MaterialTheme.typography.bodyMedium, color = VolcanicGray)
        }

        // Cómo llegar
        if (place.howToGetThere.isNotBlank() || place.transportOptions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.Directions, "Cómo llegar")
            if (place.howToGetThere.isNotBlank()) Text(place.howToGetThere, style = MaterialTheme.typography.bodyMedium, color = VolcanicGray)
            if (place.transportOptions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(place.transportOptions) { t ->
                        AssistChip(onClick = {}, label = { Text(t, style = MaterialTheme.typography.labelSmall) }, leadingIcon = { Icon(Icons.Default.DirectionsBus, contentDescription = null, modifier = Modifier.size(14.dp)) })
                    }
                }
            }
        }

        // Mini mapa offline
        if (showMiniMap) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.Map, "Ubicación (mapa offline)")
            Box(modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp))) {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    pins = listOf(place.toPin(highlighted = true)),
                    focus = Pair(place.latitude, place.longitude),
                    focusZoom = 14.0,
                    fitToPins = false,
                    interactive = false
                )
                Surface(
                    color = Color.White.copy(alpha = 0.9f), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clickable {
                        Intents.copyToClipboard(context, "Coordenadas", "${place.latitude}, ${place.longitude}")
                    }
                ) {
                    Text(
                        String.format(java.util.Locale.US, "%.5f, %.5f  (toca para copiar)", place.latitude, place.longitude),
                        style = MaterialTheme.typography.labelSmall, color = AndeanBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (onShowOnMap != null) {
                    FilledTonalButton(onClick = onShowOnMap, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ver en mapa", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Contactos
        if (!place.contact.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.ContactPhone, "Contactos y redes")
            ContactRow(place)
        }

        // Tips
        if (place.localTips.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.TipsAndUpdates, "Consejos de viajeros")
            place.localTips.forEach { tip -> Text("• $tip", style = MaterialTheme.typography.bodySmall, color = VolcanicGray, modifier = Modifier.padding(vertical = 2.dp)) }
        }

        // Comentarios y tips de viajeros
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(Icons.Default.Forum, "Comentarios de viajeros (${comments.size})")
            if (onAddComment != null) {
                TextButton(onClick = { showAddComment = true }) {
                    Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Comentar", color = AndeanBlue)
                }
            }
        }
        if (comments.isEmpty()) {
            Text(
                "Sé el primero en contar dónde comer, precios, cómo llegar o cualquier tip para otros viajeros.",
                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                comments.take(20).forEach { c -> PlaceCommentRow(c) }
            }
        }

        // Fuentes
        if (place.sources.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(Icons.Default.Public, "Fuentes (${place.sources.size})")
            place.sources.take(8).forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { Intents.openUrl(context, s.url) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(platformIcon(s.platform), contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title.ifBlank { s.platform.displayName }, style = MaterialTheme.typography.bodySmall, color = AndeanBlue, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (s.author.isNotBlank()) "${s.platform.displayName} · @${s.author}" else s.url, style = MaterialTheme.typography.labelSmall, color = VolcanicGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Compartir
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Intents.shareText(context, Intents.placeShareText(place), place.name) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = AndeanBlue)
                Spacer(Modifier.width(6.dp))
                Text("Compartir", color = AndeanBlue)
            }
            if (onShareToGroup != null) {
                OutlinedButton(onClick = onShareToGroup, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(6.dp))
                    Text("Al grupo", color = AndeanBlue)
                }
            }
        }
        if (place.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(place.tags.joinToString("  ") { "#${it.replace(" ", "")}" }, style = MaterialTheme.typography.labelSmall, color = GalapagosTeal)
        }
    }

    if (showDayPicker) {
        AlertDialog(
            onDismissRequest = { showDayPicker = false },
            title = { Text("¿Qué día de tu ruta?", color = AndeanBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    (1..totalDays).forEach { d ->
                        TextButton(onClick = { showDayPicker = false; onAddToRoute(d) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Día $d", color = AndeanBlue)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDayPicker = false }) { Text("Cancelar") } }
        )
    }

    if (showAddComment && onAddComment != null) {
        AddCommentDialog(
            onDismiss = { showAddComment = false },
            onSubmit = { text, category, photoUri ->
                onAddComment(text, category, photoUri)
                showAddComment = false
            }
        )
    }
}

@Composable
private fun PlaceCommentRow(comment: PlaceComment) {
    val bitmap = remember(comment.photoPath, comment.photoThumb) {
        if (comment.photoPath.isBlank() && comment.photoThumb.isBlank()) null
        else PhotoStore.decodeLocal(comment.photoPath, 480) ?: PhotoStore.decodeThumb(comment.photoThumb)
    }
    Surface(color = SurfaceContainerLowest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AndeanBlue.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        comment.category.emoji + " " + comment.category.displayName,
                        style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "@${comment.authorName.ifBlank { "viajero" }} · ${placeCommentTimeAgo(comment.createdAt)}",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
            }
            if (comment.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(comment.text, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
            }
            if (bitmap != null) {
                Spacer(Modifier.height(6.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = "Foto del comentario",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
private fun AddCommentDialog(
    onDismiss: () -> Unit,
    onSubmit: (text: String, category: CommentCategory, photoUri: Uri?) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CommentCategory.GENERAL) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comentar este lugar", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CommentCategory.entries.toList()) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.emoji + " " + cat.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(280) },
                    placeholder = { Text("Ej. La sopa de la señora Rosita, junto a la iglesia, cuesta \$2.50") },
                    minLines = 3, modifier = Modifier.fillMaxWidth()
                )
                if (photoUri != null) {
                    Box {
                        AsyncImage(
                            model = photoUri, contentDescription = "Foto adjunta", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp))
                        )
                        IconButton(onClick = { photoUri = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Default.Close, contentDescription = "Quitar foto", tint = Color.White)
                        }
                    }
                } else {
                    OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("Agregar una foto (opcional)", color = AndeanBlue)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(text.trim(), category, photoUri) },
                enabled = text.isNotBlank() || photoUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Publicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun placeCommentTimeAgo(ts: Long): String {
    if (ts <= 0) return ""
    val diff = (System.currentTimeMillis() - ts) / 1000
    return when {
        diff < 60 -> "ahora"
        diff < 3600 -> "hace ${diff / 60} min"
        diff < 86400 -> "hace ${diff / 3600} h"
        else -> "hace ${diff / 86400} d"
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Icon(icon, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoGrid(place: Place) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                InfoCell(Modifier.weight(1f), Icons.Default.Payments, "Costo", place.costLabel, StatusNormal)
                InfoCell(Modifier.weight(1f), Icons.Default.Schedule, "Horario", place.openingHours.ifBlank { "Por confirmar" }, AndeanBlue)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                InfoCell(Modifier.weight(1f), Icons.Default.Terrain, "Altitud", place.altitudeMeters?.let { "$it msnm" } ?: "—", EquatorialGold)
                InfoCell(
                    Modifier.weight(1f), Icons.Default.Star, "Calificación",
                    place.rating?.let { String.format(java.util.Locale.US, "%.1f", it) + (place.ratingCount?.let { c -> " ($c)" } ?: "") } ?: "—", EquatorialGold
                )
            }
            if (place.costNotes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(place.costNotes, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
            }
            if (place.address.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(place.address, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
            }
        }
    }
}

@Composable
private fun InfoCell(modifier: Modifier, icon: ImageVector, label: String, value: String, tint: Color) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
            Text(value, style = MaterialTheme.typography.bodySmall, color = AndeanBlue, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContactRow(place: Place) {
    val context = LocalContext.current
    val c = place.contact
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c.phone.isNotBlank()) item { ContactButton(Icons.Default.Call, "Llamar", c.phone) { Intents.dial(context, c.phone) } }
        if (c.whatsapp.isNotBlank()) item { ContactButton(Icons.AutoMirrored.Filled.Chat, "WhatsApp", c.whatsapp) { Intents.whatsapp(context, c.whatsapp, "Hola, vi ${place.name} en Travesías en Ecuador y quiero información.") } }
        if (c.website.isNotBlank()) item { ContactButton(Icons.Default.Language, "Sitio web", c.website.removePrefix("https://").removePrefix("http://").take(24)) { Intents.openUrl(context, c.website) } }
        if (c.instagram.isNotBlank()) item { ContactButton(Icons.Default.CameraAlt, "Instagram", c.instagram.take(24)) { Intents.openInstagram(context, c.instagram) } }
        if (c.tiktok.isNotBlank()) item { ContactButton(Icons.Default.MusicNote, "TikTok", c.tiktok.take(24)) { Intents.openTikTok(context, c.tiktok) } }
        if (c.facebook.isNotBlank()) item { ContactButton(Icons.Default.ThumbUp, "Facebook", c.facebook.take(24)) { Intents.openFacebook(context, c.facebook) } }
        if (c.email.isNotBlank()) item { ContactButton(Icons.Default.Email, "Correo", c.email.take(24)) { Intents.email(context, c.email) } }
    }
}

@Composable
private fun ContactButton(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Surface(
        color = SurfaceContainerLowest, shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp,
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(min = 90.dp, max = 160.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
            }
            Text(value, style = MaterialTheme.typography.labelSmall, color = VolcanicGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

package ec.travesias.app.ui.community

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ec.travesias.app.data.social.AlbumBridge
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.ui.theme.SurfaceContainerHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ec.travesias.app.data.social.AlbumPost
import ec.travesias.app.data.social.PhotoStore
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Foto de perfil redonda, sin recuadro. Si no hay imagen, se ven las iniciales.
 */
@Composable
fun CommunityMiniAvatar(
    thumb: String,
    path: String = "",
    initials: String,
    size: Dp,
    onClick: (() -> Unit)? = null
) {
    val local = remember(path) { if (path.isBlank()) null else PhotoStore.decodeLocal(path, 256) }
    val remote = remember(thumb) { if (thumb.isBlank()) null else PhotoStore.decodeThumb(thumb) }
    val bitmap = local ?: remote
    val base = Modifier.size(size).clip(CircleShape)
    val modifier = if (onClick != null) base.clickable { onClick() } else base
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Foto de perfil",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier.background(GalapagosTeal.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            if (initials.isNotBlank()) {
                Text(initials, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = VolcanicGray, modifier = Modifier.size(size / 2))
            }
        }
    }
}

/**
 * Una imagen del álbum a todo el ancho, sin bordes ni sombra.
 * @param crop true en la rejilla del perfil; false en el feed para no recortar ni girar mal.
 */
@Composable
fun CommunityPhotoFill(
    foto: AlbumPhoto,
    modifier: Modifier = Modifier,
    maxDecode: Int = 1080,
    crop: Boolean = true,
    onNeedFull: ((AlbumPhoto) -> Unit)? = null
) {
    // Si no está en este teléfono —o solo está la previsualización borrosa— se le pide al
    // dueño por el puente. Firebase no sirve la foto lista: la manda a trozos el teléfono
    // de quien la publicó.
    LaunchedEffect(foto.id, foto.photoPath) {
        if (!PhotoStore.exists(foto.photoPath) || AlbumBridge.esPrevia(foto.photoPath)) onNeedFull?.invoke(foto)
    }
    // Decodificar fuera del hilo de la pantalla: si no, cada foto del feed la frena un
    // instante y el desplazamiento se siente a tirones.
    val bitmap by produceState<Bitmap?>(null, foto.photoPath, foto.photoThumb, maxDecode) {
        value = withContext(Dispatchers.IO) {
            PhotoStore.decodeLocal(foto.photoPath, maxDecode) ?: PhotoStore.decodeThumb(foto.photoThumb)
        }
    }
    // Copia local: `bitmap` viene de un delegado (by produceState) y Kotlin no deja
    // hacer el smart cast a no-nulo sobre un delegado.
    val listo = bitmap
    Box(modifier.background(SurfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (listo != null) {
            Image(
                bitmap = listo.asImageBitmap(),
                contentDescription = foto.placeName.ifBlank { foto.caption }.ifBlank { "Foto" },
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = VolcanicGray)
        }
    }
}

/** Proporción de la foto para el feed: ni cuadrado forzado ni un mural enorme. */
private fun photoAspect(w: Int, h: Int): Float {
    if (w <= 0 || h <= 0) return 4f / 3f
    return (w.toFloat() / h.toFloat()).coerceIn(0.75f, 1.777f)
}

/**
 * Carrusel horizontal de una publicación. Puntos abajo, sin recuadro.
 * Si [aspect] es null, se usa la proporción real de la primera foto.
 */
@Composable
fun CommunityCarousel(
    fotos: List<AlbumPhoto>,
    modifier: Modifier = Modifier,
    aspect: Float? = null,
    onNeedFull: ((AlbumPhoto) -> Unit)? = null
) {
    if (fotos.isEmpty()) return
    val cover = fotos.first()
    val ratio = remember(cover.photoPath, cover.photoThumb, aspect) {
        if (aspect != null) aspect
        else {
            val bmp = PhotoStore.decodeLocal(cover.photoPath, 96) ?: PhotoStore.decodeThumb(cover.photoThumb)
            if (bmp != null) photoAspect(bmp.width, bmp.height) else 4f / 3f
        }
    }
    if (fotos.size == 1) {
        CommunityPhotoFill(
            fotos.first(),
            modifier.fillMaxWidth().heightIn(max = 480.dp).aspectRatio(ratio),
            crop = false,
            onNeedFull = onNeedFull
        )
        return
    }
    val pager = rememberPagerState(pageCount = { fotos.size })
    Box(modifier.fillMaxWidth()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).aspectRatio(ratio)) { i ->
            CommunityPhotoFill(fotos[i], Modifier.fillMaxSize(), crop = false, onNeedFull = onNeedFull)
        }
        // Contador discreto, esquina superior derecha.
        Text(
            "${pager.currentPage + 1}/${fotos.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(fotos.size) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 6.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.45f))
                )
            }
        }
    }
}

/**
 * Rejilla 3×N del perfil: cuadrados pegados, badge si hay varias fotos.
 */
@Composable
fun CommunityPhotoGrid(
    posts: List<AlbumPost>,
    onAbrir: (AlbumPost) -> Unit,
    modifier: Modifier = Modifier,
    onNeedFull: ((AlbumPhoto) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        posts.chunked(3).forEach { fila ->
            Row(Modifier.fillMaxWidth()) {
                fila.forEach { post ->
                    val cover = post.cover
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable { onAbrir(post) }
                    ) {
                        if (cover != null) {
                            CommunityPhotoFill(cover, Modifier.fillMaxSize(), maxDecode = 480, onNeedFull = onNeedFull)
                        }
                        if (post.isCarousel) {
                            Icon(
                                Icons.Default.Collections,
                                contentDescription = "Varias fotos",
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp)
                            )
                        }
                    }
                }
                repeat(3 - fila.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

/** Visor a pantalla: se desliza entre las fotos de la publicación. */
@Composable
fun CommunityPhotoViewer(
    post: AlbumPost,
    puedeBorrar: Boolean,
    onCerrar: () -> Unit,
    onBorrarFoto: ((AlbumPhoto) -> Unit)? = null,
    /** Si viene, el botón de la papelera se lleva la publicación entera (lo que espera la gente). */
    onBorrarPublicacion: (() -> Unit)? = null,
    onNeedFull: ((AlbumPhoto) -> Unit)? = null
) {
    // Antes esto era un carrusel sin zoom: la foto se veía del tamaño de la pantalla y
    // punto. Ahora usa el mismo visor que el chat, con pellizco, doble toque y arrastre.
    LaunchedEffect(post.photos) { post.photos.forEach { onNeedFull?.invoke(it) } }
    val fotos = remember(post.photos, post.placeName, post.caption) {
        post.photos.map { f ->
            FotoVisor(
                id = f.id.ifBlank { f.photoThumb.take(24) },
                path = f.photoPath,
                thumb = f.photoThumb,
                titulo = listOf(post.caption, post.mentionsLabel).filter { it.isNotBlank() }.joinToString(" · "),
                lugar = post.placeName
            )
        }
    }
    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VisorFotos(fotos = fotos, inicial = 0, onCerrar = onCerrar)
            if (puedeBorrar && (onBorrarPublicacion != null || onBorrarFoto != null) && post.cover != null) {
                var confirmar by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { confirmar = true },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(6.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color.White)
                }
                if (confirmar) {
                    AlertDialog(
                        onDismissRequest = { confirmar = false },
                        title = { Text("¿Borrar la publicación?") },
                        text = {
                            Text(
                                if (post.isCarousel) "Se van las " + post.photos.size + " fotos, sus me gusta y sus comentarios."
                                else "Se va la foto, sus me gusta y sus comentarios."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmar = false
                                if (onBorrarPublicacion != null) onBorrarPublicacion()
                                else post.cover?.let { foto -> onBorrarFoto?.invoke(foto) }
                            }) { Text("Borrar", fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Cancelar") } }
                    )
                }
            }
        }
    }
}

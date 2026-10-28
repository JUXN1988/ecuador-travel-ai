package ec.travesias.app.ui.community

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Una foto para el visor: el archivo del teléfono manda; la miniatura es el respaldo. */
data class FotoVisor(
    val id: String,
    val path: String = "",
    val thumb: String = "",
    val titulo: String = "",
    val lugar: String = ""
)

/**
 * Visor de fotos a pantalla completa, dentro de la app: se pasa de una a otra
 * deslizando, se acerca pellizcando o con doble toque, y se arrastra cuando está
 * acercada. Antes las fotos solo se veían del tamaño de la miniatura.
 */
@Composable
fun VisorFotos(
    fotos: List<FotoVisor>,
    inicial: Int = 0,
    onCerrar: () -> Unit
) {
    if (fotos.isEmpty()) return
    val pager = rememberPagerState(initialPage = inicial.coerceIn(0, fotos.lastIndex)) { fotos.size }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp
        ) { pagina ->
            PaginaFoto(fotos[pagina], onCerrar = onCerrar)
        }

        // Barra de arriba: cerrar y el contador.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCerrar,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
            ) { Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White) }
            Spacer(Modifier.width(10.dp))
            if (fotos.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${fotos.size}",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Pie: título y lugar de la foto que se está viendo.
        val actual = fotos.getOrNull(pager.currentPage)
        if (actual != null && (actual.titulo.isNotBlank() || actual.lugar.isNotBlank())) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (actual.titulo.isNotBlank()) {
                    Text(actual.titulo, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                if (actual.lugar.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn, contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            actual.lugar,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** Una foto con zoom propio. Cada página empieza sin acercar. */
@Composable
private fun PaginaFoto(foto: FotoVisor, onCerrar: () -> Unit) {
    // La foto grande se decodifica fuera del hilo de la interfaz: si no, al abrir el
    // visor la app se queda congelada un segundo.
    val imagen by produceState<ImageBitmap?>(initialValue = null, foto.id) {
        value = withContext(Dispatchers.IO) {
            val grande = if (foto.path.isNotBlank()) PhotoStore.decodeLocal(foto.path, 2048) else null
            (grande ?: if (foto.thumb.isNotBlank()) PhotoStore.decodeThumb(foto.thumb) else null)?.asImageBitmap()
        }
    }

    var escala by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    val escalaSuave by animateFloatAsState(escala, label = "zoom")

    // Al cambiar de foto se vuelve al tamaño normal.
    LaunchedEffect(foto.id) { escala = 1f; x = 0f; y = 0f }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = imagen
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            Image(
                bitmap = bmp,
                contentDescription = foto.titulo.ifBlank { "Foto" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(foto.id) {
                        detectTransformGestures { _, desplazamiento, zoom, _ ->
                            val nueva = (escala * zoom).coerceIn(1f, 5f)
                            // Solo se puede arrastrar si está acercada; si no, el gesto es
                            // del pager y hay que dejarlo pasar para cambiar de foto.
                            if (nueva > 1f) {
                                x += desplazamiento.x
                                y += desplazamiento.y
                            } else {
                                x = 0f; y = 0f
                            }
                            escala = nueva
                        }
                    }
                    .pointerInput(foto.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (escala > 1.05f) { escala = 1f; x = 0f; y = 0f } else escala = 2.5f
                            },
                            onTap = { if (abs(escala - 1f) < 0.05f) onCerrar() }
                        )
                    }
                    .graphicsLayer(
                        scaleX = escalaSuave,
                        scaleY = escalaSuave,
                        translationX = x,
                        translationY = y
                    )
            )
        }
    }
}

package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.data.social.AlbumPost
import ec.travesias.app.data.social.agruparPublicaciones
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Rejilla de álbum en el perfil de otro viajero: 3 columnas, sin recuadros.
 * [altura] se ignora: las celdas son cuadradas, como en Instagram.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TravelerAlbumGallery(
    fotos: List<AlbumPhoto>,
    esMio: Boolean,
    onAgregar: (() -> Unit)? = null,
    onBorrar: ((AlbumPhoto) -> Unit)? = null,
    altura: Int = 320,
    onNeedFull: ((AlbumPhoto) -> Unit)? = null
) {
    val posts = remember(fotos) { agruparPublicaciones(fotos, { "" }) }
    var abierta by remember { mutableStateOf<AlbumPost?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (fotos.isEmpty()) {
            Text(
                if (esMio) "Todavía no hay fotos." else "Aún no publicó fotos.",
                color = VolcanicGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = esMio && onAgregar != null) { onAgregar?.invoke() }
                    .padding(vertical = 16.dp)
            )
        } else {
            CommunityPhotoGrid(posts = posts, onAbrir = { abierta = it }, onNeedFull = onNeedFull)
        }
        if (esMio && onAgregar != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Subir fotos",
                color = AndeanBlue,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onAgregar() }.padding(8.dp)
            )
        }
    }
    abierta?.let { post ->
        CommunityPhotoViewer(
            post = post,
            puedeBorrar = esMio && onBorrar != null,
            onCerrar = { abierta = null },
            onBorrarFoto = { foto ->
                onBorrar?.invoke(foto)
                abierta = null
            },
            onNeedFull = onNeedFull
        )
    }
}

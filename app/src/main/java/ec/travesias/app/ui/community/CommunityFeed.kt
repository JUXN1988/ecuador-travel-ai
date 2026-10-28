package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.data.social.AlbumPost
import ec.travesias.app.data.social.Comentario
import ec.travesias.app.data.social.SocialDePost
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Inicio de Comunidad: fotos a lo ancho, integradas a la app (sin cards ni segunda barra),
 * con "me gusta", comentarios y el menú para borrar lo tuyo.
 */
@Composable
fun CommunityFeed(
    posts: List<AlbumPost>,
    social: Map<String, SocialDePost>,
    yo: String?,
    onPerfil: (fingerprint: String, username: String) -> Unit,
    onPublicar: () -> Unit,
    onNeedPhoto: (AlbumPhoto) -> Unit = {},
    onVerSocial: (AlbumPost) -> Unit = {},
    onReaccionar: (AlbumPost, String) -> Unit = { _, _ -> },
    onComentar: (AlbumPost, String) -> Unit = { _, _ -> },
    onBorrarComentario: (AlbumPost, Comentario) -> Unit = { _, _ -> },
    puedeBorrarComentario: (AlbumPost, Comentario) -> Boolean = { _, _ -> false },
    onBorrarPublicacion: (AlbumPost) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (posts.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text("📸  Aún no hay fotos", fontWeight = FontWeight.Bold, color = AndeanBlue)
            Spacer(Modifier.height(4.dp))
            Text(
                "Publica un viaje o agrega amigos para ver las suyas aquí.",
                color = VolcanicGray,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.clickable { onPublicar() }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Subir fotos", fontWeight = FontWeight.SemiBold, color = AndeanBlue)
            }
        }
        return
    }

    /** Publicación cuyos comentarios se están leyendo, o null. */
    var comentando by remember { mutableStateOf<AlbumPost?>(null) }
    /** Publicación que se va a borrar (se pregunta antes: borrar no tiene vuelta atrás). */
    var borrando by remember { mutableStateOf<AlbumPost?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(posts, key = { it.setId }) { post ->
            FeedPost(
                post = post,
                social = social[post.setId] ?: SocialDePost(post.setId),
                yo = yo,
                onPerfil = { onPerfil(post.ownerFingerprint, post.username) },
                onNeedPhoto = onNeedPhoto,
                onVerSocial = onVerSocial,
                onReaccionar = { emoji -> onReaccionar(post, emoji) },
                onComentarios = { comentando = post },
                onBorrarPublicacion = { borrando = post }
            )
        }
    }

    val abierto = comentando
    if (abierto != null) {
        val suSocial = social[abierto.setId] ?: SocialDePost(abierto.setId)
        HojaComentarios(
            titulo = "Comentarios de @" + abierto.username,
            comentarios = suSocial.comentarios,
            puedeBorrar = { c -> puedeBorrarComentario(abierto, c) },
            onEnviar = { texto -> onComentar(abierto, texto) },
            onBorrar = { c -> onBorrarComentario(abierto, c) },
            onPerfil = { fp, user -> comentando = null; onPerfil(fp, user) },
            onCerrar = { comentando = null }
        )
    }

    val aBorrar = borrando
    if (aBorrar != null) {
        AlertDialog(
            onDismissRequest = { borrando = null },
            title = { Text("¿Borrar la publicación?") },
            text = {
                Text(
                    if (aBorrar.isCarousel) "Se van las " + aBorrar.photos.size + " fotos, sus me gusta y sus comentarios. No se puede deshacer."
                    else "Se va la foto, sus me gusta y sus comentarios. No se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onBorrarPublicacion(aBorrar)
                    borrando = null
                }) { Text("Borrar", color = StatusClosed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { borrando = null }) { Text("Cancelar") } }
        )
    }
}

/** Una publicación: usuario, foto(s), pie, reacciones y comentarios. Sin contenedor alrededor. */
@Composable
private fun FeedPost(
    post: AlbumPost,
    social: SocialDePost,
    yo: String?,
    onPerfil: () -> Unit,
    onNeedPhoto: (AlbumPhoto) -> Unit,
    onVerSocial: (AlbumPost) -> Unit,
    onReaccionar: (String) -> Unit,
    onComentarios: () -> Unit,
    onBorrarPublicacion: () -> Unit
) {
    // Al asomarse la publicación se piden sus me gusta y comentarios (una vez por sesión).
    LaunchedEffect(post.setId) { onVerSocial(post) }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPerfil() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CommunityMiniAvatar(
                thumb = post.ownerThumb,
                initials = post.username.take(2).uppercase(),
                size = 36.dp,
                onClick = onPerfil
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    post.username,
                    fontWeight = FontWeight.SemiBold,
                    color = AndeanBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (post.placeName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = GalapagosTeal, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "📍 " + post.placeName,
                            color = VolcanicGray,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (post.createdAt > 0) {
                Text(communityTimeAgo(post.createdAt), color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
            }
        }
        // Sin recorte 1:1: la foto usa su proporción real (y PhotoStore ya corrige el giro EXIF).
        CommunityCarousel(post.photos, Modifier.fillMaxWidth(), onNeedFull = onNeedPhoto)
        if (post.caption.isNotBlank() || post.mentionsLabel.isNotBlank()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (post.caption.isNotBlank()) {
                    Text(
                        post.username + "  " + post.caption,
                        color = AndeanBlue,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (post.mentionsLabel.isNotBlank()) {
                    Text("👋  " + post.mentionsLabel, color = GalapagosTeal, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        BarraSocial(
            social = social,
            yo = yo,
            onReaccionar = onReaccionar,
            onComentarios = onComentarios,
            esMio = yo != null && post.ownerFingerprint == yo,
            onBorrarPublicacion = onBorrarPublicacion
        )
        HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f), modifier = Modifier.padding(top = 4.dp))
    }
}

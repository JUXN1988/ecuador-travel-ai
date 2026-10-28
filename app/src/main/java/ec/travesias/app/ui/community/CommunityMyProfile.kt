package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.data.social.AlbumPost
import ec.travesias.app.data.social.Comentario
import ec.travesias.app.data.social.TravelerProfile
import ec.travesias.app.data.social.agruparPublicaciones
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Mi perfil dentro de Comunidad: avatar, conteos, bio y rejilla de fotos.
 * Ajustes (privacidad, vitrina, cuenta) se abren con el engranaje.
 */
@Composable
fun CommunityMyProfile(
    me: TravelerProfile,
    fotos: List<AlbumPhoto>,
    amigos: Int,
    sinCuenta: Boolean,
    onAjustes: () -> Unit,
    onCambiarFoto: () -> Unit,
    onPublicar: () -> Unit,
    onProtegerCuenta: () -> Unit,
    onBorrarFoto: (AlbumPhoto) -> Unit,
    /** Borra la publicación entera (todas sus fotos, sus me gusta y sus comentarios). */
    onBorrarPublicacion: (AlbumPost) -> Unit = {},
    /** Mi muro: lo que me han escrito los demás en el perfil. */
    muro: List<Comentario> = emptyList(),
    puedeBorrarMuro: (Comentario) -> Boolean = { true },
    onEscribirMuro: (String) -> Unit = {},
    onBorrarMuro: (Comentario) -> Unit = {},
    onPerfilDeComentario: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val posts = remember(fotos, me.username) {
        agruparPublicaciones(fotos, { me.username }, { me.avatarThumb })
    }
    var abierta by remember { mutableStateOf<AlbumPost?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CommunityMiniAvatar(
                thumb = me.avatarThumb,
                path = me.avatarPath,
                initials = me.initials,
                size = 86.dp,
                onClick = onCambiarFoto
            )
            Spacer(Modifier.width(24.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(posts.size.toString(), "📷 fotos")
                Stat(amigos.toString(), "🤝 amigos")
            }
            IconButton(onClick = onAjustes) {
                Icon(Icons.Outlined.Settings, contentDescription = "Ajustes", tint = AndeanBlue)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(me.username, fontWeight = FontWeight.Bold, color = AndeanBlue)
            if (me.bio.isNotBlank()) {
                Text(me.bio, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
            }
            val ruta = me.routeLabel
            if (ruta.isNotBlank() || me.region.isNotBlank()) {
                Text(
                    listOfNotNull(me.region.takeIf { it.isNotBlank() }, ruta.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = VolcanicGray
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAjustes) { Text("✏️  Editar perfil", color = AndeanBlue) }
            TextButton(onClick = onPublicar) {
                Icon(Icons.Outlined.AddBox, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                Spacer(Modifier.width(4.dp))
                Text("Fotos", color = AndeanBlue)
            }
        }
        if (sinCuenta) {
            TextButton(onClick = onProtegerCuenta, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Proteger cuenta con contraseña", color = AndeanBlue)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.GridOn, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (posts.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp).clickable { onPublicar() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Comparte tus viajes 🌄", fontWeight = FontWeight.SemiBold, color = AndeanBlue)
                Text("Hasta 10 fotos por publicación.", color = VolcanicGray, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            CommunityPhotoGrid(posts = posts, onAbrir = { abierta = it })
        }
        // Mi muro: lo que me escriben los amigos en el perfil.
        MuroDelPerfil(
            comentarios = muro,
            puedeEscribir = !sinCuenta,
            puedeBorrar = puedeBorrarMuro,
            onEnviar = onEscribirMuro,
            onBorrar = onBorrarMuro,
            onPerfil = onPerfilDeComentario,
            titulo = "Mi muro"
        )
        Spacer(Modifier.height(24.dp))
    }

    abierta?.let { post ->
        CommunityPhotoViewer(
            post = post,
            puedeBorrar = true,
            onCerrar = { abierta = null },
            onBorrarFoto = { foto ->
                onBorrarFoto(foto)
                abierta = null
            },
            onBorrarPublicacion = {
                onBorrarPublicacion(post)
                abierta = null
            }
        )
    }
}

@Composable
private fun Stat(valor: String, etiqueta: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.titleMedium)
        Text(etiqueta, color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
    }
}

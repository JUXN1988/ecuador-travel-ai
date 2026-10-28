package ec.travesias.app.ui.community

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.social.Comentario
import ec.travesias.app.data.social.Reacciones
import ec.travesias.app.data.social.SocialDePost
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.GalapagosTeal
import ec.travesias.app.ui.theme.HairLine
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Lo que convierte el álbum en una red social: reaccionar, comentar y borrar lo tuyo.
 *
 * Un toque en el corazón = me gusta. Mantenerlo pulsado abre las otras reacciones
 * (👍 😍 🔥 😮 👏). Volver a tocar la misma reacción la quita.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BarraSocial(
    social: SocialDePost,
    yo: String?,
    onReaccionar: (String) -> Unit,
    onComentarios: () -> Unit,
    esMio: Boolean = false,
    onBorrarPublicacion: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val miReaccion = social.miReaccion(yo)
    var abanico by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .combinedClickable(
                            onClick = { onReaccionar(miReaccion ?: Reacciones.POR_DEFECTO) },
                            onLongClick = { abanico = true }
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        miReaccion == null -> Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = "Me gusta",
                            tint = VolcanicGray,
                            modifier = Modifier.size(21.dp)
                        )
                        miReaccion == Reacciones.POR_DEFECTO -> Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Ya no me gusta",
                            tint = StatusClosed,
                            modifier = Modifier.size(21.dp)
                        )
                        else -> Text(miReaccion, style = MaterialTheme.typography.titleMedium)
                    }
                    if (social.cuantasReacciones > 0) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            social.cuantasReacciones.toString(),
                            color = if (miReaccion != null) AndeanBlue else VolcanicGray,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                // Abanico de reacciones: sale al mantener pulsado, como en las apps de mensajes.
                if (abanico) {
                    DropdownMenu(expanded = true, onDismissRequest = { abanico = false }) {
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            Reacciones.LISTA.forEach { emoji ->
                                Text(
                                    emoji,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            abanico = false
                                            onReaccionar(emoji)
                                        }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onComentarios() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = "Comentar",
                    tint = VolcanicGray,
                    modifier = Modifier.size(19.dp)
                )
                if (social.cuantosComentarios > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        social.cuantosComentarios.toString(),
                        color = VolcanicGray,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Los emojis que ha puesto la gente, en pequeñito.
            if (social.emojisResumen.size > 1) {
                Spacer(Modifier.width(4.dp))
                Text(social.emojisResumen.joinToString(""), style = MaterialTheme.typography.labelMedium)
            }

            Box(Modifier.weight(1f)) {}

            if (esMio && onBorrarPublicacion != null) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = VolcanicGray, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Borrar publicación", color = StatusClosed) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusClosed) },
                            onClick = {
                                menu = false
                                onBorrarPublicacion()
                            }
                        )
                    }
                }
            }
        }

        val resumen = social.resumen(yo)
        if (resumen.isNotBlank()) {
            Text(
                resumen,
                color = AndeanBlue,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
            )
        }
        // Últimos comentarios, como en Instagram: se leen sin abrir nada.
        social.comentarios.takeLast(2).forEach { c ->
            Text(
                "@" + c.usuario + "  " + c.texto,
                color = VolcanicGray,
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable { onComentarios() }.padding(horizontal = 16.dp, vertical = 1.dp)
            )
        }
        if (social.cuantosComentarios > 2) {
            Text(
                "Ver los " + social.cuantosComentarios + " comentarios",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { onComentarios() }.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Hoja de comentarios: se lee todo, se escribe abajo y quien puede, borra.
 * Vale igual para una publicación y para el muro de un perfil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaComentarios(
    titulo: String,
    comentarios: List<Comentario>,
    puedeBorrar: (Comentario) -> Boolean,
    onEnviar: (String) -> Unit,
    onBorrar: (Comentario) -> Unit,
    onPerfil: (String, String) -> Unit,
    onCerrar: () -> Unit
) {
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = estado, containerColor = SurfaceLight) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text(
                titulo,
                fontWeight = FontWeight.Bold,
                color = AndeanBlue,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = HairLine)
            if (comentarios.isEmpty()) {
                Text(
                    "Todavía no hay comentarios. Sé el primero.",
                    color = VolcanicGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(comentarios, key = { it.id }) { c ->
                        FilaComentario(
                            comentario = c,
                            puedeBorrar = puedeBorrar(c),
                            onBorrar = { onBorrar(c) },
                            onPerfil = { onPerfil(c.quien, c.usuario) }
                        )
                    }
                }
            }
            HorizontalDivider(color = HairLine)
            CajaDeComentario(onEnviar = onEnviar)
        }
    }
}

/** Una línea de comentario: quién, qué dijo y cuándo. */
@Composable
fun FilaComentario(
    comentario: Comentario,
    puedeBorrar: Boolean,
    onBorrar: () -> Unit,
    onPerfil: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        CommunityMiniAvatar(
            thumb = "",
            initials = comentario.usuario.take(2).uppercase(),
            size = 30.dp,
            onClick = onPerfil
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "@" + comentario.usuario.ifBlank { "viajero" },
                    fontWeight = FontWeight.SemiBold,
                    color = AndeanBlue,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onPerfil() }
                )
                Spacer(Modifier.width(6.dp))
                Text(communityTimeAgo(comentario.at), color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
                if (comentario.pendiente) {
                    Spacer(Modifier.width(6.dp))
                    Text("· enviando", color = GalapagosTeal, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(comentario.texto, color = AndeanBlue, style = MaterialTheme.typography.bodySmall)
        }
        if (puedeBorrar) {
            IconButton(onClick = onBorrar, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Borrar comentario", tint = VolcanicGray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Caja para escribir un comentario, con emojis a mano. */
@Composable
fun CajaDeComentario(
    onEnviar: (String) -> Unit,
    placeholder: String = "Escribe un comentario…"
) {
    var texto by remember { mutableStateOf("") }
    var emojis by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (emojis) {
            SelectorEmojis { emoji ->
                texto += emoji
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { emojis = !emojis }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.EmojiEmotions, contentDescription = "Emojis", tint = GalapagosTeal)
            }
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val limpio = texto.trim()
                    if (limpio.isNotBlank()) {
                        onEnviar(limpio)
                        texto = ""
                        emojis = false
                    }
                },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar",
                    tint = if (texto.isBlank()) VolcanicGray else AndeanBlue
                )
            }
        }
    }
}

/**
 * Muro del perfil: los amigos dejan mensajes en la ficha de la persona, no solo en sus fotos.
 */
@Composable
fun MuroDelPerfil(
    comentarios: List<Comentario>,
    puedeEscribir: Boolean,
    puedeBorrar: (Comentario) -> Boolean,
    onEnviar: (String) -> Unit,
    onBorrar: (Comentario) -> Unit,
    onPerfil: (String, String) -> Unit,
    titulo: String = "Muro",
    modifier: Modifier = Modifier
) {
    var abierto by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💬  " + titulo, fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(comentarios.size.toString(), color = VolcanicGray, style = MaterialTheme.typography.labelMedium)
            Box(Modifier.weight(1f)) {}
            if (comentarios.size > 3) {
                TextButton(onClick = { abierto = true }) { Text("Ver todos") }
            }
        }
        if (comentarios.isEmpty()) {
            Text(
                if (puedeEscribir) "Todavía nadie ha escrito aquí. Déjale un saludo."
                else "Todavía nadie ha escrito aquí.",
                color = VolcanicGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else {
            Surface(color = SurfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                Column {
                    comentarios.takeLast(3).forEach { c ->
                        FilaComentario(
                            comentario = c,
                            puedeBorrar = puedeBorrar(c),
                            onBorrar = { onBorrar(c) },
                            onPerfil = { onPerfil(c.quien, c.usuario) }
                        )
                    }
                }
            }
        }
        if (puedeEscribir) CajaDeComentario(onEnviar = onEnviar, placeholder = "Escribe en su muro…")
        Spacer(Modifier.height(6.dp))
    }
    if (abierto) {
        HojaComentarios(
            titulo = titulo,
            comentarios = comentarios,
            puedeBorrar = puedeBorrar,
            onEnviar = onEnviar,
            onBorrar = onBorrar,
            onPerfil = onPerfil,
            onCerrar = { abierto = false }
        )
    }
}

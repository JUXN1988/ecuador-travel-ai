package ec.travesias.app.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.sp
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.data.social.Comentario
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.BrandFill
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.StatusNormal
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Perfil de otro viajero a pantalla completa, estilo Instagram.
 * Sustituye el diálogo flotante: misma info y mismas acciones, sin recuadro encima de Gente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityTravelerPage(
    card: TravelerCard,
    album: List<AlbumPhoto>,
    cargando: Boolean,
    misIntereses: List<String>,
    onCerrar: () -> Unit,
    onEscribir: () -> Unit,
    onAgregarAmigo: () -> Unit,
    onBloquear: () -> Unit,
    onDesbloquear: () -> Unit,
    onNota: (String) -> Unit,
    onInvitarAGrupo: () -> Unit,
    onQuitarAmigo: () -> Unit = {},
    onNeedPhoto: (AlbumPhoto) -> Unit = {},
    /** Muro del perfil: lo que le han escrito sus amigos. */
    muro: List<Comentario> = emptyList(),
    puedeEscribirMuro: Boolean = false,
    puedeBorrarMuro: (Comentario) -> Boolean = { false },
    onEscribirMuro: (String) -> Unit = {},
    onBorrarMuro: (Comentario) -> Unit = {},
    onPerfilDeComentario: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var nota by remember(card.fingerprint) { mutableStateOf("") }
    var editandoNota by remember(card.fingerprint) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val enComun = remember(card, misIntereses) {
        card.inCommon.ifEmpty { card.interests.filter { i -> misIntereses.any { it.equals(i, true) } } }
    }
    val yaEsAmigo = card.state == ContactState.CONTACTO
    val usuario = card.username.ifBlank { card.fingerprint.take(10) }
    val fotos = album.ifEmpty { card.album }
    val estado = when {
        card.isBlocked -> "Bloqueado"
        !card.canViewProfile && card.visibility == ProfileVisibility.PRIVATE -> "Perfil privado"
        !card.canViewProfile -> "Perfil solo para amigos"
        yaEsAmigo -> "Es tu amigo"
        card.showcase -> "Busca compañeros de viaje"
        else -> "Aún no es tu amigo"
    }

    // Atrás del sistema cierra este perfil, no la pestaña Comunidad.
    BackHandler(onBack = onCerrar)

    Column(modifier.fillMaxSize().background(SurfaceLight)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCerrar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = AndeanBlue)
            }
            Text(
                "@$usuario",
                color = AndeanBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            if (card.verified) {
                Icon(Icons.Default.VerifiedUser, contentDescription = "Firma verificada", tint = StatusNormal, modifier = Modifier.size(18.dp))
            }
            BoxMenu(
                abierto = menu,
                onAbrir = { menu = true },
                onCerrar = { menu = false },
                bloqueado = card.isBlocked,
                yaEsAmigo = yaEsAmigo,
                usuario = usuario,
                onBloquear = { menu = false; onBloquear() },
                onDesbloquear = { menu = false; onDesbloquear() },
                onQuitarAmigo = { menu = false; onQuitarAmigo() },
                onEscribir = { menu = false; onEscribir() },
                onInvitar = { menu = false; onInvitarAGrupo() }
            )
        }

        if (cargando) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CommunityMiniAvatar(thumb = card.thumb, initials = card.initials, size = 86.dp)
                Spacer(Modifier.width(20.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Conteo(fotos.size.toString(), "fotos")
                    Conteo(if (card.messagesSeen > 0) card.messagesSeen.toString() else "—", "msgs")
                    if (card.contributions > 0) Conteo(card.contributions.toString(), "aportes")
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                if (card.displayName.isNotBlank() && !card.displayName.equals(card.username, true)) {
                    Text(card.displayName, color = AndeanBlue, fontWeight = FontWeight.Bold)
                }
                Text(estado, style = MaterialTheme.typography.labelSmall, color = if (card.isBlocked) StatusClosed else VolcanicGray)
                if (card.canViewProfile && card.bio.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(card.bio, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                }
                if (card.canViewProfile && (card.region.isNotBlank() || card.travelMonth.isNotBlank() || card.routeLabel.isNotBlank())) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            card.region.takeIf { it.isNotBlank() }?.let { "Viaja a $it" },
                            card.travelMonth.takeIf { it.isNotBlank() }?.let { "en $it" },
                            card.routeLabel.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = AndeanBlue
                    )
                }
                if (card.knownSince > 0) {
                    Text("desde " + communityTimeAgo(card.knownSince), style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
                if (card.canViewProfile && enComun.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Les gusta lo mismo: " + enComun.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                }
                if (card.canViewProfile && card.interests.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.interests.forEach { i ->
                            Text(i, style = MaterialTheme.typography.labelSmall, color = AndeanBlue)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            AccionesPerfil(
                card = card,
                yaEsAmigo = yaEsAmigo,
                onEscribir = onEscribir,
                onAgregarAmigo = onAgregarAmigo,
                onDesbloquear = onDesbloquear,
                onInvitarAGrupo = onInvitarAGrupo
            )

            Column(Modifier.padding(horizontal = 16.dp)) {
                if (editandoNota) {
                    OutlinedTextField(
                        value = nota,
                        onValueChange = { nota = it.take(140) },
                        label = { Text("Nota privada") },
                        placeholder = { Text("Ej. el de la camioneta que va a Baños") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { onNota(nota); editandoNota = false }) {
                        Text("Guardar nota", color = AndeanBlue)
                    }
                } else {
                    TextButton(onClick = { editandoNota = true }) {
                        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Ponerle una nota que solo veas tú", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!card.verified && card.messagesSeen > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GppMaybe, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Alguno de sus mensajes no se pudo verificar. Desconfía si te pide dinero o datos.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusClosed
                        )
                    }
                }
            }

            HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f), modifier = Modifier.padding(top = 8.dp))

            if (!card.canViewProfile) {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (card.visibility == ProfileVisibility.PRIVATE)
                            "Este perfil es privado. No se ven fotos ni datos de viaje."
                        else
                            "Solo sus amigos pueden ver el perfil y el álbum. Agrégalo y, cuando él te agregue, lo verás.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VolcanicGray
                    )
                }
            } else {
                TravelerAlbumGallery(
                    fotos = fotos,
                    esMio = false,
                    onNeedFull = onNeedPhoto
                )
                HorizontalDivider(color = VolcanicGray.copy(alpha = 0.12f), modifier = Modifier.padding(top = 12.dp))
                // Muro: los amigos le dejan mensajes en el perfil, no solo debajo de sus fotos.
                MuroDelPerfil(
                    comentarios = muro,
                    puedeEscribir = puedeEscribirMuro,
                    puedeBorrar = puedeBorrarMuro,
                    onEnviar = onEscribirMuro,
                    onBorrar = onBorrarMuro,
                    onPerfil = onPerfilDeComentario,
                    titulo = "Muro de @" + usuario
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Conteo(valor: String, etiqueta: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, fontWeight = FontWeight.Bold, color = AndeanBlue, style = MaterialTheme.typography.titleMedium)
        Text(etiqueta, color = VolcanicGray, style = MaterialTheme.typography.labelSmall)
    }
}

/** Chatear / agregar / grupo en una fila, pegados a la página (no en un recuadro). */
@Composable
private fun AccionesPerfil(
    card: TravelerCard,
    yaEsAmigo: Boolean,
    onEscribir: () -> Unit,
    onAgregarAmigo: () -> Unit,
    onDesbloquear: () -> Unit,
    onInvitarAGrupo: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.isBlocked) {
            Button(
                onClick = onDesbloquear,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Desbloquear") }
        } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onEscribir,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (yaEsAmigo) "Chatear" else "Escribir")
            }
            if (!yaEsAmigo) {
                OutlinedButton(onClick = onAgregarAmigo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Amigo", color = AndeanBlue)
                }
            }
        }
        if (card.username.length >= 3) {
            TextButton(onClick = onInvitarAGrupo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                Spacer(Modifier.width(6.dp))
                Text("Meter en un grupo de viaje", color = AndeanBlue)
            }
        }
        }
    }
}

@Composable
private fun BoxMenu(
    abierto: Boolean,
    onAbrir: () -> Unit,
    onCerrar: () -> Unit,
    bloqueado: Boolean,
    yaEsAmigo: Boolean,
    usuario: String,
    onBloquear: () -> Unit,
    onDesbloquear: () -> Unit,
    onQuitarAmigo: () -> Unit,
    onEscribir: () -> Unit,
    onInvitar: () -> Unit
) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = onAbrir) {
            Icon(Icons.Default.MoreVert, contentDescription = "Más", tint = AndeanBlue)
        }
        if (abierto) {
            val acciones = buildList {
                add(ec.travesias.app.ui.components.FloatingMenuAction("Escribir") { onEscribir() })
                add(ec.travesias.app.ui.components.FloatingMenuAction("Invitar a un grupo") { onInvitar() })
                if (bloqueado) {
                    add(ec.travesias.app.ui.components.FloatingMenuAction("Desbloquear") { onDesbloquear() })
                } else {
                    if (yaEsAmigo) {
                        add(ec.travesias.app.ui.components.FloatingMenuAction("Quitar de amigos") { onQuitarAmigo() })
                    }
                    add(
                        ec.travesias.app.ui.components.FloatingMenuAction(
                            "Bloquear",
                            "No podrá escribirte",
                            destructive = true
                        ) { onBloquear() }
                    )
                }
            }
            ec.travesias.app.ui.components.FloatingContextMenu(
                onDismiss = onCerrar,
                headerTitle = "@$usuario",
                headerBody = "Opciones",
                actions = acciones
            )
        }
    }
}

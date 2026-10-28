package ec.travesias.app.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.data.social.TravelerProfile
import ec.travesias.app.ui.theme.SurfaceLight
import ec.travesias.app.viewmodel.CommunityViewModel

/**
 * Caparazón de Viajeros: feed, mensajes, grupos, amigos y perfil.
 * Las secciones van en el menú inferior; el menú de la app queda oculto aquí.
 * El chat cifrado se abre encima (GroupChat en CommunityScreen) y no se toca aquí.
 */
@Composable
fun CommunityWorld(
    community: CommunityViewModel,
    me: TravelerProfile,
    sinCuenta: Boolean,
    onPickAlbum: () -> Unit,
    onPickAvatar: () -> Unit,
    onEditProfile: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onWriteUser: () -> Unit,
    onInviteGroup: (String) -> Unit,
    onInviteTraveler: (TravelerCard) -> Unit,
    onProtectAccount: () -> Unit,
    /** Vuelve a la pestaña Mi Ruta (el menú inferior está oculto aquí). */
    onBackToRoute: () -> Unit
) {
    var tab by remember { mutableStateOf(CommunityMundoTab.INICIO) }
    // Ajustes van como pantalla de la app, no como diálogo con recuadros.
    var mostrandoAjustes by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<CommunityHoldTarget?>(null) }
    val feed by community.feedPosts.collectAsState()
    val chats by community.chats.collectAsState()
    val solicitudes by community.requests.collectAsState()
    val grupos by community.travelGroups.collectAsState()
    val contactos by community.contacts.collectAsState()
    val bloqueados by community.blockedTravelers.collectAsState()
    val buddies by community.buddies.collectAsState()
    val miAlbum by community.myAlbum.collectAsState()
    val social by community.social.collectAsState()
    val miMuro by community.miMuro.collectAsState()
    val miHuella by community.myFingerprint.collectAsState()
    val busy by community.busy.collectAsState()
    val amigos = remember(contactos) { contactos.count { it.state == ContactState.CONTACTO } }

    // Al abrir Comunidad se bajan las fotos de amigos; en Gente se buscan afines.
    LaunchedEffect(miHuella) {
        if (!miHuella.isNullOrBlank()) community.refreshFeed()
    }
    LaunchedEffect(tab, miHuella) {
        if (tab == CommunityMundoTab.GENTE) community.refreshBuddies()
        if (tab == CommunityMundoTab.PERFIL) community.refrescarMiMuro()
    }

    if (mostrandoAjustes) {
        CommunitySettings(
            profile = me,
            options = community.interestOptions,
            tagOptions = community.travelTagOptions,
            conCuenta = !sinCuenta,
            onCerrar = { mostrandoAjustes = false },
            onShowcase = { community.setShowcase(it) },
            onVisibility = { community.setVisibility(it) },
            onSignOut = {
                mostrandoAjustes = false
                community.signOut()
            },
            onProtegerCuenta = {
                mostrandoAjustes = false
                onProtectAccount()
            },
            onSave = { actualizado ->
                community.saveProfile(actualizado)
                mostrandoAjustes = false
            }
        )
        return
    }

    // Sin menú inferior: atrás del sistema (y la flecha) vuelven a Mi Ruta.
    BackHandler(enabled = hold == null, onBack = onBackToRoute)

    Box(Modifier.fillMaxSize().background(SurfaceLight)) {
    Column(Modifier.fillMaxSize()) {
        CommunityMundoHeader(
            tab = tab,
            solicitudes = solicitudes.size,
            onBackToRoute = onBackToRoute,
            onPublicar = onPickAlbum
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                CommunityMundoTab.INICIO -> CommunityFeed(
                    posts = feed,
                    social = social,
                    yo = miHuella,
                    onPerfil = { fp, user ->
                        if (fp == miHuella) tab = CommunityMundoTab.PERFIL
                        else community.showProfile(fp, user)
                    },
                    onPublicar = onPickAlbum,
                    onNeedPhoto = { community.ensureAlbumPhoto(it) },
                    onVerSocial = { community.verSocialDe(it) },
                    onReaccionar = { post, emoji -> community.reaccionar(post, emoji) },
                    onComentar = { post, texto -> community.comentar(post, texto) },
                    onBorrarComentario = { post, c -> community.borrarComentario(post.ownerFingerprint, c) },
                    puedeBorrarComentario = { post, c -> community.puedoBorrarComentario(post.ownerFingerprint, c) },
                    onBorrarPublicacion = { community.borrarPublicacion(it) }
                )
                CommunityMundoTab.MENSAJES ->                 CommunityInbox(
                    chats = chats,
                    solicitudes = solicitudes,
                    yo = miHuella,
                    miUsuario = me.username,
                    yoUid = me.uid,
                    esAmigo = { community.esAmigo(it) },
                    avatarDe = { fp -> contactos.firstOrNull { it.fingerprint == fp }?.thumb.orEmpty() },
                    onEscribirUsuario = onWriteUser,
                    onAbrirChat = { community.openGroup(it) },
                    onVerPerfil = { fp, user -> community.showProfile(fp, user) },
                    onResponder = { g ->
                        if (g.direct) community.replyToRequest(g.id) else community.acceptRequest(g.id)
                    },
                    onAgregarAmigo = { g ->
                        val quien = g.name.removePrefix("\uD83D\uDC64 ")
                        val otro = g.otherMember(miHuella) ?: g.invitedBy
                        community.addFriend(otro, quien, g.id)
                        community.openGroup(g.id)
                    },
                    onRechazar = { community.rejectRequest(it.id, false) },
                    onAceptarGrupo = { community.acceptRequest(it.id) },
                    onHoldChat = { hold = CommunityHoldTarget.Chat(it) }
                )
                CommunityMundoTab.GRUPOS -> CommunityGroupsPanel(
                    grupos = grupos,
                    onCrear = onCreateGroup,
                    onUnirme = onJoinGroup,
                    onAbrir = { community.openGroup(it) },
                    onInvitar = onInviteGroup,
                    onHoldGroup = { hold = CommunityHoldTarget.Group(it) }
                )
                CommunityMundoTab.GENTE -> CommunityPeople(
                    contactos = contactos,
                    buddies = buddies,
                    bloqueados = bloqueados,
                    busy = busy,
                    yoFingerprint = miHuella,
                    miUsuario = me.username,
                    yoUid = me.uid,
                    onEscribirUsuario = onWriteUser,
                    onBuscarAfines = {
                        community.syncActiveRouteToProfile()
                        community.refreshBuddies()
                    },
                    onPerfil = { fp, user -> community.showProfile(fp, user) },
                    onEscribir = { community.chatConUsuario(it) },
                    onInvitar = onInviteTraveler,
                    onContactarBuddy = { uid, user -> community.contactBuddy(uid, user) },
                    onDesbloquear = { community.unblockTraveler(it) },
                    onHoldPerson = { hold = CommunityHoldTarget.Person(it) }
                )
                CommunityMundoTab.PERFIL -> CommunityMyProfile(
                    me = me,
                    fotos = miAlbum,
                    amigos = amigos,
                    sinCuenta = sinCuenta,
                    onAjustes = { mostrandoAjustes = true },
                    onCambiarFoto = onPickAvatar,
                    onPublicar = onPickAlbum,
                    onProtegerCuenta = onProtectAccount,
                    onBorrarFoto = { community.deleteAlbumPhoto(it.id) },
                    onBorrarPublicacion = { community.borrarPublicacion(it) },
                    muro = miMuro,
                    puedeBorrarMuro = { c -> community.puedoBorrarComentario(miHuella.orEmpty(), c) },
                    onEscribirMuro = { texto -> miHuella?.let { community.escribirEnMuro(it, texto) } },
                    onBorrarMuro = { c -> community.borrarComentario(miHuella.orEmpty(), c) },
                    onPerfilDeComentario = { fp, user -> if (fp != miHuella) community.showProfile(fp, user) }
                )
            }
        }
        CommunityBottomBar(
            tab = tab,
            solicitudes = solicitudes.size,
            onSelect = { tab = it }
        )
    }
        val holdActual = hold
        if (holdActual != null) {
            CommunityHoldMenu(
                target = holdActual,
                yo = miHuella,
                miUsuario = me.username,
                esAmigo = { community.esAmigo(it) },
                onDismiss = { hold = null },
                onAbrirChat = { community.openGroup(it) },
                onVerPerfil = { fp, user -> community.showProfile(fp, user) },
                onAgregarAmigo = { fp, user, gid -> community.addFriend(fp, user, gid) },
                onQuitarAmigo = { fp, user -> community.removeFriend(fp, user) },
                onBloquear = { fp, user -> community.blockTraveler(fp, user) },
                onDesbloquear = { community.unblockTraveler(it) },
                onSilenciar = { id, silenciar -> community.muteGroup(id, silenciar) },
                onEliminarConversacion = { community.deleteConversation(it) },
                onInvitarGrupo = onInviteGroup,
                onInvitarPersona = onInviteTraveler,
                onEscribir = { community.chatConUsuario(it) },
                onCompartirRuta = { community.shareActiveRoute(it) }
            )
        }
    }
}

// onEditProfile se conserva en la firma para no romper CommunityScreen; los ajustes
// se editan en CommunitySettings, no en el diálogo anterior.

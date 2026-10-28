package ec.travesias.app.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.model.EcuadorRegion
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import ec.travesias.app.data.social.AccountService
import ec.travesias.app.data.social.AccountState
import ec.travesias.app.data.social.Attachments
import ec.travesias.app.data.social.BuddyMatch
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.data.social.GroupPost
import ec.travesias.app.data.social.PhotoStore
import ec.travesias.app.data.social.PostTrust
import ec.travesias.app.data.social.PostType
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.SyncState
import ec.travesias.app.data.social.SyncStatus
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.TravelerProfile
import ec.travesias.app.data.social.TransportAuth
import ec.travesias.app.data.social.esLaMismaPersona
import ec.travesias.app.data.social.esMensajeMio
import ec.travesias.app.data.social.nombreDelInterlocutor
import ec.travesias.app.ui.auth.BotonEntrarConHuella
import ec.travesias.app.ui.auth.BrandMark
import ec.travesias.app.ui.auth.DialogoGuardarHuella
import ec.travesias.app.ui.auth.FilaContrasenaGuardada
import ec.travesias.app.ui.auth.FondoTravesias
import ec.travesias.app.ui.auth.PanelVidrio
import ec.travesias.app.ui.auth.PieEcuador
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.components.PlaceSummaryCard
import ec.travesias.app.ui.community.ChatComposerBar
import ec.travesias.app.ui.community.FotoVisor
import ec.travesias.app.ui.community.VisorFotos
import ec.travesias.app.ui.community.tamanoSiSoloEmojis
import ec.travesias.app.ui.community.ChatPeerActions
import ec.travesias.app.ui.community.CommunityComposerPage
import ec.travesias.app.ui.community.CommunityCreateGroupPage
import ec.travesias.app.ui.community.CommunityHoldMenu
import ec.travesias.app.ui.community.CommunityHoldTarget
import ec.travesias.app.ui.community.CommunityInvitePage
import ec.travesias.app.ui.community.CommunityJoinGroupPage
import ec.travesias.app.ui.community.CommunityTravelerPage
import ec.travesias.app.ui.community.CommunityWorld
import ec.travesias.app.ui.community.CommunityWriteUserPage
import ec.travesias.app.ui.community.FilaMiHuella
import ec.travesias.app.ui.community.IncomingRequestActions
import ec.travesias.app.ui.community.PanelAdmin
import ec.travesias.app.ui.community.ProfilePrivacySettings
import ec.travesias.app.ui.components.holdToMenu
import ec.travesias.app.ui.theme.*
import androidx.compose.ui.unit.sp
import ec.travesias.app.ui.util.Intents
import ec.travesias.app.viewmodel.CommunityViewModel
import ec.travesias.app.viewmodel.RouteViewModel

/** El emoji con el que se marca un chat de dos personas. */
private const val DIRECT_PREFIX = "\uD83D\uDC64 "

/**
 * Comunidad. Todo se guarda en este teléfono: perfil, grupos, mensajes y fotos.
 * Firebase solo replica lo mínimo para que el resto del grupo lo vea.
 */
@Composable
fun CommunityScreen(
    community: CommunityViewModel,
    routeVm: RouteViewModel,
    onOpenMap: () -> Unit,
    onBackToRoute: () -> Unit
) {
    val sync by community.sync.collectAsState()
    val profile by community.profile.collectAsState()
    val groups by community.groups.collectAsState()
    val selectedGroup by community.selectedGroup.collectAsState()
    val posts by community.posts.collectAsState()
    val busy by community.busy.collectAsState()
    val grupos by community.travelGroups.collectAsState()
    val contactos by community.contacts.collectAsState()
    val miHuella by community.myFingerprint.collectAsState()
    val lugares by routeVm.allPlaces.collectAsState()
    val perfilAbierto by community.openProfile.collectAsState()
    val perfilCargando by community.profileLoading.collectAsState()
    val albumAjeno by community.openAlbum.collectAsState()
    val muroAjeno by community.muro.collectAsState()
    val cuenta by community.accountState.collectAsState()
    val sinCuenta = cuenta is AccountState.SignedOut

    var protegiendoCuenta by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showJoinGroup by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    /** El aviso de "termina tu perfil" se puede cerrar; no vuelve a molestar en esta sesión. */
    var avisoPerfilOculto by remember { mutableStateOf(false) }
    /** Usuario y contraseña que se acaban de teclear, para ofrecer guardarlos con la huella. */
    var credencialReciente by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ofrecerHuella by remember { mutableStateOf(false) }
    var pendientesAlbum by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) community.setAvatar(uri)
    }
    val albumPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendientesAlbum = uris.take(10)
    }
    val chatPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) community.sendPhoto(uri)
    }
    // Video, audio, PDF, lo que sea: todo entra por el mismo sitio.
    val chatFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) community.sendFile(uri)
    }
    val contexto = LocalContext.current
    // Cuando un adjunto termina de bajar, se abre con la app que el viajero tenga.
    LaunchedEffect(Unit) {
        community.abrirArchivo.collect { (ruta, mime) -> Intents.openFile(contexto, ruta, mime) }
    }
    var mostrarDiagnostico by remember { mutableStateOf(false) }
    var escribirAUsuario by remember { mutableStateOf(false) }
    /** Id del grupo al que se está invitando a alguien, o null. */
    var invitandoAlGrupo by remember { mutableStateOf<String?>(null) }
    /** Viajero que se quiere meter en un grupo desde su perfil (sin códigos). */
    var invitandoViajero by remember { mutableStateOf<TravelerCard?>(null) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) community.shareMyLocation()
    }

    val me = profile
    val group = selectedGroup
    val cardAbierto = perfilAbierto

    // Cuando la cuenta queda creada, se sale solo de la pantalla de "proteger cuenta".
    LaunchedEffect(cuenta) { if (cuenta is AccountState.SignedIn) protegiendoCuenta = false }

    // Al abrir un perfil se trae su muro; al cerrarlo se suelta.
    LaunchedEffect(cardAbierto?.fingerprint) {
        val fp = cardAbierto?.fingerprint
        if (!fp.isNullOrBlank()) community.abrirMuro(fp)
    }

    // Entró con una contraseña tecleada y en este teléfono todavía no está guardada:
    // se ofrece una sola vez. Es el seguro contra "se me olvidó la contraseña".
    val contexto0 = LocalContext.current
    LaunchedEffect(cuenta, credencialReciente) {
        val cred = credencialReciente
        if (cuenta is AccountState.SignedIn && cred != null &&
            cred.second.isNotBlank() &&
            !ec.travesias.app.data.social.HuellaBoveda.hayCredencial(contexto0) &&
            ec.travesias.app.data.social.HuellaBoveda.estado(contexto0) == ec.travesias.app.data.social.HuellaBoveda.Estado.LISTO
        ) {
            ofrecerHuella = true
        }
    }

    Box(Modifier.fillMaxSize().background(SurfaceLight)) {
    Column(Modifier.fillMaxSize()) {
        if (me == null || protegiendoCuenta) {
            // Puerta de la comunidad a pantalla completa; atrás vuelve a la portada.
            BackHandler {
                if (protegiendoCuenta) protegiendoCuenta = false else onBackToRoute()
            }
            AccountGate(
                community = community,
                busy = busy,
                sync = sync,
                sugerido = me?.username.orEmpty(),
                onVolver = { if (protegiendoCuenta) protegiendoCuenta = false else onBackToRoute() },
                onDiagnosticar = { mostrarDiagnostico = true; community.diagnosticar() },
                onCredencialUsada = { u, c -> credencialReciente = u to c },
                onAviso = { community.avisar(it) }
            )
        } else if (cardAbierto != null) {
            // Perfil a pantalla completa (Instagram): tapa pestañas y chat, no un diálogo.
            CommunityTravelerPage(
                card = cardAbierto,
                album = albumAjeno,
                cargando = perfilCargando,
                misIntereses = me.interests,
                onCerrar = { community.closeProfile() },
                onEscribir = { community.closeProfile(); community.chatConUsuario(cardAbierto.username) },
                onAgregarAmigo = { community.addFriend(cardAbierto.fingerprint, cardAbierto.username) },
                onBloquear = { community.blockTraveler(cardAbierto.fingerprint, cardAbierto.username) },
                onDesbloquear = { community.unblockTraveler(cardAbierto.fingerprint) },
                onQuitarAmigo = { community.removeFriend(cardAbierto.fingerprint, cardAbierto.username) },
                onNota = { community.setContactNote(cardAbierto.fingerprint, it) },
                onInvitarAGrupo = { community.closeProfile(); invitandoViajero = cardAbierto },
                onNeedPhoto = { community.ensureAlbumPhoto(it) },
                muro = muroAjeno,
                puedeEscribirMuro = cardAbierto.canViewProfile && !sinCuenta,
                puedeBorrarMuro = { c -> community.puedoBorrarComentario(cardAbierto.fingerprint, c) },
                onEscribirMuro = { texto -> community.escribirEnMuro(cardAbierto.fingerprint, texto) },
                onBorrarMuro = { c -> community.borrarComentario(cardAbierto.fingerprint, c) },
                onPerfilDeComentario = { fp, user -> community.showProfile(fp, user) }
            )
        } else if (group != null) {
            GroupChat(
                group = group,
                posts = posts,
                me = me,
                yo = miHuella,
                busy = busy,
                community = community,
                routeVm = routeVm,
                onOpenMap = onOpenMap,
                onInvitar = { invitandoAlGrupo = group.id },
                onVerPerfil = { fp, user -> community.showProfile(fp, user) },
                onPickPhoto = { chatPhotoPicker.launch("image/*") },
                onPickVideo = { chatFilePicker.launch("video/*") },
                onPickFile = { chatFilePicker.launch("*/*") },
                onShareLocation = {
                    locationPermission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            )
        } else {
            SyncBanner(sync, onRetry = { community.retrySync() }, onDiagnose = { mostrarDiagnostico = true; community.diagnosticar() })
            // Después del registro corto: recordatorio suave para llenar gustos y región.
            if (!avisoPerfilOculto && (me.interests.isEmpty() || me.region.isBlank())) {
                PerfilIncompletoCard(
                    onCompletar = { showEditProfile = true },
                    onOcultar = { avisoPerfilOculto = true }
                )
            }
            // Mundo Comunidad: feed de fotos, mensajes, grupos, gente y perfil.
            CommunityWorld(
                community = community,
                me = me,
                sinCuenta = sinCuenta,
                onPickAlbum = { albumPicker.launch("image/*") },
                onPickAvatar = { avatarPicker.launch("image/*") },
                onEditProfile = { showEditProfile = true },
                onCreateGroup = { showCreateGroup = true },
                onJoinGroup = { showJoinGroup = true },
                onWriteUser = { escribirAUsuario = true },
                onInviteGroup = { invitandoAlGrupo = it },
                onInviteTraveler = { invitandoViajero = it },
                onProtectAccount = { protegiendoCuenta = true },
                onBackToRoute = onBackToRoute
            )
        }
    }

    val credGuardar = credencialReciente
    if (ofrecerHuella && credGuardar != null) {
        DialogoGuardarHuella(
            usuario = credGuardar.first,
            clave = credGuardar.second,
            onCerrar = { ofrecerHuella = false; credencialReciente = null },
            onAviso = { community.avisar(it) }
        )
    }

    if (showCreateGroup) {
        CommunityCreateGroupPage(
            onDismiss = { showCreateGroup = false },
            onConfirm = { name, desc ->
                if (name.isNotBlank()) { community.createGroup(name, desc); showCreateGroup = false }
            }
        )
    }
    if (showJoinGroup) {
        CommunityJoinGroupPage(
            onDismiss = { showJoinGroup = false },
            onUnirme = { code ->
                if (code.isNotBlank()) { community.joinGroup(code); showJoinGroup = false }
            }
        )
    }
    // El perfil ajeno se ve a pantalla completa arriba (CommunityTravelerPage).

    if (pendientesAlbum.isNotEmpty()) {
        CommunityComposerPage(
            uris = pendientesAlbum,
            busy = busy,
            lugaresSugeridos = lugares.map { it.id to it.name },
            contactos = contactos.map { it.username }.filter { it.isNotBlank() },
            onDismiss = { pendientesAlbum = emptyList() },
            onPublicar = { caption, place, placeId, tagged ->
                community.addAlbumPhotos(pendientesAlbum, caption, place, placeId, tagged)
                pendientesAlbum = emptyList()
            }
        )
    }

    val gidInvitar = invitandoAlGrupo
    val viajeroInvitar = invitandoViajero
    if (gidInvitar != null || viajeroInvitar != null) {
        CommunityInvitePage(
            busy = busy,
            nombreGrupo = groups.firstOrNull { it.id == gidInvitar }?.name.orEmpty(),
            enlace = groups.firstOrNull { it.id == gidInvitar }?.let { community.inviteLink(it) }.orEmpty(),
            contactos = contactos,
            gruposViaje = grupos,
            invitadoFijo = viajeroInvitar,
            onDismiss = { invitandoAlGrupo = null; invitandoViajero = null },
            onInvitarUsuario = { usuario ->
                if (gidInvitar != null) community.inviteToGroup(gidInvitar, usuario)
                invitandoAlGrupo = null; invitandoViajero = null
            },
            onInvitarAGrupo = { gid, usuario ->
                community.inviteToGroup(gid, usuario)
                invitandoAlGrupo = null; invitandoViajero = null
            }
        )
    }

    if (escribirAUsuario) {
        CommunityWriteUserPage(
            busy = busy,
            onDismiss = { escribirAUsuario = false },
            onEscribir = { usuario -> community.chatConUsuario(usuario); escribirAUsuario = false }
        )
    }

    if (mostrarDiagnostico) {
        val chequeos by community.diagnostico.collectAsState()
        val revisando by community.diagnosticando.collectAsState()
        DiagnosticoDialog(
            chequeos = chequeos,
            revisando = revisando,
            onRepetir = { community.diagnosticar() },
            onDismiss = { mostrarDiagnostico = false; community.cerrarDiagnostico() }
        )
    }

    if (showEditProfile && me != null) {
        ProfileEditorDialog(
            profile = me,
            options = community.interestOptions,
            tagOptions = community.travelTagOptions,
            conCuenta = !sinCuenta,
            onAviso = { community.avisar(it) },
            community = community,
            onDismiss = { showEditProfile = false },
            onShowcase = { community.setShowcase(it) },
            onVisibility = { community.setVisibility(it) },
            onSignOut = { showEditProfile = false; community.signOut() },
            onSave = { community.saveProfile(it); showEditProfile = false }
        )
    }
    }
}

// ============================================================================
//  Piezas
// ============================================================================

/** Una conversación de dos en la lista de chats. */
@Composable
private fun ChatRow(
    group: TravelGroup,
    yo: String?,
    onOpen: () -> Unit,
    onProfile: (String, String) -> Unit
) {
    val otro = group.otherMember(yo)
    val nombre = group.name.removePrefix(DIRECT_PREFIX).ifBlank { "Chat" }
    val esperando = group.waitingForOther(yo)
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(enabled = otro != null) { otro?.let { onProfile(it, nombre) } }) {
                Avatar(thumb = "", path = "", initials = nombre.take(2).uppercase(), size = 44.dp, background = GalapagosTeal)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nombre, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (group.muted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.NotificationsOff, contentDescription = "Silenciado", tint = VolcanicGray, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    when {
                        esperando -> "Esperando a que te responda"
                        group.lastMessagePreview.isNotBlank() -> group.lastMessagePreview
                        else -> "Sin mensajes todavía"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (esperando) EquatorialGold else VolcanicGray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (group.lastMessageAt > 0) {
                Text(timeAgo(group.lastMessageAt), style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
            }
        }
    }
}

/**
 * Una solicitud: quién es, qué te escribió y qué puedes hacer. Es la pieza que faltaba para
 * que los mensajes fueran y vinieran de verdad, porque antes el chat aparecía a medias y
 * nadie sabía que había que hacer algo.
 */
@Composable
private fun SolicitudCard(
    group: TravelGroup,
    yo: String?,
    onVerPerfil: (String, String) -> Unit,
    onAceptar: () -> Unit,
    onRechazar: () -> Unit,
    onBloquear: () -> Unit,
    onAbrir: () -> Unit
) {
    val quien = group.memberNames.firstOrNull { it.isNotBlank() && it != group.name.removePrefix(DIRECT_PREFIX) }
        ?: group.name.removePrefix(DIRECT_PREFIX)
    Card(
        colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(thumb = "", path = "", initials = quien.take(2).uppercase(), size = 40.dp, background = EquatorialGold)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group.direct) "@" + quien + " quiere hablarte" else "Te invitaron a \"" + group.name + "\"",
                        color = AndeanBlue, fontWeight = FontWeight.Bold
                    )
                    Text(timeAgo(group.createdAt), style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
                TextButton(onClick = { onVerPerfil(group.invitedBy, quien) }) {
                    Text("Ver perfil", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (group.lastMessagePreview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = SurfaceContainerLowest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        group.lastMessagePreview,
                        color = VolcanicGray, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp).clickable { onAbrir() },
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAceptar,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusNormal)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Aceptar")
                }
                OutlinedButton(onClick = onRechazar, modifier = Modifier.weight(1f)) {
                    Text("Rechazar", color = VolcanicGray)
                }
                IconButton(onClick = onBloquear) {
                    Icon(Icons.Default.Block, contentDescription = "Bloquear", tint = StatusClosed)
                }
            }
        }
    }
}

/** Alguien que ya conozco, en la lista de gente. */
@Composable
private fun ContactoRow(
    card: TravelerCard,
    onPerfil: () -> Unit,
    onEscribir: () -> Unit,
    onInvitarAGrupo: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPerfil() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(thumb = card.thumb, path = "", initials = card.initials, size = 40.dp, background = GalapagosTeal)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@" + card.username.ifBlank { card.fingerprint.take(10) }, color = AndeanBlue, fontWeight = FontWeight.Bold)
                if (card.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.VerifiedUser, contentDescription = "Verificado", tint = StatusNormal, modifier = Modifier.size(13.dp))
                }
            }
            Text(
                listOfNotNull(
                    if (card.state == ContactState.CONTACTO) "contacto" else null,
                    if (card.messagesSeen > 0) card.messagesSeen.toString() + " mensajes" else null,
                    if (card.knownSince > 0) "desde " + timeAgo(card.knownSince) else null
                ).joinToString(" · ").ifBlank { "todavía no han hablado" },
                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
            )
        }
        IconButton(onClick = onInvitarAGrupo) {
            Icon(Icons.Default.GroupAdd, contentDescription = "Meter en un grupo", tint = AndeanBlue)
        }
        IconButton(onClick = onEscribir) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Escribir", tint = AndeanBlue)
        }
    }
}

/** Un viajero afín, con su puntaje y por qué coincide. */
@Composable
private fun BuddyCard(match: BuddyMatch, onPerfil: () -> Unit, onContactar: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onPerfil() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(thumb = match.profile.avatarThumb, path = "", initials = match.profile.initials, size = 44.dp, background = GalapagosTeal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@" + match.profile.username, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    match.reasons.take(3).forEach { r ->
                        Text("✓ " + r, style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                    }
                }
                Surface(color = EquatorialGold.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        match.score.toString() + " pts", color = AndeanBlue, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (match.profile.travelTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(match.profile.travelTags) { tag ->
                        Surface(color = AndeanBlue.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onContactar,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = BrandFill, contentColor = Color.White)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Escribirle")
            }
        }
    }
}


@Composable
private fun SyncBanner(status: SyncStatus, onRetry: () -> Unit, onDiagnose: () -> Unit = {}) {
    if (status.state == SyncState.ONLINE) return
    val color = when (status.state) {
        SyncState.CONNECTING -> VolcanicGray
        else -> EquatorialGold
    }
    Surface(color = color.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (status.state == SyncState.CONNECTING) Icons.Default.Sync else Icons.Default.CloudOff,
                contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(status.label, style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                if (status.detail.isNotBlank()) {
                    Text(status.detail, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }
            }
            if (status.state != SyncState.CONNECTING) {
                TextButton(onClick = onRetry) { Text("Reintentar", color = AndeanBlue) }
                // "No se pudo" no le dice a nadie qué arreglar: aquí se ve pieza por pieza.
                TextButton(onClick = onDiagnose) { Text("Revisar", color = AndeanBlue, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Avatar(
    thumb: String,
    path: String,
    initials: String,
    size: Dp,
    background: Color = EquatorialGold,
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
        Box(modifier.background(background), contentAlignment = Alignment.Center) {
            Text(initials, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ProfileHeader(me: TravelerProfile, onEdit: () -> Unit, onChangePhoto: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandFill),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(
                    thumb = me.avatarThumb, path = me.avatarPath, initials = me.initials,
                    size = 56.dp, onClick = onChangePhoto
                )
                Surface(color = EquatorialGold, shape = CircleShape, modifier = Modifier.clickable { onChangePhoto() }) {
                    Icon(
                        Icons.Default.AddAPhoto, contentDescription = "Cambiar foto", tint = AndeanBlue,
                        modifier = Modifier.padding(3.dp).size(13.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${me.username}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                val subtitle = listOf(me.region, me.routeLabel, me.travelMonth)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    subtitle.ifBlank { "Toca el lápiz para completar tu perfil" },
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall
                )
                if (me.interests.isNotEmpty()) {
                    Text(me.interests.joinToString(" · "), color = EquatorialGold, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Editar perfil", tint = Color.White) }
        }
    }
}

@Composable
private fun GroupRow(group: TravelGroup, onOpen: () -> Unit, onInvitar: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(BrandFill), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = AndeanBlue, fontWeight = FontWeight.Bold, maxLines = 1)
                val preview = group.lastMessagePreview.ifBlank { "${group.memberUids.size} miembro(s)" }
                Text(preview, style = MaterialTheme.typography.labelSmall, color = VolcanicGray, maxLines = 1)
                if (group.memberNames.isNotEmpty()) {
                    Text(
                        group.memberNames.take(4).joinToString(", ") { "@$it" },
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray.copy(alpha = 0.7f), maxLines = 1
                    )
                }
            }
            // Un toque abre el sheet de invitaciones (contactos / @usuario / enlace). Nada de códigos TRV1.
            Surface(
                color = EquatorialGold.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable { onInvitar() }
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Invitar",
                        color = AndeanBlue, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = VolcanicGray.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(body, color = VolcanicGray, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Puerta de la Comunidad. Es lo ÚNICO de la app que pide cuenta: al resto se entra
 * de frente. Primero se ve lo que hay adentro; después se pide lo mínimo para entrar
 * (usuario y contraseña) y los gustos se llenan luego, desde el perfil.
 */
@Composable
private fun AccountGate(
    community: CommunityViewModel,
    busy: Boolean,
    sync: SyncStatus,
    sugerido: String = "",
    onVolver: () -> Unit = {},
    onDiagnosticar: () -> Unit = {},
    onCredencialUsada: (usuario: String, clave: String) -> Unit = { _, _ -> },
    onAviso: (String) -> Unit = {}
) {
    val lang by AppSettings.language.collectAsState()
    var entrando by remember { mutableStateOf(false) }   // false = crear cuenta
    var username by remember { mutableStateOf(sugerido) }
    var password by remember { mutableStateOf("") }
    var verClave by remember { mutableStateOf(false) }

    // Si teclean un correo en el campo del usuario, se avisa en vez de recortarlo en
    // silencio (antes "soporte@travesias.ec" se convertía en "usuariogmail.com" sin decir nada).
    val pareceUnCorreo = AccountService.pareceCorreo(username)
    val usuarioOk = !pareceUnCorreo && AccountService.normalizeUsername(username).length in 3..20
    val claveOk = password.length >= 8
    val puede = if (entrando) usuarioOk && password.isNotBlank() else usuarioOk && claveOk
    var buscandoPorCorreo by remember { mutableStateOf(false) }
    /** Correo que trajo Google, o el que se escriba. Es OPCIONAL y solo sirve para recordar el usuario. */
    var correoRecuperacion by remember { mutableStateOf("") }
    val contextoGoogle = LocalContext.current
    val alcanceGoogle = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        FondoTravesias(Modifier.fillMaxSize(), conMarca = false)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.75f))
                        .clickable(onClick = onVolver)
                        .padding(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t(lang, "Volver", "Back"), tint = AndeanBlue)
                }
            }

            Spacer(Modifier.height(14.dp))
            BrandMark(escala = 0.72f)
            Spacer(Modifier.height(18.dp))

            // ---- Qué se puede hacer aquí adentro (en breve, como pidió el viajero)
            PanelVidrio {
                Text(
                    t(lang, "La comunidad de viajeros", "The travelers community"),
                    color = AndeanBlue,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    t(
                        lang,
                        "Es la parte de la app donde no viajas solo. Esto es lo que se puede hacer:",
                        "This is where you don't travel alone. Here's what you can do:"
                    ),
                    color = MutedGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                val cosas = listOf(
                    Triple(
                        Icons.AutoMirrored.Filled.Chat, SkyAccent,
                        t(lang, "Escribirte con otros viajeros", "Message other travelers") to
                            t(lang, "Chats y grupos con fotos, videos y archivos. Todo va cifrado.",
                                "Chats and groups with photos, video and files. All encrypted.")
                    ),
                    Triple(
                        Icons.Default.Groups, AndesGreen,
                        t(lang, "Armar tu grupo de viaje", "Build your travel group") to
                            t(lang, "Comparte tu ruta y organiza el camino con quien va contigo.",
                                "Share your route and plan the way with whoever comes along.")
                    ),
                    Triple(
                        Icons.Default.Explore, EquatorialGold,
                        t(lang, "Encontrar gente en tu camino", "Find people on your way") to
                            t(lang, "Viajeros que van a la misma región y con tus mismos gustos.",
                                "Travelers heading to the same region with the same interests.")
                    ),
                    Triple(
                        Icons.Default.AddLocationAlt, AndeanBlue,
                        t(lang, "Aportar lugares y tips", "Add places and tips") to
                            t(lang, "Lo que subes le llega a todos los que usan la app.",
                                "What you upload reaches everyone using the app.")
                    )
                )
                cosas.forEach { (icono, color, textos) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(textos.first, color = AndeanBlue, style = MaterialTheme.typography.titleSmall)
                            Text(textos.second, color = MutedGray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Registro corto: dos campos y listo
            PanelVidrio(opaco = true) {
                Text(
                    if (entrando) t(lang, "Entra a tu cuenta", "Sign in")
                    else t(lang, "Crea tu cuenta en 20 segundos", "Create your account in 20 seconds"),
                    color = AndeanBlue,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (entrando)
                        t(lang, "Vuelven tus grupos y tus conversaciones, aunque sea un teléfono nuevo.",
                            "Your groups and chats come back, even on a new phone.")
                    else
                        t(lang, "Sin correo, sin número de teléfono y sin pagar. Tu usuario, tu foto y tus gustos quedan visibles para los demás viajeros (se apaga en un toque desde tu perfil); tus mensajes y tu ruta, nunca.",
                            "No email, no phone number, no payment. Your username, photo and interests are visible to other travelers (one tap in your profile turns that off); your messages and route never are."),
                    color = MutedGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(14.dp))

                // Atajo: la cuenta de Google solo rellena nombre, correo y usuario.
                // Sigue haciendo falta la contraseña, que es la que abre la llave.
                if (!entrando && ec.travesias.app.data.social.CuentaGoogle.disponible(contextoGoogle)) {
                    OutlinedButton(
                        onClick = {
                            alcanceGoogle.launch {
                                runCatching { ec.travesias.app.data.social.CuentaGoogle.elegir(contextoGoogle) }
                                    .onSuccess { perfil ->
                                        if (perfil != null) {
                                            if (username.isBlank()) username = perfil.usuarioSugerido()
                                            correoRecuperacion = perfil.correo
                                            onAviso(t(lang, "Listo. Elige tu usuario y una contraseña.",
                                                "Done. Pick your username and a password."))
                                        }
                                    }
                                    .onFailure { onAviso(it.message ?: "No se pudo usar la cuenta de Google") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AndeanBlue)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "Continuar con Google", "Continue with Google"), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Si en este teléfono ya se guardó la contraseña, se entra sin teclear nada.
                BotonEntrarConHuella(
                    onEntrar = { u, c -> onCredencialUsada(u, c); community.signIn(u, c) },
                    onAviso = onAviso
                )

                // La arroba la pinta el campo, no se teclea. Con eso desaparece la duda de
                // "¿pongo @ o no?" y de si esto era el correo. Un @usuario nunca lleva
                // arroba dentro, así que jamás se puede confundir con una dirección.
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(30) },
                    label = { Text(t(lang, "Tu usuario", "Your username")) },
                    placeholder = { Text(t(lang, "gabo_viajero", "gabo_traveler")) },
                    prefix = { Text("@", color = MutedGray, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    supportingText = {
                        when {
                            pareceUnCorreo -> Text(
                                t(lang, "Eso es un correo. Aquí va tu nombre en la comunidad, sin arroba.",
                                    "That's an email. This is your name in the community, without an @."),
                                color = StatusClosed
                            )
                            !entrando -> Text(t(lang, "Es tu nombre en la comunidad: así te llaman y te encuentran",
                                "It's your name in the community: how people call and find you"))
                            else -> {}
                        }
                    },
                    isError = pareceUnCorreo || (username.isNotBlank() && !usuarioOk),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(t(lang, "Contraseña", "Password")) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { verClave = !verClave }) {
                            Icon(
                                if (verClave) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = t(lang, "Ver contraseña", "Show password")
                            )
                        }
                    },
                    visualTransformation = if (verClave) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = {
                        if (!entrando) Text(t(lang, "Mínimo 8 caracteres. Si la pierdes, se pierde la cuenta.",
                            "At least 8 characters. If you lose it, the account is gone."))
                    },
                    isError = !entrando && password.isNotEmpty() && !claveOk,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        // Cuenta mínima: los gustos y la región se llenan luego desde el perfil.
                        onCredencialUsada(username.trim(), password)
                        if (entrando) {
                            community.signIn(username, password)
                        } else {
                            community.createAccount(username, password, emptyList(), "")
                            // Si vino de Google, ese correo queda como correo de recuperación:
                            // sirve para recordar el @usuario, nada más.
                            if (correoRecuperacion.isNotBlank()) {
                                community.guardarCorreoRecuperacion(correoRecuperacion)
                            }
                        }
                    },
                    enabled = !busy && puede,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(
                        if (entrando) t(lang, "Entrar", "Sign in") else t(lang, "Crear mi cuenta", "Create my account"),
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
                if (entrando) {
                    TextButton(
                        onClick = { buscandoPorCorreo = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            t(lang, "No me acuerdo de mi usuario", "I forgot my username"),
                            color = MutedGray, style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                TextButton(
                    onClick = { entrando = !entrando; password = "" },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (entrando) t(lang, "No tengo cuenta: quiero crear una", "I don't have an account: create one")
                        else t(lang, "Ya tengo cuenta: quiero entrar", "I already have an account: sign in"),
                        color = SkyAccent, style = MaterialTheme.typography.labelLarge
                    )
                }

                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = StatusNormal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t(
                            lang,
                            "Tus mensajes viajan cifrados: en los servidores solo queda un bulto ilegible. Y como cada mensaje va firmado con tu llave, nadie puede escribir haciéndose pasar por ti.",
                            "Your messages travel encrypted: servers only keep an unreadable blob. And since every message is signed with your key, nobody can write pretending to be you."
                        ),
                        style = MaterialTheme.typography.labelSmall, color = MutedGray
                    )
                }
            }

            if (!sync.isOnline) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
                    SyncBanner(sync, onRetry = { community.retrySync() }, onDiagnose = onDiagnosticar)
                }
            }

            Spacer(Modifier.height(16.dp))
            PieEcuador(t(lang, "Ecuador, un país por descubrir", "Ecuador, a country to discover"))
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // "No me acuerdo de mi usuario": el correo entra SOLO para devolver el @usuario.
    // No entra a la cuenta ni la identifica; eso lo sigue haciendo la llave.
    if (buscandoPorCorreo) {
        var correo by remember { mutableStateOf(correoRecuperacion) }
        AlertDialog(
            onDismissRequest = { buscandoPorCorreo = false },
            icon = { Icon(Icons.Default.Email, contentDescription = null, tint = SkyAccent) },
            title = {
                Text(t(lang, "¿Cuál era mi usuario?", "What was my username?"),
                    color = AndeanBlue, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        t(lang,
                            "Escribe el correo que guardaste en tu perfil. Solo sirve para recordarte tu @usuario: no entra a tu cuenta ni sustituye a la contraseña.",
                            "Type the email you saved in your profile. It only reminds you of your @username: it doesn't sign you in and doesn't replace your password."),
                        color = MutedGray, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = correo,
                        onValueChange = { correo = it.take(80) },
                        label = { Text(t(lang, "Tu correo", "Your email")) },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        community.recordarUsuario(correo) { encontrado ->
                            username = encontrado
                            buscandoPorCorreo = false
                        }
                    }
                ) { Text(t(lang, "Buscar", "Search"), color = AndeanBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { buscandoPorCorreo = false }) {
                    Text(t(lang, "Cerrar", "Close"), color = MutedGray)
                }
            }
        )
    }
}

/** Aviso suave, después del registro corto: completar gustos y región cuando quiera. */
@Composable
private fun PerfilIncompletoCard(onCompletar: () -> Unit, onOcultar: () -> Unit) {
    val lang by AppSettings.language.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SkyAccent.copy(alpha = 0.10f))
            .clickable(onClick = onCompletar)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SkyAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t(lang, "Termina tu perfil", "Finish your profile"),
                color = AndeanBlue, style = MaterialTheme.typography.titleSmall
            )
            Text(
                t(lang, "Elige tus gustos y a qué región viajas: así te encuentran viajeros afines.",
                    "Pick your interests and where you're headed, so like-minded travelers find you."),
                color = MutedGray, style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onOcultar) {
            Icon(Icons.Default.Close, contentDescription = t(lang, "Ocultar", "Dismiss"), tint = MutedGray)
        }
    }
}

// ============================================================================
//  Chat del grupo
// ============================================================================

@Composable
private fun GroupChat(
    group: TravelGroup,
    posts: List<GroupPost>,
    me: TravelerProfile,
    yo: String?,
    busy: Boolean,
    community: CommunityViewModel,
    routeVm: RouteViewModel,
    onOpenMap: () -> Unit,
    onInvitar: () -> Unit,
    onVerPerfil: (String, String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onShareLocation: () -> Unit
) {
    val context = LocalContext.current
    var holdMenu by remember { mutableStateOf(false) }
    val transferencias by community.transferencias.collectAsState()
    /** Id de la foto que se está viendo a pantalla completa, o null. */
    var fotoAbierta by remember { mutableStateOf<String?>(null) }

    // Todas las fotos de la conversación, en orden: el visor deja pasar de una a otra.
    val fotosDelChat = remember(posts) {
        posts.filter { it.type == PostType.PHOTO || (it.type == PostType.FILE && Attachments.Ficha.fromJson(it.payloadJson)?.esImagen == true) }
            .sortedBy { it.createdAt }
            .map {
                FotoVisor(
                    id = it.id,
                    path = it.photoPath,
                    thumb = it.photoThumb,
                    titulo = it.text,
                    lugar = it.locationLabel
                )
            }
    }

    // Los tres estados que antes no se veían por ningún lado y hacían pensar que la app
    // estaba rota: esto es una solicitud que YO tengo que aceptar; el otro todavía no me ha
    // aceptado a mí; o llegaron mensajes que no se pueden abrir.
    val esSolicitudParaMi = group.isRequestFor(yo)
    val esperandoAlOtro = group.waitingForOther(yo)
    val elOtroRespondio = posts.any { !esMensajeMio(it.authorUid, it.authorName, yo, me) }
    val hayCerrados = posts.any { it.trust == PostTrust.LOCKED }
    val elOtro = group.otherMember(yo)
    val nombreOtro = group.nombreDelInterlocutor(yo, me.username)
    val elOtroEsYo = esLaMismaPersona(elOtro, nombreOtro, yo, me.username, me.uid)
    val yaEsAmigo = community.esAmigo(elOtro)
    // El que escribió primero puede seguir cuando el otro responde, aunque Firestore tarde.
    val sigueEsperando = esperandoAlOtro && !elOtroRespondio && !esSolicitudParaMi

    // Atrás cierra el chat y vuelve al listado de Comunidad (no sale a Mi Ruta todavía).
    BackHandler { if (fotoAbierta != null) fotoAbierta = null else community.openGroup(null) }

    val abierta = fotoAbierta
    if (abierta != null && fotosDelChat.isNotEmpty()) {
        VisorFotos(
            fotos = fotosDelChat,
            inicial = fotosDelChat.indexOfFirst { it.id == abierta }.coerceAtLeast(0),
            onCerrar = { fotoAbierta = null }
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = BrandFill) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { community.openGroup(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .holdToMenu(
                            onClick = {
                                if (group.direct && elOtro != null && !elOtroEsYo) onVerPerfil(elOtro, nombreOtro)
                            },
                            onHold = { holdMenu = true }
                        )
                ) {
                    Text(
                        if (group.direct) nombreOtro else group.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        "${group.memberUids.size} miembro(s)" + if (group.encrypted) " · cifrado de punta a punta" else "",
                        color = EquatorialGold, style = MaterialTheme.typography.labelSmall
                    )
                }
                if (!group.direct) {
                    IconButton(onClick = onInvitar) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Invitar", tint = Color.White)
                    }
                } else if (elOtro != null && !elOtroEsYo) {
                    IconButton(onClick = { onVerPerfil(elOtro, nombreOtro) }) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Ver perfil", tint = Color.White)
                    }
                }
                IconButton(onClick = { holdMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Más opciones", tint = Color.White)
                }
            }
        }

        if (posts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                EmptyHint(
                    icon = Icons.Default.Forum,
                    title = "Empieza la conversación",
                    body = "Escribe un mensaje, manda una foto o comparte tu ubicación para que el grupo sepa dónde estás."
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(posts, key = { it.id }) { post ->
                    PostBubble(
                        post = post,
                        mine = esMensajeMio(post.authorUid, post.authorName, yo, me),
                        community = community,
                        routeVm = routeVm,
                        onOpenMap = onOpenMap,
                        progreso = transferencias[post.id],
                        onAbrirFoto = { fotoAbierta = it }
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)

        // Mensajes que llegaron pero no se pueden abrir. Antes desaparecían y el chat parecía
        // muerto; ahora se ven con candado y hay un botón que lo arregla de verdad.
        if (hayCerrados && group.direct) {
            Surface(color = StatusRestriction.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockPerson, contentDescription = null, tint = StatusRestriction, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Hay mensajes que no se pueden abrir", color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Pasa cuando la otra persona volvió a crear su cuenta: la llave con la que se armó este chat ya no es la suya.",
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                        )
                    }
                    Button(
                        onClick = { community.repairChat(group.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("Reparar", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        if (group.direct && elOtro != null && !elOtroEsYo) {
            Surface(color = SurfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                ChatPeerActions(
                    yaEsAmigo = yaEsAmigo,
                    nombre = nombreOtro,
                    onAgregarAmigo = { community.addFriend(elOtro, nombreOtro, group.id) },
                    onVerPerfil = { onVerPerfil(elOtro, nombreOtro) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        when {
            // Grupo de viaje: hay que aceptar para entrar. Chat directo: se puede responder ya.
            esSolicitudParaMi && !group.direct -> IncomingRequestActions(
                nombre = nombreOtro,
                esDirecto = false,
                yaEsAmigo = false,
                onResponder = { community.acceptRequest(group.id) },
                onAgregarAmigo = {},
                onVerPerfil = { onVerPerfil(group.invitedBy, nombreOtro) },
                onRechazar = { community.rejectRequest(group.id, false) }
            )

            esSolicitudParaMi && group.direct -> Column {
                IncomingRequestActions(
                    nombre = nombreOtro,
                    esDirecto = true,
                    yaEsAmigo = yaEsAmigo,
                    // Responder abre el chat de verdad: saca de "pendientes" a los dos lados.
                    onResponder = { community.replyToRequest(group.id) },
                    onAgregarAmigo = { if (elOtro != null) community.addFriend(elOtro, nombreOtro, group.id) },
                    onVerPerfil = { if (elOtro != null) onVerPerfil(elOtro, nombreOtro) },
                    onRechazar = { community.rejectRequest(group.id, false) },
                    mostrarResponder = false
                )
                ChatComposerBar(
                    placeholder = "Responde a @$nombreOtro…",
                    onSend = { community.sendMessage(it) },
                    onPickPhoto = onPickPhoto,
                    onPickVideo = onPickVideo,
                    onPickFile = onPickFile,
                    onShareLocation = onShareLocation,
                    onShareRoute = { community.shareActiveRoute(group.id) }
                )
            }

            sigueEsperando -> Surface(color = SurfaceLight, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = EquatorialGold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Esperando a que @$nombreOtro te responda", color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Ya le llegó tu mensaje. Cuando responda o te agregue como amigo podrás seguir escribiendo.",
                            style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                        )
                    }
                }
            }

            else -> ChatComposerBar(
                placeholder = if (group.direct) "Escribe a @$nombreOtro…" else "Escribe al grupo…",
                onSend = { community.sendMessage(it) },
                onPickPhoto = onPickPhoto,
                onPickVideo = onPickVideo,
                onPickFile = onPickFile,
                onShareLocation = onShareLocation,
                onShareRoute = { community.shareActiveRoute(group.id) }
            )
        }
    }
    if (holdMenu) {
        CommunityHoldMenu(
            target = if (group.direct) CommunityHoldTarget.Chat(group) else CommunityHoldTarget.Group(group),
            yo = yo,
            miUsuario = me.username,
            esAmigo = { community.esAmigo(it) },
            onDismiss = { holdMenu = false },
            onAbrirChat = { holdMenu = false },
            onVerPerfil = { fp, user -> onVerPerfil(fp, user) },
            onAgregarAmigo = { fp, user, gid -> community.addFriend(fp, user, gid) },
            onQuitarAmigo = { fp, user -> community.removeFriend(fp, user) },
            onBloquear = { fp, user -> community.blockTraveler(fp, user) },
            onDesbloquear = { community.unblockTraveler(it) },
            onSilenciar = { id, silenciar -> community.muteGroup(id, silenciar) },
            onEliminarConversacion = { community.deleteConversation(it) },
            onInvitarGrupo = { onInvitar() },
            onInvitarPersona = {},
            onEscribir = {},
            onCompartirRuta = { community.shareActiveRoute(it) }
        )
    }
    }
}

@Composable
private fun PostBubble(
    post: GroupPost,
    mine: Boolean,
    community: CommunityViewModel,
    routeVm: RouteViewModel,
    onOpenMap: () -> Unit,
    progreso: Int? = null,
    onAbrirFoto: (String) -> Unit = {}
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!mine) {
                Avatar(thumb = post.authorThumb, path = "", initials = post.authorName.take(2).uppercase(), size = 22.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                (if (mine) "Tú" else "@${post.authorName}") + " · " + timeAgo(post.createdAt),
                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
            )
            // Firma comprobada, mensaje viejo sin firma, o alguien usando el nombre de otro.
            if (!mine) {
                Spacer(Modifier.width(4.dp))
                when (post.trust) {
                    PostTrust.VERIFIED -> Icon(
                        Icons.Default.VerifiedUser, contentDescription = "Firma verificada",
                        tint = StatusNormal, modifier = Modifier.size(12.dp)
                    )
                    PostTrust.SUSPICIOUS -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GppMaybe, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("no se pudo verificar", style = MaterialTheme.typography.labelSmall, color = StatusClosed)
                    }
                    PostTrust.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = StatusRestriction, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("cerrado", style = MaterialTheme.typography.labelSmall, color = StatusRestriction)
                    }
                    PostTrust.LEGACY -> {}
                }
            }
            if (post.pendingSync) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Schedule, contentDescription = "Pendiente de enviar", tint = VolcanicGray, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.height(2.dp))

        when (post.type) {
            // Un mensaje que llegó pero no se pudo abrir. Se enseña igual, con candado y
            // diciendo por qué: es la única forma de que se sepa que el otro SÍ escribió.
            PostType.MESSAGE if post.trust == PostTrust.LOCKED -> Surface(
                color = StatusRestriction.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = StatusRestriction, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(post.text, color = VolcanicGray, style = MaterialTheme.typography.bodySmall)
                }
            }

            PostType.MESSAGE -> {
                // Un mensaje que son solo emojis se pinta grande y sin globo, como en
                // cualquier chat: si va del tamaño del texto no se lee ni se disfruta.
                val tamanoEmoji = remember(post.text) { tamanoSiSoloEmojis(post.text) }
                if (tamanoEmoji != null) {
                    Text(
                        post.text,
                        fontSize = tamanoEmoji.sp,
                        lineHeight = (tamanoEmoji + 8).sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(max = 280.dp)
                    )
                } else {
                    Surface(
                        color = if (mine) BrandFill else SurfaceContainerLowest,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            post.text,
                            color = if (mine) OnBrand else VolcanicGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp)
                        )
                    }
                }
            }

            PostType.PHOTO -> {
                val bitmap = remember(post.photoPath, post.photoThumb) {
                    PhotoStore.decodeLocal(post.photoPath, 720) ?: PhotoStore.decodeThumb(post.photoThumb)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 260.dp)
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Foto compartida",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clickable { onAbrirFoto(post.id) }
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = VolcanicGray)
                        }
                    }
                    if (post.text.isNotBlank()) {
                        Text(post.text, color = VolcanicGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                    }
                }
            }

            // Adjunto: foto grande, video, audio o documento. La miniatura se ve al momento;
            // el archivo entero se baja de un toque y se abre con la app que uno prefiera.
            PostType.FILE -> {
                val ficha = remember(post.payloadJson) { Attachments.Ficha.fromJson(post.payloadJson) }
                val bajado = remember(post.photoPath, progreso) { Attachments.existe(post.photoPath) }
                // La foto se pide sola al puente; no hay que tocarla para verla.
                LaunchedEffect(post.id, bajado, ficha?.esImagen) {
                    if (!bajado && ficha?.esImagen == true) community.prefetchChatImage(post)
                }
                val vista = remember(post.photoPath, post.photoThumb, bajado) {
                    if (bajado && ficha?.esImagen == true) PhotoStore.decodeLocal(post.photoPath, 720)
                        ?: PhotoStore.decodeThumb(post.photoThumb)
                    else PhotoStore.decodeThumb(post.photoThumb)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 280.dp).clickable {
                        if (ficha != null) {
                            if (bajado) Intents.openFile(context, post.photoPath, ficha.mime)
                            else community.openAttachment(post)
                        }
                    }
                ) {
                    if (vista != null) {
                        Box {
                            Image(
                                bitmap = vista.asImageBitmap(),
                                contentDescription = ficha?.nombre ?: "Adjunto",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(190.dp)
                            )
                            if (ficha?.esVideo == true) {
                                Box(
                                    Modifier.matchParentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(color = Color.Black.copy(alpha = 0.45f), shape = CircleShape) {
                                        Icon(
                                            Icons.Default.PlayArrow, contentDescription = "Reproducir",
                                            tint = Color.White, modifier = Modifier.padding(10.dp).size(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when {
                                ficha == null -> Icons.Default.AttachFile
                                ficha.esImagen -> Icons.Default.Image
                                ficha.esVideo -> Icons.Default.Movie
                                ficha.esAudio -> Icons.Default.Audiotrack
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            },
                            contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ficha?.nombre ?: "Archivo", color = AndeanBlue, maxLines = 1,
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                listOfNotNull(
                                    ficha?.tamanoLegible,
                                    ficha?.duracionLegible?.takeIf { it.isNotBlank() },
                                    if (bajado) "en tu teléfono" else "toca para bajarlo"
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                            )
                        }
                        if (bajado) {
                            IconButton(onClick = { ficha?.let { Intents.shareFile(context, post.photoPath, it.mime) } }) {
                                Icon(Icons.Default.Share, contentDescription = "Compartir", tint = AndeanBlue, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Descargar", tint = EquatorialGold)
                        }
                    }
                    if (progreso != null) {
                        LinearProgressIndicator(
                            progress = { progreso / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = EquatorialGold
                        )
                    }
                    if (post.text.isNotBlank()) {
                        Text(
                            post.text, color = VolcanicGray, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                        )
                    }
                }
            }

            PostType.LOCATION -> Card(
                colors = CardDefaults.cardColors(containerColor = GalapagosTeal.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = StatusClosed)
                        Spacer(Modifier.width(6.dp))
                        Text("Ubicación compartida", color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    if (post.locationLabel.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(post.locationLabel, color = VolcanicGray, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        formatCoords(post.latitude, post.longitude),
                        color = VolcanicGray.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { routeVm.focusOnMap(post.latitude, post.longitude); onOpenMap() },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ver en el mapa", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                Intents.navigateTo(context, post.latitude, post.longitude, post.locationLabel.ifBlank { post.authorName })
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(14.dp), tint = AndeanBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Cómo llegar", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            PostType.PLACE -> {
                val place = remember(post.payloadJson) { community.decodePlace(post.payloadJson) }
                if (place != null) {
                    Box(Modifier.widthIn(max = 320.dp)) {
                        PlaceSummaryCard(
                            place = place,
                            onClick = { routeVm.selectPlace(place) },
                            onAddToRoute = { routeVm.addToRoute(place) }
                        )
                    }
                } else {
                    Text(post.text, color = VolcanicGray)
                }
            }

            PostType.ROUTE -> Card(
                colors = CardDefaults.cardColors(containerColor = EquatorialGold.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text(post.text, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { routeVm.importSharedRoute(post.payloadJson, post.authorName) },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandFill),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copiar a mis rutas", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ============================================================================
//  Diálogos
// ============================================================================

/**
 * La ficha de otro viajero.
 *
 * Solo enseña lo que esa persona decidió publicar (su vitrina) y lo que llegó firmado dentro
 * de sus propios mensajes. No hay nada sacado por detrás: si no encendió la vitrina, se ve lo
 * poco que se aprendió hablando con él, y se dice tal cual.
 */
@Composable
private fun PerfilSheet(
    card: TravelerCard,
    cargando: Boolean,
    misIntereses: List<String>,
    onCerrar: () -> Unit,
    onEscribir: () -> Unit,
    onBloquear: () -> Unit,
    onDesbloquear: () -> Unit,
    onNota: (String) -> Unit,
    onInvitarAGrupo: () -> Unit
) {
    var nota by remember(card.fingerprint) { mutableStateOf("") }
    var editandoNota by remember(card.fingerprint) { mutableStateOf(false) }
    val enComun = remember(card, misIntereses) {
        card.inCommon.ifEmpty { card.interests.filter { i -> misIntereses.any { it.equals(i, true) } } }
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(thumb = card.thumb, path = "", initials = card.initials, size = 48.dp, background = GalapagosTeal)
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("@" + card.username.ifBlank { card.fingerprint.take(10) }, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        if (card.verified) {
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Default.VerifiedUser, contentDescription = "Firma verificada", tint = StatusNormal, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(
                        when {
                            card.isBlocked -> "Bloqueado"
                            card.state == ContactState.CONTACTO -> "Es tu contacto"
                            card.showcase -> "Está buscando compañeros de viaje"
                            else -> "Todavía no es tu contacto"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (card.isBlocked) StatusClosed else VolcanicGray
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (cargando) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
                    Spacer(Modifier.height(8.dp))
                }
                if (card.displayName.isNotBlank() && !card.displayName.equals(card.username, true)) {
                    Text(card.displayName, color = AndeanBlue, fontWeight = FontWeight.Bold)
                }
                if (card.bio.isNotBlank()) {
                    Text(card.bio, style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                    Spacer(Modifier.height(8.dp))
                }

                // Lo que de verdad dice si vale la pena hablarle: cuánto se conocen y cuánto suma.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatoChip(Icons.Default.Forum, if (card.messagesSeen > 0) card.messagesSeen.toString() + " mensajes" else "sin mensajes")
                    if (card.knownSince > 0) DatoChip(Icons.Default.Schedule, "desde " + timeAgo(card.knownSince))
                    if (card.contributions > 0) DatoChip(Icons.Default.Public, card.contributions.toString() + " aportes")
                }

                if (card.region.isNotBlank() || card.travelMonth.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOfNotNull(
                            card.region.takeIf { it.isNotBlank() }?.let { "Viaja a " + it },
                            card.travelMonth.takeIf { it.isNotBlank() }?.let { "en " + it }
                        ).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium, color = AndeanBlue
                    )
                }
                if (card.routeLabel.isNotBlank()) {
                    Text(card.routeLabel, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                }

                if (enComun.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Les gusta lo mismo: " + enComun.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = StatusNormal)
                }
                if (card.interests.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(card.interests) { i ->
                            Surface(color = EquatorialGold.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                                Text(i, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
                if (card.travelTags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(card.travelTags) { tag ->
                            Surface(color = AndeanBlue.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                                Text(tag, style = MaterialTheme.typography.labelSmall, color = AndeanBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (editandoNota) {
                    OutlinedTextField(
                        value = nota, onValueChange = { nota = it.take(140) },
                        label = { Text("Nota privada") },
                        placeholder = { Text("Ej. el de la camioneta que va a Baños") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { onNota(nota); editandoNota = false }) { Text("Guardar nota", color = AndeanBlue) }
                } else {
                    TextButton(onClick = { editandoNota = true }) {
                        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Ponerle una nota que solo veas tú", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (!card.isBlocked && card.username.length >= 3) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onInvitarAGrupo, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("Meter en un grupo de viaje", color = AndeanBlue)
                    }
                }

                if (!card.verified && card.messagesSeen > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GppMaybe, contentDescription = null, tint = StatusRestriction, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Alguno de sus mensajes no se pudo verificar. Desconfía si te pide dinero o datos.",
                            style = MaterialTheme.typography.labelSmall, color = StatusRestriction
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (card.isBlocked) {
                Button(onClick = onDesbloquear, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                    Text("Desbloquear")
                }
            } else {
                Button(onClick = onEscribir, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Escribirle")
                }
            }
        },
        dismissButton = {
            Row {
                if (!card.isBlocked) {
                    TextButton(onClick = onBloquear) { Text("Bloquear", color = StatusClosed) }
                }
                TextButton(onClick = onCerrar) { Text("Cerrar") }
            }
        }
    )
}

@Composable
private fun DatoChip(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String) {
    Surface(color = SurfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(texto, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
        }
    }
}

/**
 * Invitar a un grupo: por @usuario (la llave del grupo se cifra solo para esa persona) o
 * mandando el enlace por WhatsApp. En ninguno de los dos casos nadie teclea un código.
 */
@Composable
private fun InvitarDialog(
    busy: Boolean,
    enlace: String,
    nombreGrupo: String,
    onDismiss: () -> Unit,
    onInvitar: (String) -> Unit
) {
    val context = LocalContext.current
    var usuario by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invitar a " + nombreGrupo.ifBlank { "tu grupo" }, color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                    label = { Text("Nombre de usuario") },
                    placeholder = { Text("ej. gabriel_ec") },
                    prefix = { Text("@", color = VolcanicGray) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Le llega como invitación: entra al grupo cuando la acepte.",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
                if (enlace.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = VolcanicGray.copy(alpha = 0.15f))
                    Spacer(Modifier.height(10.dp))
                    Text("O mándale el enlace", style = MaterialTheme.typography.labelLarge, color = AndeanBlue, fontWeight = FontWeight.Bold)
                    Text(
                        "Quien lo toque entra directo, sin escribir nada. El enlace lleva la llave del grupo dentro, " +
                            "así que compártelo solo con quien quieras dentro.",
                        style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                Intents.shareText(
                                    context,
                                    "Te invito a mi grupo \"" + nombreGrupo + "\" en Travesías en Ecuador.\n\nToca aquí para entrar:\n" + enlace,
                                    "Invitación"
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusNormal)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Compartir")
                        }
                        OutlinedButton(
                            onClick = { Intents.copyToClipboard(context, "Invitación", enlace) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Copiar", color = AndeanBlue)
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (usuario.length >= 3) onInvitar(usuario) },
                enabled = usuario.length >= 3 && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Invitar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

/**
 * Escribirle a alguien sabiendo solo su usuario, como quien tiene tu número de teléfono.
 * No hay lista de usuarios que se pueda husmear: hay que saber el nombre exacto.
 */
@Composable
private fun UsuarioDialog(busy: Boolean, onDismiss: () -> Unit, onEscribir: (String) -> Unit) {
    var usuario by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escribir a un viajero", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it.lowercase().replace(Regex("[^a-z0-9_.]"), "").take(20) },
                    label = { Text("Nombre de usuario") },
                    placeholder = { Text("ej. gabriel_ec") },
                    prefix = { Text("@", color = VolcanicGray) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Se abre un chat solo entre ustedes dos, cifrado de punta a punta. Nadie más " +
                        "puede leerlo, ni siquiera nosotros. Tu usuario no aparece en ninguna lista: " +
                        "solo te encuentra quien lo sepa escribir tal cual.",
                    style = MaterialTheme.typography.labelSmall, color = VolcanicGray
                )
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = EquatorialGold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (usuario.length >= 3) onEscribir(usuario) },
                enabled = usuario.length >= 3 && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Escribirle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Diagnóstico de la Comunidad: prueba pieza por pieza y dice qué falla y cómo se arregla.
 * Nace de un problema real: "No se pudo activar la cuenta" no le decía a nadie qué tocar.
 */
@Composable
private fun DiagnosticoDialog(
    chequeos: List<ec.travesias.app.data.social.CommunityRepository.Chequeo>?,
    revisando: Boolean,
    onRepetir: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revisión de la Comunidad", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (revisando || chequeos == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = AndeanBlue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Probando cada pieza…", color = VolcanicGray)
                    }
                } else {
                    chequeos.forEach { c ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Icon(
                                if (c.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (c.ok) StatusNormal else StatusClosed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.nombre, color = AndeanBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Text(c.detalle, style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                            }
                        }
                    }
                    if (chequeos.any { !it.ok }) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { Intents.openUrl(context, TransportAuth.URL_CONSOLA) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = AndeanBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Abrir la consola de Firebase", color = AndeanBlue)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onRepetir, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                Text("Volver a probar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    firstLabel: String,
    firstPlaceholder: String,
    secondLabel: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first, onValueChange = { first = it },
                    label = { Text(firstLabel) }, placeholder = { Text(firstPlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = second, onValueChange = { second = it },
                    label = { Text(secondLabel) }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(first, second) }, colors = ButtonDefaults.buttonColors(containerColor = BrandFill)) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ProfileEditorDialog(
    profile: TravelerProfile,
    options: List<String>,
    tagOptions: List<String> = emptyList(),
    conCuenta: Boolean = false,
    onAviso: (String) -> Unit = {},
    community: CommunityViewModel? = null,
    onDismiss: () -> Unit,
    onShowcase: (Boolean) -> Unit = {},
    onVisibility: (ProfileVisibility) -> Unit = {},
    onSignOut: () -> Unit = {},
    onSave: (TravelerProfile) -> Unit
) {
    var bio by remember { mutableStateOf(profile.bio) }
    var region by remember { mutableStateOf(profile.region) }
    var origin by remember { mutableStateOf(profile.routeOrigin) }
    var dest by remember { mutableStateOf(profile.routeDestination) }
    var month by remember { mutableStateOf(profile.travelMonth) }
    val interests = remember { mutableStateListOf<String>().apply { addAll(profile.interests) } }
    val travelTags = remember { mutableStateListOf<String>().apply { addAll(profile.travelTags) } }
    var vis by remember { mutableStateOf(profile.visibility) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mi perfil de viajero", color = AndeanBlue, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = bio, onValueChange = { bio = it.take(120) },
                    label = { Text("Sobre mí") }, modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(options) { opt ->
                        val sel = interests.contains(opt)
                        FilterChip(
                            selected = sel,
                            onClick = { if (sel) interests.remove(opt) else interests.add(opt) },
                            label = { Text(opt) }
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EcuadorRegion.entries.toList()) { r ->
                        FilterChip(selected = region == r.displayName, onClick = { region = r.displayName }, label = { Text(r.displayName) })
                    }
                }
                OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Mi ruta: desde") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dest, onValueChange = { dest = it }, label = { Text("Mi ruta: hasta") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = month, onValueChange = { month = it },
                    label = { Text("Mes de viaje (AAAA-MM)") }, placeholder = { Text("2026-10") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (tagOptions.isNotEmpty()) {
                    Text(
                        "Etiquetas de viaje (para encontrar compañeros, aventón o mochileros)",
                        style = MaterialTheme.typography.labelMedium, color = AndeanBlue, fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(tagOptions) { tag ->
                            val sel = travelTags.contains(tag)
                            FilterChip(
                                selected = sel,
                                onClick = { if (sel) travelTags.remove(tag) else travelTags.add(tag) },
                                label = { Text(tag) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EquatorialGold, selectedLabelColor = AndeanBlue)
                            )
                        }
                    }
                }

                if (conCuenta) {
                    Spacer(Modifier.height(4.dp))
                    // Correo de recuperación: OPCIONAL, y solo sirve para recordar el
                    // @usuario. No entra a la cuenta ni sustituye a la contraseña.
                    var correoRec by remember { mutableStateOf(profile.recoveryEmail) }
                    OutlinedTextField(
                        value = correoRec,
                        onValueChange = { correoRec = it.take(80) },
                        label = { Text("Correo de recuperación (opcional)") },
                        placeholder = { Text("para recordar tu @usuario") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        supportingText = {
                            Text(
                                if (profile.recoveryEmail.isBlank())
                                    "Sin esto, si olvidas tu @usuario no hay forma de recordarlo."
                                else "Guardado. Solo se publica una huella del correo, nunca la dirección."
                            )
                        },
                        trailingIcon = {
                            if (correoRec != profile.recoveryEmail) {
                                TextButton(onClick = { community?.guardarCorreoRecuperacion(correoRec.trim()) }) {
                                    Text("Guardar")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    if (community != null) {
                        FilaMiHuella(community)
                        Spacer(Modifier.height(6.dp))
                        PanelAdmin(community)
                        Spacer(Modifier.height(6.dp))
                    }
                    // Ver o quitar la contraseña guardada bajo la huella. Es lo que evita
                    // perder la cuenta al cambiar de teléfono.
                    FilaContrasenaGuardada(onAviso = onAviso)
                    Spacer(Modifier.height(4.dp))
                    ProfilePrivacySettings(
                        actual = vis,
                        onCambiar = { nueva ->
                            vis = nueva
                            onVisibility(nueva)
                        }
                    )
                    TextButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusClosed)
                        Spacer(Modifier.width(6.dp))
                        Text("Cerrar sesión en este teléfono", color = StatusClosed, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            bio = bio.trim(), interests = interests.toList(), region = region,
                            routeOrigin = origin.trim(), routeDestination = dest.trim(), travelMonth = month.trim(),
                            travelTags = travelTags.toList(),
                            visibility = vis,
                            showcase = vis == ProfileVisibility.PUBLIC
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandFill)
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun formatCoords(lat: Double, lng: Double): String =
    String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)

private fun timeAgo(ts: Long): String {
    if (ts <= 0) return ""
    val diff = (System.currentTimeMillis() - ts) / 1000
    return when {
        diff < 60 -> "ahora"
        diff < 3600 -> "hace ${diff / 60} min"
        diff < 86400 -> "hace ${diff / 3600} h"
        else -> "hace ${diff / 86400} d"
    }
}

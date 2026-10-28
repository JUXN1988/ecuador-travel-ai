package ec.travesias.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ec.travesias.app.TravesiasApp
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.repository.JsonCodecPlace
import ec.travesias.app.data.social.AccountState
import ec.travesias.app.data.social.AlbumPhoto
import ec.travesias.app.data.social.AlbumPost
import ec.travesias.app.data.social.agruparPublicaciones
import ec.travesias.app.data.social.Attachments
import ec.travesias.app.data.social.AuthPrefs
import ec.travesias.app.data.social.PhotoStore
import ec.travesias.app.data.social.Comentario
import ec.travesias.app.data.social.CommunityRepository
import ec.travesias.app.data.social.InteraccionesRepository
import ec.travesias.app.data.social.Reacciones
import ec.travesias.app.data.social.SocialDePost
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.data.social.BuddyMatch
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.data.social.GroupPost
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.SyncStatus
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.TravelerProfile
import ec.travesias.app.data.social.esConversacionConmigo
import ec.travesias.app.data.social.esYo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel de la Comunidad. Todo se lee de la base local del teléfono, así que la
 * pantalla funciona igual con o sin internet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityViewModel(application: Application) : AndroidViewModel(application) {

    private val container = TravesiasApp.from(application)
    private val community = container.community
    private val albumRepo = container.album
    private val interacciones = container.interacciones
    private val routeRepo = container.routeRepository
    private val geocoding = container.geocoding

    val sync: StateFlow<SyncStatus> = community.sync

    val profile: StateFlow<TravelerProfile?> =
        community.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<TravelGroup>> =
        community.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Mi huella: es mi identificador dentro de los grupos y lo que decide qué es una solicitud. */
    val myFingerprint: StateFlow<String?> = community.accountState
        .map { (it as? AccountState.SignedIn)?.identity?.fingerprint }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Lo que me llegó y todavía no he aceptado. Aquí es donde se ve quién quiere hablarme,
     * con su perfil delante, para poder decir que sí, que no, o bloquear.
     */
    val requests: StateFlow<List<TravelGroup>> = combine(groups, myFingerprint, profile) { lista, yo, me ->
        lista.filter { g ->
            g.isRequestFor(yo) && !g.esConversacionConmigo(yo, me?.username, me?.uid)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chats de dos personas, ya aceptados. Nunca un chat con uno mismo. */
    val chats: StateFlow<List<TravelGroup>> = combine(groups, myFingerprint, profile) { lista, yo, me ->
        lista.filter { g ->
            g.direct && !g.isRequestFor(yo) && !g.esConversacionConmigo(yo, me?.username, me?.uid)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Grupos de viaje (tres o más), ya aceptados. */
    val travelGroups: StateFlow<List<TravelGroup>> = combine(groups, myFingerprint) { lista, yo ->
        lista.filter { !it.direct && !it.isRequestFor(yo) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gente que conozco: con quien he hablado o a quien acepté. Yo no salgo aquí. */
    val contacts: StateFlow<List<TravelerCard>> =
        combine(community.contacts, myFingerprint, profile) { lista, yo, me ->
            lista.filter { !it.esYo(yo, me?.username, me?.uid) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bloqueados, para poder darles marcha atrás. Yo no salgo aquí. */
    val blockedTravelers: StateFlow<List<TravelerCard>> =
        combine(community.blocked, myFingerprint, profile) { lista, yo, me ->
            lista.filter { !it.esYo(yo, me?.username, me?.uid) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Perfil abierto ahora mismo (null si no hay ninguno). */
    private val _openProfile = MutableStateFlow<TravelerCard?>(null)
    val openProfile: StateFlow<TravelerCard?> = _openProfile.asStateFlow()

    private val _profileLoading = MutableStateFlow(false)
    val profileLoading: StateFlow<Boolean> = _profileLoading.asStateFlow()

    /** Álbum del perfil abierto (fotos de la otra persona). */
    private val _openAlbum = MutableStateFlow<List<AlbumPhoto>>(emptyList())
    val openAlbum: StateFlow<List<AlbumPhoto>> = _openAlbum.asStateFlow()

    /** Mi álbum de viajes, para el recorte del perfil. */
    val myAlbum: StateFlow<List<AlbumPhoto>> = combine(myFingerprint, community.accountState) { fp, _ -> fp }
        .flatMapLatest { fp ->
            if (fp.isNullOrBlank()) flowOf(emptyList()) else albumRepo.observeMine(fp)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Feed de Comunidad: mis fotos y las de mis amigos, agrupadas en publicaciones
     * (una o varias imágenes por post, como en Instagram).
     */
    val feedPosts: StateFlow<List<AlbumPost>> = combine(
        albumRepo.observeCached(),
        myFingerprint,
        profile,
        contacts
    ) { fotos, yo, me, amigos ->
        val amigosFp = amigos.filter { it.state == ContactState.CONTACTO }.map { it.fingerprint }.toSet()
        val visibles = fotos.filter { foto ->
            foto.ownerFingerprint == yo || foto.ownerFingerprint in amigosFp
        }
        val nombres = buildMap {
            if (!yo.isNullOrBlank()) put(yo, me?.username.orEmpty())
            amigos.forEach { put(it.fingerprint, it.username) }
        }
        val thumbs = buildMap {
            if (!yo.isNullOrBlank()) put(yo, me?.avatarThumb.orEmpty())
            amigos.forEach { put(it.fingerprint, it.thumb) }
        }
        agruparPublicaciones(
            visibles,
            usernameOf = { nombres[it].orEmpty().ifBlank { "viajero" } },
            thumbOf = { thumbs[it].orEmpty() }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Reacciones y comentarios de cada publicación, por setId. Sale de la base local, así
     * que el feed ya viene pintado antes de que Firestore conteste (y con avión activado
     * se siguen viendo los de siempre).
     */
    val social: StateFlow<Map<String, SocialDePost>> = interacciones.observar()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Publicaciones cuyo social ya se pidió en esta sesión: no se piden dos veces. */
    private val socialPedido = HashSet<String>()

    /** Huella del perfil cuyo muro se está mirando ("" si ninguno). */
    private val _muroDe = MutableStateFlow("")

    /** Comentarios del muro del perfil que se está mirando. */
    val muro: StateFlow<List<Comentario>> = _muroDe
        .flatMapLatest { fp -> if (fp.isBlank()) flowOf(emptyList()) else interacciones.observarMuro(fp) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Mi propio muro. Va por separado del de arriba a propósito: si compartieran estado,
     * abrir el perfil de otra persona borraría el mío de la pantalla.
     */
    val miMuro: StateFlow<List<Comentario>> = myFingerprint
        .flatMapLatest { fp -> if (fp.isNullOrBlank()) flowOf(emptyList()) else interacciones.observarMuro(fp) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedGroupId = MutableStateFlow<String?>(null)

    val selectedGroup: StateFlow<TravelGroup?> =
        combine(groups, _selectedGroupId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val posts: StateFlow<List<GroupPost>> = _selectedGroupId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else community.posts(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _buddies = MutableStateFlow<List<BuddyMatch>>(emptyList())
    val buddies: StateFlow<List<BuddyMatch>> = _buddies.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Cuánto va de cada envío o descarga de adjunto (0-100), por id de mensaje. */
    val transferencias: StateFlow<Map<String, Int>> = community.transferencias

    /** Un archivo ya bajado y listo para abrirlo: (ruta en el teléfono, tipo). */
    private val _abrirArchivo = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val abrirArchivo: SharedFlow<Pair<String, String>> = _abrirArchivo.asSharedFlow()

    /** Resultado del diagnóstico de la comunidad, o null si no se ha pedido. */
    private val _diagnostico = MutableStateFlow<List<CommunityRepository.Chequeo>?>(null)
    val diagnostico: StateFlow<List<CommunityRepository.Chequeo>?> = _diagnostico.asStateFlow()

    private val _diagnosticando = MutableStateFlow(false)
    val diagnosticando: StateFlow<Boolean> = _diagnosticando.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message.asSharedFlow()

    /** Aviso suelto desde la interfaz (huella, permisos…): sale por el mismo sitio que los demás. */
    fun avisar(texto: String) = io { _message.emit(texto) }

    // ---------------------------------------------------------------- correo -> @usuario
    /**
     * "No me acuerdo de mi usuario": se busca por el correo de recuperación.
     * El correo NUNCA identifica a nadie; solo sirve para recordar el @usuario.
     * Devuelve el usuario por [onEncontrado], o avisa si no hay nada.
     */
    fun recordarUsuario(correo: String, onEncontrado: (String) -> Unit) = io {
        if (!ec.travesias.app.data.social.AccountService.pareceCorreo(correo)) {
            _message.emit("Escribe el correo completo, con arroba y punto.")
            return@io
        }
        _busy.value = true
        try {
            val usuario = community.usuarioPorCorreo(correo)
            if (usuario.isNullOrBlank()) {
                _message.emit("Ningún viajero guardó ese correo. Prueba con otro o entra con tu @usuario.")
            } else {
                onEncontrado(usuario)
                _message.emit("Tu usuario es @" + usuario)
            }
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo buscar")
        } finally {
            _busy.value = false
        }
    }

    // ---------------------------------------------------------------- administración
    private val _soyAdmin = MutableStateFlow(false)
    /** true solo si mi huella está dada de alta en `admins` de Firestore. */
    val soyAdmin: StateFlow<Boolean> = _soyAdmin.asStateFlow()

    private val _huerfanas = MutableStateFlow<List<ec.travesias.app.data.social.AdminService.Candidata>>(emptyList())
    val huerfanas: StateFlow<List<ec.travesias.app.data.social.AdminService.Candidata>> = _huerfanas.asStateFlow()

    /** Mi huella `tv_...`, que es lo que se pone en la consola para nombrar un administrador. */
    fun miHuella(): String = community.miHuella()

    fun comprobarSiSoyAdmin() = io {
        _soyAdmin.value = runCatching { community.soyAdmin() }.getOrDefault(false)
    }

    fun cargarHuerfanas(dias: Int = 30) = io {
        _busy.value = true
        try {
            _huerfanas.value = community.cuentasHuerfanas(dias)
            if (_huerfanas.value.isEmpty()) _message.emit("No hay cuentas sin usar de más de $dias días")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo revisar")
        } finally {
            _busy.value = false
        }
    }

    fun borrarHuerfana(c: ec.travesias.app.data.social.AdminService.Candidata) = io {
        _busy.value = true
        try {
            val fallos = community.borrarCuentaHuerfana(c)
            _huerfanas.value = _huerfanas.value.filterNot { it.huella == c.huella }
            _message.emit(
                if (fallos.isEmpty()) "Se borró @" + c.usuario
                else "@" + c.usuario + ": no se pudo borrar " + fallos.joinToString(", ")
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo borrar")
        } finally {
            _busy.value = false
        }
    }

    /** Guarda (o quita) el correo de recuperación del perfil. */
    fun guardarCorreoRecuperacion(correo: String) = io {
        _busy.value = true
        try {
            community.setRecoveryEmail(correo)
            _message.emit(
                if (correo.isBlank()) "Se quitó tu correo de recuperación"
                else "Listo: con ese correo puedes recordar tu @usuario"
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo guardar el correo")
        } finally {
            _busy.value = false
        }
    }

    val interestOptions = listOf(
        "Naturaleza", "Gastronomía", "Aventura", "Playa", "Cultura", "Fotografía", "Senderismo", "Surf",
        "Volcanes", "Selva", "Vida nocturna", "Mochilero", "Familiar", "Fotos virales", "Camping", "Buceo"
    )

    /** Para encontrar compañeros de ruta, aventón o mochileros afines. */
    val travelTagOptions = listOf(
        "Mochilero/a", "Busco aventón", "Ofrezco aventón", "Viajo solo/a", "Viajo en pareja",
        "Viajo en grupo", "Flexible con fechas", "Tengo carro/camioneta", "Busco compañeros de ruta"
    )

    // ---------------------------------------------------------------- cuenta

    /** Si en este teléfono hay una sesión iniciada (con llave propia). */
    val accountState: StateFlow<AccountState> = community.accountState

    /**
     * Crea la cuenta: usuario + contraseña. La contraseña no sale del teléfono; lo que sube a
     * Firebase es la llave privada metida en un sobre que solo esa contraseña abre.
     */
    fun createAccount(username: String, password: String, interests: List<String>, region: String) = io {
        _busy.value = true
        try {
            val p = community.createAccount(username, password, interests, region)
            val aviso = sync.value.detail
            _message.emit(
                if (aviso.isBlank()) "Cuenta creada. ¡Bienvenido/a, @" + p.username + "!"
                else "Tu cuenta ya funciona en este teléfono, @" + p.username + ". " + aviso
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo crear la cuenta")
        } finally {
            _busy.value = false
        }
    }

    /**
     * Crear cuenta desde la bienvenida: correo + contraseña + @usuario.
     * El cifrado sigue igual; el correo se recuerda en este teléfono.
     */
    fun createAccountWithEmail(
        email: String,
        password: String,
        username: String,
        interests: List<String>,
        region: String
    ) = io {
        _busy.value = true
        try {
            val mail = email.trim()
            if (!ec.travesias.app.ui.auth.correoValido(mail)) {
                _message.emit("Escribe un correo válido.")
                return@io
            }
            val user = username.ifBlank { ec.travesias.app.ui.auth.usuarioDesdeCorreo(mail) }
            val p = community.createAccount(user, password, interests, region)
            AuthPrefs(getApplication()).saveEmail(mail, p.username)
            val aviso = sync.value.detail
            _message.emit(
                if (aviso.isBlank()) "Cuenta creada. ¡Bienvenido/a, @" + p.username + "!"
                else "Tu cuenta ya funciona en este teléfono, @" + p.username + ". " + aviso
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo crear la cuenta")
        } finally {
            _busy.value = false
        }
    }

    /** Entrar en este teléfono con una cuenta que ya existe: vuelven sus grupos. */
    fun signIn(username: String, password: String) = io {
        _busy.value = true
        try {
            val p = community.signIn(username, password)
            _message.emit("Hola de nuevo, @" + p.username)
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo entrar")
        } finally {
            _busy.value = false
        }
    }

    /** Entrar con correo o con @usuario; misma cuenta de siempre. */
    fun signInWithEmailOrUser(login: String, password: String) = io {
        _busy.value = true
        try {
            val prefs = AuthPrefs(getApplication())
            val handle = ec.travesias.app.ui.auth.identificadorDeEntrada(login, prefs)
            if (handle.length < 3) {
                _message.emit("Escribe tu correo o @usuario.")
                return@io
            }
            val p = community.signIn(handle, password)
            if (login.contains("@")) prefs.saveEmail(login.trim(), p.username)
            _message.emit("Hola de nuevo, @" + p.username)
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo entrar")
        } finally {
            _busy.value = false
        }
    }

    /** Cierra sesión en ESTE teléfono; la cuenta y los grupos siguen existiendo. */
    fun signOut() = io {
        _busy.value = true
        try {
            community.signOutAccount()
            _message.emit("Sesión cerrada en este teléfono")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo cerrar la sesión")
        } finally {
            _busy.value = false
        }
    }

    fun changePassword(actual: String, nueva: String) = io {
        try {
            community.changePassword(actual, nueva)
            _message.emit("Contraseña cambiada")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo cambiar la contraseña")
        }
    }

    /** Vitrina: lo mínimo del perfil para que otros te encuentren. Se puede apagar. */
    fun setShowcase(enabled: Boolean) = io {
        try {
            community.setShowcase(enabled)
            _message.emit(if (enabled) "Tu perfil es público: otros viajeros pueden encontrarte" else "Tu perfil quedó solo para amigos")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo cambiar")
        }
    }

    /** Público, solo amigos o privado. */
    fun setVisibility(vis: ProfileVisibility) = io {
        try {
            community.setVisibility(vis)
            _message.emit(
                when (vis) {
                    ProfileVisibility.PUBLIC -> "Perfil público: cualquiera puede verte"
                    ProfileVisibility.FRIENDS -> "Perfil solo para amigos"
                    ProfileVisibility.PRIVATE -> "Perfil privado: nadie ve tus fotos ni tus datos"
                }
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo cambiar la privacidad")
        }
    }

    // ---------------------------------------------------------------- perfil

    fun register(username: String, interests: List<String>, region: String) = io {
        _busy.value = true
        try {
            val p = community.registerUsername(username, interests, region)
            _message.emit("¡Bienvenido/a, @${p.username}!")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo crear el perfil")
        } finally {
            _busy.value = false
        }
    }

    fun saveProfile(updated: TravelerProfile) = io {
        try {
            community.updateProfile(updated)
            _message.emit("Perfil actualizado")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo guardar el perfil")
        }
    }

    /** La foto se guarda en este teléfono; solo se comparte una miniatura. */
    fun setAvatar(uri: Uri) = io {
        _busy.value = true
        try {
            if (community.setAvatar(uri)) _message.emit("Foto de perfil actualizada")
            else _message.emit("No se pudo leer esa imagen")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo guardar la foto")
        } finally {
            _busy.value = false
        }
    }

    /** Copia origen/destino de la ruta activa al perfil para que otros te encuentren. */
    fun syncActiveRouteToProfile() = io {
        val me = profile.value ?: return@io
        val route = routeRepo.activeRoute.first() ?: return@io
        runCatching {
            community.updateProfile(
                me.copy(
                    routeOrigin = route.originName,
                    routeDestination = route.destinationName,
                    travelMonth = route.startDate.take(7)
                )
            )
        }
    }

    fun retrySync() {
        community.retrySync()
    }

    // ---------------------------------------------------------------- grupos

    fun createGroup(name: String, description: String) = io {
        try {
            val g = community.createGroup(name, description)
            _selectedGroupId.value = g.id
            // No se enseña el código TRV1: se invita por @usuario o por el enlace corto.
            _message.emit("Grupo \"" + g.name + "\" listo. Invita a tus amigos por @usuario o mándales el enlace.")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo crear el grupo")
        }
    }

    fun joinGroup(code: String) = io {
        _busy.value = true
        try {
            val g = community.joinByLinkOrCode(code)
            _selectedGroupId.value = g.id
            _message.emit("Te uniste a '${g.name}'")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo unir al grupo")
        } finally {
            _busy.value = false
        }
    }

    fun leaveGroup(groupId: String) = io {
        try {
            community.leaveGroup(groupId)
            if (_selectedGroupId.value == groupId) _selectedGroupId.value = null
            _message.emit("Saliste del grupo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo salir del grupo")
        }
    }

    fun openGroup(id: String?) {
        _selectedGroupId.value = id
    }

    // ---------------------------------------------------------------- solicitudes y gente

    /** Aceptar: a partir de aquí los mensajes van y vienen sin límite. */
    fun acceptRequest(groupId: String) = io {
        try {
            community.acceptInvitation(groupId)
            _selectedGroupId.value = groupId
            _message.emit("Listo, ya pueden hablar y quedó como amigo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo aceptar")
        }
    }

    /**
     * Responder sin ser amigos: abre el chat y me saca de "pendientes" en los dos lados.
     *
     * ANTES esto solo seleccionaba el grupo. La consecuencia: quien recibía el primer mensaje
     * respondía, pero seguía figurando como pendiente en Firestore y en su propio teléfono,
     * así que el cartel de "te escribió" no se iba nunca y el chat quedaba a medias.
     */
    fun replyToRequest(groupId: String) = io {
        _selectedGroupId.value = groupId
        runCatching { community.replyWithoutFriending(groupId) }
    }

    /**
     * Si estoy respondiendo a un chat directo en el que todavía figuro como pendiente,
     * me saco de pendientes ANTES de mandar. Vale para texto, fotos, archivos y ubicación:
     * responder ES aceptar la conversación, aunque no se agregue a nadie como amigo.
     */
    private suspend fun abrirSiEstoyPendiente(groupId: String) {
        val g = groups.value.firstOrNull { it.id == groupId } ?: return
        val yo = myFingerprint.value ?: return
        if (g.direct && yo in g.pendingMembers) {
            runCatching { community.replyWithoutFriending(groupId) }
        }
    }

    /** Agregar como amigo. Si hay un chat pendiente, también lo desbloquea. */
    fun addFriend(fingerprint: String, username: String = "", groupId: String? = null) = io {
        try {
            community.addFriend(fingerprint, username, groupId)
            _openProfile.value = _openProfile.value?.copy(state = ContactState.CONTACTO)
            _message.emit("Ahora @" + username.ifBlank { "ese viajero" } + " es tu amigo. Ya pueden chatear.")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo agregar")
        }
    }

    /** Deja de ser contacto; el chat se queda. */
    fun removeFriend(fingerprint: String, username: String = "") = io {
        try {
            community.removeFriend(fingerprint)
            _openProfile.value = _openProfile.value?.copy(state = ContactState.CONOCIDO)
            _message.emit("@" + username.ifBlank { "ese viajero" } + " ya no es tu amigo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo quitar de amigos")
        }
    }

    /** Borra el chat o sale del grupo en este teléfono (misma lógica que leaveGroup). */
    fun deleteConversation(groupId: String) = leaveGroup(groupId)

    /** Rechazar (y bloquear si se pide). Lo rechazado desaparece de este teléfono. */
    fun rejectRequest(groupId: String, bloquear: Boolean = false) = io {
        try {
            community.rejectInvitation(groupId, bloquear)
            if (_selectedGroupId.value == groupId) _selectedGroupId.value = null
            _message.emit(if (bloquear) "Rechazado y bloqueado" else "Solicitud rechazada")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo rechazar")
        }
    }

    fun blockTraveler(fingerprint: String, username: String = "") = io {
        try {
            community.blockTraveler(fingerprint)
            _openProfile.value = _openProfile.value?.copy(state = ContactState.BLOQUEADO)
            _message.emit("Bloqueado" + if (username.isNotBlank()) ": @" + username else "")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo bloquear")
        }
    }

    fun unblockTraveler(fingerprint: String) = io {
        try {
            community.unblockTraveler(fingerprint)
            _openProfile.value = _openProfile.value?.copy(state = ContactState.CONOCIDO)
            _message.emit("Desbloqueado")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo desbloquear")
        }
    }

    fun muteGroup(groupId: String, silenciado: Boolean) = io {
        runCatching { community.muteGroup(groupId, silenciado) }
        _message.emit(if (silenciado) "Chat silenciado" else "Ya vuelve a avisarte")
    }

    fun setContactNote(fingerprint: String, nota: String) = io {
        runCatching { community.setContactNote(fingerprint, nota) }
    }

    /** Abre la ficha de otro viajero: lo que él publicó y lo que aprendí de sus mensajes. */
    fun showProfile(fingerprint: String, username: String = "") = io {
        _profileLoading.value = true
        _openProfile.value = TravelerCard(fingerprint = fingerprint, username = username)
        _openAlbum.value = emptyList()
        try {
            val card = community.travelerCard(fingerprint, username)
            val fotos = when {
                !card.canViewProfile -> emptyList()
                fingerprint == myFingerprint.value -> myAlbum.value
                else -> albumRepo.fetchRemote(community.firestoreOrNull(), fingerprint)
            }
            _openAlbum.value = fotos
            _openProfile.value = card.copy(album = fotos)
            // La foto grande se pide al puente, una a una; el sheet ya se ve con el metadato.
            fotos.take(12).forEach { f ->
                runCatching { albumRepo.ensureLocal(community.firestoreOrNull(), f) }
            }
            val actualizadas = if (fingerprint == myFingerprint.value) myAlbum.value
            else albumRepo.fetchRemote(null, fingerprint).ifEmpty { fotos }
            if (actualizadas.isNotEmpty()) {
                _openAlbum.value = actualizadas
                _openProfile.value = card.copy(album = actualizadas)
            }
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo cargar el perfil")
        } finally {
            _profileLoading.value = false
        }
    }

    fun closeProfile() {
        _openProfile.value = null
        _openAlbum.value = emptyList()
    }

    /** Meter a alguien en un grupo sabiendo solo su usuario. Nada de códigos. */
    fun inviteToGroup(groupId: String, username: String) = io {
        _busy.value = true
        try {
            val quien = community.inviteToGroup(groupId, username)
            _message.emit("Invitación enviada a @" + quien + ". Entrará cuando acepte.")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo invitar")
        } finally {
            _busy.value = false
        }
    }

    /** El enlace para mandar por WhatsApp. Quien lo toque entra directo. */
    fun inviteLink(group: TravelGroup): String = community.inviteLink(group)

    /**
     * Vuelve a montar un chat cuyos mensajes llegan con candado. Pasa cuando el otro rehízo su
     * cuenta: la llave con la que se armó el chat ya no es la suya.
     */
    fun repairChat(groupId: String) = io {
        _busy.value = true
        try {
            val g = community.repairDirectChat(groupId)
            _selectedGroupId.value = g.id
            _message.emit("Chat vuelto a crear con la llave buena. Escríbele otra vez.")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo reparar el chat")
        } finally {
            _busy.value = false
        }
    }

    // ---------------------------------------------------------------- chat

    fun sendMessage(text: String) = io {
        val gid = _selectedGroupId.value ?: return@io
        abrirSiEstoyPendiente(gid)
        try {
            community.sendMessage(gid, text)
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo enviar el mensaje")
        }
    }

    fun sendPhoto(uri: Uri, caption: String = "") = io {
        val gid = _selectedGroupId.value ?: return@io
        abrirSiEstoyPendiente(gid)
        _busy.value = true
        try {
            community.sendPhoto(gid, uri, caption)
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo enviar la foto")
        } finally {
            _busy.value = false
        }
    }

    /** Comparte la ubicación actual con el grupo abierto. */
    fun shareMyLocation() = io {
        val gid = _selectedGroupId.value ?: return@io
        val app = getApplication<Application>()
        if (!DeviceLocation.hasPermission(app)) {
            _message.emit("Concede el permiso de ubicación para compartirla")
            return@io
        }
        _busy.value = true
        try {
            val loc = DeviceLocation.current(app)
            if (loc == null) {
                _message.emit("No se pudo obtener tu ubicación. Activa el GPS e inténtalo de nuevo.")
                return@io
            }
            val label = runCatching { geocoding.reverse(loc.latitude, loc.longitude)?.displayName }
                .getOrNull()?.take(90) ?: ""
            community.sendLocation(gid, loc.latitude, loc.longitude, label)
            _message.emit("Ubicación compartida con el grupo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo compartir la ubicación")
        } finally {
            _busy.value = false
        }
    }

    /** Carga el lugar desde Room (allPlaces puede estar vacío si nadie lo está observando). */
    fun sharePlaceById(placeId: String, groupId: String? = _selectedGroupId.value) = io {
        // Ahora se entra a la app sin cuenta: compartir es lo primero que se topa con eso.
        if (accountState.value !is AccountState.SignedIn) {
            _message.emit("Crea tu cuenta en Viajeros para compartir con el grupo")
            return@io
        }
        val place = routeRepo.getPlace(placeId)
        if (place == null) {
            _message.emit("No se encontró el lugar para compartir")
            return@io
        }
        val gid = groupId ?: groups.value.firstOrNull()?.id
        if (gid == null) {
            _message.emit("Primero crea o abre un grupo")
            return@io
        }
        try {
            community.sendPlace(gid, place.name, JsonCodecPlace.encode(place))
            _message.emit("Lugar compartido con el grupo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo compartir el lugar")
        }
    }

    fun sharePlace(place: Place, groupId: String? = _selectedGroupId.value) = io {
        val gid = groupId ?: groups.value.firstOrNull()?.id
        if (gid == null) {
            _message.emit("Primero crea o abre un grupo")
            return@io
        }
        try {
            community.sendPlace(gid, place.name, JsonCodecPlace.encode(place))
            _message.emit("Lugar compartido con el grupo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo compartir el lugar")
        }
    }

    fun shareActiveRoute(groupId: String? = _selectedGroupId.value) = io {
        if (accountState.value !is AccountState.SignedIn) {
            _message.emit("Crea tu cuenta en Viajeros para compartir con el grupo")
            return@io
        }
        val gid = groupId ?: groups.value.firstOrNull()?.id
        if (gid == null) {
            _message.emit("Primero crea o abre un grupo")
            return@io
        }
        val json = routeRepo.exportActiveRouteAsJson()
        if (json == null) {
            _message.emit("No tienes una ruta activa")
            return@io
        }
        val name = routeRepo.activeRoute.first()?.name ?: "Ruta"
        try {
            community.sendRoute(gid, name, json)
            _message.emit("Ruta compartida con el grupo")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo compartir la ruta")
        }
    }

    /**
     * Manda un video, un audio, un PDF o cualquier archivo al chat abierto. El archivo viaja
     * cifrado y en trozos; el mensaje aparece al momento con su miniatura y la barrita de envío.
     */
    fun sendFile(uri: Uri) = io {
        val gid = _selectedGroupId.value ?: return@io
        abrirSiEstoyPendiente(gid)
        try {
            community.sendFile(gid, uri)
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo enviar el archivo")
        }
    }

    /** Baja un adjunto (si hace falta) y lo abre con la app que el viajero prefiera. */
    fun openAttachment(post: GroupPost) = io {
        try {
            val ficha = Attachments.Ficha.fromJson(post.payloadJson)
            val ruta = community.downloadAttachment(post)
            if (ruta == null) _message.emit("No se pudo abrir el archivo")
            else _abrirArchivo.emit(ruta to (ficha?.mime ?: "*/*"))
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo bajar el archivo")
        }
    }

    /**
     * Trae la foto del chat al teléfono sin abrirla fuera. Así se ve en la burbuja
     * sola, pidiendo los trozos al puente una sola vez.
     */
    fun prefetchChatImage(post: GroupPost) = io {
        val ficha = Attachments.Ficha.fromJson(post.payloadJson) ?: return@io
        if (!ficha.esImagen) return@io
        if (Attachments.existe(post.photoPath)) return@io
        runCatching { community.downloadAttachment(post) }
    }

    /** Pide la foto del álbum al puente y la deja en este teléfono. */
    fun ensureAlbumPhoto(foto: AlbumPhoto) = io {
        if (PhotoStore.exists(foto.photoPath)) return@io
        runCatching { albumRepo.ensureLocal(community.firestoreOrNull(), foto) }
    }

    /** El tope por archivo, para poder decirlo en la pantalla sin repetir el número. */
    val topeAdjuntoMb: Int get() = (Attachments.MAX_BYTES / (1024 * 1024)).toInt()

    // ---------------------------------------------------------------- diagnóstico

    /**
     * Revisa una por una las piezas de la comunidad (proyecto, accesos, reglas, permisos) y
     * enseña qué falla y cómo se arregla. Es la respuesta a "no se pudo crear la cuenta" sin
     * más explicación.
     */
    fun diagnosticar() = io {
        _diagnosticando.value = true
        try {
            _diagnostico.value = community.diagnosticar()
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo hacer el diagnóstico")
        } finally {
            _diagnosticando.value = false
        }
    }

    fun cerrarDiagnostico() { _diagnostico.value = null }

    // ---------------------------------------------------------------- compañeros

    fun refreshBuddies() = io {
        _busy.value = true
        try {
            val yo = myFingerprint.value
            val me = profile.value
            // Afines tampoco me incluyen a mí (vitrina propia o misma huella).
            _buddies.value = community.findBuddies().filter { !it.esYo(yo, me?.username, me?.uid) }
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudieron buscar compañeros")
        } finally {
            _busy.value = false
        }
    }

    /** Crea (o reabre) un chat directo de 2 personas con un compañero, sin código de invitación. */
    fun contactBuddy(uid: String, username: String) = io {
        _busy.value = true
        try {
            val g = community.startDirectChat(uid, username)
            _selectedGroupId.value = g.id
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo iniciar el chat")
        } finally {
            _busy.value = false
        }
    }

    /**
     * Escribirle en privado a alguien sabiendo solo su @usuario, sin códigos de invitación:
     * la app busca su carné, comprueba que la huella corresponda a su llave y abre el chat.
     */
    fun chatConUsuario(username: String) = io {
        _busy.value = true
        try {
            val g = community.startDirectChatWithUsername(username)
            _selectedGroupId.value = g.id
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo abrir el chat")
        } finally {
            _busy.value = false
        }
    }

    fun decodePlace(json: String): Place? = JsonCodecPlace.decode(json)

    /**
     * Publica una foto en mi álbum de viajes: lugar etiquetado y compañeros mencionados.
     */
    fun addAlbumPhoto(uri: Uri, caption: String, placeName: String, placeId: String, tagged: List<String>) =
        addAlbumPhotos(listOf(uri), caption, placeName, placeId, tagged)

    /** Publica una o varias fotos juntas (carrusel). */
    fun addAlbumPhotos(uris: List<Uri>, caption: String, placeName: String, placeId: String, tagged: List<String>) = io {
        val yo = myFingerprint.value ?: community.accountState.value.let { (it as? AccountState.SignedIn)?.identity?.fingerprint }
        if (yo.isNullOrBlank()) {
            _message.emit("Entra con tu cuenta para publicar fotos en tu perfil")
            return@io
        }
        _busy.value = true
        try {
            albumRepo.addPhotos(yo, uris, caption, placeName, placeId, tagged)
            albumRepo.pushPending(community.firestoreOrNull(), yo)
            _message.emit(if (uris.size > 1) "Publicación con ${uris.size} fotos" else "Foto publicada")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo publicar")
        } finally {
            _busy.value = false
        }
    }

    fun deleteAlbumPhoto(id: String) = io {
        try {
            albumRepo.deletePhoto(id, community.firestoreOrNull())
            _message.emit("Foto quitada del álbum")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo borrar")
        }
    }

    /** Borra el carrusel entero (todas las fotos de esa publicación). */
    fun deleteAlbumPost(setId: String) = io {
        try {
            albumRepo.deleteSet(setId, community.firestoreOrNull())
            _message.emit("Publicación quitada")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo borrar")
        }
    }

    // ---------------------------------------------------------------- reacciones y comentarios

    /**
     * Trae de Firestore los "me gusta" y comentarios de una publicación. Se llama cuando
     * la publicación aparece en pantalla, y una sola vez por sesión: así el feed no se
     * pone a gastar lecturas en cosas que nadie llegó a ver.
     */
    fun verSocialDe(post: AlbumPost) {
        if (post.setId.isBlank()) return
        if (!socialPedido.add(post.setId)) return
        io { interacciones.refrescarPost(community.firestoreOrNull(), post.ownerFingerprint, post.setId, myFingerprint.value) }
    }

    /**
     * Pone o quita mi reacción en una publicación. Si ya había puesto ESE mismo emoji, se
     * quita (segundo toque = ya no me gusta); si había puesto otro, se cambia.
     */
    fun reaccionar(post: AlbumPost, emoji: String = Reacciones.POR_DEFECTO) = io {
        val yo = myFingerprint.value
        if (yo.isNullOrBlank()) {
            _message.emit("Entra con tu cuenta para reaccionar")
            return@io
        }
        val actual = social.value[post.setId]?.miReaccion(yo)
        val destino = if (actual == emoji) null else emoji
        runCatching {
            interacciones.reaccionar(
                community.firestoreOrNull(),
                post.ownerFingerprint,
                post.setId,
                yo,
                profile.value?.username.orEmpty(),
                destino
            )
        }
    }

    /** Comenta una publicación. */
    fun comentar(post: AlbumPost, texto: String) = io {
        val yo = myFingerprint.value
        if (yo.isNullOrBlank()) {
            _message.emit("Entra con tu cuenta para comentar")
            return@io
        }
        try {
            interacciones.comentar(
                community.firestoreOrNull(),
                post.ownerFingerprint,
                post.setId,
                yo,
                profile.value?.username.orEmpty(),
                texto
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo comentar")
        }
    }

    /** Borra un comentario: lo puede hacer quien lo escribió o el dueño de la publicación. */
    fun borrarComentario(dueño: String, comentario: Comentario) = io {
        runCatching { interacciones.borrarComentario(community.firestoreOrNull(), dueño, comentario) }
    }

    /** ¿Puedo borrar este comentario? Sí si es mío, o si el sitio donde está es mío. */
    fun puedoBorrarComentario(dueño: String, comentario: Comentario): Boolean {
        val yo = myFingerprint.value.orEmpty()
        if (yo.isBlank()) return false
        return comentario.quien == yo || dueño == yo
    }

    /** Borra MI publicación entera (fotos, reacciones y comentarios). */
    fun borrarPublicacion(post: AlbumPost) = io {
        val yo = myFingerprint.value
        if (yo.isNullOrBlank() || post.ownerFingerprint != yo) {
            _message.emit("Solo puedes borrar tus propias publicaciones")
            return@io
        }
        try {
            val fs = community.firestoreOrNull()
            interacciones.borrarTodoDe(fs, yo, post.setId)
            albumRepo.deleteSet(post.setId, fs)
            socialPedido.remove(post.setId)
            _message.emit("Publicación borrada")
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo borrar la publicación")
        }
    }

    // ---------------------------------------------------------------- muro del perfil

    /** Abre el muro de un perfil (el mío o el de un amigo) y lo pone al día. */
    fun abrirMuro(huella: String) {
        if (huella.isBlank()) return
        _muroDe.value = huella
        io { interacciones.refrescarMuro(community.firestoreOrNull(), huella, myFingerprint.value) }
    }

    fun cerrarMuro() { _muroDe.value = "" }

    /** Pone al día MI muro (al entrar en la pestaña "Yo"). */
    fun refrescarMiMuro() = io {
        val yo = myFingerprint.value ?: return@io
        runCatching { interacciones.refrescarMuro(community.firestoreOrNull(), yo, yo) }
    }

    /** Deja un mensaje en el muro de alguien. */
    fun escribirEnMuro(huella: String, texto: String) = io {
        val yo = myFingerprint.value
        if (yo.isNullOrBlank()) {
            _message.emit("Entra con tu cuenta para escribir en su muro")
            return@io
        }
        try {
            interacciones.escribirEnMuro(
                community.firestoreOrNull(),
                huella,
                yo,
                profile.value?.username.orEmpty(),
                texto
            )
        } catch (e: Exception) {
            _message.emit(e.message ?: "No se pudo publicar")
        }
    }

    /** Baja las fotos de amigos (y las mías pendientes) para el feed. */
    fun refreshFeed() = io {
        val yo = myFingerprint.value ?: return@io
        val fs = community.firestoreOrNull()
        albumRepo.pushPending(fs, yo)
        albumRepo.publishBinsForMine(fs, yo)
        // Comentarios que se escribieron sin internet: salen ahora.
        runCatching { interacciones.subirPendientes(fs) }
        contacts.value.filter { it.state == ContactState.CONTACTO }.take(30).forEach { amigo ->
            runCatching { albumRepo.fetchRemote(fs, amigo.fingerprint) }
        }
        // Y se adelantan las fotos nuevas para que no se vean borrosas al llegar a ellas.
        runCatching { albumRepo.prefetch(fs, feedPosts.value.flatMap { it.photos }, tope = 12) }
        // Mis publicaciones se miran SIEMPRE, hayan pasado por pantalla o no: es de donde
        // salen los "me gusta" y los comentarios que hay que avisarme. Solo las 10 más
        // nuevas, que es donde ocurre todo.
        feedPosts.value.asSequence()
            .filter { it.ownerFingerprint == yo }
            .sortedByDescending { it.createdAt }
            .take(10)
            .forEach { mia ->
                socialPedido.add(mia.setId)
                runCatching { interacciones.refrescarPost(fs, yo, mia.setId, yo) }
            }
        runCatching { interacciones.refrescarMuro(fs, yo, yo) }
    }

    /** ¿Esta persona ya es mi amigo? */
    fun esAmigo(fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank()) return false
        return contacts.value.any { it.fingerprint == fingerprint && it.state == ContactState.CONTACTO }
    }

    private inline fun io(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

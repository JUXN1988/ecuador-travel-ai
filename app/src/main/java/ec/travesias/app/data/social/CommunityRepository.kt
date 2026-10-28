package ec.travesias.app.data.social

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.JsonCodec
import ec.travesias.app.data.local.toBuddyEntity
import ec.travesias.app.data.local.toDomain
import ec.travesias.app.data.local.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Comunidad de viajeros — **primero el teléfono**.
 *
 * Todo (perfil, grupos, mensajes, fotos y ubicaciones) se guarda en la base de datos
 * local del dispositivo. Firebase actúa únicamente como *conector*: replica esas mismas
 * filas para que los demás miembros del grupo las vean y para traer las suyas.
 * Si no hay internet la app sigue funcionando y lo pendiente se envía al reconectar.
 *
 * Las fotos grandes no viven en Firebase: el álbum y el chat las piden a trozos
 * cuando alguien las quiere ver, y quedan en el teléfono de quien las bajó.
 */
class CommunityRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope
) {

    private val profileDao = db.myProfileDao()
    private val groupDao = db.socialGroupDao()
    private val postDao = db.socialPostDao()
    private val buddyDao = db.socialBuddyDao()
    private val commentDao = db.placeCommentDao()
    private val identityDao = db.knownIdentityDao()

    /**
     * La cuenta del viajero: su par de llaves. La contraseña no viaja a ningún lado y Firebase
     * solo guarda un sobre cerrado con la llave privada dentro.
     */
    val account = AccountService(context)
    val accountState: StateFlow<AccountState> = account.state

    /** Catálogo común de lugares. Lo pone AppContainer al arrancar. */
    var catalog: CatalogSync? = null

    /** Álbum de fotos del perfil. Lo pone AppContainer al arrancar. */
    var albumRepo: AlbumRepository? = null

    /** Llaves públicas que ya conocemos, por nombre de usuario. Se llena con los mensajes que llegan. */
    private val conocidos = ConcurrentHashMap<String, String>()

    /** Conexión a Firestore, para el catálogo común. null si aún no hay sesión. */
    fun firestoreOrNull(): FirebaseFirestore? = firestore

    /** Mi identificador dentro de los grupos: la huella de mi llave (o el uid anónimo si aún no hay cuenta). */
    private fun myId(): String? = account.identity?.fingerprint ?: authUid

    private val _sync = MutableStateFlow(SyncStatus(SyncState.CONNECTING))
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()

    /** Mi perfil, leído siempre del teléfono. */
    val profile: Flow<TravelerProfile?> = profileDao.observe().map { it?.toDomain() }

    /** Mis grupos, leídos siempre del teléfono. */
    val groups: Flow<List<TravelGroup>> = groupDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Mensajes de un grupo, leídos siempre del teléfono. */
    fun posts(groupId: String): Flow<List<GroupPost>> =
        postDao.observeForGroup(groupId).map { list -> list.map { it.toDomain() } }

    private var firestore: FirebaseFirestore? = null
    private var authUid: String? = null

    /**
     * Cómo entró la app a Firebase la última vez. Guarda el motivo REAL cuando algo falla, que
     * es lo que antes se perdía por el camino y dejaba al viajero con un mensaje genérico.
     */
    @Volatile
    var transporte: TransportAuth.Sesion? = null
        private set

    /** true si la sesión actual es de su propia cuenta (la única que las reglas reconocen). */
    val conCuentaPropia: Boolean get() = transporte?.conCuenta == true
    private var groupsListener: ListenerRegistration? = null
    /** Un solo hilo toca los mapas de listeners: Firestore y las corrutinas IO se pisan si no. */
    private val listenerLock = Any()
    private val postListeners = HashMap<String, ListenerRegistration>()
    private val commentListeners = HashMap<String, ListenerRegistration>()
    /** Evita dos connect() a la vez (arrancar + Reintentar + crear cuenta). */
    private val connectMutex = Mutex()
    private val groupPromoter = LocalGroupPromoter(groupDao, postDao, profileDao)

    // ======================================================================
    //  Conector de sincronización
    // ======================================================================

    /** Se llama una vez al arrancar la app. Nunca lanza excepciones. */
    fun start() {
        // La sesión de este teléfono se restaura ya, sin esperar a Firebase.
        account.restoreLocal()
        scope.launch { runCatching { connect() } }
    }

    /** Reintento manual desde la pantalla de comunidad. */
    fun retrySync() {
        scope.launch { runCatching { connect() } }
    }

    /** Serializa las reconexiones: un connect() a la vez. */
    private suspend fun connect() {
        if (!connectMutex.tryLock()) return
        try {
            connectBody()
        } finally {
            connectMutex.unlock()
        }
    }

    private suspend fun connectBody() {
        _sync.value = SyncStatus(SyncState.CONNECTING)
        // La cuenta vive en el teléfono: se restaura aunque no haya señal.
        account.restoreLocal()
        runCatching { cargarConocidos() }
        runCatching { refrescarBloqueados() }

        val ready = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
            FirebaseApp.getApps(context).isNotEmpty()
        }.getOrDefault(false)
        if (!ready) {
            _sync.value = SyncStatus(SyncState.LOCAL_ONLY, "Falta el archivo google-services.json en la app.")
            return
        }

        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) {
            _sync.value = SyncStatus(SyncState.ERROR, "No se pudo inicializar el acceso a la comunidad.")
            return
        }

        // Con cuenta propia entramos a Firebase COMO ESA CUENTA (correo interno derivado de
        // la llave). Así el identificador es el mismo en todos sus teléfonos y las reglas de
        // Firestore pueden comprobar que cada quien solo toca lo suyo. Sin cuenta, anónimo.
        val identidad = account.identity
        val sesion = runCatching {
            if (identidad != null) TransportAuth.entrar(auth, identidad) else TransportAuth.invitado(auth)
        }.getOrElse { TransportAuth.Sesion(null, false, TransportAuth.clasificar(it), it.message.orEmpty()) }
        transporte = sesion

        // La base de datos se abre igual: aunque la sesión venga a medias, lo local sigue vivo
        // y el catálogo (que las reglas permiten a cualquiera con sesión) se puede leer.
        firestore = runCatching { FirebaseFirestore.getInstance() }.getOrNull()

        if (!sesion.entro) {
            _sync.value = SyncStatus(SyncState.ERROR, TransportAuth.explicar(sesion.fallo))
            return
        }
        if (firestore == null) {
            _sync.value = SyncStatus(SyncState.ERROR, "No se pudo abrir la base de datos de la comunidad.")
            return
        }
        val uid = sesion.uid.orEmpty()
        authUid = uid

        // Entró, pero NO como su propia cuenta: se puede leer, no escribir a su nombre. Se dice
        // exactamente qué falta en vez de dejar al viajero adivinando.
        _sync.value = if (identidad != null && !sesion.conCuenta)
            SyncStatus(SyncState.ERROR, TransportAuth.explicar(sesion.fallo))
        else SyncStatus(SyncState.ONLINE)

        // Cuenta creada cuando Firebase no dejaba escribir: ahora sí se puede publicar, y sin
        // volver a pedir la contraseña (el sobre ya estaba cerrado desde el primer día).
        if (sesion.conCuenta && account.hasPending) {
            runCatching { if (account.publishPending(firestore)) _sync.value = SyncStatus(SyncState.ONLINE) }
        }

        // La vitrina se publica AL CONECTAR, no solo al editar el perfil.
        //
        // Por qué hace falta: quien se registró mientras el acceso por Correo/contraseña
        // estaba apagado entró como invitado, la escritura de la vitrina falló en silencio
        // (va dentro de un runCatching) y NUNCA se reintentaba, porque pushProfile() solo
        // corría al tocar el perfil, la foto o la privacidad. Resultado: cuentas de verdad
        // que no existían para nadie más. Con esto se arreglan solas al abrir la app.
        //
        // De paso refresca `vistoEn`, que es por donde se ordenan los "viajeros recientes":
        // así la lista enseña a quien de verdad anda usando la app. Es UNA escritura por
        // arranque, muy por debajo del plan gratuito.
        if (sesion.conCuenta) {
            scope.launch { runCatching { pushProfile() } }
        }

        // Con cuenta propia mi identificador es la huella de mi llave, no el uid anónimo.
        if (account.identity == null) runCatching { adoptRemoteUid(uid) }
        else runCatching { restoreGroupsFromVault() }
        // Grupos creados sin red: pasan de local-* a un id de Firestore y luego se suben.
        val fsOk = firestore
        if (fsOk != null) {
            runCatching {
                groupPromoter.promover(fsOk).forEach { g ->
                    runCatching { pushGroup(g) }
                    listenPosts(g.id)
                }
            }
        }
        runCatching { pushPending() }
        runCatching { purgeMailbox() }
        // El catálogo de lugares viaja aparte: lo que descubrió cualquiera llega a todos.
        scope.launch { runCatching { catalog?.sync(firestore) } }
        attachListeners()
    }


    /**
     * Un fallo al escuchar sale a la vista en el banner de sincronización, con el arreglo
     * concreto. Un error que nadie ve es un error que nadie arregla.
     */
    private fun reportarFallo(que: String, e: Exception) {
        val detalle = friendlyError(e)
        _sync.value = SyncStatus(SyncState.ERROR, "No se pudieron recibir $que. $detalle")
    }

    /** Traduce los errores de Firebase a algo que un usuario entienda. */
    private fun friendlyError(e: Exception): String {
        val msg = (e.message ?: "").uppercase()
        return when {
            msg.contains("CONFIGURATION_NOT_FOUND") || msg.contains("OPERATION_NOT_ALLOWED") ->
                "Falta activar en la consola de Firebase (Authentication → Método de acceso) el acceso Anónimo y el de Correo/contraseña. Los dos son gratis."
            msg.contains("PERMISSION_DENIED") ->
                "Las reglas de Firestore no permiten esta operación. Revisa las reglas del proyecto."
            msg.contains("NETWORK") || msg.contains("UNAVAILABLE") || msg.contains("TIMEOUT") ->
                "Sin conexión: todo se guarda en el teléfono y se enviará al reconectar."
            else -> "No se pudo conectar con la comunidad. Se sigue guardando en este teléfono."
        }
    }

    /**
     * Si el perfil se creó sin conexión llevaba un identificador local.
     * Al conectar por primera vez se adopta el de Firebase y se reescriben los datos locales.
     */
    private suspend fun adoptRemoteUid(uid: String) {
        val local = profileDao.get() ?: return
        if (local.uid == uid) return
        val old = local.uid
        profileDao.updateUid(uid)
        postDao.remapAuthor(old, uid)
        groupDao.getAll().forEach { g ->
            val members = JsonCodec.parseStringList(g.memberUidsJson)
            if (members.contains(old)) {
                groupDao.upsert(
                    g.copy(
                        memberUidsJson = JsonCodec.stringList(members.map { if (it == old) uid else it }),
                        ownerUid = if (g.ownerUid == old) uid else g.ownerUid,
                        pendingSync = true
                    )
                )
            }
        }
    }

    /** Sube lo que quedó pendiente mientras no había conexión. */
    private suspend fun pushPending() {
        pushProfile()
        groupDao.getPending().forEach { g -> runCatching { pushGroup(g.toDomain()) } }
        postDao.getPending().forEach { p -> runCatching { pushPost(p.toDomain()) } }
        commentDao.getPending().forEach { c -> runCatching { pushComment(c.toDomain()) } }
        val yoAlbum = myId()
        if (yoAlbum != null) {
            runCatching { albumRepo?.pushPending(firestore, yoAlbum) }
            runCatching { pushPrivacy() }
        }
    }

    private fun attachListeners() {
        val fs = firestore ?: return
        val uid = myId() ?: return
        groupsListener?.remove()
        groupsListener = fs.collection("groups")
            .whereArrayContains("memberUids", uid)
            .addSnapshotListener { snap, error ->
                // ANTES: `if (error != null || snap == null) return`. Si las reglas denegaban la
                // consulta (pasa cuando Firebase entra como invitado porque falta activar
                // Correo/contraseña), los mensajes NO llegaban y la app no decía ni una palabra.
                // Ese silencio es lo que hacía imposible saber por qué "no funciona la comunidad".
                if (error != null) { reportarFallo("los grupos", error); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documents
                scope.launch {
                    docs.forEach { doc ->
                        val local = groupDao.getById(doc.id)
                        // La llave del grupo sale de aquí: del teléfono, o del sobre que me
                        // dejaron a mí en un chat directo. Firebase nunca la ve.
                        val llave = local?.secretKey?.takeIf { it.isNotBlank() }?.let { CryptoBox.b64d(it) }
                            ?: llaveDeChatDirecto(doc)
                        val g = readGroup(doc, llave) ?: return@forEach
                        // Un bloqueado no puede volver a aparecer, ni metiéndome a un grupo.
                        if (g.invitedBy.isNotBlank() && g.invitedBy in bloqueados) return@forEach
                        val pendLocal = JsonCodec.parseStringList(local?.pendingJson ?: "[]")
                        val pendRemoto = g.pendingMembers
                        // En un chat de dos: si en este teléfono ya se salió de pendientes
                        // (respondió o agregó amigo), un snapshot viejo NO lo vuelve a meter.
                        // Así el otro puede escribir de vuelta sin quedarse trabado.
                        val pend = if (g.direct && local != null) {
                            pendRemoto.filter { it in pendLocal }
                        } else pendRemoto
                        groupDao.upsert(
                            g.toEntity().copy(
                                name = if (g.name.isNotBlank()) g.name else (local?.name ?: "Grupo"),
                                lastMessageAt = local?.lastMessageAt ?: g.createdAt,
                                lastMessagePreview = local?.lastMessagePreview ?: "",
                                secretKey = llave?.let { CryptoBox.b64(it) } ?: local?.secretKey.orEmpty(),
                                encrypted = llave != null,
                                pendingJson = JsonCodec.stringList(pend),
                                // Si aún no se subió, el snapshot no debe borrar el pendiente.
                                pendingSync = local?.pendingSync == true,
                                muted = local?.muted == true
                            )
                        )
                        listenPosts(g.id)
                    }
                    // Grupos de los que ya no formo parte: se suelta el listener, no la fila local.
                    val vivos = docs.map { it.id }.toSet()
                    synchronized(listenerLock) {
                        postListeners.keys.filter { it !in vivos }.toList().forEach { id ->
                            postListeners.remove(id)?.remove()
                        }
                    }
                    runCatching { saveVault() }
                }
            }
    }

    /**
     * En un chat directo no hay código que compartir: el que invita deja la llave del chat
     * cifrada CON LA LLAVE PÚBLICA del invitado (ECDH). Solo él puede abrirla, ni siquiera
     * Firebase, que solo ve un montón de bytes.
     */
    private fun llaveDeChatDirecto(doc: DocumentSnapshot): ByteArray? {
        val yo = account.identity ?: return null
        @Suppress("UNCHECKED_CAST")
        val sobres = doc.get("dk") as? Map<String, Any?> ?: return null
        val mio = sobres[yo.fingerprint] ?: return null

        // Formato nuevo: cada sobre lleva DENTRO la llave pública de quien invitó.
        // Antes se usaba el campo `ok` del grupo, que es el de quien subió el documento por
        // última vez: en cuanto invitaba alguien que no fuera el creador, el invitado calculaba
        // el secreto contra la llave equivocada y no podía abrir NADA.
        val (suPublica, sellado) = when (mio) {
            is Map<*, *> -> (mio["k"] as? String).orEmpty() to (mio["s"] as? String).orEmpty()
            is String -> doc.getString("ok").orEmpty() to mio
            else -> return null
        }
        if (suPublica.isBlank() || sellado.isBlank()) return null
        val compartida = CryptoBox.sharedKey(CryptoBox.privateFrom(yo.privateKeyBytes), suPublica) ?: return null
        return CryptoBox.openText(compartida, sellado, doc.id)?.let { CryptoBox.b64d(it) }
    }

    /** El sobre que se le deja a alguien con la llave de un grupo, cifrado solo para él. */
    private fun sobreParaInvitado(
        yo: TravelerIdentity, suPublica: String, groupId: String, llaveGrupoB64: String
    ): Map<String, String>? {
        val compartida = CryptoBox.sharedKey(CryptoBox.privateFrom(yo.privateKeyBytes), suPublica) ?: return null
        return mapOf("k" to yo.publicKeyB64, "s" to CryptoBox.sealText(compartida, llaveGrupoB64, groupId))
    }

    private fun listenPosts(groupId: String) {
        val fs = firestore ?: return
        if (groupId.startsWith(LOCAL_ID_PREFIX)) return
        synchronized(listenerLock) {
            if (postListeners.containsKey(groupId)) return
        }
        // Buzón: solo se traen los mensajes recientes. Lo anterior ya está en el teléfono y
        // no hace falta volver a leerlo de Firebase (menos datos y menos cuota gastada).
        val desde = System.currentTimeMillis() - SecureEnvelope.MAILBOX_MILLIS
        val registro = fs.collection("groups").document(groupId).collection("posts")
            .whereGreaterThan("createdAt", desde)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snap, error ->
                // Mismo caso que arriba: un fallo aquí es "escribo y no le llega a nadie".
                if (error != null) { reportarFallo("los mensajes", error); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                val documentos = snap.documents
                // Mensajes de OTROS que de verdad acaban de llegar del servidor ahora mismo:
                // ni historial viejo (primera carga del listener) ni el eco de lo que yo mismo escribí.
                val ahora = System.currentTimeMillis()
                val recienLlegados = snap.documentChanges.filter { cambio ->
                    cambio.type == com.google.firebase.firestore.DocumentChange.Type.ADDED &&
                        !cambio.document.metadata.hasPendingWrites() &&
                        (ahora - (cambio.document.getLong("createdAt") ?: 0L)) < 3 * 60_000L
                }.map { it.document }

                scope.launch {
                    val llave = groupKeyOf(groupId)
                    val abiertos = documentos.mapNotNull { abrirPost(it, groupId, llave) }
                    if (abiertos.isEmpty()) return@launch

                    // De cada mensaje aprendemos el carné de quien lo escribió: así la app
                    // conoce a la gente por sus propios mensajes y no por un directorio.
                    abiertos.forEach { o ->
                        o.card?.let { runCatching { aprender(it) } }
                        // Si la firma no cuadra, queda avisado para siempre en su ficha.
                        if (o.trust == PostTrust.SUSPICIOUS && o.post.authorUid.isNotBlank()) {
                            runCatching { identityDao.markSuspicious(o.post.authorUid) }
                        }
                    }

                    val incoming = abiertos.map { it.post }.filter { it.authorUid !in bloqueados }
                    val yo = myId()
                    incoming.forEach { post ->
                        // Conservar la foto original que ya tuviéramos guardada aquí.
                        val existing = postDao.getById(post.id)
                        val keepPath = existing?.photoPath ?: ""
                        val docRemoto = documentos.firstOrNull { it.id == post.id }
                        // Si yo lo mandé y Firestore aún lo tiene como escritura pendiente, no
                        // se marca sincronizado: si no, se pierde el reintento.
                        val siguePendiente = existing?.pendingSync == true &&
                            post.authorUid == yo &&
                            docRemoto?.metadata?.hasPendingWrites() == true
                        postDao.upsert(post.toEntity().copy(photoPath = keepPath, pendingSync = siguePendiente))
                    }
                    val last = incoming.maxByOrNull { it.createdAt }
                    if (last != null) groupDao.touch(groupId, last.createdAt, previewOf(last))

                    val grupoLocal = groupDao.getById(groupId)?.toDomain()
                    // Si el otro ya contestó, el chat deja de ser "solicitud" en este teléfono
                    // aunque Firebase tarde un segundo en borrar `pend`. Así el que escribió
                    // primero puede responder de inmediato.
                    if (grupoLocal != null && grupoLocal.direct && yo != null) {
                        val queRespondieron = incoming
                            .map { it.authorUid }
                            .filter { it.isNotBlank() && it != yo }
                            .toSet()
                        if (queRespondieron.isNotEmpty() && grupoLocal.pendingMembers.any { it in queRespondieron }) {
                            groupDao.upsert(
                                grupoLocal.copy(
                                    pendingMembers = grupoLocal.pendingMembers.filter { it !in queRespondieron }
                                ).toEntity()
                            )
                        }
                    }
                    val nuevosDeOtros = recienLlegados
                        .mapNotNull { abrirPost(it, groupId, llave)?.post }
                        .filter { it.authorUid != yo && it.authorUid !in bloqueados }
                    // Un chat silenciado se sigue recibiendo, pero no suena.
                    if (nuevosDeOtros.isNotEmpty() && grupoLocal?.muted != true) {
                        val nombreGrupo = grupoLocal?.name ?: "Tu grupo"
                        nuevosDeOtros.forEach { post ->
                            ec.travesias.app.data.notify.NotificationHelper.notifyMessage(
                                context, groupId, nombreGrupo, post.authorName.ifBlank { "Un compañero" }, previewOf(post)
                            )
                        }
                    }
                }
            }
        synchronized(listenerLock) {
            if (postListeners.containsKey(groupId)) registro.remove()
            else postListeners[groupId] = registro
        }
    }

    // ======================================================================
    //  Abrir, verificar y aprender
    // ======================================================================

    /** Cómo me ven en el grupo: mi nombre visible, o el usuario si no puse ninguno. */
    private fun nombreVisible(perfil: TravelerProfile?, yo: TravelerIdentity): String =
        perfil?.displayName.orEmpty().ifBlank { perfil?.username.orEmpty() }.ifBlank { yo.username }

    /** Llave del grupo guardada en este teléfono (null si es un grupo antiguo sin cifrar). */
    private suspend fun groupKeyOf(groupId: String): ByteArray? =
        groupDao.getById(groupId)?.secretKey?.takeIf { it.isNotBlank() }?.let { CryptoBox.b64d(it) }

    /** Descifra el mensaje y comprueba su firma. Devuelve null si no se puede leer. */
    private fun abrirPost(doc: DocumentSnapshot, groupId: String, llave: ByteArray?): OpenedPost? {
        val datos = doc.data ?: return null
        return SecureEnvelope.open(doc.id, groupId, datos, llave) { usuario -> conocidos[usuario] }
    }

    /** Guarda la llave pública de alguien la primera vez que lo vemos escribir. */
    private suspend fun aprender(card: IdentityCard) {
        if (card.fingerprint.isBlank() || card.publicKeyB64.isBlank()) return
        // Mis propios mensajes también llegan por el listener: no me guardo como contacto.
        val yoFp = myId()
        if (!yoFp.isNullOrBlank() && card.fingerprint == yoFp) return
        val miUsuario = profileDao.get()?.username
        if (!miUsuario.isNullOrBlank() && card.username.equals(miUsuario, ignoreCase = true)) return
        val ahora = System.currentTimeMillis()
        val previo = identityDao.get(card.fingerprint)
        identityDao.upsert(
            ec.travesias.app.data.local.KnownIdentityEntity(
                fingerprint = card.fingerprint,
                username = card.username,
                publicKey = card.publicKeyB64,
                displayName = card.displayName,
                thumb = card.thumb.ifBlank { previo?.thumb.orEmpty() },
                firstSeenAt = previo?.firstSeenAt ?: ahora,
                lastSeenAt = ahora,
                // Lo que ya se sabía de él no se pisa: si era contacto sigue siéndolo, y si
                // estaba bloqueado NO se desbloquea solo porque haya vuelto a escribir.
                state = previo?.state ?: ContactState.CONOCIDO.name,
                messagesSeen = (previo?.messagesSeen ?: 0) + 1,
                note = previo?.note.orEmpty(),
                suspicious = previo?.suspicious ?: false
            )
        )
        if (card.username.isNotBlank()) conocidos.putIfAbsent(card.username, card.fingerprint)
    }

    private suspend fun cargarConocidos() {
        identityDao.recent(300).forEach { e ->
            if (e.username.isNotBlank()) conocidos.putIfAbsent(e.username, e.fingerprint)
        }
    }

    // ======================================================================
    //  Perfil
    // ======================================================================

    suspend fun registerUsername(rawUsername: String, interests: List<String>, region: String): TravelerProfile {
        val username = normalizeUsername(rawUsername)
        require(username.length in 3..20) { "El nombre de usuario debe tener entre 3 y 20 letras o números." }

        val fs = firestore
        val yoFp = account.identity?.fingerprint
        val uid = yoFp ?: authUid ?: (TravelerProfile.LOCAL_UID_PREFIX + UUID.randomUUID().toString().take(12))

        if (fs != null) {
            val taken = withTimeoutOrNull(12_000L) {
                runCatching { fs.collection(AccountService.COL_IDENTIDADES).document(username).get().await() }.getOrNull()
            }
            if (taken != null && taken.exists() && taken.getString("fp") != yoFp) {
                throw IllegalStateException("Ese nombre de usuario ya está en uso. Prueba con otro.")
            }
        }

        val profile = TravelerProfile(
            uid = uid,
            username = username,
            displayName = rawUsername.trim(),
            interests = interests,
            region = region,
            createdAt = System.currentTimeMillis(),
            // Una red de viajeros donde nadie se ve no es una red. La cuenta nace VISIBLE:
            // usuario, foto, gustos y región. Nada de mensajes, rutas ni álbum privado.
            // Se apaga en un toque desde el perfil (Privacidad → Solo amigos / Privado).
            visibility = ProfileVisibility.PUBLIC,
            showcase = true
        )
        profileDao.upsert(profile.toEntity())
        scope.launch { runCatching { pushProfile() } }
        return profile
    }

    // ======================================================================
    //  Administración
    // ======================================================================
    //  Solo mira y borra fichas PÚBLICAS. No descifra nada: los mensajes, las fotos y los
    //  cofres van con llaves que un administrador no tiene y nunca va a tener.

    private val admin = AdminService { account.identity }

    /** Mi huella, que es lo que hay que poner en la colección `admins` de la consola. */
    fun miHuella(): String = account.identity?.fingerprint.orEmpty()

    suspend fun soyAdmin(): Boolean = admin.soyAdmin(firestore)

    suspend fun cuentasHuerfanas(dias: Int = 30): List<AdminService.Candidata> =
        admin.buscarHuerfanas(firestore, dias)

    /** Borra una cuenta huérfana. Devuelve lo que no se pudo borrar (vacío si fue bien). */
    suspend fun borrarCuentaHuerfana(c: AdminService.Candidata): List<String> {
        val fallos = admin.borrarCuenta(firestore, c)
        // La anotación del correo se va con la cuenta; si no, apuntaría a un usuario que
        // ya no existe y nadie más podría usar ese correo.
        runCatching {
            val fs = firestore
            if (fs != null) fs.collection(AccountService.COL_POR_CORREO)
                .whereEqualTo("fp", c.huella).limit(5).get().await()
                .documents.forEach { it.reference.delete().await() }
        }
        return fallos
    }

    /**
     * Busca el @usuario a partir del correo de recuperación.
     * Nunca devuelve la huella ni la llave: solo el nombre, que es público de todos modos.
     */
    suspend fun usuarioPorCorreo(correo: String): String? =
        account.usuarioPorCorreo(firestore, correo)

    /**
     * Pone (o quita) el correo de recuperación. Del correo solo se publica su HUELLA,
     * para que quien lo sepa pueda recordar su usuario y nadie pueda sacar direcciones.
     */
    suspend fun setRecoveryEmail(correo: String) {
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Todavía no hay perfil.")
        val yo = account.identity ?: throw IllegalStateException("Entra con tu cuenta primero.")
        val limpio = correo.trim().lowercase()
        if (limpio.isNotBlank() && !AccountService.pareceCorreo(limpio)) {
            throw IllegalStateException("Ese correo no parece válido.")
        }
        // Si había otro apuntado, se borra su anotación: si no, quedaría señalando a esta
        // cuenta para siempre y otro viajero no podría usar ese correo.
        val anterior = me.recoveryEmail
        if (anterior.isNotBlank() && anterior != limpio) {
            runCatching { account.olvidarCorreo(firestore, anterior) }
        }
        profileDao.upsert(me.copy(recoveryEmail = limpio, recoveryVerified = false).toEntity())
        if (limpio.isNotBlank()) {
            val ok = account.publicarCorreo(firestore, yo, limpio)
            if (!ok) throw IllegalStateException("Se guardó aquí, pero no se pudo publicar. Se reintentará al reconectar.")
        }
    }

    suspend fun updateProfile(updated: TravelerProfile) {
        profileDao.upsert(updated.toEntity())
        scope.launch { runCatching { pushProfile() } }
    }

    /** Guarda la foto de perfil en el teléfono y sincroniza solo la miniatura. */
    suspend fun setAvatar(uri: Uri): Boolean {
        val current = profileDao.get()?.toDomain() ?: return false
        val saved = PhotoStore.save(context, uri, "avatar") ?: return false
        profileDao.upsert(current.copy(avatarPath = saved.localPath, avatarThumb = saved.thumbBase64).toEntity())
        scope.launch { runCatching { pushProfile() } }
        return true
    }

    /**
     * La VITRINA: lo mínimo para encontrarte (usuario, región, mes, etiquetas, llave)
     * y una foto de perfil MINÚSCULA. La biografía y el álbum no van aquí: el álbum se
     * pide al dueño por el puente cuando alguien abre el perfil.
     * Si apaga la vitrina, el documento se BORRA de Firebase.
     */
    private suspend fun pushProfile() {
        val fs = firestore ?: return
        val yo = account.identity ?: return
        val me = profileDao.get()?.toDomain() ?: return
        val doc = fs.collection("showcase").document(yo.fingerprint)
        withTimeoutOrNull(20_000L) {
            runCatching {
                if (me.visibility != ProfileVisibility.PUBLIC) doc.delete().await()
                else doc.set(showcaseMap(me, yo)).await()
            }
        }
        runCatching { pushPrivacy() }
    }

    private fun showcaseMap(p: TravelerProfile, yo: TravelerIdentity): Map<String, Any> {
        val cuerpo = yo.fingerprint + "|" + p.username + "|" + p.region + "|" + p.travelMonth
        return mapOf(
            "fp" to yo.fingerprint,
            "username" to p.username,
            "displayName" to p.displayName,
            "bio" to p.bio.take(300),
            "k" to yo.publicKeyB64,
            "region" to p.region,
            "routeOrigin" to p.routeOrigin,
            "routeDestination" to p.routeDestination,
            "travelMonth" to p.travelMonth,
            "travelTags" to p.travelTags,
            "interests" to p.interests.take(10),
            "createdAt" to p.createdAt,
            // Se ordena por este campo al listar viajeros recientes: si faltara, Firestore
            // dejaría el documento FUERA de la consulta y el viajero sería invisible.
            "vistoEn" to System.currentTimeMillis(),
            "sig" to yo.signText(cuerpo),
            "av" to PhotoStore.shrinkThumb(p.avatarThumb)
        )
    }

    /** Enciende o apaga la vitrina. Apagarla deja el perfil en solo amigos. */
    suspend fun setShowcase(enabled: Boolean) {
        setVisibility(if (enabled) ProfileVisibility.PUBLIC else ProfileVisibility.FRIENDS)
    }

    /** Cambia quién puede ver el perfil y el álbum: público, solo amigos o privado. */
    suspend fun setVisibility(vis: ProfileVisibility) {
        val me = profileDao.get()?.toDomain() ?: return
        profileDao.upsert(
            me.copy(
                visibility = vis,
                showcase = vis == ProfileVisibility.PUBLIC
            ).toEntity()
        )
        runCatching { pushProfile() }
    }

    /** Ficha de privacidad en Firebase: visibilidad + lista de amigos (para las reglas del álbum). */
    private suspend fun pushPrivacy() {
        val fs = firestore ?: return
        val yo = account.identity ?: return
        val me = profileDao.get()?.toDomain() ?: return
        val amigos = identityDao.recent(300)
            .filter { it.state == ContactState.CONTACTO.name }
            .map { it.fingerprint }
            .filter { it.isNotBlank() }
        withTimeoutOrNull(15_000L) {
            runCatching {
                fs.collection("privacy").document(yo.fingerprint).set(
                    mapOf(
                        "vis" to me.visibility.clave,
                        "friends" to amigos,
                        "u" to me.username
                    )
                ).await()
            }
        }
    }

    // ======================================================================
    //  Grupos
    // ======================================================================

    /**
     * Crea un grupo CIFRADO: se genera una llave que solo existe en este teléfono y dentro del
     * código de invitación. Firebase guarda el grupo pero no puede leer ni su nombre.
     */
    suspend fun createGroup(name: String, description: String): TravelGroup {
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Primero elige tu nombre de usuario.")
        val yo = account.identity ?: throw IllegalStateException("Crea tu cuenta con contraseña para hacer grupos seguros.")
        val id = firestore?.collection("groups")?.document()?.id ?: newLocalId()
        val llave = CryptoBox.b64(CryptoBox.randomBytes(32))
        val group = TravelGroup(
            id = id,
            name = name.trim().ifBlank { "Mi grupo" },
            description = description.trim(),
            inviteCode = SecureEnvelope.inviteCode(id, llave),
            ownerUid = yo.fingerprint,
            memberUids = listOf(yo.fingerprint),
            memberNames = listOf(me.username),
            createdAt = System.currentTimeMillis(),
            lastMessageAt = System.currentTimeMillis(),
            pendingSync = true,
            secretKey = llave,
            encrypted = true
        )
        groupDao.upsert(group.toEntity())
        profileDao.upsert(me.copy(groupIds = (me.groupIds + id).distinct()).toEntity())
        scope.launch {
            runCatching { pushGroup(group) }
            runCatching { saveVault() }
            listenPosts(id)
        }
        return group
    }

    /**
     * Entrar a un grupo con su código. El código NUEVO (TRV1-...) trae dentro la llave del
     * grupo: por eso no hace falta buscar por todo Firebase ni guardar la llave allá. Los
     * códigos viejos de 6 letras se siguen aceptando para no dejar a nadie fuera.
     */
    suspend fun joinGroupByCode(code: String): TravelGroup {
        val fs = firestore ?: throw IllegalStateException("Necesitas internet para unirte a un grupo.")
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Primero elige tu nombre de usuario.")

        val invitacion = SecureEnvelope.parseInvite(code)
        if (invitacion != null) {
            val yo = account.identity ?: throw IllegalStateException("Entra con tu cuenta para unirte a un grupo cifrado.")
            val (groupId, llaveB64) = invitacion
            val doc = fs.collection("groups").document(groupId).get().await()
            if (!doc.exists()) throw IllegalStateException("Ese grupo ya no existe.")
            val llave = CryptoBox.b64d(llaveB64)
            val grupo = readGroup(doc, llave)
                ?: throw IllegalStateException("El código no corresponde a este grupo.")
            if (grupo.name.isBlank()) throw IllegalStateException("El código no abre este grupo: pide uno nuevo.")

            doc.reference.update("memberUids", FieldValue.arrayUnion(yo.fingerprint)).await()
            val conLlave = grupo.copy(
                secretKey = llaveB64,
                encrypted = true,
                memberUids = (grupo.memberUids + yo.fingerprint).distinct(),
                memberNames = (grupo.memberNames + me.username).distinct()
            )
            groupDao.upsert(conLlave.toEntity())
            profileDao.upsert(me.copy(groupIds = (me.groupIds + groupId).distinct()).toEntity())
            runCatching { saveVault() }
            listenPosts(groupId)
            return conLlave
        }

        // ---- Códigos antiguos (grupos creados antes del cifrado) ----
        val clean = code.trim().uppercase()
        if (clean.length < 4) throw IllegalStateException("El código de invitación no es válido.")

        val snap = fs.collection("groups").whereEqualTo("inviteCode", clean).limit(1).get().await()
        val doc = snap.documents.firstOrNull() ?: throw IllegalStateException("No existe ningún grupo con el código $clean.")

        doc.reference.update(
            mapOf(
                "memberUids" to FieldValue.arrayUnion(myId() ?: me.uid),
                "memberNames" to FieldValue.arrayUnion(me.username)
            )
        ).await()

        val fresh = doc.reference.get().await()
        val group = readGroup(fresh, null) ?: throw IllegalStateException("No se pudo leer el grupo.")
        groupDao.upsert(group.toEntity())
        profileDao.upsert(me.copy(groupIds = (me.groupIds + group.id).distinct()).toEntity())
        listenPosts(group.id)
        return group
    }

    suspend fun leaveGroup(groupId: String) {
        val me = profileDao.get()?.toDomain()
        synchronized(listenerLock) { postListeners.remove(groupId)?.remove() }
        postDao.deleteForGroup(groupId)
        groupDao.deleteById(groupId)
        if (me != null) profileDao.upsert(me.copy(groupIds = me.groupIds - groupId).toEntity())
        scope.launch { runCatching { saveVault() } }
        val fs = firestore
        if (fs != null && me != null && !groupId.startsWith(LOCAL_ID_PREFIX)) {
            scope.launch {
                withTimeoutOrNull(15_000L) {
                    runCatching {
                        fs.collection("groups").document(groupId).update(
                            mapOf(
                                "memberUids" to FieldValue.arrayRemove(myId() ?: me.uid, me.uid),
                                "memberNames" to FieldValue.arrayRemove(me.username)
                            )
                        ).await()
                    }
                }
            }
        }
    }

    /**
     * Sube el grupo. **Va con `merge`, y esto es un arreglo de verdad, no un detalle:** antes
     * era un `set` completo, así que cualquier subida posterior BORRABA el campo `dk` (los
     * sobres con la llave del chat que cada invitado necesita para poder leer). Resultado: el
     * otro dejaba de poder abrir los mensajes y parecía que no llegaban.
     */
    private suspend fun pushGroup(group: TravelGroup, extra: Map<String, Any> = emptyMap()) {
        val fs = firestore ?: return
        if (group.id.startsWith(LOCAL_ID_PREFIX)) return
        val datos = groupToMap(group) + extra
        val ok = withTimeoutOrNull(20_000L) {
            runCatching {
                fs.collection("groups").document(group.id)
                    .set(datos, com.google.firebase.firestore.SetOptions.merge()).await()
            }.isSuccess
        }
        if (ok == true) groupDao.markSynced(group.id)
    }

    // ======================================================================
    //  Mensajes (texto, foto, ubicación, lugar y ruta)
    // ======================================================================

    suspend fun sendMessage(groupId: String, text: String): GroupPost? {
        if (text.isBlank()) return null
        return addPost(groupId, PostType.MESSAGE, text.trim())
    }

    /**
     * Manda una foto al grupo.
     *
     * ANTES: solo viajaba una miniatura de 320 px. El grupo veía un cuadrito borroso y la foto
     * de verdad se quedaba en el teléfono de quien la mandó, sin manera de pedirla.
     * AHORA: la miniatura sigue apareciendo al instante (para que la conversación no se trabe)
     * y además va la foto entera, cifrada y en trozos, lista para bajarla de un toque.
     */
    suspend fun sendPhoto(groupId: String, uri: Uri, caption: String = ""): GroupPost? =
        sendFile(groupId, uri, caption)

    // ======================================================================
    //  Adjuntos: fotos pesadas, videos, audios y documentos
    // ======================================================================
    //  El archivo NO viaja dentro del mensaje: se parte en trozos, cada trozo se cifra con la
    //  llave del grupo y se sube como un documento más de Firestore (gratis, sin Storage ni
    //  plan de pago). El mensaje solo lleva la ficha del archivo y una miniatura, así que la
    //  conversación se ve al instante y el archivo se baja cuando el otro quiera.

    /** Cuánto va de cada transferencia, de 0 a 100, por id de mensaje. Para la barrita. */
    private val _transferencias = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transferencias: StateFlow<Map<String, Int>> = _transferencias.asStateFlow()

    private fun avanzar(id: String, porcentaje: Int) {
        _transferencias.value = _transferencias.value + (id to porcentaje.coerceIn(0, 100))
    }

    private fun terminar(id: String) {
        _transferencias.value = _transferencias.value - id
    }

    /**
     * Manda un archivo al grupo: foto grande, video, audio, PDF, lo que sea.
     * Los trozos se suben ANTES que el mensaje, para que nadie vea nunca un adjunto roto.
     */
    suspend fun sendFile(groupId: String, uri: Uri, caption: String = ""): GroupPost {
        val preparado = Attachments.preparar(context, uri)
        // El mensaje se crea YA (así aparece al instante en la conversación, con su miniatura) y
        // la subida de los trozos la hace pushPost. Si no hay señal se queda pendiente y se
        // reintenta sola al reconectar, igual que cualquier otro mensaje.
        return addPost(
            groupId, PostType.FILE, caption.trim(),
            payloadJson = preparado.ficha.toJson(),
            photoPath = preparado.rutaLocal,
            photoThumb = preparado.miniatura
        )
    }

    /**
     * Sube los trozos de un archivo. Si ya estaban subidos (por ejemplo, se cortó la señal a
     * medias y se reintenta), no los vuelve a mandar: solo los que falten.
     */
    private suspend fun subirBulto(
        fs: FirebaseFirestore,
        groupId: String,
        ficha: Attachments.Ficha,
        rutaLocal: String,
        progreso: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val bulto = fs.collection("groups").document(groupId)
            .collection(Attachments.COL_BULTOS).document(ficha.id)
        val yaEsta = runCatching { bulto.get().await() }.getOrNull()
        if (yaEsta != null && yaEsta.exists() && yaEsta.getBoolean("listo") == true) return

        val llave = groupKeyOf(groupId)
        val expira = System.currentTimeMillis() + SecureEnvelope.MAILBOX_MILLIS
        for (i in 0 until ficha.trozos) {
            val crudo = Attachments.leerTrozo(rutaLocal, i)
                ?: throw IllegalStateException("El archivo ya no está en el teléfono.")
            // Cada trozo se ata a SU archivo y a SU posición: no se puede mover ni reordenar.
            val etiqueta = ficha.id + "#" + i
            val contenido = if (llave != null) CryptoBox.b64(CryptoBox.seal(llave, crudo, etiqueta.toByteArray()))
            else CryptoBox.b64(crudo)
            bulto.collection(Attachments.COL_PARTES).document(i.toString())
                .set(mapOf("i" to i, "d" to contenido, "exp" to expira)).await()
            progreso(i + 1, ficha.trozos)
        }
        bulto.set(
            mapOf("n" to ficha.trozos, "listo" to true, "by" to (myId() ?: ""), "exp" to expira)
        ).await()
    }

    /**
     * Baja un adjunto y lo arma en el teléfono. Devuelve la ruta del archivo listo para abrir,
     * o null si no se pudo. Si ya estaba bajado no vuelve a gastar datos.
     */
    suspend fun downloadAttachment(post: GroupPost): String? {
        val ficha = Attachments.Ficha.fromJson(post.payloadJson) ?: return null
        if (Attachments.existe(post.photoPath)) return post.photoPath
        val fs = firestore ?: throw IllegalStateException("Necesitas conexión para bajar este archivo.")
        val llave = groupKeyOf(post.groupId)
        val partes = fs.collection("groups").document(post.groupId)
            .collection(Attachments.COL_BULTOS).document(ficha.id).collection(Attachments.COL_PARTES)

        avanzar(post.id, 1)
        try {
            val trozos = ArrayList<ByteArray>(ficha.trozos)
            for (i in 0 until ficha.trozos) {
                val doc = withTimeoutOrNull(30_000L) {
                    runCatching { partes.document(i.toString()).get().await() }.getOrNull()
                } ?: throw IllegalStateException("Se cortó la descarga. Inténtalo otra vez.")
                if (!doc.exists()) throw IllegalStateException("Este archivo ya no está disponible (los adjuntos se borran a los 30 días).")
                val texto = doc.getString("d").orEmpty()
                val bytes = if (llave != null) CryptoBox.open(llave, CryptoBox.b64d(texto), (ficha.id + "#" + i).toByteArray())
                else CryptoBox.b64d(texto)
                if (bytes == null) throw IllegalStateException("No se pudo descifrar el archivo: te falta la llave de este chat.")
                trozos.add(bytes)
                avanzar(post.id, 5 + (90 * (i + 1) / maxOf(ficha.trozos, 1)))
            }
            val ruta = Attachments.armar(context, ficha, trozos)
                ?: throw IllegalStateException("El archivo llegó incompleto o cambiado. Pide que lo manden otra vez.")
            postDao.upsert(post.toEntity().copy(photoPath = ruta))
            return ruta
        } finally {
            terminar(post.id)
        }
    }

    suspend fun sendLocation(groupId: String, latitude: Double, longitude: Double, label: String): GroupPost? =
        addPost(
            groupId, PostType.LOCATION,
            label.ifBlank { "Mi ubicación" },
            latitude = latitude, longitude = longitude, locationLabel = label
        )

    suspend fun sendPlace(groupId: String, placeName: String, placeJson: String): GroupPost? =
        addPost(groupId, PostType.PLACE, "Compartió el lugar: $placeName", payloadJson = placeJson)

    suspend fun sendRoute(groupId: String, routeName: String, routeJson: String): GroupPost? =
        addPost(groupId, PostType.ROUTE, "Compartió la ruta: $routeName", payloadJson = routeJson)

    private suspend fun addPost(
        groupId: String,
        type: PostType,
        text: String,
        payloadJson: String = "",
        photoPath: String = "",
        photoThumb: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        locationLabel: String = "",
        idFijo: String = ""
    ): GroupPost {
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Primero elige tu nombre de usuario.")

        val grupo = groupDao.getById(groupId)?.toDomain()
        val yoId = myId()
        // Si YO soy el invitado y escribo, salgo de pendientes: eso es "responder".
        // El otro ya puede seguir chateando. NO nos convertimos en amigos por responder.
        if (grupo != null && grupo.direct && yoId != null && yoId in grupo.pendingMembers) {
            runCatching { unlockDirectChat(groupId, yoId) }
        }
        // Mientras el otro no responda, solo un mensaje de presentación. Cuando hay un
        // mensaje suyo (aunque Firebase aún no haya limpiado `pend`), el chat queda abierto.
        val grupoAhora = groupDao.getById(groupId)?.toDomain() ?: grupo
        if (grupoAhora != null && grupoAhora.waitingForOther(yoId)) {
            val autor = yoId ?: me.uid
            val mios = postDao.countByAuthor(groupId, autor)
            val delOtro = postDao.countFromOthers(groupId, autor)
            if (mios >= 1 && delOtro == 0) {
                val quien = grupoAhora.memberNames.firstOrNull { it != me.username }?.let { "@" + it } ?: "esa persona"
                throw IllegalStateException(
                    "Ya mandaste tu mensaje de presentación. Podrás seguir escribiendo cuando " + quien + " te responda o te agregue como amigo."
                )
            }
        }

        val id = idFijo.ifBlank {
            if (groupId.startsWith(LOCAL_ID_PREFIX)) newLocalId()
            else firestore?.collection("groups")?.document(groupId)?.collection("posts")?.document()?.id ?: newLocalId()
        }

        val yo = account.identity
        val post = GroupPost(
            id = id,
            groupId = groupId,
            type = type,
            authorUid = yo?.fingerprint ?: me.uid,
            authorName = me.username,
            authorThumb = me.avatarThumb,
            text = text,
            payloadJson = payloadJson,
            photoPath = photoPath,
            photoThumb = photoThumb,
            latitude = latitude,
            longitude = longitude,
            locationLabel = locationLabel,
            createdAt = System.currentTimeMillis(),
            pendingSync = true,
            authorKey = yo?.publicKeyB64.orEmpty(),
            // Lo que yo escribo en este teléfono está verificado por definición: lo firmo yo.
            trust = if (yo != null) PostTrust.VERIFIED else PostTrust.LEGACY
        )
        postDao.upsert(post.toEntity())
        groupDao.touch(groupId, post.createdAt, previewOf(post))
        // Se espera a que el mensaje salga: si se lanzaba en segundo plano y fallaba, aquí
        // se veía enviado y al otro no le llegaba nunca. Ahora, con señal, o llega o se avisa.
        val fs = firestore
        if (fs != null && !groupId.startsWith(LOCAL_ID_PREFIX)) {
            val ok = runCatching { pushPost(post) }.getOrDefault(false)
            if (!ok) {
                throw IllegalStateException(
                    "El mensaje quedó en tu teléfono pero no se pudo entregar. Toca Reintentar arriba o vuelve a mandarlo con señal."
                )
            }
        } else {
            scope.launch { runCatching { pushPost(post) } }
        }
        return post
    }

    /**
     * Sube el mensaje FIRMADO y (si el grupo tiene llave) CIFRADO. Lo que queda en Firebase
     * es un bulto ilegible con la fecha y de quién viene, nada más.
     */
    private suspend fun pushPost(post: GroupPost): Boolean {
        val fs = firestore ?: return false
        if (post.groupId.startsWith(LOCAL_ID_PREFIX)) return false

        // Un adjunto va antes que su mensaje: así nadie ve nunca un archivo que todavía no está.
        if (post.type == PostType.FILE) {
            val ficha = Attachments.Ficha.fromJson(post.payloadJson)
            if (ficha != null && Attachments.existe(post.photoPath)) {
                avanzar(post.id, 1)
                val subido = runCatching {
                    subirBulto(fs, post.groupId, ficha, post.photoPath) { hechos, total ->
                        avanzar(post.id, 5 + (90 * hechos / maxOf(total, 1)))
                    }
                }
                terminar(post.id)
                // Si no se pudo subir, el mensaje se queda pendiente y se reintenta al reconectar.
                if (subido.isFailure) return false
            }
        }

        val yo = account.identity
        val me = profileDao.get()?.toDomain()
        val datos: Map<String, Any> = if (yo != null) {
            SecureEnvelope.seal(
                post = post,
                identity = yo,
                card = yo.card(nombreVisible(me, yo), PhotoStore.shrinkThumb(me?.avatarThumb.orEmpty())),
                groupKey = groupKeyOf(post.groupId)
            )
        } else {
            postToMap(post)   // cuentas de la versión anterior: como antes, sin firma
        }
        val ok = withTimeoutOrNull(25_000L) {
            runCatching {
                fs.collection("groups").document(post.groupId)
                    .collection("posts").document(post.id)
                    .set(datos).await()
            }.isSuccess
        }
        if (ok == true) {
            postDao.markSynced(post.id)
            return true
        }
        return false
    }

    // ======================================================================
    //  Compañeros afines
    // ======================================================================

    suspend fun findBuddies(limit: Int = 25): List<BuddyMatch> {
        val me = profileDao.get()?.toDomain() ?: return emptyList()
        val fs = firestore

        if (fs != null) {
            val fetched = LinkedHashMap<String, TravelerProfile>()
            // OJO: las tres consultas van en runCatching SEPARADOS. Antes compartían uno
            // solo y, si la primera fallaba (índice, permisos, red), las otras dos ni se
            // intentaban: el resultado era una lista vacía sin explicación.
            withTimeoutOrNull(20_000L) {
                if (me.region.isNotBlank()) runCatching {
                    fs.collection("showcase").whereEqualTo("region", me.region).limit(60).get().await()
                        .documents.forEach { d -> readBuddy(d)?.let { fetched[it.uid] = it } }
                }
                if (me.interests.isNotEmpty()) runCatching {
                    fs.collection("showcase").whereArrayContainsAny("interests", me.interests.take(10)).limit(60).get().await()
                        .documents.forEach { d -> readBuddy(d)?.let { fetched[it.uid] = it } }
                }
                // Los viajeros recientes se traen SIEMPRE, no solo cuando faltan: aunque yo
                // no haya puesto gustos ni región, la comunidad tiene que verse.
                runCatching {
                    fs.collection("showcase").orderBy("vistoEn", Query.Direction.DESCENDING).limit(60).get().await()
                        .documents.forEach { d -> readBuddy(d)?.let { fetched[it.uid] = it } }
                }
                // Vitrinas de antes de que existiera `vistoEn`: quedarían fuera del orden anterior.
                if (fetched.size < 10) runCatching {
                    fs.collection("showcase").limit(40).get().await()
                        .documents.forEach { d -> readBuddy(d)?.let { fetched[it.uid] = it } }
                }
            }
            if (fetched.isNotEmpty()) {
                val now = System.currentTimeMillis()
                buddyDao.upsertAll(fetched.values.map { it.toBuddyEntity(now) })
            }
        }

        // A la vitrina se suma la gente que ya conozco por sus propios mensajes: esos no
        // necesitan estar publicados en ningún lado.
        val deMisChats = identityDao.recent(120).map { k ->
            TravelerProfile(
                uid = k.fingerprint, username = k.username, displayName = k.displayName,
                avatarThumb = k.thumb, publicKey = k.publicKey, createdAt = k.firstSeenAt
            )
        }
        val yoFp = myId()
        val candidates = (buddyDao.getAll().map { it.toDomain() } + deMisChats)
            .distinctBy { it.uid }
            .filter { p ->
                !p.esYo(yoFp, me.username) && p.uid != me.uid
            }
        return score(me, candidates).take(limit)
    }

    private fun score(me: TravelerProfile, candidates: List<TravelerProfile>): List<BuddyMatch> {
        val myInterests = me.interests.map { it.lowercase() }.toSet()
        return candidates
            .filter { it.uid != me.uid && it.username.isNotBlank() && !it.username.equals(me.username, ignoreCase = true) }
            .map { other ->
                val reasons = ArrayList<String>()
                var points = 0
                val shared = other.interests.map { it.lowercase() }.filter { myInterests.contains(it) }
                if (shared.isNotEmpty()) {
                    points += shared.size * 10
                    reasons.add("Gustos en común: " + shared.joinToString(", "))
                }
                if (other.region.isNotBlank() && other.region == me.region) {
                    points += 15
                    reasons.add("Viaja a " + me.region)
                }
                if (norm(other.routeDestination).isNotBlank() && norm(other.routeDestination) == norm(me.routeDestination)) {
                    points += 25
                    reasons.add("Mismo destino: " + other.routeDestination)
                }
                if (norm(other.routeOrigin).isNotBlank() && norm(other.routeOrigin) == norm(me.routeOrigin)) {
                    points += 10
                    reasons.add("Sale desde " + other.routeOrigin)
                }
                if (other.travelMonth.isNotBlank() && other.travelMonth == me.travelMonth) {
                    points += 20
                    reasons.add("Viaja el mismo mes")
                }
                val myTags = me.travelTags.toSet()
                val otherTags = other.travelTags.toSet()
                val yaContado = HashSet<String>()
                myTags.forEach { tag ->
                    val complemento = COMPLEMENTARY_TAGS[tag]
                    if (complemento != null && otherTags.contains(complemento) && tag !in yaContado && complemento !in yaContado) {
                        points += 30
                        reasons.add("$tag ↔ $complemento")
                        yaContado.add(tag); yaContado.add(complemento)
                    }
                }
                val etiquetasComunes = otherTags.filter { it in myTags && it !in yaContado && COMPLEMENTARY_TAGS[it] == null }
                if (etiquetasComunes.isNotEmpty()) {
                    points += etiquetasComunes.size * 8
                    reasons.add("También: " + etiquetasComunes.joinToString(", "))
                }
                BuddyMatch(other, points, reasons)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
    }

    // ======================================================================
    //  Comentarios y tips por lugar
    // ======================================================================

    /** Comentarios de un lugar, leídos siempre del teléfono. */
    fun comments(placeId: String): Flow<List<PlaceComment>> =
        commentDao.observeForPlace(placeId).map { list -> list.map { it.toDomain() } }

    /** Se llama al abrir la ficha de un lugar: escucha comentarios nuevos de otros viajeros. */
    fun listenComments(placeId: String) {
        val fs = firestore ?: return
        if (placeId.isBlank()) return
        synchronized(listenerLock) {
            if (commentListeners.containsKey(placeId)) return
        }
        val registro = fs.collection("places").document(placeId).collection("comments")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                val incoming = snap.documents.mapNotNull { readComment(it, placeId) }
                if (incoming.isEmpty()) return@addSnapshotListener
                scope.launch {
                    incoming.forEach { c ->
                        val existing = commentDao.getById(c.id)
                        val keepPath = existing?.photoPath ?: ""
                        commentDao.upsert(c.toEntity().copy(photoPath = keepPath, pendingSync = false))
                    }
                }
            }
        synchronized(listenerLock) {
            if (commentListeners.containsKey(placeId)) registro.remove()
            else commentListeners[placeId] = registro
        }
    }

    /**
     * Se llama al cerrar la ficha del lugar. Sin esto, cada lugar que abre el viajero deja un
     * listener de Firestore vivo para todo lo que dure la app: memoria y lecturas que se pagan
     * sin que nadie las esté mirando.
     */
    fun stopListeningComments(placeId: String) {
        synchronized(listenerLock) { commentListeners.remove(placeId)?.remove() }
    }

    /** Publica un comentario/tip sobre un lugar. La foto (si hay) se guarda igual que en el chat. */
    suspend fun addComment(placeId: String, text: String, category: CommentCategory, photoUri: Uri? = null): PlaceComment? {
        if (placeId.isBlank() || (text.isBlank() && photoUri == null)) return null
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Primero elige tu nombre de usuario en Comunidad.")
        val saved = photoUri?.let { PhotoStore.save(context, it, "tip") }
        val id = if (placeId.startsWith(LOCAL_ID_PREFIX)) newLocalId()
        else firestore?.collection("places")?.document(placeId)?.collection("comments")?.document()?.id ?: newLocalId()

        val comment = PlaceComment(
            id = id,
            placeId = placeId,
            authorUid = myId() ?: me.uid,
            authorName = me.username,
            authorThumb = me.avatarThumb,
            text = text.trim(),
            category = category,
            photoPath = saved?.localPath ?: "",
            photoThumb = saved?.thumbBase64 ?: "",
            createdAt = System.currentTimeMillis(),
            pendingSync = true
        )
        commentDao.upsert(comment.toEntity())
        scope.launch { runCatching { pushComment(comment) } }
        return comment
    }

    private suspend fun pushComment(comment: PlaceComment) {
        val fs = firestore ?: return
        val ok = withTimeoutOrNull(20_000L) {
            runCatching {
                fs.collection("places").document(comment.placeId)
                    .collection("comments").document(comment.id)
                    .set(commentToMap(comment)).await()
            }.isSuccess
        }
        if (ok == true) commentDao.markSynced(comment.id)
    }

    private fun commentToMap(c: PlaceComment): Map<String, Any> = mapOf(
        "authorUid" to c.authorUid,
        "authorName" to c.authorName,
        "authorThumb" to c.authorThumb,
        "text" to c.text,
        "category" to c.category.name,
        "photoThumb" to c.photoThumb,
        "createdAt" to c.createdAt
    )

    private fun readComment(doc: DocumentSnapshot, placeId: String): PlaceComment? {
        if (!doc.exists()) return null
        return PlaceComment(
            id = doc.id,
            placeId = placeId,
            authorUid = doc.getString("authorUid") ?: "",
            authorName = doc.getString("authorName") ?: "",
            authorThumb = doc.getString("authorThumb") ?: "",
            text = doc.getString("text") ?: "",
            category = CommentCategory.from(doc.getString("category") ?: "GENERAL"),
            photoThumb = doc.getString("photoThumb") ?: "",
            createdAt = doc.getLong("createdAt") ?: 0L,
            pendingSync = false
        )
    }

    // ======================================================================
    //  Contacto directo con un compañero
    // ======================================================================

    /**
     * Crea (o reabre) un grupo de 2 personas para contactar directo a un compañero de viaje,
     * sin tener que intercambiar código de invitación. Necesita conexión: es la única forma
     * de que le llegue la invitación a la otra persona.
     */
    suspend fun startDirectChat(otherUid: String, otherUsername: String): TravelGroup {
        val fs = firestore ?: throw IllegalStateException("Necesitas conexión para contactar directamente. Mientras tanto, comparte tu código de grupo.")
        val me = profileDao.get()?.toDomain() ?: throw IllegalStateException("Primero elige tu nombre de usuario.")
        val yoFp = myId()
        // No se abre un chat con uno mismo (aparecía en la bandeja como otro usuario).
        if (esLaMismaPersona(otherUid, otherUsername, yoFp, me.username, me.uid)) {
            throw IllegalStateException("Ese eres tú.")
        }

        if (identityDao.get(otherUid)?.state == ContactState.BLOQUEADO.name) {
            throw IllegalStateException("Tienes bloqueado a @" + otherUsername + ". Desbloquéalo en su perfil para poder escribirle.")
        }

        val existente = groupDao.getAll().map { it.toDomain() }.firstOrNull {
            it.direct && it.memberUids.toSet() == setOf(myId() ?: me.uid, otherUid)
        }
        if (existente != null) return existente

        val yo = account.identity
            ?: throw IllegalStateException("Entra con tu cuenta para escribir en privado.")
        val suPublica = publicKeyOf(otherUid, otherUsername)
            ?: throw IllegalStateException("No encuentro la llave de @" + otherUsername + ". Revisa que el usuario esté bien escrito.")

        val id = fs.collection("groups").document().id
        val llave = CryptoBox.b64(CryptoBox.randomBytes(32))
        // La llave del chat va cifrada con el secreto que solo él y yo podemos calcular, y el
        // sobre lleva dentro MI llave pública para que él sepa contra quién calcularlo.
        val sobreParaEl = sobreParaInvitado(yo, suPublica, id, llave)
            ?: throw IllegalStateException("No se pudo preparar el chat cifrado.")

        val group = TravelGroup(
            id = id,
            name = DIRECT_CHAT_PREFIX + otherUsername,
            description = "Chat directo",
            inviteCode = "",
            ownerUid = yo.fingerprint,
            memberUids = listOf(yo.fingerprint, otherUid),
            memberNames = listOf(me.username, otherUsername),
            createdAt = System.currentTimeMillis(),
            lastMessageAt = System.currentTimeMillis(),
            pendingSync = true,
            secretKey = llave,
            encrypted = true,
            // Le llega como SOLICITUD: verá mi perfil y mi primer mensaje, y decide.
            pendingMembers = listOf(otherUid),
            direct = true
        )
        groupDao.upsert(group.toEntity())
        profileDao.upsert(me.copy(groupIds = (me.groupIds + id).distinct()).toEntity())
        pushGroup(group, mapOf("dk" to mapOf(otherUid to sobreParaEl)))
        runCatching { saveVault() }
        listenPosts(id)
        return group
    }

    /**
     * Llave pública de un compañero, buscada en tres sitios y en este orden:
     *  1. Lo que ya aprendí de sus propios mensajes (no gasta ni una lectura).
     *  2. Su vitrina, si la tiene encendida.
     *  3. Su documento de identidad, buscándolo por nombre de usuario EXACTO.
     *
     * El tercero es el que hace posible escribirle a alguien sabiendo solo su @usuario, como
     * quien tiene tu número de teléfono. Las reglas permiten leer un documento concreto pero
     * NO listar la colección: nadie se puede bajar el padrón de usuarios de la app.
     */
    private suspend fun publicKeyOf(fingerprint: String, username: String = ""): String? {
        val fs = firestore

        // 1. LA FUENTE QUE MANDA: su documento de identidad, buscado por nombre exacto.
        //
        // Esto antes estaba al final y era EL BUG: se usaba primero la llave guardada en el
        // teléfono, y si esa persona había vuelto a crear su cuenta (llave nueva), el chat se
        // montaba contra la llave vieja. Se creaba, se veía, y los mensajes no se podían abrir
        // NUNCA. Ahora, si hay señal, se pregunta a la fuente y de paso se refresca la caché.
        if (fs != null && username.isNotBlank()) {
            val carne = runCatching { account.lookup(fs, username) }.getOrNull()
            if (carne != null && carne.fingerprint == fingerprint) {
                runCatching {
                    aprender(IdentityCard(carne.username, carne.fingerprint, carne.publicKeyB64, carne.username, ""))
                }
                return carne.publicKeyB64
            }
        }

        // 2. Lo que aprendí de sus propios mensajes (funciona sin señal).
        identityDao.get(fingerprint)?.publicKey?.takeIf { it.isNotBlank() }?.let { return it }
        if (username.isNotBlank()) {
            identityDao.getByUsername(username)?.publicKey?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 3. Su vitrina, si la tiene encendida.
        if (fs == null) return null
        val doc = withTimeoutOrNull(15_000L) {
            runCatching { fs.collection("showcase").document(fingerprint).get().await() }.getOrNull()
        }
        return doc?.getString("k")?.takeIf { it.isNotBlank() }
    }

    /**
     * Escribirle a alguien sabiendo solo su @usuario: se busca su carné, se comprueba que la
     * huella corresponda de verdad a su llave pública y se abre el chat cifrado.
     */
    suspend fun startDirectChatWithUsername(rawUsername: String): TravelGroup {
        val fs = firestore ?: throw IllegalStateException("Necesitas conexión para buscar a alguien por su usuario.")
        val usuario = AccountService.normalizeUsername(rawUsername)
        require(usuario.length >= 3) { "Escribe el nombre de usuario completo (mínimo 3 letras)." }
        val yo = account.identity ?: throw IllegalStateException("Entra con tu cuenta para escribir en privado.")
        if (usuario == yo.username) throw IllegalStateException("Ese eres tú.")
        val carne = account.lookup(fs, usuario)
            ?: throw IllegalStateException("No existe ningún viajero con el usuario \"@" + usuario + "\". Revisa cómo se escribe.")
        // Guardamos su carné para reconocerlo desde el primer mensaje.
        runCatching {
            aprender(IdentityCard(carne.username, carne.fingerprint, carne.publicKeyB64, carne.username, ""))
        }
        return startDirectChat(carne.fingerprint, carne.username)
    }

    // ======================================================================
    //  Solicitudes, contactos y bloqueos
    // ======================================================================
    //  La regla de oro: NADIE entra a tu chat sin que tú digas que sí. Quien te escribe por
    //  primera vez llega como SOLICITUD, con su perfil y su primer mensaje a la vista, y tú
    //  decides. Lo mismo para que te metan a un grupo.

    /** Huellas bloqueadas, en memoria, para no consultar la base en cada mensaje que llega. */
    @Volatile
    private var bloqueados: Set<String> = emptySet()

    private suspend fun refrescarBloqueados() {
        bloqueados = runCatching { identityDao.blockedFingerprints().toSet() }.getOrDefault(emptySet())
    }

    /** Mis contactos y conocidos, para la pestaña de gente. */
    val contacts: Flow<List<TravelerCard>> = identityDao.observeContacts().map { lista ->
        lista.map { it.toCard() }
    }

    /** Los que tengo bloqueados, para poder desbloquearlos. */
    val blocked: Flow<List<TravelerCard>> = identityDao.observeBlocked().map { lista ->
        lista.map { it.toCard() }
    }

    private fun ec.travesias.app.data.local.KnownIdentityEntity.toCard() = TravelerCard(
        fingerprint = fingerprint,
        username = username,
        displayName = displayName,
        thumb = thumb,
        publicKey = publicKey,
        state = ContactState.from(state),
        knownSince = firstSeenAt,
        messagesSeen = messagesSeen,
        verified = !suspicious && publicKey.isNotBlank()
    )

    /**
     * La ficha de otro viajero: lo que él mismo publicó y lo que llegó firmado en sus mensajes.
     * Nada más. Si no tiene vitrina, se ve lo que se aprendió hablando con él, que ya es algo.
     */
    suspend fun travelerCard(fingerprint: String, username: String = ""): TravelerCard {
        val yoFp = myId()
        val yo = profileDao.get()?.toDomain()
        // Mi propio perfil: siempre lo veo, con mi álbum local, sin pasar por privacidad ajena.
        if (!yoFp.isNullOrBlank() && yoFp == fingerprint && yo != null) {
            return TravelerCard(
                fingerprint = yoFp,
                username = yo.username,
                displayName = yo.displayName,
                thumb = yo.avatarThumb,
                state = ContactState.CONTACTO,
                bio = yo.bio,
                interests = yo.interests,
                travelTags = yo.travelTags,
                region = yo.region,
                travelMonth = yo.travelMonth,
                routeLabel = yo.routeLabel,
                visibility = yo.visibility,
                canViewProfile = true,
                showcase = yo.showcase
            )
        }
        val local = identityDao.get(fingerprint)
        var card = local?.toCard() ?: TravelerCard(fingerprint = fingerprint, username = username)
        if (card.username.isBlank() && username.isNotBlank()) card = card.copy(username = username)

        val fs = firestore
        if (fs != null) {
            val privacidad = withTimeoutOrNull(12_000L) {
                runCatching { fs.collection("privacy").document(fingerprint).get().await() }.getOrNull()
            }
            val hayPrivacy = privacidad != null && privacidad.exists()
            // Sin ficha de privacy las reglas tratan el álbum como público: aquí igual.
            val vis = if (hayPrivacy) {
                ProfileVisibility.from(privacidad.getString("vis"))
            } else ProfileVisibility.PUBLIC
            val amigosSuyos = if (hayPrivacy) strings(privacidad.get("friends")) else emptyList()
            val puedoVer = vis == ProfileVisibility.PUBLIC ||
                (vis == ProfileVisibility.FRIENDS && yoFp != null && yoFp in amigosSuyos)
            card = card.copy(visibility = vis, canViewProfile = puedoVer)

            if (puedoVer) {
                val vitrina = withTimeoutOrNull(12_000L) {
                    runCatching { fs.collection("showcase").document(fingerprint).get().await() }.getOrNull()
                }
                if (vitrina != null && vitrina.exists()) {
                    val intereses = strings(vitrina.get("interests"))
                    val av = vitrina.getString("av").orEmpty().ifBlank { vitrina.getString("avatarThumb").orEmpty() }
                    card = card.copy(
                        username = card.username.ifBlank { vitrina.getString("username").orEmpty() },
                        thumb = card.thumb.ifBlank { av },
                        region = vitrina.getString("region").orEmpty(),
                        travelMonth = vitrina.getString("travelMonth").orEmpty(),
                        travelTags = strings(vitrina.get("travelTags")),
                        interests = intereses,
                        showcase = true,
                        inCommon = intereses.filter { i -> yo?.interests.orEmpty().any { it.equals(i, true) } }
                    )
                }
                val aportes = withTimeoutOrNull(12_000L) {
                    runCatching { fs.collection("catalog").whereEqualTo("by", fingerprint).limit(30).get().await() }.getOrNull()
                }
                if (aportes != null) card = card.copy(contributions = aportes.documents.size)
            } else {
                // Perfil cerrado: no se muestran bio, fotos ni detalles de ruta.
                card = card.copy(
                    bio = "",
                    interests = emptyList(),
                    travelTags = emptyList(),
                    region = "",
                    travelMonth = "",
                    routeLabel = "",
                    album = emptyList(),
                    showcase = false
                )
            }
        }
        return card
    }

    /** Nota privada sobre alguien ("el de la camioneta de Baños"). No sale de este teléfono. */
    suspend fun setContactNote(fingerprint: String, note: String) {
        identityDao.setNote(fingerprint, note.take(140))
    }

    /** Aceptar la solicitud: a partir de aquí los mensajes van y vienen con normalidad. */
    suspend fun acceptInvitation(groupId: String) {
        val yo = myId() ?: throw IllegalStateException("Entra con tu cuenta para aceptar.")
        val g = groupDao.getById(groupId)?.toDomain() ?: throw IllegalStateException("Esa solicitud ya no está.")
        unlockDirectChat(groupId, yo)
        // Quien me invitó pasa a ser contacto: ya hubo un sí de las dos partes.
        if (g.invitedBy.isNotBlank()) {
            identityDao.setState(g.invitedBy, ContactState.CONTACTO.name, System.currentTimeMillis())
        } else {
            g.otherMember(yo)?.let { identityDao.setState(it, ContactState.CONTACTO.name, System.currentTimeMillis()) }
        }
        runCatching { saveVault() }
    }

    /**
     * Responder sin agregar como amigo: salgo de pendientes para que el chat quede abierto
     * en los dos teléfonos. La amistad es otro botón.
     */
    suspend fun replyWithoutFriending(groupId: String) {
        val yo = myId() ?: throw IllegalStateException("Entra con tu cuenta para responder.")
        unlockDirectChat(groupId, yo)
    }

    /**
     * Agregar como amigo (contacto). Si hay un chat directo pendiente con esa persona,
     * también se desbloquea para que puedan escribirse sin límite.
     */
    suspend fun addFriend(fingerprint: String, username: String = "", groupId: String? = null) {
        if (fingerprint.isBlank()) throw IllegalStateException("No se pudo reconocer a esa persona.")
        val yoFp = myId()
        val miUsuario = profileDao.get()?.username
        if (esLaMismaPersona(fingerprint, username, yoFp, miUsuario, yoFp)) {
            throw IllegalStateException("No puedes agregarte a ti mismo.")
        }
        val ahora = System.currentTimeMillis()
        val conocido = identityDao.get(fingerprint)
        if (conocido == null) {
            identityDao.upsert(
                ec.travesias.app.data.local.KnownIdentityEntity(
                    fingerprint = fingerprint, username = username, publicKey = "", displayName = username,
                    thumb = "", firstSeenAt = ahora, lastSeenAt = ahora,
                    state = ContactState.CONTACTO.name
                )
            )
        } else {
            identityDao.setState(fingerprint, ContactState.CONTACTO.name, ahora)
        }
        val yo = myId()
        val gid = groupId ?: groupDao.getAll().map { it.toDomain() }
            .firstOrNull { it.direct && fingerprint in it.memberUids }?.id
        if (gid != null && yo != null) {
            val g = groupDao.getById(gid)?.toDomain()
            if (g != null && yo in g.pendingMembers) runCatching { unlockDirectChat(gid, yo) }
        }
        runCatching { saveVault() }
        // Quien agrego como amigo puede ver mi perfil si lo tengo en "solo amigos".
        runCatching { pushPrivacy() }
    }

    /**
     * Quita de amigos: pasa a conocido. El chat no se borra; bloqueo y borrar conversación van aparte.
     */
    suspend fun removeFriend(fingerprint: String) {
        if (fingerprint.isBlank()) return
        identityDao.setState(fingerprint, ContactState.CONOCIDO.name, System.currentTimeMillis())
        runCatching { saveVault() }
        runCatching { pushPrivacy() }
    }

    /**
     * Saca a [yo] de la lista de pendientes, en el teléfono y en Firebase.
     * A partir de aquí el otro puede seguir escribiendo. No convierte a nadie en amigo.
     */
    private suspend fun unlockDirectChat(groupId: String, yo: String) {
        val g = groupDao.getById(groupId)?.toDomain() ?: return
        if (yo !in g.pendingMembers) {
            listenPosts(groupId)
            return
        }
        groupDao.upsert(g.copy(pendingMembers = g.pendingMembers - yo).toEntity())
        val fs = firestore
        if (fs != null && !groupId.startsWith(LOCAL_ID_PREFIX)) {
            val ok = withTimeoutOrNull(15_000L) {
                runCatching {
                    fs.collection("groups").document(groupId)
                        .update("pend", FieldValue.arrayRemove(yo)).await()
                }.isSuccess
            }
            if (ok != true) {
                // El chat ya está abierto aquí; el otro se enterará al reconectar o al ver la respuesta.
            }
        }
        listenPosts(groupId)
        runCatching { saveVault() }
    }

    /**
     * Rechazar: me saco del grupo y borro lo que llegó. Si además bloqueo, esa persona no me
     * puede volver a escribir ni meter a ningún grupo.
     */
    suspend fun rejectInvitation(groupId: String, tambienBloquear: Boolean = false) {
        val yo = myId()
        val g = groupDao.getById(groupId)?.toDomain()
        val fs = firestore
        if (fs != null && yo != null && !groupId.startsWith(LOCAL_ID_PREFIX)) {
            runCatching {
                fs.collection("groups").document(groupId).update(
                    mapOf(
                        "memberUids" to FieldValue.arrayRemove(yo),
                        "pend" to FieldValue.arrayRemove(yo)
                    )
                ).await()
            }
        }
        synchronized(listenerLock) { postListeners.remove(groupId)?.remove() }
        postDao.deleteForGroup(groupId)
        groupDao.deleteById(groupId)
        if (tambienBloquear && g != null && g.invitedBy.isNotBlank()) blockTraveler(g.invitedBy)
    }

    /** Bloquear: se va de mis contactos, se cierran sus chats y no vuelve a entrar. */
    suspend fun blockTraveler(fingerprint: String) {
        if (fingerprint.isBlank()) return
        val ahora = System.currentTimeMillis()
        val conocido = identityDao.get(fingerprint)
        if (conocido == null) {
            identityDao.upsert(
                ec.travesias.app.data.local.KnownIdentityEntity(
                    fingerprint = fingerprint, username = "", publicKey = "", displayName = "",
                    thumb = "", firstSeenAt = ahora, lastSeenAt = ahora,
                    state = ContactState.BLOQUEADO.name
                )
            )
        } else {
            identityDao.setState(fingerprint, ContactState.BLOQUEADO.name, ahora)
        }
        refrescarBloqueados()
        // Sus chats de dos se cierran; los grupos con más gente se dejan (ahí no manda él).
        groupDao.getAll().map { it.toDomain() }
            .filter { it.direct && fingerprint in it.memberUids }
            .forEach { g ->
                synchronized(listenerLock) { postListeners.remove(g.id)?.remove() }
                postDao.deleteForGroup(g.id)
                groupDao.deleteById(g.id)
                val fs = firestore
                val yo = myId()
                if (fs != null && yo != null && !g.id.startsWith(LOCAL_ID_PREFIX)) {
                    runCatching {
                        fs.collection("groups").document(g.id)
                            .update("memberUids", FieldValue.arrayRemove(yo)).await()
                    }
                }
            }
    }

    suspend fun unblockTraveler(fingerprint: String) {
        identityDao.setState(fingerprint, ContactState.CONOCIDO.name, System.currentTimeMillis())
        refrescarBloqueados()
    }

    /** Silenciar un chat sin salirse de él. */
    suspend fun muteGroup(groupId: String, silenciado: Boolean) {
        val g = groupDao.getById(groupId) ?: return
        groupDao.upsert(g.copy(muted = silenciado))
    }

    // ======================================================================
    //  Invitar a un grupo: por usuario o por enlace, nunca tecleando códigos
    // ======================================================================

    /**
     * Mete a alguien en un grupo sabiendo solo su @usuario. La llave del grupo se cifra
     * SOLO para él (ECDH), así que Firebase sigue sin poder leer nada, y le llega como
     * solicitud: entra cuando acepte, no antes.
     */
    suspend fun inviteToGroup(groupId: String, rawUsername: String): String {
        val fs = firestore ?: throw IllegalStateException("Necesitas conexión para invitar.")
        val yo = account.identity ?: throw IllegalStateException("Entra con tu cuenta para invitar.")
        val g = groupDao.getById(groupId)?.toDomain() ?: throw IllegalStateException("Ese grupo ya no está.")
        if (g.secretKey.isBlank()) {
            throw IllegalStateException("Este grupo es de una versión anterior y no tiene llave. Crea uno nuevo para poder invitar por usuario.")
        }
        val usuario = AccountService.normalizeUsername(rawUsername)
        if (usuario == yo.username) throw IllegalStateException("Ese eres tú.")
        val carne = account.lookup(fs, usuario)
            ?: throw IllegalStateException("No existe ningún viajero con el usuario \"@" + usuario + "\".")
        if (carne.fingerprint in g.memberUids) throw IllegalStateException("@" + usuario + " ya está en el grupo.")

        val sobre = sobreParaInvitado(yo, carne.publicKeyB64, groupId, g.secretKey)
            ?: throw IllegalStateException("No se pudo preparar la invitación cifrada.")
        runCatching {
            fs.collection("groups").document(groupId).update(
                mapOf(
                    "memberUids" to FieldValue.arrayUnion(carne.fingerprint),
                    "pend" to FieldValue.arrayUnion(carne.fingerprint),
                    "dk." + carne.fingerprint to sobre
                )
            ).await()
        }.getOrElse { throw IllegalStateException("No se pudo enviar la invitación: " + (it.message ?: "revisa tu conexión")) }

        groupDao.upsert(
            g.copy(
                memberUids = (g.memberUids + carne.fingerprint).distinct(),
                memberNames = (g.memberNames + carne.username).distinct(),
                pendingMembers = (g.pendingMembers + carne.fingerprint).distinct()
            ).toEntity()
        )
        runCatching { aprender(IdentityCard(carne.username, carne.fingerprint, carne.publicKeyB64, carne.username, "")) }
        return carne.username
    }

    /** Enlace de invitación para mandar por WhatsApp: un toque y adentro, sin teclear nada. */
    fun inviteLink(group: TravelGroup): String =
        if (group.inviteCode.isBlank()) "" else "https://travesias.ec/g/" + group.inviteCode.removePrefix("TRV1-")

    /** Acepta tanto el enlace como el código pegado a mano. */
    suspend fun joinByLinkOrCode(texto: String): TravelGroup {
        val limpio = texto.trim()
        val codigo = when {
            limpio.contains("/g/") -> "TRV1-" + limpio.substringAfter("/g/").substringBefore("?").trim('/')
            else -> limpio
        }
        return joinGroupByCode(codigo)
    }

    // ======================================================================
    //  Reparar un chat que no se puede leer
    // ======================================================================

    /**
     * Cuando los mensajes llegan con candado (la llave no abre), esto vuelve a montar el chat
     * desde cero con la llave BUENA del otro y se lo manda otra vez. Es el botón que faltaba
     * para no quedarse mirando un chat muerto.
     */
    suspend fun repairDirectChat(groupId: String): TravelGroup {
        val g = groupDao.getById(groupId)?.toDomain() ?: throw IllegalStateException("Ese chat ya no está.")
        if (!g.direct) throw IllegalStateException("Esto solo se puede hacer en un chat de dos personas.")
        val yo = myId()
        val otro = g.otherMember(yo) ?: throw IllegalStateException("No sé con quién es este chat.")
        val suUsuario = identityDao.get(otro)?.username.orEmpty()
        // Se borra el chat viejo (con su llave equivocada) y se abre uno nuevo con la llave real.
        synchronized(listenerLock) { postListeners.remove(groupId)?.remove() }
        postDao.deleteForGroup(groupId)
        groupDao.deleteById(groupId)
        return startDirectChat(otro, suUsuario)
    }

    // ======================================================================
    //  Diagnóstico: qué funciona y qué no, con el arreglo de cada cosa
    // ======================================================================

    /** Un chequeo del diagnóstico: qué se probó, si salió bien y qué hacer si salió mal. */
    data class Chequeo(val nombre: String, val ok: Boolean, val detalle: String)

    /**
     * Prueba una por una las piezas de la comunidad y devuelve el resultado en cristiano.
     * Existe porque "no se pudo crear la cuenta" no le dice a nadie qué arreglar: aquí se ve
     * exactamente en qué paso se rompe y qué botón hay que tocar.
     */
    suspend fun diagnosticar(): List<Chequeo> {
        val salida = ArrayList<Chequeo>()

        val appOk = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
            FirebaseApp.getApps(context).isNotEmpty()
        }.getOrDefault(false)
        salida += Chequeo(
            "Proyecto de Firebase", appOk,
            if (appOk) "Conectado a " + TransportAuth.PROYECTO
            else "Falta app/google-services.json. Descárgalo de la consola y vuelve a compilar."
        )
        if (!appOk) return salida

        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) {
            salida += Chequeo("Acceso a la comunidad", false, "No se pudo abrir Firebase Authentication.")
            return salida
        }

        // 1. Modo invitado (acceso Anónimo).
        val invitado = runCatching { TransportAuth.invitado(auth) }.getOrNull()
        salida += Chequeo(
            "Acceso Anónimo (para mirar sin cuenta)", invitado?.entro == true,
            if (invitado?.entro == true) "Activado"
            else "Enciéndelo en Authentication → Sign-in method → Anónimo. " + (invitado?.detalle ?: "")
        )

        // 2. Acceso por correo: es el que sostiene las cuentas de los viajeros.
        val yo = account.identity
        val sesion = if (yo != null) runCatching { TransportAuth.entrar(auth, yo) }.getOrNull() else null
        val correoOk = when {
            sesion != null -> sesion.conCuenta
            // Sin cuenta en este teléfono no se puede probar de verdad; se dice qué falta mirar.
            else -> transporte?.conCuenta == true
        }
        salida += Chequeo(
            "Acceso Correo/contraseña (para tener cuenta)", correoOk,
            when {
                correoOk -> "Activado. Tu cuenta es la misma en todos tus teléfonos."
                yo == null -> "Todavía no hay cuenta en este teléfono. Crea una para probarlo."
                else -> TransportAuth.explicar(sesion?.fallo ?: TransportAuth.Fallo.CORREO_APAGADO)
            }
        )

        val fs = firestore ?: runCatching { FirebaseFirestore.getInstance() }.getOrNull()
        if (fs == null) {
            salida += Chequeo("Base de datos", false, "No se pudo abrir Firestore.")
            return salida
        }

        // 3. Leer el catálogo: comprueba que las reglas estén publicadas.
        val lectura = runCatching {
            withTimeoutOrNull(15_000L) { fs.collection("catalog").limit(1).get().await() } != null
        }.getOrDefault(false)
        salida += Chequeo(
            "Leer el catálogo de lugares", lectura,
            if (lectura) "Bien" else "Las reglas de Firestore no dejan leer. Pega el archivo firestore.rules del proyecto en la consola → Firestore → Reglas → Publicar."
        )

        // 4. Escribir algo mío: es lo que falla cuando las reglas no reconocen la cuenta.
        if (yo != null) {
            val escritura = runCatching {
                withTimeoutOrNull(15_000L) {
                    fs.collection(AccountService.COL_COFRES).document(yo.fingerprint)
                        .set(mapOf("ping" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge()).await()
                    true
                } == true
            }.getOrDefault(false)
            salida += Chequeo(
                "Guardar lo mío (cofre de grupos)", escritura,
                if (escritura) "Bien: tus grupos se recuperan al entrar en otro teléfono."
                else "Las reglas no te reconocen como dueño. Suele ser porque el acceso Correo/contraseña está apagado."
            )
            salida += Chequeo(
                "Tu cuenta publicada", !account.hasPending,
                if (!account.hasPending) "Publicada: puedes entrar desde cualquier teléfono."
                else "Creada en este teléfono pero pendiente de subir. Se sube sola en cuanto se pueda."
            )
        }
        return salida
    }

        // ======================================================================
    //  Utilidades
    // ======================================================================

    // ======================================================================
    //  Cuenta: crear, entrar, salir
    // ======================================================================

    /**
     * Crea la cuenta del viajero. A partir de aquí sus mensajes van firmados y puede entrar
     * desde cualquier teléfono con su usuario y su contraseña.
     */
    suspend fun createAccount(
        rawUsername: String,
        password: String,
        interests: List<String> = emptyList(),
        region: String = ""
    ): TravelerProfile {
        // 1. Las llaves se generan aquí mismo, sin red. Esto no puede fallar por Firebase.
        val identidad = account.prepare(rawUsername, password)
        // El sobre se cierra YA: a partir de aquí la contraseña no vuelve a hacer falta, ni
        // siquiera para publicar la cuenta más tarde.
        val sobre = account.sealEnvelope(identidad, password)

        // 2. Abrir Firebase. Si ni siquiera arranca, la cuenta se crea igual en el teléfono.
        val auth = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
            FirebaseAuth.getInstance()
        }.getOrNull()
        val fs = firestore ?: runCatching { FirebaseFirestore.getInstance() }.getOrNull()?.also { firestore = it }

        // 3. Que el nombre no esté cogido. Hace falta una sesión para poder leer, así que si el
        //    acceso Anónimo también está apagado esta comprobación simplemente se salta: no es
        //    motivo para no dejar crear la cuenta.
        if (auth != null && fs != null && auth.currentUser == null) {
            runCatching { TransportAuth.invitado(auth) }
        }
        if (auth?.currentUser != null && fs != null) {
            account.ensureFree(fs, identidad.username)
        }

        // 4. Entrar a Firebase como esa identidad (crea su cuenta de transporte).
        val sesion = if (auth == null) TransportAuth.Sesion(null, false, TransportAuth.Fallo.SIN_PROYECTO)
        else runCatching { TransportAuth.entrar(auth, identidad) }
            .getOrElse { TransportAuth.Sesion(null, false, TransportAuth.clasificar(it), it.message.orEmpty()) }
        transporte = sesion
        authUid = sesion.uid ?: authUid

        // 5. La cuenta se adopta en este teléfono PASE LO QUE PASE. Antes, si Firebase decía que
        //    no, el viajero perdía usuario, contraseña, gustos y región, y tenía que empezar de
        //    cero sin saber por qué. Ahora la cuenta existe, funciona en local, y lo que falte
        //    de publicar se publica solo cuando Firebase deje.
        account.adopt(identidad)
        var avisoPendiente = ""
        if (sesion.conCuenta && fs != null) {
            val subida = runCatching { account.publishSealed(fs, identidad, sobre) }
            if (subida.isFailure) {
                account.rememberPending(identidad, sobre)
                avisoPendiente = "Tu cuenta quedó guardada en este teléfono, pero todavía no se pudo " +
                    "publicar: " + (subida.exceptionOrNull()?.message ?: "error de Firebase") +
                    ". Se reintenta sola al reconectar."
            }
        } else {
            account.rememberPending(identidad, sobre)
            avisoPendiente = TransportAuth.explicar(sesion.fallo)
        }
        _sync.value = if (avisoPendiente.isBlank()) SyncStatus(SyncState.ONLINE)
        else SyncStatus(SyncState.ERROR, avisoPendiente)

        val anterior = profileDao.get()?.toDomain()
        val perfil = (anterior ?: TravelerProfile(createdAt = System.currentTimeMillis())).copy(
            uid = identidad.fingerprint,
            username = identidad.username,
            displayName = anterior?.displayName?.ifBlank { rawUsername.trim() } ?: rawUsername.trim(),
            interests = if (interests.isNotEmpty()) interests else anterior?.interests.orEmpty(),
            region = region.ifBlank { anterior?.region.orEmpty() },
            publicKey = identidad.publicKeyB64,
            createdAt = anterior?.createdAt ?: System.currentTimeMillis()
        )
        profileDao.upsert(perfil.toEntity())
        if (anterior != null && anterior.uid.isNotBlank() && anterior.uid != identidad.fingerprint) {
            adoptIdentity(anterior.uid, identidad.fingerprint)
        }
        runCatching { pushProfile() }
        runCatching { saveVault() }
        attachListeners()
        return perfil
    }

    /** Entrar en este teléfono con una cuenta que ya existe. Devuelve el perfil listo. */
    suspend fun signIn(rawUsername: String, password: String): TravelerProfile {
        val fs = firestore ?: throw IllegalStateException("Necesitas internet la primera vez que entras en este teléfono.")
        // Baja el sobre y lo abre con la contraseña; si no cuadra, ni siquiera abre.
        val identidad = account.unlock(fs, rawUsername, password)
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
            ?: throw IllegalStateException("No se pudo abrir el acceso a la comunidad.")
        val sesion = TransportAuth.entrar(auth, identidad)
        transporte = sesion
        if (!sesion.entro) throw IllegalStateException(TransportAuth.explicar(sesion.fallo))
        authUid = sesion.uid
        // Entró, pero no como su cuenta: la contraseña era correcta (el sobre abrió), así que el
        // problema es de configuración y hay que decirlo tal cual, no culpar al viajero.
        if (!sesion.conCuenta) _sync.value = SyncStatus(SyncState.ERROR, TransportAuth.explicar(sesion.fallo))
        account.adopt(identidad)
        val anterior = profileDao.get()?.toDomain()
        val mismaPersona = anterior != null && (anterior.username == identidad.username || anterior.uid == identidad.fingerprint)
        val perfil = if (mismaPersona) {
            anterior!!.copy(uid = identidad.fingerprint, username = identidad.username, publicKey = identidad.publicKeyB64)
        } else {
            TravelerProfile(
                uid = identidad.fingerprint,
                username = identidad.username,
                displayName = identidad.username,
                publicKey = identidad.publicKeyB64,
                createdAt = identidad.createdAt
            )
        }
        profileDao.upsert(perfil.toEntity())
        if (anterior != null && mismaPersona && anterior.uid != identidad.fingerprint) {
            adoptIdentity(anterior.uid, identidad.fingerprint)
        }
        // Sus grupos vuelven del cofre cifrado.
        runCatching { restoreGroupsFromVault() }
        attachListeners()
        return perfil
    }

    /**
     * Cierra la sesión EN ESTE TELÉFONO y borra lo que había aquí. La cuenta, los grupos y
     * los mensajes siguen existiendo: se recuperan entrando otra vez.
     */
    suspend fun signOutAccount() {
        synchronized(listenerLock) {
            postListeners.values.forEach { it.remove() }
            postListeners.clear()
            commentListeners.values.forEach { it.remove() }
            commentListeners.clear()
            groupsListener?.remove()
            groupsListener = null
        }
        account.signOut()
        // También se sale de la cuenta de transporte: la próxima conexión será anónima.
        runCatching { FirebaseAuth.getInstance().signOut() }
        authUid = null
        postDao.clear()
        groupDao.clear()
        profileDao.clear()
        identityDao.clear()
        conocidos.clear()
    }

    suspend fun changePassword(actual: String, nueva: String) = account.changePassword(firestore, actual, nueva)

    /**
     * Al estrenar cuenta, los grupos y mensajes que ya existían pasan a estar a nombre de la
     * huella nueva. Así no se pierde nada de lo de antes.
     */
    private suspend fun adoptIdentity(anterior: String, huella: String) {
        if (anterior.isBlank() || anterior == huella) return
        postDao.remapAuthor(anterior, huella)
        val fs = firestore
        groupDao.getAll().forEach { g ->
            val miembros = JsonCodec.parseStringList(g.memberUidsJson)
            if (!miembros.contains(anterior) && !miembros.contains(huella)) return@forEach
            groupDao.upsert(
                g.copy(
                    memberUidsJson = JsonCodec.stringList((miembros - anterior + huella).distinct()),
                    ownerUid = if (g.ownerUid == anterior) huella else g.ownerUid
                )
            )
            if (fs != null && !g.id.startsWith(LOCAL_ID_PREFIX)) {
                runCatching {
                    fs.collection("groups").document(g.id)
                        .update("memberUids", FieldValue.arrayUnion(huella)).await()
                }
            }
        }
    }

    // ======================================================================
    //  Cofre de grupos y limpieza del buzón
    // ======================================================================

    /** Guarda en Firebase, CIFRADA, la lista de mis grupos y sus llaves. */
    private suspend fun saveVault() {
        if (account.identity == null) return
        val fs = firestore ?: return
        val grupos = groupDao.getAll()
            .filter { it.secretKey.isNotBlank() && !it.id.startsWith(LOCAL_ID_PREFIX) }
            .map { GroupSecret(it.id, it.secretKey, it.name) }
        account.saveVault(fs, grupos)
    }

    /** Devuelve los grupos al entrar en un teléfono nuevo. */
    private suspend fun restoreGroupsFromVault() {
        val fs = firestore ?: return
        val secretos = account.loadVault(fs)
        secretos.forEach { secreto ->
            val local = groupDao.getById(secreto.groupId)
            if (local == null) {
                val doc = runCatching { fs.collection("groups").document(secreto.groupId).get().await() }.getOrNull()
                    ?: return@forEach
                val g = readGroup(doc, CryptoBox.b64d(secreto.keyB64)) ?: return@forEach
                groupDao.upsert(
                    g.toEntity().copy(
                        name = g.name.ifBlank { secreto.name },
                        secretKey = secreto.keyB64,
                        encrypted = true
                    )
                )
            } else if (local.secretKey.isBlank()) {
                groupDao.upsert(local.copy(secretKey = secreto.keyB64, encrypted = true))
            }
            listenPosts(secreto.groupId)
        }
    }

    /**
     * Vacía del buzón MIS mensajes de más de 30 días. Ya están en los teléfonos del grupo,
     * así que en Firebase solo estorban: menos datos guardados y menos cuota gastada.
     */
    private suspend fun purgeMailbox() {
        val fs = firestore ?: return
        val yo = myId() ?: return
        val corte = System.currentTimeMillis() - SecureEnvelope.MAILBOX_MILLIS
        groupDao.getAll().filterNot { it.id.startsWith(LOCAL_ID_PREFIX) }.take(12).forEach { g ->
            runCatching {
                val viejos = fs.collection("groups").document(g.id).collection("posts")
                    .whereLessThan("createdAt", corte)
                    .limit(60)
                    .get().await()
                viejos.documents
                    .filter { (it.getString("a") ?: it.getString("authorUid")) == yo }
                    .forEach { d -> runCatching { d.reference.delete().await() } }
            }
            // Los adjuntos ocupan mucho más que los mensajes: los míos de más de 30 días se
            // borran del buzón. Quien ya los bajó los tiene en su teléfono para siempre.
            runCatching {
                val bultos = fs.collection("groups").document(g.id).collection(Attachments.COL_BULTOS)
                    .whereLessThan("exp", System.currentTimeMillis())
                    .limit(10).get().await()
                bultos.documents.filter { it.getString("by") == yo }.forEach { b ->
                    val n = (b.getLong("n") ?: 0L).toInt()
                    for (i in 0 until n) {
                        runCatching { b.reference.collection(Attachments.COL_PARTES).document(i.toString()).delete().await() }
                    }
                    runCatching { b.reference.delete().await() }
                }
            }
        }
    }

    fun normalizeUsername(raw: String): String =
        java.text.Normalizer.normalize(raw.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9_]"), "")

    private fun norm(s: String) = s.trim().lowercase()

    private fun newLocalId(): String = LOCAL_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun generateCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        repeat(6) { sb.append(alphabet.random()) }
        return sb.toString()
    }

    private fun previewOf(post: GroupPost): String = when (post.type) {
        PostType.PHOTO -> "📷 Foto"
        PostType.LOCATION -> "📍 " + post.locationLabel.ifBlank { "Ubicación" }
        PostType.PLACE -> "📌 " + post.text
        PostType.ROUTE -> "🧭 " + post.text
        PostType.FILE -> {
            val f = Attachments.Ficha.fromJson(post.payloadJson)
            when {
                f == null -> "📎 Archivo"
                f.esImagen -> "📷 Foto" + if (post.text.isNotBlank()) ": " + post.text else ""
                f.esVideo -> "🎬 Video" + if (f.duracionLegible.isNotBlank()) " (" + f.duracionLegible + ")" else ""
                f.esAudio -> "🎧 Audio" + if (f.duracionLegible.isNotBlank()) " (" + f.duracionLegible + ")" else ""
                else -> "📎 " + f.nombre
            }
        }
        PostType.MESSAGE -> post.text
    }

    // ---------------- Serialización hacia/desde Firestore ----------------

    /**
     * Lo que se sube de un grupo. Ojo con lo que NO va: ni la llave, ni el código de
     * invitación, ni los nombres de los miembros. El nombre y la descripción van cifrados.
     */
    private fun groupToMap(g: TravelGroup): Map<String, Any> {
        val llave = g.secretKey.takeIf { it.isNotBlank() }?.let { CryptoBox.b64d(it) }
            ?: return mapOf(   // grupo antiguo: se mantiene el formato de siempre
                "id" to g.id,
                "name" to g.name,
                "description" to g.description,
                "inviteCode" to g.inviteCode,
                "ownerUid" to g.ownerUid,
                "memberUids" to g.memberUids,
                "memberNames" to g.memberNames,
                "createdAt" to g.createdAt
            )
        val meta = JSONObject().apply {
            put("n", g.name)
            put("d", g.description)
            // Quién es quién dentro del grupo, CIFRADO igual que el nombre. Sirve para que en un
            // chat de dos el otro vea MI nombre y no el suyo propio (antes cada uno veía su
            // propio usuario como título del chat, que no tiene ningún sentido).
            if (g.memberNames.size == g.memberUids.size && g.memberNames.isNotEmpty()) {
                val quienes = JSONObject()
                g.memberUids.forEachIndexed { i, fp -> quienes.put(fp, g.memberNames[i]) }
                put("m", quienes)
            }
        }.toString()
        return mapOf(
            "v" to 1,
            "o" to g.ownerUid,
            "ok" to (account.identity?.publicKeyB64 ?: ""),
            "memberUids" to g.memberUids,
            // Quién fue invitado y todavía no acepta. Es lo que convierte un grupo en una
            // solicitud para el que lo recibe.
            "pend" to g.pendingMembers,
            "dir" to g.direct,
            "c" to CryptoBox.sealText(llave, meta, g.id),
            "createdAt" to g.createdAt
        )
    }

    /** Ojo: [GroupPost.photoPath] no se envía nunca; la foto original se queda en el teléfono. */
    private fun postToMap(p: GroupPost): Map<String, Any> = mapOf(
        "type" to p.type.name,
        "authorUid" to p.authorUid,
        "authorName" to p.authorName,
        "authorThumb" to p.authorThumb,
        "text" to p.text,
        "payloadJson" to p.payloadJson,
        "photoThumb" to p.photoThumb,
        "latitude" to p.latitude,
        "longitude" to p.longitude,
        "locationLabel" to p.locationLabel,
        "createdAt" to p.createdAt
    )

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        else -> emptyList()
    }

    private fun readGroup(doc: DocumentSnapshot, llave: ByteArray?): TravelGroup? {
        if (!doc.exists()) return null
        if ((doc.getLong("v") ?: 0L) >= 1L) {
            // Grupo cifrado: sin la llave solo se ve que existe.
            val meta = llave?.let { k -> doc.getString("c")?.let { CryptoBox.openText(k, it, doc.id) } }
            val o = meta?.let { runCatching { JSONObject(it) }.getOrNull() }
            val miembros = strings(doc.get("memberUids"))
            val quienes = o?.optJSONObject("m")
            val nombres = miembros.map { fp -> quienes?.optString(fp, "").orEmpty() }
            // En un chat de dos, el título es SIEMPRE el del otro, se mire desde donde se mire.
            val crudo = o?.optString("n", "").orEmpty()
            val titulo = if (crudo.startsWith(DIRECT_CHAT_PREFIX) && quienes != null) {
                val yo = myId()
                val otro = miembros.firstOrNull { it != yo }?.let { quienes.optString(it, "") }.orEmpty()
                if (otro.isNotBlank()) DIRECT_CHAT_PREFIX + otro else crudo
            } else crudo
            return TravelGroup(
                id = doc.id,
                name = titulo,
                description = o?.optString("d", "").orEmpty(),
                inviteCode = llave?.let { SecureEnvelope.inviteCode(doc.id, CryptoBox.b64(it)) }.orEmpty(),
                ownerUid = doc.getString("o").orEmpty(),
                memberUids = miembros,
                // Se mantiene la MISMA longitud que memberUids: es lo que empareja huella con
                // nombre cuando el grupo se vuelva a subir.
                memberNames = if (nombres.any { it.isNotBlank() }) nombres else emptyList(),
                createdAt = doc.getLong("createdAt") ?: 0L,
                pendingSync = false,
                secretKey = llave?.let { CryptoBox.b64(it) }.orEmpty(),
                encrypted = true,
                pendingMembers = strings(doc.get("pend")),
                invitedBy = doc.getString("o").orEmpty(),
                direct = doc.getBoolean("dir") ?: crudo.startsWith(DIRECT_CHAT_PREFIX)
            )
        }
        return TravelGroup(
            id = doc.id,
            name = doc.getString("name") ?: "",
            description = doc.getString("description") ?: "",
            inviteCode = doc.getString("inviteCode") ?: "",
            ownerUid = doc.getString("ownerUid") ?: "",
            memberUids = strings(doc.get("memberUids")),
            memberNames = strings(doc.get("memberNames")),
            createdAt = doc.getLong("createdAt") ?: 0L,
            pendingSync = false
        )
    }

    private fun readBuddy(doc: DocumentSnapshot): TravelerProfile? {
        if (!doc.exists()) return null
        val uid = doc.getString("fp") ?: doc.getString("uid") ?: doc.id
        if (uid.isBlank()) return null
        return TravelerProfile(
            uid = uid,
            username = doc.getString("username") ?: "",
            displayName = doc.getString("displayName") ?: "",
            bio = doc.getString("bio") ?: "",
            interests = strings(doc.get("interests")),
            region = doc.getString("region") ?: "",
            routeOrigin = doc.getString("routeOrigin") ?: "",
            routeDestination = doc.getString("routeDestination") ?: "",
            travelMonth = doc.getString("travelMonth") ?: "",
            avatarThumb = doc.getString("av").orEmpty().ifBlank { doc.getString("avatarThumb") ?: "" },
            createdAt = doc.getLong("createdAt") ?: doc.getLong("vistoEn") ?: 0L,
            travelTags = strings(doc.get("travelTags"))
        )
    }

    companion object {
        const val LOCAL_ID_PREFIX = "local-"
        const val DIRECT_CHAT_PREFIX = "\uD83D\uDC64 "

        /** Etiquetas que se buscan entre sí (quien ofrece aventón con quien lo busca, etc.). */
        private val COMPLEMENTARY_TAGS = mapOf(
            "Busco aventón" to "Ofrezco aventón",
            "Ofrezco aventón" to "Busco aventón"
        )
    }
}

/** Convierte una Task de Google Play Services en función suspendida (sin dependencias extra). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        if (e != null) cont.resumeWithException(e)
        else if (task.isCanceled) cont.cancel()
        else @Suppress("UNCHECKED_CAST") cont.resume(task.result as T)
    }
}

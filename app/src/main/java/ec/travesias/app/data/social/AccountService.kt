package ec.travesias.app.data.social

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * La cuenta del viajero: crearla, entrar en cualquier teléfono y llevarse sus grupos.
 *
 * CÓMO FUNCIONA (y por qué no cuesta nada):
 *  1. Al registrarse se crea un par de llaves en el teléfono. La pública es su carné; la
 *     privada es su firma y no sale de aquí.
 *  2. La contraseña NO se envía a ningún servidor. Con ella se deriva una llave (PBKDF2,
 *     150.000 vueltas) que mete la llave privada en un "sobre" cerrado.
 *  3. A Firebase sube un único documento diminuto: usuario, llave pública y el sobre.
 *     Firebase no puede abrirlo: no tiene la contraseña. Es un casillero, no un dueño.
 *  4. Entrar en otro teléfono = bajar el sobre + escribir la contraseña = misma identidad,
 *     y con ella se abre el cofre donde están las llaves de sus grupos.
 *
 * LA CUENTA DE TRANSPORTE (esto es lo que hace que las reglas de Firestore sirvan):
 * Firebase necesita saber quién escribe para poder bloquear a los demás. Antes se usaba
 * acceso anónimo, que cambia en cada teléfono y no se puede atar a nadie. Ahora, de la
 * propia llave privada se derivan un correo interno (huella@travesias.app) y una contraseña
 * larguísima; con ellos la app entra al acceso por correo de Firebase, que es GRATIS e
 * ilimitado (lo que se cobra es el SMS, que aquí no se usa). Resultado: el mismo viajero
 * tiene el MISMO identificador en todos sus teléfonos, y las reglas pueden comprobar de
 * verdad que cada quien solo toca lo suyo.
 */
class AccountService(context: Context) {

    private val vault = IdentityVault(context)

    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Identidad en memoria, o null si no hay sesión en este teléfono. */
    @Volatile
    var identity: TravelerIdentity? = null
        private set

    val isSignedIn: Boolean get() = identity != null

    companion object {
        const val COL_IDENTIDADES = "identities"
        const val COL_COFRES = "vaults"
        /** Huella del correo -> @usuario. Nunca guarda el correo en claro. */
        const val COL_POR_CORREO = "porCorreo"
        const val MIN_CLAVE = 8

        /** Dominio interno de las cuentas de transporte. No se manda correo a nadie. */
        const val DOMINIO = "travesias.app"

        /** Reglas del nombre de usuario: letras, números, punto y guion bajo. */
        fun normalizeUsername(raw: String): String =
            raw.trim().removePrefix("@").lowercase().replace(Regex("[^a-z0-9_.]"), "")

        // ==================================================================
        //  El @usuario y el correo NO son lo mismo, y nunca se mezclan
        // ==================================================================
        //  El @usuario es el nombre en la comunidad: obligatorio, y es con lo que la gente
        //  te encuentra y te llama. El correo es OPCIONAL y sirve para una sola cosa:
        //  recordar cuál era tu @usuario si se te olvidó.
        //
        //  El lío venía de que los dos llevan arroba y había un único campo "Correo o
        //  @usuario" que adivinaba mirando si había una '@'. Ahora son campos distintos y,
        //  además, un @usuario NO PUEDE contener arroba (normalizeUsername la quita), así
        //  que jamás se puede confundir uno con otro.

        /** true si lo escrito tiene pinta de correo. Sirve para avisar, no para adivinar. */
        fun pareceCorreo(raw: String): Boolean {
            val t = raw.trim()
            return t.contains("@") && t.substringAfter("@").contains(".")
        }

        /**
         * El correo NO se publica: se publica su huella. Así, quien conoce el correo puede
         * preguntar "¿cuál era mi usuario?", pero nadie puede bajarse una lista de correos
         * de la base de datos. La sal fija hace que la huella sea distinta a cualquier otra
         * que se calcule por ahí con el mismo correo.
         */
        fun huellaDeCorreo(correo: String): String {
            val limpio = correo.trim().lowercase()
            if (limpio.isBlank()) return ""
            return CryptoBox.base32(
                CryptoBox.sha256(("travesias-correo-v1:" + limpio).toByteArray(Charsets.UTF_8))
            ).lowercase().take(32)
        }
    }

    /** Se llama al arrancar: si ya hay cuenta en este teléfono, la deja lista. */
    fun restoreLocal() {
        val guardada = runCatching { vault.cargar() }.getOrNull()
        identity = guardada
        _state.value = if (guardada != null) AccountState.SignedIn(guardada) else AccountState.SignedOut
    }

    // ======================================================================
    //  Crear cuenta
    // ======================================================================

    /** Paso 1: las llaves, aquí mismo, sin red. */
    fun prepare(rawUsername: String, password: String): TravelerIdentity {
        val usuario = normalizeUsername(rawUsername)
        require(usuario.length in 3..20) { "El usuario debe tener entre 3 y 20 letras o números." }
        require(password.length >= MIN_CLAVE) { "La contraseña debe tener al menos $MIN_CLAVE caracteres." }

        val par = CryptoBox.newKeyPair()
        val publicas = CryptoBox.publicBytes(par.public)
        return TravelerIdentity(
            username = usuario,
            fingerprint = CryptoBox.fingerprint(publicas),
            publicKeyB64 = CryptoBox.b64(publicas),
            privateKeyBytes = CryptoBox.privateBytes(par.private),
            createdAt = System.currentTimeMillis()
        )
    }

    /** Paso 2: comprobar que ese nombre está libre. */
    suspend fun ensureFree(firestore: FirebaseFirestore?, username: String) = withContext(Dispatchers.IO) {
        val fs = firestore ?: throw IllegalStateException("Necesitas internet para crear tu cuenta.")
        val existente = withTimeoutOrNull(15_000L) {
            runCatching { fs.collection(COL_IDENTIDADES).document(username).get().await() }.getOrNull()
        }
        if (existente != null && existente.exists()) {
            throw IllegalStateException("El usuario \"$username\" ya existe. Si es tuyo, entra con tu contraseña.")
        }
    }

    /**
     * El "sobre": la llave privada cerrada con la contraseña. Se calcula aquí, en el teléfono,
     * y a partir de este momento la contraseña ya no hace falta para nada más. Por eso el sobre
     * se puede guardar y subir más tarde si en este instante Firebase no deja escribir.
     */
    fun sealEnvelope(identidad: TravelerIdentity, password: String): String = CryptoBox.b64(
        CryptoBox.seal(
            CryptoBox.deriveKey(password, CryptoBox.saltFor("cuenta", identidad.username)),
            identidad.privateKeyBytes,
            identidad.username.toByteArray()
        )
    )

    /** El documento que va a Firebase. Firmado por la propia llave: nadie lo puede falsificar. */
    private fun identityDoc(identidad: TravelerIdentity, sobre: String): Map<String, Any> {
        val cuerpo = identidad.username + "|" + identidad.publicKeyB64 + "|" + identidad.createdAt
        return mapOf(
            "u" to identidad.username,
            "k" to identidad.publicKeyB64,
            "w" to sobre,
            "it" to CryptoBox.KDF_ITERATIONS,
            "fp" to identidad.fingerprint,
            "sig" to identidad.signText(cuerpo),
            "createdAt" to identidad.createdAt,
            "v" to 1
        )
    }

    /**
     * Paso 3: subir el sobre cerrado. Es lo ÚNICO que Firebase guarda de la identidad.
     *
     * Se le pasa el sobre YA calculado a propósito: cerrarlo cuesta 150.000 vueltas de PBKDF2
     * y en un teléfono de gama baja eso son un par de segundos. Haciéndolo una sola vez, el
     * registro se siente la mitad de lento.
     */
    suspend fun publishSealed(
        firestore: FirebaseFirestore?,
        identidad: TravelerIdentity,
        sobre: String
    ) = withContext(Dispatchers.IO) {
        val fs = firestore ?: throw IllegalStateException("Necesitas internet para crear tu cuenta.")
        runCatching { fs.collection(COL_IDENTIDADES).document(identidad.username).set(identityDoc(identidad, sobre)).await() }
            .getOrElse { throw IllegalStateException("No se pudo crear la cuenta: " + friendly(it)) }
        Unit
    }

    /** Igual que [publishSealed], pero cerrando el sobre aquí mismo. */
    suspend fun publish(
        firestore: FirebaseFirestore?,
        identidad: TravelerIdentity,
        password: String
    ) = publishSealed(firestore, identidad, sealEnvelope(identidad, password))

    // ======================================================================
    //  Cuenta creada sin poder publicarla todavía
    // ======================================================================
    //  Si al registrarse Firebase no deja escribir (falta encender el acceso por correo en la
    //  consola, o no hay señal), antes se perdía todo y el viajero se quedaba mirando un error.
    //  Ahora la cuenta se crea igual en el teléfono y el sobre queda esperando aquí: en cuanto
    //  se pueda entrar de verdad, se publica sola. La contraseña NO se guarda en ningún momento;
    //  lo que se guarda es el sobre, que sin ella no se abre.

    /** Deja el sobre esperando a que se pueda publicar. */
    fun rememberPending(identidad: TravelerIdentity, sobre: String) {
        vault.guardarPendiente(identidad.username, sobre)
    }

    /** true si hay una cuenta creada en este teléfono que todavía no llegó a Firebase. */
    val hasPending: Boolean get() = vault.sobrePendiente() != null

    /**
     * Sube el sobre que quedó pendiente. Se llama sola al conectar. Devuelve true si a partir
     * de ahora la cuenta ya existe en Firebase.
     */
    suspend fun publishPending(firestore: FirebaseFirestore?): Boolean = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext false
        val identidad = identity ?: return@withContext false
        val pendiente = vault.sobrePendiente() ?: return@withContext false
        val (usuario, sobre) = pendiente
        if (usuario != identidad.username) { vault.olvidarPendiente(); return@withContext false }
        val ok = runCatching {
            fs.collection(COL_IDENTIDADES).document(usuario).set(identityDoc(identidad, sobre)).await()
        }.isSuccess
        if (ok) vault.olvidarPendiente()
        ok
    }

    // ======================================================================
    //  Correo de recuperación: solo para recordar el @usuario
    // ======================================================================

    /**
     * Deja apuntado "esta huella de correo pertenece a este @usuario".
     * Lo que se guarda es la huella, nunca el correo.
     */
    suspend fun publicarCorreo(
        firestore: FirebaseFirestore?,
        identidad: TravelerIdentity,
        correo: String
    ): Boolean = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext false
        val huella = huellaDeCorreo(correo)
        if (huella.isBlank()) return@withContext false
        withTimeoutOrNull(15_000L) {
            runCatching {
                fs.collection(COL_POR_CORREO).document(huella).set(
                    mapOf("u" to identidad.username, "fp" to identidad.fingerprint)
                ).await()
            }.isSuccess
        } == true
    }

    /** Quita la anotación de un correo (al cambiarlo o al borrarlo del perfil). */
    suspend fun olvidarCorreo(firestore: FirebaseFirestore?, correo: String) = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext
        val huella = huellaDeCorreo(correo)
        if (huella.isBlank()) return@withContext
        withTimeoutOrNull(12_000L) {
            runCatching { fs.collection(COL_POR_CORREO).document(huella).delete().await() }
        }
        Unit
    }

    /**
     * "¿Cuál era mi usuario?" a partir del correo. Devuelve el @usuario o null.
     * No dice nada más: ni la llave, ni la huella, ni si ese correo existe en otra cuenta.
     */
    suspend fun usuarioPorCorreo(firestore: FirebaseFirestore?, correo: String): String? =
        withContext(Dispatchers.IO) {
            val fs = firestore ?: return@withContext null
            val huella = huellaDeCorreo(correo)
            if (huella.isBlank()) return@withContext null
            val doc = withTimeoutOrNull(15_000L) {
                runCatching { fs.collection(COL_POR_CORREO).document(huella).get().await() }.getOrNull()
            } ?: return@withContext null
            if (!doc.exists()) return@withContext null
            doc.getString("u")?.takeIf { it.isNotBlank() }
        }

    // ======================================================================
    //  Buscar a alguien por su nombre de usuario
    // ======================================================================

    /**
     * Devuelve la huella y la llave pública de un viajero a partir de su @usuario.
     *
     * Las reglas dejan LEER un documento concreto de `identities` pero NO listar la colección:
     * o sea, quien sepa el nombre exacto puede escribirle (como quien sabe tu número de
     * teléfono), pero nadie se puede bajar el padrón de usuarios de la app.
     */
    suspend fun lookup(firestore: FirebaseFirestore?, rawUsername: String): PublicCard? = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext null
        val usuario = normalizeUsername(rawUsername)
        if (usuario.length < 3) return@withContext null
        val doc = withTimeoutOrNull(15_000L) {
            runCatching { fs.collection(COL_IDENTIDADES).document(usuario).get().await() }.getOrNull()
        } ?: return@withContext null
        if (!doc.exists()) return@withContext null
        val publica = doc.getString("k").orEmpty()
        val huella = doc.getString("fp").orEmpty().ifBlank {
            runCatching { CryptoBox.fingerprint(CryptoBox.b64d(publica)) }.getOrDefault("")
        }
        if (publica.isBlank() || huella.isBlank()) return@withContext null
        // Que la huella corresponda de verdad a esa llave: si no, alguien manipuló el documento.
        val calculada = runCatching { CryptoBox.fingerprint(CryptoBox.b64d(publica)) }.getOrDefault("")
        if (calculada != huella) return@withContext null
        PublicCard(usuario, huella, publica)
    }

    // ======================================================================
    //  Entrar
    // ======================================================================

    /**
     * Baja el sobre de esa cuenta y lo abre con la contraseña. Si la contraseña no es la
     * correcta, el sobre sencillamente no abre: no hay nada que adivinar en el servidor.
     */
    suspend fun unlock(
        firestore: FirebaseFirestore?,
        rawUsername: String,
        password: String
    ): TravelerIdentity = withContext(Dispatchers.IO) {
        val usuario = normalizeUsername(rawUsername)
        require(usuario.isNotBlank()) { "Escribe tu nombre de usuario." }
        require(password.isNotBlank()) { "Escribe tu contraseña." }
        val fs = firestore ?: throw IllegalStateException("Necesitas internet la primera vez que entras en este teléfono.")

        val doc = withTimeoutOrNull(20_000L) {
            runCatching { fs.collection(COL_IDENTIDADES).document(usuario).get().await() }.getOrNull()
        } ?: throw IllegalStateException("No se pudo consultar la cuenta. Revisa tu conexión.")
        if (!doc.exists()) throw IllegalStateException("No existe la cuenta \"$usuario\".")

        val publica = doc.getString("k").orEmpty()
        val sobre = doc.getString("w").orEmpty()
        val vueltas = (doc.getLong("it") ?: CryptoBox.KDF_ITERATIONS.toLong()).toInt()
        if (publica.isBlank() || sobre.isBlank()) throw IllegalStateException("Esa cuenta está incompleta. Crea una nueva.")

        val llave = CryptoBox.deriveKey(password, CryptoBox.saltFor("cuenta", usuario), vueltas)
        val privadas = CryptoBox.open(llave, CryptoBox.b64d(sobre), usuario.toByteArray())
            ?: throw IllegalStateException("Contraseña incorrecta.")

        val identidad = TravelerIdentity(
            username = usuario,
            fingerprint = doc.getString("fp").orEmpty().ifBlank { CryptoBox.fingerprint(CryptoBox.b64d(publica)) },
            publicKeyB64 = publica,
            privateKeyBytes = privadas,
            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
        )
        // Que la llave privada corresponda de verdad a esa pública.
        val prueba = "prueba-" + System.currentTimeMillis()
        if (!CryptoBox.verify(publica, prueba, identidad.signText(prueba))) {
            throw IllegalStateException("La cuenta no se pudo verificar. Vuelve a intentarlo.")
        }
        // Si esta cuenta ya está en Firebase, no queda nada pendiente de subir.
        vault.olvidarPendiente()
        identidad
    }

    /** Deja la identidad guardada en este teléfono y la marca como sesión activa. */
    fun adopt(identidad: TravelerIdentity) {
        vault.guardar(identidad)
        identity = identidad
        _state.value = AccountState.SignedIn(identidad)
    }

    /** Cierra sesión en este teléfono. La cuenta y los grupos siguen existiendo. */
    fun signOut() {
        vault.borrar()
        identity = null
        _state.value = AccountState.SignedOut
    }

    /** Cambiar la contraseña = volver a envolver la MISMA llave. La identidad no cambia. */
    suspend fun changePassword(
        firestore: FirebaseFirestore?,
        actual: String,
        nueva: String
    ): Unit = withContext(Dispatchers.IO) {
        val yo = identity ?: throw IllegalStateException("Primero entra con tu cuenta.")
        require(nueva.length >= MIN_CLAVE) { "La contraseña nueva debe tener al menos $MIN_CLAVE caracteres." }
        val fs = firestore ?: throw IllegalStateException("Necesitas internet para cambiar la contraseña.")

        val doc = runCatching { fs.collection(COL_IDENTIDADES).document(yo.username).get().await() }.getOrNull()
            ?: throw IllegalStateException("No se pudo leer tu cuenta.")
        val vueltas = (doc.getLong("it") ?: CryptoBox.KDF_ITERATIONS.toLong()).toInt()
        CryptoBox.open(
            CryptoBox.deriveKey(actual, CryptoBox.saltFor("cuenta", yo.username), vueltas),
            CryptoBox.b64d(doc.getString("w").orEmpty()),
            yo.username.toByteArray()
        ) ?: throw IllegalStateException("La contraseña actual no es correcta.")

        val nuevoSobre = CryptoBox.b64(
            CryptoBox.seal(
                CryptoBox.deriveKey(nueva, CryptoBox.saltFor("cuenta", yo.username)),
                yo.privateKeyBytes,
                yo.username.toByteArray()
            )
        )
        fs.collection(COL_IDENTIDADES).document(yo.username)
            .update(mapOf("w" to nuevoSobre, "it" to CryptoBox.KDF_ITERATIONS)).await()
    }

    // ======================================================================
    //  Cofre: las llaves de mis grupos, cifradas
    // ======================================================================
    //  UN documento por viajero con la lista de sus grupos y la llave de cada uno, cifrado
    //  con una llave que sale de su llave privada. Firebase no puede abrirlo, pero al entrar
    //  en un teléfono nuevo el viajero recupera todos sus grupos de una.

    suspend fun saveVault(firestore: FirebaseFirestore?, grupos: List<GroupSecret>) = withContext(Dispatchers.IO) {
        val yo = identity ?: return@withContext
        val fs = firestore ?: return@withContext
        val arr = JSONArray()
        grupos.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.groupId)
                put("k", g.keyB64)
                put("n", g.name)
            })
        }
        val sobre = CryptoBox.sealText(yo.vaultKey(), arr.toString(), yo.fingerprint)
        runCatching {
            fs.collection(COL_COFRES).document(yo.fingerprint)
                .set(mapOf("b" to sobre, "at" to System.currentTimeMillis())).await()
        }
        Unit
    }

    suspend fun loadVault(firestore: FirebaseFirestore?): List<GroupSecret> = withContext(Dispatchers.IO) {
        val yo = identity ?: return@withContext emptyList()
        val fs = firestore ?: return@withContext emptyList()
        val doc = withTimeoutOrNull(20_000L) {
            runCatching { fs.collection(COL_COFRES).document(yo.fingerprint).get().await() }.getOrNull()
        } ?: return@withContext emptyList()
        val sobre = doc.getString("b").orEmpty()
        if (sobre.isBlank()) return@withContext emptyList()
        val texto = CryptoBox.openText(yo.vaultKey(), sobre, yo.fingerprint) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(texto) }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val k = o.optString("k", "")
            if (id.isBlank() || k.isBlank()) null else GroupSecret(id, k, o.optString("n", ""))
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("PERMISSION_DENIED", true) -> "el proyecto de Firebase no permite escribir aquí (revisa las reglas)."
            m.contains("Unable to resolve host") || m.contains("UNAVAILABLE") -> "sin conexión."
            else -> m.take(120)
        }
    }
}

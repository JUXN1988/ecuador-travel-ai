package ec.travesias.app.data.social

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * La **puerta de entrada a Firebase**: convertir la llave del viajero en una sesión que las
 * reglas de Firestore sepan reconocer.
 *
 * POR QUÉ EXISTE ESTE ARCHIVO
 * Antes esto eran cuatro líneas dentro de CommunityRepository con `runCatching{}.getOrNull()`.
 * Eso tenía un problema serio: **se tragaba el motivo real del fallo**. Daba igual que faltara
 * activar un botón en la consola, que el teléfono no tuviera señal o que el nombre estuviera
 * cogido: la app siempre decía lo mismo y la cuenta no se creaba. Ahora cada fallo se clasifica
 * y se cuenta tal cual es, y lo que se puede salvar se salva.
 *
 * CÓMO ENTRA LA APP
 * Nadie escribe un correo. De la llave privada del viajero se derivan un correo interno
 * (huella@travesias.app, al que jamás se manda nada) y una contraseña de 32 bytes. Con eso la
 * app usa el acceso **Correo/contraseña** de Firebase, que es gratis e ilimitado. La gracia es
 * que el identificador resultante es el MISMO en todos sus teléfonos, y por eso las reglas
 * pueden exigir que cada quien solo toque lo suyo.
 *
 * SI ESE ACCESO ESTÁ APAGADO EN LA CONSOLA
 * La app ya no se queda tiesa: entra como invitada (anónimo), avisa exactamente qué falta
 * activar, y guarda la cuenta en el teléfono con su sobre ya cerrado. En cuanto se active el
 * acceso, la cuenta se publica sola sin volver a pedir la contraseña.
 */
object TransportAuth {

    /** Por qué no se pudo entrar con la cuenta propia. */
    enum class Fallo {
        NINGUNO,

        /** En la consola de Firebase falta activar Authentication -> Correo/contraseña. */
        CORREO_APAGADO,

        /** Falta activar Authentication -> Anónimo (y por eso ni siquiera hay modo invitado). */
        ANONIMO_APAGADO,

        /** El teléfono no llegó a los servidores de Firebase. */
        SIN_RED,

        /**
         * Ese correo interno existe pero la contraseña derivada no abre. Solo puede pasar si dos
         * llaves distintas dieran la misma huella (prácticamente imposible) o si alguien tocó la
         * cuenta a mano en la consola.
         */
        CLAVE_NO_CUADRA,

        /** Falta google-services.json o el proyecto no está bien configurado. */
        SIN_PROYECTO,

        DESCONOCIDO
    }

    /**
     * Resultado de entrar.
     *
     * @param uid identificador que dio Firebase, o null si no se pudo entrar de ninguna forma.
     * @param conCuenta true si entró con SU cuenta (el correo derivado de su llave). Solo en ese
     *   caso las reglas de Firestore lo reconocen como dueño de lo suyo.
     * @param fallo por qué no se pudo entrar con la cuenta propia (aunque haya entrado de invitado).
     */
    data class Sesion(
        val uid: String?,
        val conCuenta: Boolean,
        val fallo: Fallo = Fallo.NINGUNO,
        val detalle: String = ""
    ) {
        val entro: Boolean get() = !uid.isNullOrBlank()

        /** true si el problema se arregla en la consola de Firebase, no en el teléfono. */
        val esDeConsola: Boolean get() = fallo == Fallo.CORREO_APAGADO || fallo == Fallo.ANONIMO_APAGADO
    }

    /** Tiempo máximo por intento. Más que esto es que no hay red de verdad. */
    private const val ESPERA_MS = 25_000L

    /** Id del proyecto de Firebase, para poder nombrarlo en los avisos. */
    const val PROYECTO = "ec-travesias-app"

    /** Enlace directo a la pantalla donde se encienden los accesos. */
    const val URL_CONSOLA = "https://console.firebase.google.com/project/ec-travesias-app/authentication/providers"

    // ======================================================================
    //  Entrar con la cuenta del viajero
    // ======================================================================

    /**
     * Entra a Firebase como esta identidad. Si la cuenta de transporte aún no existe (primer
     * teléfono) la crea. Si el acceso por correo está apagado en la consola, entra como invitada
     * y lo dice, en vez de dejar al viajero sin nada.
     */
    suspend fun entrar(auth: FirebaseAuth, identidad: TravelerIdentity): Sesion {
        val correo = identidad.firebaseEmail()
        val clave = identidad.firebasePassword()

        // Ya estamos dentro con esta misma cuenta: nada que hacer.
        auth.currentUser?.let { actual ->
            if (actual.email.equals(correo, ignoreCase = true)) {
                return Sesion(actual.uid, conCuenta = true)
            }
        }

        // 1) Intentar entrar.
        val entrada = intentar { auth.signInWithEmailAndPassword(correo, clave).await().user?.uid }
        entrada.uid?.let { return Sesion(it, conCuenta = true) }

        val falloEntrada = clasificar(entrada.error)
        // La cuenta de transporte todavía no existe: hay que crearla. Es el caso normal la
        // primera vez que alguien se registra.
        val hayQueCrear = falloEntrada == Fallo.DESCONOCIDO ||
            falloEntrada == Fallo.CLAVE_NO_CUADRA ||
            esUsuarioInexistente(entrada.error)

        if (hayQueCrear) {
            val creada = intentar { auth.createUserWithEmailAndPassword(correo, clave).await().user?.uid }
            creada.uid?.let { return Sesion(it, conCuenta = true) }

            // Alguien (o este mismo teléfono en otro intento) la creó entre medias: reintentar.
            if (esCuentaYaExistente(creada.error)) {
                val segunda = intentar { auth.signInWithEmailAndPassword(correo, clave).await().user?.uid }
                segunda.uid?.let { return Sesion(it, conCuenta = true) }
                return conInvitado(auth, Fallo.CLAVE_NO_CUADRA, texto(segunda.error))
            }
            return conInvitado(auth, clasificar(creada.error), texto(creada.error))
        }

        return conInvitado(auth, falloEntrada, texto(entrada.error))
    }

    /** Entrar como invitado (anónimo). Se usa mientras el viajero no tiene cuenta propia. */
    suspend fun invitado(auth: FirebaseAuth): Sesion {
        auth.currentUser?.let { if (it.isAnonymous) return Sesion(it.uid, conCuenta = false) }
        val r = intentar { auth.signInAnonymously().await().user?.uid }
        r.uid?.let { return Sesion(it, conCuenta = false) }
        val f = clasificar(r.error)
        return Sesion(
            null, conCuenta = false,
            fallo = if (f == Fallo.CORREO_APAGADO) Fallo.ANONIMO_APAGADO else f,
            detalle = texto(r.error)
        )
    }

    /**
     * No se pudo entrar con la cuenta propia: se entra de invitado para que la app siga viva
     * (el catálogo y todo lo local siguen funcionando), pero se conserva el motivo real para
     * poder contárselo al viajero.
     */
    private suspend fun conInvitado(auth: FirebaseAuth, fallo: Fallo, detalle: String): Sesion {
        if (fallo == Fallo.SIN_RED) return Sesion(null, false, fallo, detalle)
        val inv = invitado(auth)
        return Sesion(inv.uid, conCuenta = false, fallo = fallo, detalle = detalle)
    }

    // ======================================================================
    //  Traducir los errores de Firebase
    // ======================================================================

    private class Intento(val uid: String?, val error: Throwable?)

    private suspend fun intentar(bloque: suspend () -> String?): Intento = try {
        Intento(withTimeout(ESPERA_MS) { bloque() }, null)
    } catch (e: TimeoutCancellationException) {
        Intento(null, e)
    } catch (e: Throwable) {
        Intento(null, e)
    }

    fun clasificar(e: Throwable?): Fallo {
        if (e == null) return Fallo.DESCONOCIDO
        if (e is TimeoutCancellationException) return Fallo.SIN_RED
        val codigo = (e as? FirebaseAuthException)?.errorCode.orEmpty().uppercase()
        val msg = (e.message ?: "").uppercase()
        val clase = e.javaClass.simpleName
        return when {
            codigo.contains("OPERATION_NOT_ALLOWED") || msg.contains("OPERATION_NOT_ALLOWED") ||
                msg.contains("CONFIGURATION_NOT_FOUND") || msg.contains("ADMIN_ONLY_OPERATION") ||
                msg.contains("PASSWORD_LOGIN_DISABLED") -> Fallo.CORREO_APAGADO

            msg.contains("API KEY NOT VALID") || msg.contains("API_KEY_INVALID") ||
                msg.contains("PROJECT_NOT_FOUND") || msg.contains("NO SUCH APP") -> Fallo.SIN_PROYECTO

            clase == "FirebaseNetworkException" || msg.contains("NETWORK") || msg.contains("UNABLE TO RESOLVE HOST") ||
                msg.contains("TIMEOUT") || msg.contains("UNAVAILABLE") || msg.contains("DEADLINE") -> Fallo.SIN_RED

            codigo.contains("WRONG_PASSWORD") || msg.contains("WRONG_PASSWORD") ||
                msg.contains("INVALID_LOGIN_CREDENTIALS") -> Fallo.CLAVE_NO_CUADRA

            else -> Fallo.DESCONOCIDO
        }
    }

    /** El correo interno todavía no está registrado: toca crearlo. */
    private fun esUsuarioInexistente(e: Throwable?): Boolean {
        val m = (e?.message ?: "").uppercase() + " " + ((e as? FirebaseAuthException)?.errorCode.orEmpty().uppercase())
        return m.contains("USER_NOT_FOUND") || m.contains("EMAIL_NOT_FOUND") ||
            m.contains("NO USER RECORD") || m.contains("INVALID_LOGIN_CREDENTIALS")
    }

    private fun esCuentaYaExistente(e: Throwable?): Boolean {
        val m = (e?.message ?: "").uppercase() + " " + ((e as? FirebaseAuthException)?.errorCode.orEmpty().uppercase())
        return m.contains("EMAIL_ALREADY_IN_USE") || m.contains("EMAIL_EXISTS") ||
            e?.javaClass?.simpleName == "FirebaseAuthUserCollisionException"
    }

    private fun texto(e: Throwable?): String = (e?.message ?: "").take(160)

    // ======================================================================
    //  Lo que se le enseña al viajero
    // ======================================================================

    /** Explicación en cristiano, con el arreglo concreto. */
    fun explicar(fallo: Fallo): String = when (fallo) {
        Fallo.NINGUNO -> ""
        Fallo.CORREO_APAGADO ->
            "Falta encender el acceso por correo en tu proyecto de Firebase. " +
                "Entra a console.firebase.google.com -> proyecto \"" + PROYECTO + "\" -> Authentication -> " +
                "Sign-in method -> Correo electrónico/contraseña -> Habilitar. Es gratis y sin límite " +
                "(lo que se cobra es el SMS, que esta app no usa). Mientras tanto tu cuenta queda " +
                "guardada en este teléfono y se publica sola en cuanto lo actives."
        Fallo.ANONIMO_APAGADO ->
            "Falta encender el acceso Anónimo en Firebase -> Authentication -> Sign-in method. " +
                "Es lo que deja mirar el catálogo a quien todavía no tiene cuenta."
        Fallo.SIN_RED ->
            "No se pudo llegar a los servidores. Revisa tu conexión: todo lo que hagas se guarda " +
                "aquí y se envía al reconectar."
        Fallo.CLAVE_NO_CUADRA ->
            "Ese identificador ya existe en Firebase con otra llave. Elige otro nombre de usuario " +
                "o borra esa cuenta desde la consola (Authentication -> Users)."
        Fallo.SIN_PROYECTO ->
            "La app no encuentra bien el proyecto de Firebase. Revisa que app/google-services.json " +
                "sea el del proyecto \"" + PROYECTO + "\" y vuelve a compilar."
        Fallo.DESCONOCIDO ->
            "No se pudo activar la cuenta en Firebase. Abre Comunidad -> Diagnóstico para ver qué falla."
    }
}

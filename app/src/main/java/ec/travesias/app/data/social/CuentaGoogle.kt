package ec.travesias.app.data.social

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * La cuenta de Google como ATAJO para registrarse, no como identidad.
 *
 * QUÉ HACE Y QUÉ NO
 * Trae el nombre, el correo y la foto de la cuenta de Google para rellenar el registro,
 * y nada más. La identidad del viajero sigue siendo su LLAVE PRIVADA: es la que firma los
 * mensajes y la que abre el cofre cifrado.
 *
 * POR QUÉ NO SE USA GOOGLE PARA ENTRAR A FIREBASE
 * Las reglas de Firestore reconocen a cada quien por la parte de antes de la arroba de su
 * correo interno `tv_xxxx@travesias.app`, que sale de su llave. Si la sesión de Firebase
 * pasara a ser la de Google, ese correo cambiaría, `miHuella()` quedaría vacía y TODAS las
 * escrituras se denegarían — el mismo agujero que teníamos con el modo invitado. Además,
 * Google no puede descifrar un cofre: la contraseña es lo único que abre la llave privada,
 * y eso es justo lo que hace que nadie más pueda leer los mensajes.
 *
 * SE ENCIENDE SOLO
 * Hace falta el id de cliente web, que el plugin de google-services mete en
 * `R.string.default_web_client_id` SOLO si `google-services.json` trae un `oauth_client`.
 * Como hoy viene vacío, ese recurso NO existe y nombrarlo directamente rompería la
 * compilación. Por eso se busca por nombre en tiempo de ejecución: mientras no esté, el
 * botón simplemente no aparece, y el día que se suba el archivo nuevo se enciende sin
 * tocar una línea de código.
 *
 * QUÉ HAY QUE HACER EN LA CONSOLA PARA ENCENDERLO
 *  1. Authentication → Método de acceso → añadir el proveedor **Google**.
 *  2. Configuración del proyecto → tu app Android → añadir la huella **SHA-1** de la clave
 *     de firma (la de subida, y también la de depuración si se quiere probar en debug).
 *  3. Descargar el `google-services.json` nuevo y dejarlo en `app/`.
 */
object CuentaGoogle {

    /** Lo que Google nos presta para rellenar el registro. Nada de esto es la identidad. */
    data class Perfil(
        val correo: String,
        val nombre: String,
        val fotoUrl: String
    ) {
        /** @usuario propuesto a partir del correo, ya normalizado. */
        fun usuarioSugerido(): String =
            AccountService.normalizeUsername(correo.substringBefore("@")).take(20)
    }

    /** El id de cliente web, o null si el proyecto todavía no tiene Google configurado. */
    fun clienteWeb(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) return null
        return runCatching { context.getString(id) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** ¿Se puede ofrecer el botón "Continuar con Google" en este teléfono? */
    fun disponible(context: Context): Boolean = clienteWeb(context) != null

    /**
     * Abre el selector de cuentas de Google. Devuelve null si el viajero lo cierra.
     * @throws IllegalStateException con un motivo legible si falla de verdad.
     */
    suspend fun elegir(context: Context): Perfil? {
        val cliente = clienteWeb(context)
            ?: throw IllegalStateException(
                "Este proyecto de Firebase todavía no tiene configurado el acceso con Google."
            )
        val opcion = GetGoogleIdOption.Builder()
            .setServerClientId(cliente)
            // false = enseña TODAS las cuentas del teléfono, no solo las que ya usaron la app.
            // Al registrarse es lo que se quiere: aún no hay ninguna.
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val peticion = GetCredentialRequest.Builder().addCredentialOption(opcion).build()

        val respuesta = try {
            CredentialManager.create(context).getCredential(context, peticion)
        } catch (e: GetCredentialCancellationException) {
            return null   // lo cerró: no es un error
        } catch (e: Exception) {
            throw IllegalStateException(explicar(e))
        }

        val credencial = respuesta.credential
        if (credencial.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        val google = runCatching { GoogleIdTokenCredential.createFrom(credencial.data) }.getOrNull()
            ?: throw IllegalStateException("No se pudo leer la cuenta de Google.")

        return Perfil(
            correo = google.id,
            nombre = google.displayName.orEmpty().ifBlank { google.givenName.orEmpty() },
            fotoUrl = google.profilePictureUri?.toString().orEmpty()
        )
    }

    /** Los fallos de Credential Manager llegan en inglés y sin contexto: aquí se traducen. */
    private fun explicar(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            m.contains("no credentials") || m.contains("no accounts") ->
                "No hay ninguna cuenta de Google en este teléfono. Añádela en Ajustes y vuelve a intentarlo."
            m.contains("network") || m.contains("unavailable") ->
                "Sin conexión para hablar con Google. Puedes crear la cuenta con usuario y contraseña."
            m.contains("developer") || m.contains("client id") || m.contains("audience") ->
                "El acceso con Google no está bien configurado en Firebase (falta la huella SHA-1 de esta versión de la app)."
            else -> "No se pudo usar la cuenta de Google. Puedes crear la cuenta con usuario y contraseña."
        }
    }
}

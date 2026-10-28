package ec.travesias.app.data.social

import org.json.JSONObject
import java.security.PrivateKey

/**
 * Identidad del viajero. La llave privada solo existe en el teléfono y en el sobre cifrado.
 */
class TravelerIdentity(
    val username: String,
    val fingerprint: String,
    val publicKeyB64: String,
    val privateKeyBytes: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    private val privateKey: PrivateKey by lazy { CryptoBox.privateFrom(privateKeyBytes) }

    /** Firma un texto: quien lo reciba puede comprobar que salió de esta cuenta. */
    fun signText(text: String): String = CryptoBox.signText(privateKey, text)

    /** Llave del cofre personal. Sale de la llave privada: nadie más puede calcularla. */
    fun vaultKey(): ByteArray = CryptoBox.vaultKey(privateKeyBytes)

    /**
     * Cuenta de transporte de Firebase, derivada de la propia llave privada.
     *
     * Firebase necesita saber quién escribe para poder aplicar sus reglas, y el acceso
     * anónimo no sirve: cambia en cada teléfono. Así que de la llave sale un correo interno
     * (huella@travesias.app, al que nunca se envía nada) y una contraseña larguísima que
     * nadie puede adivinar ni escribir a mano, ni siquiera el dueño. Resultado: el mismo
     * viajero tiene el MISMO identificador en todos sus teléfonos, y las reglas de Firestore
     * pueden comprobar de verdad que cada quien solo toca lo suyo.
     *
     * El acceso por correo y contraseña de Firebase es gratis e ilimitado; lo que se cobra
     * es el SMS, que aquí no se usa.
     */
    fun firebaseEmail(): String = fingerprint + "@travesias.app"

    fun firebasePassword(): String =
        CryptoBox.b64(CryptoBox.sha256("travesias-transporte-v1".toByteArray(Charsets.UTF_8), privateKeyBytes))

    /** Carné público que se reparte a los compañeros de grupo. */
    fun card(displayName: String, avatarThumb: String): String = JSONObject().apply {
        put("u", username)
        put("fp", fingerprint)
        put("k", publicKeyB64)
        put("n", displayName)
        put("t", avatarThumb)
    }.toString()
}

/**
 * Carné público de otro viajero: lo mínimo para poder escribirle en privado y verificar lo
 * que firma. Sale de leer su documento de `identities` por nombre exacto.
 */
data class PublicCard(
    val username: String,
    val fingerprint: String,
    val publicKeyB64: String
)

/** Una llave de grupo guardada en el cofre del viajero. */
data class GroupSecret(val groupId: String, val keyB64: String, val name: String)

/** Estado de la cuenta para la pantalla de Comunidad. */
sealed class AccountState {
    object Loading : AccountState()
    object SignedOut : AccountState()
    data class SignedIn(val identity: TravelerIdentity) : AccountState()
}

package ec.travesias.app.data.social

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Dónde vive la llave privada DENTRO del teléfono.
 *
 * La llave privada es la identidad del viajero: quien la tenga puede escribir en su nombre.
 * Por eso no se guarda tal cual, sino cifrada con una llave del **Almacén de claves de
 * Android** (Keystore), que en la mayoría de teléfonos vive en hardware seguro y no se puede
 * copiar ni sacar del aparato. Si otra app leyera el archivo, encontraría ruido.
 *
 * Si el Keystore falla (pasa en algunos equipos viejos o con ROM modificada) se usa un
 * respaldo: una llave aleatoria guardada en las preferencias privadas de la app. Es menos
 * fuerte, pero la app sigue funcionando; queda marcado en [usaHardware].
 */
class IdentityVault(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    companion object {
        private const val ARCHIVO = "travesias_identidad"
        private const val ALIAS = "travesias_identidad_v1"
        private const val K_USUARIO = "usuario"
        private const val K_PUBLICA = "publica"
        private const val K_PRIVADA = "privada_cifrada"
        private const val K_HUELLA = "huella"
        private const val K_CREADA = "creada"
        private const val K_RESPALDO = "llave_respaldo"
        private const val K_PEND_USUARIO = "pendiente_usuario"
        private const val K_PEND_SOBRE = "pendiente_sobre"
    }

    var usaHardware: Boolean = true
        private set

    val hayCuenta: Boolean get() = prefs.contains(K_PRIVADA)

    /** Identidad guardada en este teléfono, o null si aún no hay cuenta. */
    fun cargar(): TravelerIdentity? {
        val usuario = prefs.getString(K_USUARIO, null) ?: return null
        val publica = prefs.getString(K_PUBLICA, null) ?: return null
        val cifrada = prefs.getString(K_PRIVADA, null) ?: return null
        val privada = runCatching { CryptoBox.open(llaveLocal(), CryptoBox.b64d(cifrada), usuario.toByteArray()) }.getOrNull()
            ?: return null
        return TravelerIdentity(
            username = usuario,
            fingerprint = prefs.getString(K_HUELLA, "") ?: "",
            publicKeyB64 = publica,
            privateKeyBytes = privada,
            createdAt = prefs.getLong(K_CREADA, 0L)
        )
    }

    fun guardar(identidad: TravelerIdentity) {
        val cifrada = CryptoBox.b64(
            CryptoBox.seal(llaveLocal(), identidad.privateKeyBytes, identidad.username.toByteArray())
        )
        prefs.edit()
            .putString(K_USUARIO, identidad.username)
            .putString(K_PUBLICA, identidad.publicKeyB64)
            .putString(K_HUELLA, identidad.fingerprint)
            .putString(K_PRIVADA, cifrada)
            .putLong(K_CREADA, identidad.createdAt)
            .apply()
    }

    // ======================================================================
    //  Cuenta creada pero todavía sin publicar
    // ======================================================================
    //  Cuando Firebase no deja escribir en el momento del registro (falta activar el acceso
    //  por correo, o no hay señal), el sobre cerrado espera aquí. La contraseña NO se guarda:
    //  el sobre ya está cerrado con ella y sin ella no se abre.

    fun guardarPendiente(usuario: String, sobre: String) {
        prefs.edit().putString(K_PEND_USUARIO, usuario).putString(K_PEND_SOBRE, sobre).apply()
    }

    /** (usuario, sobre) de la cuenta que falta subir, o null si no hay ninguna. */
    fun sobrePendiente(): Pair<String, String>? {
        val u = prefs.getString(K_PEND_USUARIO, null) ?: return null
        val s = prefs.getString(K_PEND_SOBRE, null) ?: return null
        return if (u.isBlank() || s.isBlank()) null else u to s
    }

    fun olvidarPendiente() {
        prefs.edit().remove(K_PEND_USUARIO).remove(K_PEND_SOBRE).apply()
    }

    /** Cierra sesión en ESTE teléfono. La cuenta sigue existiendo: se vuelve con usuario y clave. */
    fun borrar() {
        prefs.edit().remove(K_USUARIO).remove(K_PUBLICA).remove(K_PRIVADA).remove(K_HUELLA).remove(K_CREADA)
            .remove(K_PEND_USUARIO).remove(K_PEND_SOBRE).remove(K_RESPALDO).apply()
    }

    // ======================================================================
    //  Llave del teléfono (Keystore, con respaldo)
    // ======================================================================

    private fun llaveLocal(): ByteArray {
        val delKeystore = runCatching { llaveKeystore() }.getOrNull()
        if (delKeystore != null) {
            usaHardware = true
            // La llave del Keystore no se puede exportar: se usa para cifrar un secreto que sí
            // guardamos, y ese secreto es el que abre la llave privada.
            return secretoEnvueltoPorKeystore(delKeystore)
        }
        usaHardware = false
        return llaveDeRespaldo()
    }

    private fun llaveKeystore(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** Secreto de 32 bytes cifrado por el Keystore y guardado en las preferencias. */
    private fun secretoEnvueltoPorKeystore(llave: SecretKey): ByteArray {
        val guardado = prefs.getString(K_RESPALDO, null)
        if (guardado != null) {
            val datos = CryptoBox.b64d(guardado)
            if (datos.size > 12) {
                val abierto = runCatching {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, llave, GCMParameterSpec(128, datos.copyOfRange(0, 12)))
                    c.doFinal(datos, 12, datos.size - 12)
                }.getOrNull()
                if (abierto != null && abierto.size == 32) return abierto
            }
        }
        val secreto = CryptoBox.randomBytes(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave)
        val nonce = c.iv
        val sellado = nonce + c.doFinal(secreto)
        prefs.edit().putString(K_RESPALDO, CryptoBox.b64(sellado)).apply()
        return secreto
    }

    /** Plan B cuando el Keystore no está disponible. */
    private fun llaveDeRespaldo(): ByteArray {
        prefs.getString(K_RESPALDO, null)?.let { guardado ->
            val datos = CryptoBox.b64d(guardado)
            if (datos.size == 32) return datos
        }
        val nueva = CryptoBox.randomBytes(32)
        prefs.edit().putString(K_RESPALDO, CryptoBox.b64(nueva)).apply()
        return nueva
    }
}

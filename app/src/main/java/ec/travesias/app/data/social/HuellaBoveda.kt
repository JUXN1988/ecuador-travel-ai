package ec.travesias.app.data.social

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda el usuario y la contraseña de la cuenta detrás del bloqueo del teléfono
 * (huella, cara, patrón o PIN — lo que cada quien tenga puesto).
 *
 * Para qué sirve: la contraseña es lo ÚNICO que abre la cuenta en otro teléfono, y si se
 * pierde se pierde la cuenta. Con esto el viajero puede entrar con la huella y, sobre todo,
 * volver a VER su contraseña cuando la necesite, sin tener que acordarse de ella.
 *
 * Cómo se guarda: cifrada con una llave del Almacén de claves de Android, igual que la llave
 * privada en [IdentityVault]. La huella es la puerta que hay que pasar para poder descifrarla.
 * No se pide autenticación a nivel de llave (`setUserAuthenticationRequired`) porque en los
 * teléfonos con Android 9 y 10 eso choca con el patrón/PIN y deja gente fuera; el nivel de
 * protección queda igual que el de la llave privada, que ya vive así en este mismo teléfono.
 */
object HuellaBoveda {

    private const val ARCHIVO = "travesias_huella"
    private const val ALIAS = "travesias_huella_v1"
    private const val K_DATOS = "credencial"
    private const val K_SECRETO = "secreto"

    /** Qué se puede hacer en este teléfono. */
    enum class Estado { LISTO, SIN_CONFIGURAR, NO_DISPONIBLE }

    private val PERMITIDO =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    /** ¿Este teléfono puede pedir huella/patrón? */
    fun estado(ctx: Context): Estado = when (BiometricManager.from(ctx).canAuthenticate(PERMITIDO)) {
        BiometricManager.BIOMETRIC_SUCCESS -> Estado.LISTO
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Estado.SIN_CONFIGURAR
        else -> Estado.NO_DISPONIBLE
    }

    /** ¿Ya hay una contraseña guardada bajo la huella en este teléfono? */
    fun hayCredencial(ctx: Context): Boolean = prefs(ctx).contains(K_DATOS)

    /** El @usuario guardado, para poder saludarlo sin pedir la huella todavía. */
    fun usuarioGuardado(ctx: Context): String? =
        prefs(ctx).getString(K_DATOS, null)?.let { runCatching { descifrar(ctx, it).first }.getOrNull() }

    /**
     * Pide la huella (o el patrón) y, si sale bien, ejecuta [alPasar].
     * [alFallar] solo se llama en errores de verdad, no cuando el viajero cancela.
     */
    fun pedir(
        actividad: FragmentActivity,
        titulo: String,
        subtitulo: String,
        alPasar: () -> Unit,
        alFallar: (String) -> Unit = {}
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(titulo)
            .setSubtitle(subtitulo)
            .setAllowedAuthenticators(PERMITIDO)
            .build()
        val prompt = BiometricPrompt(
            actividad,
            ContextCompat.getMainExecutor(actividad),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = alPasar()
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    val cancelado = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED
                    if (!cancelado) alFallar(msg.toString())
                }
            }
        )
        runCatching { prompt.authenticate(info) }
            .onFailure { alFallar(it.message ?: "No se pudo abrir el bloqueo del teléfono") }
    }

    /** Guarda la credencial. Llamar DESPUÉS de que [pedir] haya salido bien. */
    fun guardar(ctx: Context, usuario: String, contrasena: String) {
        val texto = "$usuario\n$contrasena".toByteArray()
        val sellado = CryptoBox.b64(CryptoBox.seal(llave(ctx), texto, ALIAS.toByteArray()))
        prefs(ctx).edit().putString(K_DATOS, sellado).apply()
    }

    /** Devuelve (usuario, contraseña). Llamar DESPUÉS de que [pedir] haya salido bien. */
    fun leer(ctx: Context): Pair<String, String>? {
        val guardado = prefs(ctx).getString(K_DATOS, null) ?: return null
        return runCatching { descifrar(ctx, guardado) }.getOrNull()
    }

    /** Quita la contraseña guardada de este teléfono. La cuenta no se toca. */
    fun olvidar(ctx: Context) {
        prefs(ctx).edit().remove(K_DATOS).apply()
    }

    private fun descifrar(ctx: Context, guardado: String): Pair<String, String> {
        // CryptoBox.open devuelve null si el sobre no cuadra (llave cambiada, datos tocados).
        val claro = CryptoBox.open(llave(ctx), CryptoBox.b64d(guardado), ALIAS.toByteArray())
            ?: error("La credencial guardada ya no se puede abrir en este teléfono")
        val partes = String(claro).split("\n", limit = 2)
        return partes[0] to (partes.getOrNull(1) ?: "")
    }

    // ------------------------------------------------------------------
    //  Llave de este teléfono: misma receta que IdentityVault.
    // ------------------------------------------------------------------

    private fun llave(ctx: Context): ByteArray {
        val delKeystore = runCatching { llaveKeystore() }.getOrNull()
        return if (delKeystore != null) secretoEnvuelto(ctx, delKeystore) else secretoSuelto(ctx)
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

    private fun secretoEnvuelto(ctx: Context, llave: SecretKey): ByteArray {
        val p = prefs(ctx)
        p.getString(K_SECRETO, null)?.let { guardado ->
            val datos = CryptoBox.b64d(guardado)
            if (datos.size > 12) {
                runCatching {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, llave, GCMParameterSpec(128, datos.copyOfRange(0, 12)))
                    c.doFinal(datos, 12, datos.size - 12)
                }.getOrNull()?.let { if (it.size == 32) return it }
            }
        }
        val secreto = CryptoBox.randomBytes(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave)
        p.edit().putString(K_SECRETO, CryptoBox.b64(c.iv + c.doFinal(secreto))).apply()
        return secreto
    }

    private fun secretoSuelto(ctx: Context): ByteArray {
        val p = prefs(ctx)
        p.getString(K_SECRETO, null)?.let { g ->
            val datos = CryptoBox.b64d(g)
            if (datos.size == 32) return datos
        }
        val nueva = CryptoBox.randomBytes(32)
        p.edit().putString(K_SECRETO, CryptoBox.b64(nueva)).apply()
        return nueva
    }
}

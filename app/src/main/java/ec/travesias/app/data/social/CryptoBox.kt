package ec.travesias.app.data.social

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * La caja fuerte de Travesías: firmas, cifrado y derivación de claves.
 *
 * TODO se hace con lo que ya trae Android desde la versión 7 (minSdk 24): no añade
 * ninguna librería ni servicio de pago. La idea es simple:
 *
 *  - Cada viajero tiene un PAR DE LLAVES (curva P-256). La pública es su carné; la privada
 *    no sale nunca del teléfono. Todo lo que publica va FIRMADO, así que nadie puede
 *    escribir haciéndose pasar por él: la firma no cuadraría.
 *  - La contraseña del viajero NO viaja a ningún lado. Con ella se deriva (PBKDF2) una llave
 *    que sirve para ENVOLVER su llave privada. A Firebase solo sube el sobre cerrado, que
 *    sin la contraseña no se puede abrir. Por eso puede entrar en otro teléfono.
 *  - Cada grupo tiene su propia llave secreta que viaja dentro del código de invitación.
 *    Los mensajes se cifran con ella (AES-GCM), así que en Firebase solo hay ruido.
 *
 * Nada de esto depende de Google: Firebase queda como un simple buzón que no entiende
 * lo que reparte.
 */
object CryptoBox {

    private const val CURVE = "secp256r1"          // P-256: en Android desde siempre
    private const val SIGN_ALGO = "SHA256withECDSA"
    private const val GCM_NONCE = 12
    private const val GCM_TAG_BITS = 128

    /** Vueltas del PBKDF2. Sube el costo de adivinar la contraseña a fuerza bruta. */
    const val KDF_ITERATIONS = 150_000

    private val random = SecureRandom()

    // ======================================================================
    //  Azar
    // ======================================================================

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ======================================================================
    //  Llaves de identidad (firmar / verificar)
    // ======================================================================

    fun newKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(CURVE), random)
        return gen.generateKeyPair()
    }

    fun publicBytes(key: PublicKey): ByteArray = key.encoded          // X.509
    fun privateBytes(key: PrivateKey): ByteArray = key.encoded        // PKCS#8

    fun publicFrom(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun privateFrom(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance(SIGN_ALGO)
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val s = Signature.getInstance(SIGN_ALGO)
        s.initVerify(publicKey)
        s.update(data)
        s.verify(signature)
    }.getOrDefault(false)

    /** Verifica con la llave pública en base64. Devuelve false ante cualquier problema. */
    fun verify(publicKeyB64: String, data: String, signatureB64: String): Boolean = runCatching {
        verify(publicFrom(b64d(publicKeyB64)), data.toByteArray(Charsets.UTF_8), b64d(signatureB64))
    }.getOrDefault(false)

    fun signText(privateKey: PrivateKey, text: String): String =
        b64(sign(privateKey, text.toByteArray(Charsets.UTF_8)))

    /**
     * Identificador público del viajero, derivado de su llave: "tv_" + 16 caracteres.
     * Dos personas no pueden tener el mismo, y no se puede inventar sin la llave privada.
     */
    fun fingerprint(publicKeyBytes: ByteArray): String =
        "tv_" + base32(sha256(publicKeyBytes).copyOfRange(0, 10)).lowercase()

    // ======================================================================
    //  Contraseña → llave (PBKDF2-HMAC-SHA256, implementado a mano)
    // ======================================================================
    //  Se implementa aquí y no con SecretKeyFactory porque "PBKDF2WithHmacSHA256"
    //  no existe en Android 7 y 8 (llegó en la 26): así funciona igual en todos.

    fun deriveKey(password: String, salt: ByteArray, iterations: Int = KDF_ITERATIONS, lengthBytes: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hLen = mac.macLength
        val bloques = (lengthBytes + hLen - 1) / hLen
        val salida = ByteArray(bloques * hLen)
        for (i in 1..bloques) {
            val u = mac.doFinal(salt + byteArrayOf((i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()))
            var anterior = u
            val acumulado = u.copyOf()
            repeat(iterations - 1) {
                anterior = mac.doFinal(anterior)
                for (j in acumulado.indices) acumulado[j] = (acumulado[j].toInt() xor anterior[j].toInt()).toByte()
            }
            System.arraycopy(acumulado, 0, salida, (i - 1) * hLen, hLen)
        }
        return salida.copyOfRange(0, lengthBytes)
    }

    /** Sal fija por usuario: dos personas con la misma contraseña no comparten llave. */
    fun saltFor(purpose: String, username: String): ByteArray =
        sha256(("travesias:v1:" + purpose + ":" + username.lowercase()).toByteArray(Charsets.UTF_8))

    /** Llave del cofre personal, derivada de la llave privada (Firebase no puede calcularla). */
    fun vaultKey(privateKeyBytes: ByteArray): ByteArray =
        sha256("travesias-cofre-v1".toByteArray(Charsets.UTF_8) + privateKeyBytes)

    // ======================================================================
    //  Llave compartida entre dos personas (ECDH)
    // ======================================================================
    //  Sirve para los chats directos: con MI llave privada y TU llave pública sale el mismo
    //  secreto que sale con TU privada y MI pública. Nadie más puede calcularlo, y no hace
    //  falta mandarse nada por Firebase. Es lo que permite invitar a alguien a un chat
    //  cifrado sin pasarle un código a mano.

    fun sharedKey(myPrivate: PrivateKey, otherPublicB64: String): ByteArray? = runCatching {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(publicFrom(b64d(otherPublicB64)), true)
        sha256("travesias-dm-v1".toByteArray(Charsets.UTF_8), ka.generateSecret())
    }.getOrNull()

    // ======================================================================
    //  Cifrado simétrico (AES-GCM)
    // ======================================================================

    /** Devuelve nonce(12) || ciphertext+tag. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(GCM_NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** Abre lo sellado por [seal]. Devuelve null si la llave no corresponde o vino alterado. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? = runCatching {
        if (sealed.size <= GCM_NONCE) return null
        val nonce = sealed.copyOfRange(0, GCM_NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(sealed, GCM_NONCE, sealed.size - GCM_NONCE)
    }.getOrNull()

    fun sealText(key: ByteArray, text: String, aad: String? = null): String =
        b64(seal(key, text.toByteArray(Charsets.UTF_8), aad?.toByteArray(Charsets.UTF_8)))

    fun openText(key: ByteArray, sealedB64: String, aad: String? = null): String? = runCatching {
        open(key, b64d(sealedB64), aad?.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8)
    }.getOrNull()

    // ======================================================================
    //  Utilidades
    // ======================================================================

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** Comparación en tiempo constante: no filtra cuántos bytes coincidían. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var dif = 0
        for (i in a.indices) dif = dif or (a[i].toInt() xor b[i].toInt())
        return dif == 0
    }

    // ---- Base64 propio (android.util.Base64 no se puede probar fuera del teléfono) ----

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun b64(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append(B64[(n shr 6) and 63]).append(B64[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append(B64[(n shr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    fun b64d(text: String): ByteArray {
        val limpio = text.filter { it != '\n' && it != '\r' && it != ' ' }
        val salida = java.io.ByteArrayOutputStream(limpio.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in limpio) {
            if (ch == '=') break
            val v = B64.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                salida.write((buffer shr bits) and 0xFF)
            }
        }
        return salida.toByteArray()
    }

    // ---- Base32 sin letras confusas: para códigos que la gente escribe a mano ----

    private const val B32 = "ABCDEFGHJKMNPQRSTVWXYZ0123456789"   // sin I, L, O, U

    fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun base32d(text: String): ByteArray {
        val limpio = text.uppercase().replace("-", "").replace(" ", "")
            .replace('I', '1').replace('L', '1').replace('O', '0').replace('U', 'V')
        val salida = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in limpio) {
            val v = B32.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                salida.write((buffer shr bits) and 0xFF)
            }
        }
        return salida.toByteArray()
    }
}

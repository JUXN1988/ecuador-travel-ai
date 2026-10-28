package ec.travesias.app

import ec.travesias.app.data.social.CryptoBox
import org.junit.Assert.*
import org.junit.Test

/**
 * Pruebas de la caja fuerte. No necesitan Android: se ejecutan en la JVM con `gradlew test`.
 *
 * Lo que se comprueba de verdad aquí es lo que sostiene la seguridad de la Comunidad:
 * que nadie puede firmar por ti, que sin la contraseña no se abre tu llave, y que sin la
 * llave del grupo los mensajes son ruido.
 */
class CryptoBoxTest {

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }

    @Test
    fun `PBKDF2 coincide con los vectores conocidos de la norma`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(CryptoBox.deriveKey("password", "salt".toByteArray(), 1, 32))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(CryptoBox.deriveKey("password", "salt".toByteArray(), 2, 32))
        )
        // Más largo que un bloque de SHA-256: obliga a encadenar bloques
        assertEquals(64, CryptoBox.deriveKey("passwd", "salt".toByteArray(), 1, 64).size)
    }

    @Test
    fun `base64 y base32 van y vuelven`() {
        val datos = CryptoBox.randomBytes(97)
        assertEquals(hex(datos), hex(CryptoBox.b64d(CryptoBox.b64(datos))))

        val codigo = CryptoBox.base32(CryptoBox.randomBytes(32))
        assertFalse("El código no debe traer letras que se confundan", codigo.any { it in "ILOU" })
        // Como lo escribiría alguien a mano: en minúsculas y con guiones
        assertEquals(
            hex(CryptoBox.base32d(codigo)),
            hex(CryptoBox.base32d(codigo.lowercase().chunked(6).joinToString("-")))
        )
    }

    @Test
    fun `nadie puede firmar en tu nombre`() {
        val yo = CryptoBox.newKeyPair()
        val impostor = CryptoBox.newKeyPair()
        val miPublica = CryptoBox.b64(CryptoBox.publicBytes(yo.public))
        val firma = CryptoBox.signText(yo.private, "nos vemos en Baños")

        assertTrue(CryptoBox.verify(miPublica, "nos vemos en Baños", firma))
        assertFalse("Cambiar el texto invalida la firma", CryptoBox.verify(miPublica, "nos vemos en Quito", firma))
        assertFalse(
            "Otra llave no puede validar mi firma",
            CryptoBox.verify(CryptoBox.b64(CryptoBox.publicBytes(impostor.public)), "nos vemos en Baños", firma)
        )
        assertFalse("Una firma basura no revienta la app", CryptoBox.verify(miPublica, "hola", "esto-no-es-una-firma"))
    }

    @Test
    fun `la huella identifica a cada viajero`() {
        val a = CryptoBox.newKeyPair()
        val b = CryptoBox.newKeyPair()
        val huella = CryptoBox.fingerprint(CryptoBox.publicBytes(a.public))
        assertEquals(huella, CryptoBox.fingerprint(CryptoBox.publicBytes(a.public)))
        assertNotEquals(huella, CryptoBox.fingerprint(CryptoBox.publicBytes(b.public)))
        assertTrue(huella.startsWith("tv_"))
    }

    @Test
    fun `sin la llave del grupo el mensaje es ruido`() {
        val llave = CryptoBox.randomBytes(32)
        val sobre = CryptoBox.sealText(llave, "la entrada cuesta 2 dólares", "grupo-1")

        assertEquals("la entrada cuesta 2 dólares", CryptoBox.openText(llave, sobre, "grupo-1"))
        assertNull("Otra llave no abre", CryptoBox.openText(CryptoBox.randomBytes(32), sobre, "grupo-1"))
        assertNull("Un mensaje movido a otro grupo no abre", CryptoBox.openText(llave, sobre, "grupo-2"))
        assertNotEquals("El mismo texto nunca se cifra igual dos veces", sobre, CryptoBox.sealText(llave, "la entrada cuesta 2 dólares", "grupo-1"))
    }

    @Test
    fun `entrar en otro telefono con usuario y contrasena devuelve la misma identidad`() {
        val usuario = "gabriel"
        val clave = "mi clave de viaje 2026"

        // --- Teléfono 1: se crea la cuenta ---
        val identidad = CryptoBox.newKeyPair()
        val privada = CryptoBox.privateBytes(identidad.private)
        val llaveDeClave = CryptoBox.deriveKey(clave, CryptoBox.saltFor("cuenta", usuario), 5_000, 32)
        // Esto es LO ÚNICO que sube a Firebase: un sobre cerrado.
        val sobre = CryptoBox.seal(llaveDeClave, privada, usuario.toByteArray())

        // --- Teléfono 2: solo tiene el usuario, la clave y el sobre ---
        val rehecha = CryptoBox.deriveKey(clave, CryptoBox.saltFor("cuenta", usuario), 5_000, 32)
        val recuperada = CryptoBox.open(rehecha, sobre, usuario.toByteArray())
        assertNotNull("Debe poder recuperar su llave", recuperada)
        assertEquals(hex(privada), hex(recuperada!!))

        // Y firma exactamente igual que antes: para el grupo sigue siendo la misma persona.
        val firma = CryptoBox.signText(CryptoBox.privateFrom(recuperada), "sigo siendo yo")
        assertTrue(CryptoBox.verify(CryptoBox.b64(CryptoBox.publicBytes(identidad.public)), "sigo siendo yo", firma))

        // --- Con la contraseña equivocada no hay manera ---
        val mala = CryptoBox.deriveKey("clave equivocada", CryptoBox.saltFor("cuenta", usuario), 5_000, 32)
        assertNull(CryptoBox.open(mala, sobre, usuario.toByteArray()))
    }

    @Test
    fun `el cofre personal se deriva de la llave privada`() {
        val a = CryptoBox.newKeyPair()
        val b = CryptoBox.newKeyPair()
        val cofreA = CryptoBox.vaultKey(CryptoBox.privateBytes(a.private))
        assertEquals(hex(cofreA), hex(CryptoBox.vaultKey(CryptoBox.privateBytes(a.private))))
        assertNotEquals(hex(cofreA), hex(CryptoBox.vaultKey(CryptoBox.privateBytes(b.private))))
    }
}

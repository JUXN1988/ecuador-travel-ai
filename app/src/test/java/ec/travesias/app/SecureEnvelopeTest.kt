package ec.travesias.app

import ec.travesias.app.data.social.CryptoBox
import ec.travesias.app.data.social.GroupPost
import ec.travesias.app.data.social.PostTrust
import ec.travesias.app.data.social.PostType
import ec.travesias.app.data.social.SecureEnvelope
import ec.travesias.app.data.social.TravelerIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lo que de verdad protege la Comunidad: que Firebase no pueda leer los mensajes y que
 * nadie pueda escribir en nombre de otro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureEnvelopeTest {

    private fun identidad(usuario: String): TravelerIdentity {
        val par = CryptoBox.newKeyPair()
        val pub = CryptoBox.publicBytes(par.public)
        return TravelerIdentity(usuario, CryptoBox.fingerprint(pub), CryptoBox.b64(pub), CryptoBox.privateBytes(par.private))
    }

    private fun mensaje(texto: String, autor: TravelerIdentity) = GroupPost(
        id = "post1", groupId = "grupo1", type = PostType.MESSAGE,
        authorUid = autor.fingerprint, authorName = autor.username,
        text = texto, createdAt = 1_700_000_000_000L
    )

    @Test
    fun `firebase no ve el contenido del mensaje`() {
        val gabriel = identidad("gabriel")
        val llave = CryptoBox.randomBytes(32)
        val sobre = SecureEnvelope.seal(mensaje("salimos 6am del terminal", gabriel), gabriel, gabriel.card("Gabriel", "foto"), llave)

        val loQueSeGuarda = sobre.toString()
        assertFalse("El texto no puede quedar legible", loQueSeGuarda.contains("salimos 6am"))
        assertFalse("Ni el nombre visible", loQueSeGuarda.contains("Gabriel"))
        assertTrue("Pero sí de quién viene: las reglas lo necesitan", loQueSeGuarda.contains(gabriel.fingerprint))
        assertTrue("Y cuándo caduca en el buzón", (sobre["exp"] as Long) > (sobre["createdAt"] as Long))
    }

    @Test
    fun `un miembro del grupo lo abre y queda verificado`() {
        val gabriel = identidad("gabriel")
        val llave = CryptoBox.randomBytes(32)
        val sobre = SecureEnvelope.seal(mensaje("nos vemos en el Pailón", gabriel), gabriel, gabriel.card("Gabriel", "foto"), llave)

        val abierto = SecureEnvelope.open("post1", "grupo1", sobre, llave)
        assertNotNull(abierto)
        assertEquals("nos vemos en el Pailón", abierto!!.post.text)
        assertEquals(PostTrust.VERIFIED, abierto.trust)
        assertEquals("gabriel", abierto.card?.username)

        // Sin llave o con otra llave el mensaje no desaparece: queda con candado.
        val sinLlave = SecureEnvelope.open("post1", "grupo1", sobre, null)
        assertEquals(PostTrust.LOCKED, sinLlave?.trust)
        val otraLlave = SecureEnvelope.open("post1", "grupo1", sobre, CryptoBox.randomBytes(32))
        assertEquals(PostTrust.LOCKED, otraLlave?.trust)
    }

    @Test
    fun `nadie puede escribir haciendose pasar por otro`() {
        val gabriel = identidad("gabriel")
        val impostor = identidad("gabriel")   // mismo nombre, otra llave
        val llave = CryptoBox.randomBytes(32)

        // El impostor manda un mensaje poniendo el carné de Gabriel
        val sobreFalso = SecureEnvelope.seal(
            mensaje("cambio de plan, manden plata", impostor), impostor, gabriel.card("Gabriel", "foto"), llave
        )
        val abierto = SecureEnvelope.open("post1", "grupo1", sobreFalso, llave) { usuario ->
            if (usuario == "gabriel") gabriel.fingerprint else null   // a Gabriel ya lo conocíamos
        }
        assertEquals(PostTrust.SUSPICIOUS, abierto?.trust)
    }

    @Test
    fun `un mensaje no se puede mover ni retocar`() {
        val gabriel = identidad("gabriel")
        val llave = CryptoBox.randomBytes(32)
        val sobre = SecureEnvelope.seal(mensaje("hola", gabriel), gabriel, gabriel.card("Gabriel", ""), llave)

        val enOtroGrupo = SecureEnvelope.open("post1", "grupo2", sobre, llave)
        assertEquals("En otro grupo no se descifra: queda con candado", PostTrust.LOCKED, enOtroGrupo?.trust)
        assertEquals("No se le puede cambiar el id", PostTrust.SUSPICIOUS, SecureEnvelope.open("otroId", "grupo1", sobre, llave)?.trust)

        val conFirmaCambiada = HashMap(sobre).apply { put("s", gabriel.signText("otra cosa")) }
        assertEquals(PostTrust.SUSPICIOUS, SecureEnvelope.open("post1", "grupo1", conFirmaCambiada, llave)?.trust)
    }

    @Test
    fun `los mensajes de la version anterior se siguen leyendo`() {
        val viejo = mapOf<String, Any?>(
            "type" to "MESSAGE", "authorUid" to "uid-anonimo", "authorName" to "pedro",
            "text" to "hola desde la versión anterior", "createdAt" to 1_600_000_000_000L
        )
        val abierto = SecureEnvelope.open("viejo1", "grupo1", viejo, CryptoBox.randomBytes(32))
        assertEquals("hola desde la versión anterior", abierto?.post?.text)
        assertEquals("Se muestran, pero sin garantía", PostTrust.LEGACY, abierto?.trust)
    }

    @Test
    fun `el codigo de invitacion lleva la llave del grupo`() {
        val llave = CryptoBox.b64(CryptoBox.randomBytes(32))
        val codigo = SecureEnvelope.inviteCode("abc123XYZ", llave)

        val leido = SecureEnvelope.parseInvite(codigo)
        assertEquals("abc123XYZ", leido?.first)
        assertEquals(llave, leido?.second)
        assertEquals("Como lo escribiría alguien a mano", llave, SecureEnvelope.parseInvite(" " + codigo + " ")?.second)
        assertNull("Un código inventado no abre nada", SecureEnvelope.parseInvite("ABC123"))
    }

    @Test
    fun `dos personas llegan al mismo secreto sin mandarse nada`() {
        val gabriel = identidad("gabriel")
        val maria = identidad("maria")
        val deGabriel = CryptoBox.sharedKey(CryptoBox.privateFrom(gabriel.privateKeyBytes), maria.publicKeyB64)
        val deMaria = CryptoBox.sharedKey(CryptoBox.privateFrom(maria.privateKeyBytes), gabriel.publicKeyB64)

        assertNotNull(deGabriel)
        assertArrayEquals("Es lo que cifra los chats directos", deGabriel, deMaria)
    }
}

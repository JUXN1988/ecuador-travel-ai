package ec.travesias.app.ui.community

import androidx.compose.runtime.Composable
import ec.travesias.app.data.social.ContactState
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.TravelerCard
import ec.travesias.app.data.social.nombreDelInterlocutor
import ec.travesias.app.ui.components.FloatingContextMenu
import ec.travesias.app.ui.components.FloatingMenuAction

/**
 * A quién se le está mostrando el menú al mantener pulsado.
 * Cada caso arma sus propias herramientas; el cifrado y los chats no se tocan.
 */
sealed class CommunityHoldTarget {
    data class Chat(val group: TravelGroup) : CommunityHoldTarget()
    data class Group(val group: TravelGroup) : CommunityHoldTarget()
    data class Person(val card: TravelerCard) : CommunityHoldTarget()
}

/**
 * Menú flotante de Comunidad: perfil, amigo, silenciar, bloquear y borrar conversación.
 * Los textos van cortos para que se lean bien en el panel blanco.
 */
@Composable
fun CommunityHoldMenu(
    target: CommunityHoldTarget,
    yo: String?,
    esAmigo: (String?) -> Boolean,
    onDismiss: () -> Unit,
    onAbrirChat: (String) -> Unit,
    onVerPerfil: (fingerprint: String, username: String) -> Unit,
    onAgregarAmigo: (fingerprint: String, username: String, groupId: String?) -> Unit,
    onQuitarAmigo: (fingerprint: String, username: String) -> Unit,
    onBloquear: (fingerprint: String, username: String) -> Unit,
    onDesbloquear: (fingerprint: String) -> Unit,
    onSilenciar: (groupId: String, silenciar: Boolean) -> Unit,
    onEliminarConversacion: (groupId: String) -> Unit,
    onInvitarGrupo: (groupId: String) -> Unit,
    onInvitarPersona: (TravelerCard) -> Unit,
    onEscribir: (username: String) -> Unit,
    onCompartirRuta: (groupId: String) -> Unit = {},
    miUsuario: String? = null
) {
    val acciones = when (target) {
        is CommunityHoldTarget.Chat -> accionesChat(
            g = target.group, yo = yo, miUsuario = miUsuario, esAmigo = esAmigo,
            onAbrirChat = onAbrirChat, onVerPerfil = onVerPerfil,
            onAgregarAmigo = onAgregarAmigo, onQuitarAmigo = onQuitarAmigo,
            onBloquear = onBloquear, onSilenciar = onSilenciar,
            onEliminarConversacion = onEliminarConversacion,
            onInvitarGrupo = onInvitarGrupo, onCompartirRuta = onCompartirRuta
        )
        is CommunityHoldTarget.Group -> accionesGrupo(
            g = target.group,
            onAbrirChat = onAbrirChat, onInvitarGrupo = onInvitarGrupo,
            onSilenciar = onSilenciar, onEliminarConversacion = onEliminarConversacion,
            onCompartirRuta = onCompartirRuta
        )
        is CommunityHoldTarget.Person -> accionesPersona(
            card = target.card, esAmigo = esAmigo,
            onVerPerfil = onVerPerfil, onEscribir = onEscribir,
            onAgregarAmigo = onAgregarAmigo, onQuitarAmigo = onQuitarAmigo,
            onBloquear = onBloquear, onDesbloquear = onDesbloquear,
            onInvitarPersona = onInvitarPersona
        )
    }
    val (titulo, cuerpo) = when (target) {
        is CommunityHoldTarget.Chat -> {
            val nombre = target.group.nombreDelInterlocutor(yo, miUsuario)
            nombre to if (target.group.direct) "Chat privado" else "Grupo de viaje"
        }
        is CommunityHoldTarget.Group -> {
            val n = target.group.memberUids.size
            target.group.name to if (n == 1) "1 persona" else "$n personas"
        }
        is CommunityHoldTarget.Person -> {
            val u = "@" + target.card.username.ifBlank { target.card.fingerprint.take(8) }
            u to if (target.card.state == ContactState.CONTACTO) "Amigo" else "Viajero"
        }
    }
    FloatingContextMenu(
        onDismiss = onDismiss,
        headerTitle = titulo,
        headerBody = cuerpo,
        actions = acciones
    )
}

private fun accionesChat(
    g: TravelGroup,
    yo: String?,
    miUsuario: String?,
    esAmigo: (String?) -> Boolean,
    onAbrirChat: (String) -> Unit,
    onVerPerfil: (String, String) -> Unit,
    onAgregarAmigo: (String, String, String?) -> Unit,
    onQuitarAmigo: (String, String) -> Unit,
    onBloquear: (String, String) -> Unit,
    onSilenciar: (String, Boolean) -> Unit,
    onEliminarConversacion: (String) -> Unit,
    onInvitarGrupo: (String) -> Unit,
    onCompartirRuta: (String) -> Unit
): List<FloatingMenuAction> {
    val nombre = g.nombreDelInterlocutor(yo, miUsuario)
    val otro = g.otherMember(yo)
    val items = mutableListOf<FloatingMenuAction>()
    items += FloatingMenuAction("Abrir") { onAbrirChat(g.id) }
    if (g.direct && otro != null) {
        items += FloatingMenuAction("Ver perfil") { onVerPerfil(otro, nombre) }
        if (esAmigo(otro)) {
            items += FloatingMenuAction("Quitar de amigos", "El chat se queda") {
                onQuitarAmigo(otro, nombre)
            }
        } else {
            items += FloatingMenuAction("Agregar a amigos") { onAgregarAmigo(otro, nombre, g.id) }
        }
        items += FloatingMenuAction("Bloquear", "No podrá escribirte", destructive = true) {
            onBloquear(otro, nombre)
        }
    } else if (!g.direct) {
        items += FloatingMenuAction("Invitar", "Por @usuario o enlace") { onInvitarGrupo(g.id) }
        items += FloatingMenuAction("Compartir mi ruta") { onCompartirRuta(g.id) }
    }
    items += FloatingMenuAction(
        if (g.muted) "Activar avisos" else "Silenciar",
        if (g.muted) "Volverás a enterarte" else "Los mensajes siguen llegando"
    ) { onSilenciar(g.id, !g.muted) }
    items += FloatingMenuAction(
        if (g.direct) "Borrar chat" else "Salir del grupo",
        "Solo en este teléfono",
        destructive = true
    ) { onEliminarConversacion(g.id) }
    return items
}

private fun accionesGrupo(
    g: TravelGroup,
    onAbrirChat: (String) -> Unit,
    onInvitarGrupo: (String) -> Unit,
    onSilenciar: (String, Boolean) -> Unit,
    onEliminarConversacion: (String) -> Unit,
    onCompartirRuta: (String) -> Unit
): List<FloatingMenuAction> = listOf(
    FloatingMenuAction("Abrir grupo") { onAbrirChat(g.id) },
    FloatingMenuAction("Invitar", "Por @usuario o enlace") { onInvitarGrupo(g.id) },
    FloatingMenuAction("Compartir mi ruta") { onCompartirRuta(g.id) },
    FloatingMenuAction(if (g.muted) "Activar avisos" else "Silenciar") { onSilenciar(g.id, !g.muted) },
    FloatingMenuAction("Salir del grupo", "Solo en este teléfono", destructive = true) {
        onEliminarConversacion(g.id)
    }
)

private fun accionesPersona(
    card: TravelerCard,
    esAmigo: (String?) -> Boolean,
    onVerPerfil: (String, String) -> Unit,
    onEscribir: (String) -> Unit,
    onAgregarAmigo: (String, String, String?) -> Unit,
    onQuitarAmigo: (String, String) -> Unit,
    onBloquear: (String, String) -> Unit,
    onDesbloquear: (String) -> Unit,
    onInvitarPersona: (TravelerCard) -> Unit
): List<FloatingMenuAction> {
    val user = card.username.ifBlank { card.fingerprint.take(8) }
    val items = mutableListOf<FloatingMenuAction>()
    items += FloatingMenuAction("Ver perfil") { onVerPerfil(card.fingerprint, card.username) }
    if (card.isBlocked) {
        items += FloatingMenuAction("Desbloquear") { onDesbloquear(card.fingerprint) }
        return items
    }
    if (card.username.length >= 3) {
        items += FloatingMenuAction("Escribir", "Chat privado") { onEscribir(card.username) }
    }
    if (esAmigo(card.fingerprint) || card.state == ContactState.CONTACTO) {
        items += FloatingMenuAction("Quitar de amigos") { onQuitarAmigo(card.fingerprint, user) }
    } else {
        items += FloatingMenuAction("Agregar a amigos") { onAgregarAmigo(card.fingerprint, user, null) }
    }
    items += FloatingMenuAction("Invitar a un grupo") { onInvitarPersona(card) }
    items += FloatingMenuAction("Bloquear", "No podrá escribirte", destructive = true) {
        onBloquear(card.fingerprint, user)
    }
    return items
}

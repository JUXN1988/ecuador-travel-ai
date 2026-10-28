package ec.travesias.app.ui.community

/**
 * Tiempo relativo corto para listas (chats, feed). Módulo aparte para no
 * duplicar la función privada de CommunityScreen.
 */
fun communityTimeAgo(ts: Long): String {
    if (ts <= 0) return ""
    val diff = (System.currentTimeMillis() - ts) / 1000
    return when {
        diff < 60 -> "ahora"
        diff < 3600 -> "hace ${diff / 60} min"
        diff < 86400 -> "hace ${diff / 3600} h"
        else -> "hace ${diff / 86400} d"
    }
}

package ec.travesias.app.data.social

import android.content.Context

/**
 * En este teléfono guarda el correo con el @usuario, para poder entrar con el correo
 * sin cambiar el cifrado (la cuenta sigue yendo por nombre de usuario).
 */
class AuthPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("casoma_auth", Context.MODE_PRIVATE)

    /** Recuerda correo y @usuario después de crear la cuenta. */
    fun saveEmail(email: String, username: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email.trim().lowercase())
            .putString(KEY_USER, username)
            .apply()
    }

    /** @usuario guardado para ese correo, o null si no hay en este teléfono. */
    fun usernameForEmail(email: String): String? {
        val e = email.trim().lowercase()
        if (e.isBlank()) return null
        if (prefs.getString(KEY_EMAIL, "") != e) return null
        return prefs.getString(KEY_USER, null)?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_USER = "username"
    }
}

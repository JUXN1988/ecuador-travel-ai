package ec.travesias.app.ui.auth

import ec.travesias.app.data.social.AccountService
import ec.travesias.app.data.social.AuthPrefs

/** Correo con forma mínima válida (no se envía a ningún servidor de correo). */
fun correoValido(raw: String): Boolean {
    val t = raw.trim()
    return Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(t)
}

/** Propone un @usuario a partir de la parte izquierda del correo. */
fun usuarioDesdeCorreo(email: String): String {
    val local = email.substringBefore("@")
    return AccountService.normalizeUsername(local).take(20)
}

/**
 * Lo que se manda a createAccount / signIn: si escribieron correo, se usa el @usuario
 * recordado o el derivado del correo; si no, el nombre tal cual.
 */
fun identificadorDeEntrada(raw: String, prefs: AuthPrefs): String {
    val t = raw.trim()
    if (t.contains("@")) {
        return prefs.usernameForEmail(t) ?: usuarioDesdeCorreo(t)
    }
    return AccountService.normalizeUsername(t)
}

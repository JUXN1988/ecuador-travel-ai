package ec.travesias.app.data.local

import android.content.Context
import android.content.SharedPreferences
import ec.travesias.app.data.model.TripIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Idioma de la app: afecta la interfaz y también el idioma en el que responde la IA. */
enum class AppLanguage(val code: String, val label: String, val flag: String) {
    ES("es", "Español", "🇪🇨"),
    EN("en", "English", "🇺🇸");

    companion object {
        fun from(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ES
    }
}

/** Tema de la app: seguir al teléfono, o forzar claro u oscuro. */
enum class TemaApp(val clave: String, val titulo: String, val tituloEn: String) {
    SISTEMA("sistema", "Como el teléfono", "Follow the phone"),
    CLARO("claro", "Claro", "Light"),
    OSCURO("oscuro", "Oscuro", "Dark");

    companion object {
        fun from(clave: String?): TemaApp = entries.firstOrNull { it.clave == clave } ?: SISTEMA
    }
}

/**
 * Ajustes simples de la app, guardados en el teléfono (SharedPreferences).
 * Se inicializa una sola vez en TravesiasApp.onCreate().
 */
object AppSettings {
    private const val PREFS = "travesias_settings"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_BACKGROUND = "background_tracking"
    private const val KEY_MAPA = "mapa_base"
    private const val KEY_TEMA = "app_theme"
    private const val KEY_VIAJE_ORIGEN = "viaje_origen"
    private const val KEY_VIAJE_DESTINO = "viaje_destino"
    private const val KEY_VIAJE_DIAS = "viaje_dias"
    private const val KEY_VIAJE_VIAJEROS = "viaje_viajeros"

    private var prefs: SharedPreferences? = null

    private val _language = MutableStateFlow(AppLanguage.ES)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Mapa base elegido en la pantalla del mapa. Se recuerda entre sesiones. */
    private val _mapLayer = MutableStateFlow("calles")
    val mapLayer: StateFlow<String> = _mapLayer.asStateFlow()

    private val _theme = MutableStateFlow(TemaApp.SISTEMA)
    /** Elección del viajero. Que acabe en claro u oscuro lo decide AppTheme() en la interfaz. */
    val theme: StateFlow<TemaApp> = _theme.asStateFlow()

    /** Último "de dónde a dónde" que escribió el viajero, para no preguntárselo de nuevo. */
    private val _lastTrip = MutableStateFlow(TripIntent())
    val lastTrip: StateFlow<TripIntent> = _lastTrip.asStateFlow()

    // Encendido de fábrica: es lo ÚNICO que hace que los mensajes suenen con la app
    // cerrada, y eso pesa más que el aviso fijo (que va en un canal mínimo y mudo).
    // Se apaga desde Kit -> Permisos, donde se explica el trato con todas sus letras.
    private val _backgroundTracking = MutableStateFlow(true)
    /** Si la app puede quedarse pendiente de mensajes y de la ruta con la pantalla apagada. */
    val backgroundTracking: StateFlow<Boolean> = _backgroundTracking.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _language.value = AppLanguage.from(p.getString(KEY_LANGUAGE, AppLanguage.ES.code))
        _backgroundTracking.value = p.getBoolean(KEY_BACKGROUND, true)
        _mapLayer.value = p.getString(KEY_MAPA, "calles") ?: "calles"
        _theme.value = TemaApp.from(p.getString(KEY_TEMA, TemaApp.SISTEMA.clave))
        _lastTrip.value = TripIntent(
            origin = p.getString(KEY_VIAJE_ORIGEN, "").orEmpty(),
            destination = p.getString(KEY_VIAJE_DESTINO, "").orEmpty(),
            days = p.getInt(KEY_VIAJE_DIAS, 3).coerceIn(1, 30),
            travelers = p.getInt(KEY_VIAJE_VIAJEROS, 2).coerceIn(1, 20)
        )
        // Se pinta el tema ANTES de la primera pantalla: si se hiciera dentro de la
        // composición, la app arrancaría en claro y daría un fogonazo blanco.
        val delSistema = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        ec.travesias.app.ui.theme.aplicarTemaOscuro(
            when (_theme.value) {
                TemaApp.OSCURO -> true
                TemaApp.CLARO -> false
                TemaApp.SISTEMA -> delSistema
            }
        )
    }

    fun setTheme(tema: TemaApp) {
        _theme.value = tema
        prefs?.edit()?.putString(KEY_TEMA, tema.clave)?.apply()
    }

    fun setMapLayer(clave: String) {
        _mapLayer.value = clave
        prefs?.edit()?.putString(KEY_MAPA, clave)?.apply()
    }

    fun setLanguage(lang: AppLanguage) {
        _language.value = lang
        prefs?.edit()?.putString(KEY_LANGUAGE, lang.code)?.apply()
    }

    /**
     * Guarda el último viaje que el viajero escribió en la portada. Sirve para rellenar los
     * formularios y para que, al volver a abrir la app, la primera pregunta ya venga contestada.
     */
    fun setLastTrip(intent: TripIntent) {
        val limpio = intent.copy(
            origin = intent.origin.trim(),
            destination = intent.destination.trim(),
            days = intent.days.coerceIn(1, 30),
            travelers = intent.travelers.coerceIn(1, 20)
        )
        _lastTrip.value = limpio
        prefs?.edit()
            ?.putString(KEY_VIAJE_ORIGEN, limpio.origin)
            ?.putString(KEY_VIAJE_DESTINO, limpio.destination)
            ?.putInt(KEY_VIAJE_DIAS, limpio.days)
            ?.putInt(KEY_VIAJE_VIAJEROS, limpio.travelers)
            ?.apply()
    }

    fun setBackgroundTracking(enabled: Boolean) {
        _backgroundTracking.value = enabled
        prefs?.edit()?.putBoolean(KEY_BACKGROUND, enabled)?.apply()
    }
}

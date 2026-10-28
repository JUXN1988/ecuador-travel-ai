package ec.travesias.app.ui.i18n

import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.model.TransportMode

/**
 * Traducción rápida sin archivos de recursos: t(idioma, "texto en español", "text in english").
 * El texto en los dos idiomas queda junto en el mismo lugar del código, así no se desincroniza.
 */
fun t(lang: AppLanguage, es: String, en: String): String = if (lang == AppLanguage.EN) en else es

/** Nombre del medio de transporte en el idioma activo. */
fun TransportMode.label(lang: AppLanguage): String = if (lang == AppLanguage.EN) displayNameEn else displayName

/** Nombre corto del medio de transporte en el idioma activo. */
fun TransportMode.short(lang: AppLanguage): String = if (lang == AppLanguage.EN) shortNameEn else shortName

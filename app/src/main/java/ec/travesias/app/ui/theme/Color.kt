package ec.travesias.app.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
//  Paleta sacada del logo de "Travesías en Ecuador":
//  anillo azul marino, cerro verde, agua azul y el pin amarillo.
//
//  CÓMO FUNCIONA EL TEMA OSCURO
//  Cada color es una propiedad con getter que mira [modoOscuro]. Como eso es un
//  estado de Compose, leerlo dentro de una pantalla la vuelve a dibujar sola en
//  cuanto cambia el tema. Así el modo oscuro llega a las ~40 pantallas de la app
//  sin tocar ni una: siguen escribiendo `AndeanBlue` como siempre.
//
//  REGLA AL AÑADIR COLORES: si el color es TEXTO o ICONO, en oscuro va claro.
//  Si es un RELLENO que lleva texto blanco encima (botones, tarjetas navy),
//  usa [BrandFill], que se queda oscuro en los dos temas.
// ---------------------------------------------------------------------------

private val modoOscuro = mutableStateOf(false)

/** La cambia AppSettings; no llamarla desde dentro de una composición. */
fun aplicarTemaOscuro(oscuro: Boolean) {
    if (modoOscuro.value != oscuro) modoOscuro.value = oscuro
}

val esTemaOscuro: Boolean get() = modoOscuro.value

private fun dual(claro: Color, oscuro: Color): Color = if (modoOscuro.value) oscuro else claro

// ----------------------------------------------------------------- marca
/** Color principal: TEXTO e iconos. En oscuro se aclara para que se lea. */
val AndeanBlue: Color get() = dual(Color(0xFF002F65), Color(0xFFD5E4FA))

/** Relleno de marca (botones y tarjetas con texto BLANCO encima). Oscuro siempre. */
val BrandFill: Color get() = dual(Color(0xFF002F65), Color(0xFF1B3F73))

/** Lo que va escrito sobre [BrandFill]. */
val OnBrand: Color get() = Color.White

/** Fondos a pantalla completa detrás del paisaje. */
val AndeanBlueDeep: Color get() = dual(Color(0xFF001E42), Color(0xFF05101F))

/** Amarillo del pin, tal cual el logo. Sobre marino, nunca sobre blanco. */
val BrandYellow: Color get() = Color(0xFFFFC501)

/** El mismo amarillo pero legible también sobre claro. */
val EquatorialGold: Color get() = dual(Color(0xFFF0B400), Color(0xFFFFC93C))

/** Azul del agua del logo. */
val SkyAccent: Color get() = dual(Color(0xFF0084E9), Color(0xFF56B0F5))
val SkyLight: Color get() = dual(Color(0xFF2CB9FF), Color(0xFF7FD0FF))

/** Verde del cerro. */
val AndesGreen: Color get() = dual(Color(0xFF007F4C), Color(0xFF3FBF85))
val AndesGreenLight: Color get() = dual(Color(0xFF8DBF42), Color(0xFFA8D45F))

val GalapagosTeal: Color get() = dual(Color(0xFF008080), Color(0xFF35B3B3))

// ----------------------------------------------------------------- neutros
/** Texto secundario. */
val VolcanicGray: Color get() = dual(Color(0xFF4A4E52), Color(0xFFC3CAD3))
/** Texto de apoyo, más suave. */
val MutedGray: Color get() = dual(Color(0xFF6C737A), Color(0xFF98A2AE))
val MistWhite: Color get() = dual(Color(0xFFF8F9FA), Color(0xFF0E1621))

// ----------------------------------------------------------------- estados
val StatusNormal: Color get() = dual(Color(0xFF1E8E3E), Color(0xFF4CC97A))
val StatusCaution: Color get() = dual(Color(0xFFF1C40F), Color(0xFFF5CE3A))
val StatusRestriction: Color get() = dual(Color(0xFFE67E22), Color(0xFFF09449))
val StatusClosed: Color get() = dual(Color(0xFFC0392B), Color(0xFFF0685C))

// ----------------------------------------------------------------- superficies
/** Fondo general de la app. */
val SurfaceLight: Color get() = dual(Color(0xFFF6F8FC), Color(0xFF0D141E))
/** Tarjetas y barras. */
val SurfaceContainerLowest: Color get() = dual(Color(0xFFFFFFFF), Color(0xFF16202D))
val SurfaceContainerLow: Color get() = dual(Color(0xFFF0F3F9), Color(0xFF1A2534))
val SurfaceContainerHigh: Color get() = dual(Color(0xFFE4E9F1), Color(0xFF212E3F))
val SurfaceContainerHighest: Color get() = dual(Color(0xFFDDE3EC), Color(0xFF2A3849))
/** Línea de separación discreta: en un diseño minimalista casi no debe verse. */
val HairLine: Color get() = dual(Color(0xFFE3E8F0), Color(0xFF253243))

// ----------------------------------------------------------------- vidrio
/** Recuadro translúcido sobre el paisaje (portada y entrada a Comunidad). */
val GlassFill: Color get() = dual(Color.White.copy(alpha = 0.80f), Color(0xFF0D141E).copy(alpha = 0.80f))
/** El mismo recuadro cuando lleva formulario y tiene que leerse bien. */
val GlassStrong: Color get() = dual(Color.White.copy(alpha = 0.95f), Color(0xFF111A26).copy(alpha = 0.95f))
/** Borde y pastillas del vidrio. */
val GlassEdge: Color get() = dual(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.14f))
val GlassChip: Color get() = dual(Color.White.copy(alpha = 0.62f), Color(0xFF16202D).copy(alpha = 0.72f))

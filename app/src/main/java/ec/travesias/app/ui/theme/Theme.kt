package ec.travesias.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.local.TemaApp

/**
 * El esquema se arma con los MISMOS tokens de Color.kt, así que cambia solo
 * cuando cambia el tema. `primary` es [BrandFill] (relleno oscuro con texto
 * blanco): eso hace que `contentColorFor` devuelva blanco en los dos temas y
 * los botones de marca se lean siempre.
 */
private fun esquemaClaro() = lightColorScheme(
    primary = BrandFill,
    onPrimary = OnBrand,
    primaryContainer = SurfaceContainerLow,
    onPrimaryContainer = AndeanBlue,
    secondary = SkyAccent,
    onSecondary = Color.White,
    secondaryContainer = SurfaceContainerHigh,
    onSecondaryContainer = AndeanBlue,
    tertiary = AndesGreen,
    onTertiary = Color.White,
    background = SurfaceLight,
    onBackground = VolcanicGray,
    surface = SurfaceLight,
    onSurface = VolcanicGray,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = MutedGray,
    outline = HairLine,
    outlineVariant = HairLine,
    error = StatusClosed,
    onError = Color.White,
)

private fun esquemaOscuro() = darkColorScheme(
    primary = BrandFill,
    onPrimary = OnBrand,
    primaryContainer = SurfaceContainerLow,
    onPrimaryContainer = AndeanBlue,
    secondary = SkyAccent,
    onSecondary = Color(0xFF06182B),
    secondaryContainer = SurfaceContainerHigh,
    onSecondaryContainer = AndeanBlue,
    tertiary = AndesGreen,
    onTertiary = Color(0xFF06182B),
    background = SurfaceLight,
    onBackground = VolcanicGray,
    surface = SurfaceLight,
    onSurface = VolcanicGray,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = MutedGray,
    outline = HairLine,
    outlineVariant = HairLine,
    error = StatusClosed,
    onError = Color(0xFF3A0A06),
)

@Suppress("DEPRECATION")   // statusBarColor/navigationBarColor: en API 35 ya son transparentes solas
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val elegido by AppSettings.theme.collectAsState()
    val sistemaOscuro = isSystemInDarkTheme()
    val oscuro = when (elegido) {
        TemaApp.OSCURO -> true
        TemaApp.CLARO -> false
        TemaApp.SISTEMA -> sistemaOscuro
    }
    // Fuera de la composición: escribir estado DENTRO daría un bucle de recomposición.
    // El valor de arranque ya lo dejó puesto AppSettings.init(), así que no hay fogonazo.
    SideEffect { aplicarTemaOscuro(oscuro) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Barras transparentes: la portada se ve de borde a borde.
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            // Iconos oscuros sobre el cielo claro; claros cuando la app está en oscuro.
            val controlador = WindowCompat.getInsetsController(window, view)
            controlador.isAppearanceLightStatusBars = !oscuro
            controlador.isAppearanceLightNavigationBars = !oscuro
        }
    }

    MaterialTheme(
        colorScheme = if (oscuro) esquemaOscuro() else esquemaClaro(),
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

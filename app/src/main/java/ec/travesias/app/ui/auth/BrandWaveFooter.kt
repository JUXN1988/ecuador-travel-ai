package ec.travesias.app.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.AndesGreen
import ec.travesias.app.ui.theme.EquatorialGold

/**
 * Onda navy / verde / oro al pie de la bienvenida, con los colores de la app.
 */
@Composable
fun BrandWaveFooter(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(52.dp)) {
        val w = size.width
        val h = size.height
        fun onda(yBase: Float, amp: Float): Path = Path().apply {
            moveTo(0f, h)
            lineTo(0f, yBase)
            quadraticTo(w * 0.25f, yBase - amp, w * 0.5f, yBase)
            quadraticTo(w * 0.75f, yBase + amp, w, yBase)
            lineTo(w, h)
            close()
        }
        drawPath(onda(h * 0.35f, h * 0.28f), AndeanBlue)
        drawPath(onda(h * 0.55f, h * 0.22f), AndesGreen)
        drawPath(onda(h * 0.72f, h * 0.18f), EquatorialGold)
        // Punto de ancla, como el pin del logo.
        drawCircle(EquatorialGold, radius = h * 0.12f, center = Offset(w * 0.5f, h * 0.22f))
    }
}

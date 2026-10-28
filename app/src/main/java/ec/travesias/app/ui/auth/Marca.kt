package ec.travesias.app.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.R
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.AndeanBlueDeep
import ec.travesias.app.ui.theme.BrandYellow
import ec.travesias.app.ui.theme.GlassChip
import ec.travesias.app.ui.theme.GlassEdge
import ec.travesias.app.ui.theme.GlassFill
import ec.travesias.app.ui.theme.GlassStrong
import ec.travesias.app.ui.theme.Manuscrita
import ec.travesias.app.ui.theme.Poppins

// ---------------------------------------------------------------------------
//  Piezas de marca compartidas: el fondo, el emblema y el recuadro de vidrio.
//  Las usan la portada (Inicio) y la entrada a Comunidad, para que las dos
//  pantallas se vean como la misma app.
// ---------------------------------------------------------------------------

/** Esquinas del recuadro de vidrio. Un solo valor para toda la app. */
val VidrioForma = RoundedCornerShape(30.dp)

/**
 * Paisaje a pantalla completa.
 * @param conMarca true = la imagen que ya trae el logo, el nombre y el eslogan pintados.
 *                 false = el mismo paisaje limpio, para poner texto propio encima.
 */
@Composable
fun FondoTravesias(modifier: Modifier = Modifier, conMarca: Boolean = true) {
    Box(modifier.background(AndeanBlueDeep)) {
        Image(
            painter = painterResource(if (conMarca) R.drawable.fondo_portada else R.drawable.fondo_paisaje),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )
        // Un velo muy suave abajo: ahí es donde se apoya el recuadro de vidrio.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1.0f to AndeanBlueDeep.copy(alpha = 0.42f)
                    )
                )
        )
    }
}

/** El emblema del logo, con fondo transparente. */
@Composable
fun LogoTravesias(tamano: Int = 84, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_travesias),
        contentDescription = "Travesías en Ecuador",
        modifier = modifier.size(tamano.dp).clip(CircleShape)
    )
}

/**
 * Emblema + nombre + trazo dorado + eslogan: lo mismo que está pintado en la
 * imagen de fondo, pero dibujado por la app para que quepa en cualquier pantalla.
 */
@Composable
fun BrandMark(
    escala: Float = 1f,
    color: Color = AndeanBlue,
    conEmblema: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (conEmblema) {
            LogoTravesias((96 * escala).toInt())
            Spacer(Modifier.height(10.dp))
        }
        Text(
            "Travesías",
            color = color,
            fontFamily = Poppins,
            fontWeight = FontWeight.Bold,
            fontSize = (36 * escala).sp,
            lineHeight = (40 * escala).sp,
            letterSpacing = (-1).sp
        )
        Text(
            "en Ecuador",
            color = color,
            fontFamily = Poppins,
            fontWeight = FontWeight.Bold,
            fontSize = (23 * escala).sp,
            lineHeight = (27 * escala).sp,
            letterSpacing = 2.sp
        )
        TrazoDorado(ancho = (176 * escala).dp)
        Text(
            "Tu destino, en un solo lugar",
            color = color.copy(alpha = 0.92f),
            fontFamily = Manuscrita,
            fontSize = (21 * escala).sp,
            lineHeight = (26 * escala).sp
        )
    }
}

/** El subrayado amarillo del logo, dibujado a mano para que no pixele. */
@Composable
fun TrazoDorado(ancho: Dp = 176.dp, grosor: Dp = 4.dp, color: Color = BrandYellow) {
    Canvas(Modifier.width(ancho).height(grosor + 10.dp)) {
        val w = size.width
        val y = size.height / 2f
        val camino = Path().apply {
            moveTo(0f, y + 5f)
            quadraticTo(w * 0.5f, y - 9f, w, y + 5f)
        }
        drawPath(camino, color, style = Stroke(width = grosor.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Recuadro semitransparente: deja ver el paisaje detrás y a la vez sostiene
 * texto y botones. Es lo que le da el aire limpio a la portada.
 */
@Composable
fun PanelVidrio(
    modifier: Modifier = Modifier,
    opaco: Boolean = false,
    relleno: Int = 20,
    content: @Composable ColumnScope.() -> Unit
) {
    val fondo = if (opaco) GlassStrong else GlassFill
    Column(
        modifier
            .fillMaxWidth()
            .clip(VidrioForma)
            .background(fondo)
            .border(1.dp, GlassEdge, VidrioForma)
            .padding(relleno.dp),
        content = content
    )
}

/** Pastilla del pie: pin + lema del país. */
@Composable
fun PieEcuador(texto: String = "Ecuador, un país por descubrir") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassChip)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(texto, color = AndeanBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Pantalla de arranque mientras se restaura la sesión de este teléfono. */
@Composable
fun SplashTravesias() {
    Box(Modifier.fillMaxSize()) {
        FondoTravesias(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(14.dp))
            PieEcuador()
        }
    }
}

package ec.travesias.app.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.HairLine
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.SurfaceContainerLow
import ec.travesias.app.ui.theme.SurfaceContainerLowest

/**
 * Emojis del chat, agrupados y sin depender del teclado del teléfono.
 * Se eligieron los que un viajero usa de verdad: caras, gestos, naturaleza,
 * transporte, comida y lo típico del camino.
 */
private val GRUPOS: List<Pair<String, List<String>>> = listOf(
    "Caras" to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
        "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😋", "😛",
        "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞", "😔",
        "😟", "😕", "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
        "😤", "😠", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😴"
    ),
    "Gestos" to listOf(
        "👍", "👎", "👌", "🤙", "✌️", "🤞", "🙏", "👏", "🙌", "💪",
        "🤝", "👊", "✋", "🖐️", "👋", "🫶", "❤️", "🧡", "💛", "💚",
        "💙", "💜", "🔥", "✨", "⭐", "🎉", "🎊", "💯", "✅", "❌"
    ),
    "Viaje" to listOf(
        "🏔️", "⛰️", "🌋", "🏕️", "🏞️", "🏖️", "🏝️", "🌅", "🌄", "🌊",
        "🌴", "🌲", "🌳", "🍃", "🌺", "🌸", "🦜", "🐢", "🐋", "🦎",
        "🚌", "🚐", "🚗", "🛻", "🏍️", "🚲", "🚶", "🥾", "🎒", "🗺️",
        "🧭", "📍", "⛺", "🔦", "☀️", "🌧️", "⛈️", "🌫️", "❄️", "🌈"
    ),
    "Comida" to listOf(
        "🍽️", "🍲", "🍜", "🍚", "🐟", "🦐", "🍤", "🌽", "🥑", "🍌",
        "🍍", "🥭", "🍫", "☕", "🧉", "🍺", "🍻", "🥤", "💧", "🍰"
    )
)

/**
 * Panel de emojis. Se abre desde la barra de escribir y cada toque
 * añade el emoji al final del texto: no cierra el panel, para poder poner varios.
 */
@Composable
fun SelectorEmojis(onElegir: (String) -> Unit) {
    var grupo by remember { mutableStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceContainerLowest)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GRUPOS.forEachIndexed { i, (titulo, _) ->
                val activo = i == grupo
                Text(
                    titulo,
                    color = if (activo) AndeanBlue else MutedGray,
                    fontWeight = if (activo) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (activo) SurfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { grupo = i }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HairLine))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
        ) {
            items(GRUPOS[grupo].second) { emoji ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onElegir(emoji) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Tamaño generoso: el emoji ES el contenido, no un adorno del texto.
                    Text(emoji, fontSize = 26.sp)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * Un mensaje que son solo emojis se pinta en grande, como en cualquier chat.
 * Devuelve el tamaño en sp, o null si el texto lleva letras y va normal.
 */
fun tamanoSiSoloEmojis(texto: String): Int? {
    val limpio = texto.trim()
    if (limpio.isEmpty() || limpio.length > 24) return null
    // Si hay letras o números, es un mensaje normal.
    if (limpio.any { it.isLetterOrDigit() }) return null
    val puntos = limpio.codePointCount(0, limpio.length)
    if (puntos == 0) return null
    // Ningún carácter "de texto": entonces son emojis (o signos), y esos no se agrandan.
    val soloSignos = limpio.all { it.isWhitespace() || it.code < 0x2190 }
    if (soloSignos) return null
    return when {
        puntos <= 2 -> 44
        puntos <= 5 -> 34
        puntos <= 9 -> 26
        else -> null
    }
}

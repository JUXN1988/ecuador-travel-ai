package ec.travesias.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.StatusClosed
import ec.travesias.app.ui.theme.SurfaceContainerHigh
import ec.travesias.app.ui.theme.SurfaceContainerLowest
import ec.travesias.app.ui.theme.VolcanicGray

/**
 * Una opción del menú flotante (mantener pulsado).
 * [destructive] pinta el texto en rojo (borrar, bloquear).
 */
data class FloatingMenuAction(
    val title: String,
    val subtitle: String = "",
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** Velos suave: se ve la app detrás; el panel es blanco como el resto de pantallas. */
private val MenuScrim = Color.Black.copy(alpha = 0.28f)
private val MenuShape = RoundedCornerShape(22.dp)

/**
 * Menú flotante a pantalla completa: fondo claro semitransparente y burbuja blanca.
 * Combina con SurfaceLight / SurfaceContainerLowest de la app.
 * Se cierra tocando fuera o con atrás. Cada toque en una opción también lo cierra.
 */
@Composable
fun FloatingContextMenu(
    onDismiss: () -> Unit,
    actions: List<FloatingMenuAction>,
    modifier: Modifier = Modifier,
    headerTitle: String = "",
    headerBody: String = ""
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier
            .fillMaxSize()
            .background(MenuScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .widthIn(max = 340.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* no cierra al tocar el menú */ }
                )
        ) {
            if (headerTitle.isNotBlank() || headerBody.isNotBlank()) {
                MenuBubble(Modifier.padding(bottom = 10.dp)) {
                    if (headerTitle.isNotBlank()) {
                        Text(
                            headerTitle,
                            color = AndeanBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            lineHeight = 24.sp
                        )
                    }
                    if (headerBody.isNotBlank()) {
                        Text(
                            headerBody,
                            color = VolcanicGray,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = if (headerTitle.isNotBlank()) 4.dp else 0.dp)
                        )
                    }
                }
            }
            MenuBubble {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    actions.forEachIndexed { i, action ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    action.onClick()
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            // Título más grande y con aire para que se lea bien sobre blanco.
                            Text(
                                action.title,
                                color = if (action.destructive) StatusClosed else AndeanBlue,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (action.subtitle.isNotBlank()) {
                                Text(
                                    action.subtitle,
                                    color = VolcanicGray,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                        if (i < actions.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.6.dp,
                                color = SurfaceContainerHigh
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Burbuja blanca con sombra suave, igual que las cards de la app. */
@Composable
private fun MenuBubble(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, MenuShape, ambientColor = AndeanBlue.copy(alpha = 0.10f), spotColor = AndeanBlue.copy(alpha = 0.16f))
            .clip(MenuShape)
            .background(SurfaceContainerLowest)
            .border(1.dp, SurfaceContainerHigh, MenuShape)
            .padding(vertical = 8.dp),
        content = content
    )
}

/**
 * Clic normal + mantener pulsado (con vibración) para abrir el menú flotante.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.holdToMenu(onClick: () -> Unit, onHold: () -> Unit): Modifier {
    val view = LocalView.current
    return combinedClickable(
        onClick = onClick,
        onLongClick = {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHold()
        }
    )
}

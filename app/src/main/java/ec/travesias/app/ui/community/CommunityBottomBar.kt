package ec.travesias.app.ui.community

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.EquatorialGold
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.SurfaceContainerLowest

/**
 * Menú de Viajeros abajo: Inicio, Mensajes, Grupos, Amigo y Yo.
 * Misma paleta que la barra de la app (navy + oro). El cifrado no se toca.
 */
@Composable
fun CommunityBottomBar(
    tab: CommunityMundoTab,
    solicitudes: Int,
    onSelect: (CommunityMundoTab) -> Unit
) {
    NavigationBar(
        containerColor = SurfaceContainerLowest,
        tonalElevation = 0.dp,
        // La pantalla de Comunidad ya viene metida dentro de los márgenes del Scaffold.
        // Si esta barra vuelve a aplicar el inset del sistema, queda una FRANJA BLANCA
        // debajo del menú tapando la app. Por eso aquí el inset va a cero.
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        CommunityMundoTab.entries.forEach { item ->
            val seleccionado = item == tab
            NavigationBarItem(
                selected = seleccionado,
                onClick = { onSelect(item) },
                alwaysShowLabel = true,
                icon = {
                    val icono = if (seleccionado) item.filled else item.outlined
                    if (item == CommunityMundoTab.MENSAJES && solicitudes > 0) {
                        BadgedBox(badge = { Badge { Text(solicitudes.coerceAtMost(9).toString()) } }) {
                            Icon(icono, contentDescription = item.titulo, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        Icon(icono, contentDescription = item.titulo, modifier = Modifier.size(22.dp))
                    }
                },
                label = {
                    Text(
                        item.titulo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AndeanBlue,
                    selectedTextColor = AndeanBlue,
                    unselectedIconColor = MutedGray,
                    unselectedTextColor = MutedGray,
                    indicatorColor = EquatorialGold.copy(alpha = 0.25f)
                )
            )
        }
    }
}

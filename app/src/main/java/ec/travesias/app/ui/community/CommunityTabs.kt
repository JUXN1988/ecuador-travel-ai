package ec.travesias.app.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.EquatorialGold

/**
 * Secciones internas de Viajeros (Inicio, Mensajes, Grupos, Amigo, Yo).
 * El menú visible está abajo ([CommunityBottomBar]); esta cabecera solo titula.
 */
enum class CommunityMundoTab(
    val titulo: String,
    val filled: ImageVector,
    val outlined: ImageVector
) {
    INICIO("Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    MENSAJES("Mensajes", Icons.Filled.Forum, Icons.Outlined.Forum),
    GRUPOS("Grupos", Icons.Filled.Groups, Icons.Outlined.Groups),
    GENTE("Amigo", Icons.Filled.People, Icons.Outlined.People),
    PERFIL("Yo", Icons.Filled.Person, Icons.Outlined.Person)
}

/**
 * Cabecera: atrás (vuelve a Mi Ruta), título y publicar.
 * Las pestañas internas van en [CommunityTabs] justo debajo.
 */
@Composable
fun CommunityMundoHeader(
    tab: CommunityMundoTab,
    solicitudes: Int,
    onBackToRoute: () -> Unit,
    onPublicar: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackToRoute) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver a Mi Ruta", tint = AndeanBlue)
        }
        Text(
            text = when (tab) {
                CommunityMundoTab.INICIO -> "Viajeros"
                CommunityMundoTab.MENSAJES -> "Mensajes"
                CommunityMundoTab.GRUPOS -> "Grupos"
                CommunityMundoTab.GENTE -> "Amigos"
                CommunityMundoTab.PERFIL -> "Mi perfil"
            },
            fontWeight = FontWeight.Bold,
            color = AndeanBlue,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f)
        )
        if (tab == CommunityMundoTab.MENSAJES && solicitudes > 0) {
            Text(
                "$solicitudes",
                color = EquatorialGold,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
        IconButton(onClick = onPublicar) {
            Icon(
                Icons.Outlined.AddBox,
                contentDescription = "Publicar fotos",
                tint = AndeanBlue,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Cinco secciones con icono y nombre, para que se distinga cada una.
 */
@Composable
fun CommunityTabs(
    tab: CommunityMundoTab,
    solicitudes: Int,
    onSelect: (CommunityMundoTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommunityMundoTab.entries.forEach { item ->
            val seleccionado = item == tab
            Column(
                Modifier
                    .width(64.dp)
                    .clickable { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val icono = if (seleccionado) item.filled else item.outlined
                if (item == CommunityMundoTab.MENSAJES && solicitudes > 0) {
                    BadgedBox(badge = { Badge { Text(solicitudes.coerceAtMost(9).toString()) } }) {
                        Icon(icono, contentDescription = item.titulo, tint = AndeanBlue, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Icon(icono, contentDescription = item.titulo, tint = AndeanBlue, modifier = Modifier.size(22.dp))
                }
                Text(
                    item.titulo,
                    color = AndeanBlue,
                    fontWeight = if (seleccionado) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

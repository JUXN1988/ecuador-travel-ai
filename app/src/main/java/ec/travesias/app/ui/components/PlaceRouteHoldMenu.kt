package ec.travesias.app.ui.components

import androidx.compose.runtime.Composable
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.RouteDraft
import ec.travesias.app.data.model.TravelRoute

/**
 * Menú flotante para meter un lugar encontrado (Radar IA, catálogo, mapa)
 * en la ruta activa, en otra ruta ya guardada, o en el itinerario que la IA está armando.
 */
@Composable
fun PlaceRouteHoldMenu(
    place: Place,
    activeRoute: TravelRoute?,
    otherRoutes: List<TravelRoute>,
    draft: RouteDraft?,
    inActiveRoute: Boolean,
    onDismiss: () -> Unit,
    onAddToActive: () -> Unit,
    onAddToDraft: () -> Unit,
    onAddToRoute: (routeId: String, routeName: String) -> Unit
) {
    val acciones = buildList {
        if (activeRoute != null && !inActiveRoute) {
            add(
                FloatingMenuAction(
                    title = "Añadir a mi ruta",
                    subtitle = activeRoute.name.ifBlank { "La que tienes abierta" },
                    onClick = onAddToActive
                )
            )
        } else if (activeRoute == null) {
            add(
                FloatingMenuAction(
                    title = "Añadir a mi ruta",
                    subtitle = "Se usa o se crea tu travesía",
                    onClick = onAddToActive
                )
            )
        }
        if (draft != null) {
            val yaEnBorrador = draft.stops.any { it.place.id == place.id }
            if (!yaEnBorrador) {
                add(
                    FloatingMenuAction(
                        title = "Añadir al itinerario de la IA",
                        subtitle = draft.name.ifBlank { "${draft.originName} → ${draft.destinationName}" },
                        onClick = onAddToDraft
                    )
                )
            }
        }
        otherRoutes.forEach { r ->
            val ya = r.stops.any { it.placeId == place.id }
            if (!ya) {
                add(
                    FloatingMenuAction(
                        title = "Añadir a «${r.name}»",
                        subtitle = "${r.originName} → ${r.destinationName}".trim(' ', '→')
                            .ifBlank { "${r.totalDays} día(s)" },
                        onClick = { onAddToRoute(r.id, r.name) }
                    )
                )
            }
        }
    }
    FloatingContextMenu(
        onDismiss = onDismiss,
        headerTitle = place.name,
        headerBody = "¿Dónde lo quieres sumar?",
        actions = acciones.ifEmpty {
            listOf(FloatingMenuAction("Añadir a mi ruta") { onAddToActive() })
        }
    )
}

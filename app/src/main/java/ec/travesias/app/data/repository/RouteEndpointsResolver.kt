package ec.travesias.app.data.repository

import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.remote.RoutingService
import java.util.concurrent.ConcurrentHashMap

/**
 * Ubica en el mapa el ORIGEN y el DESTINO escritos en la ruta ("Santo Domingo", "Montañita").
 *
 * Sin esto el horario empezaba en la primera parada como si uno ya estuviera ahí. Busca primero
 * en el catálogo y en el nomenclátor del teléfono (funciona sin señal); solo si el nombre no está
 * pide coordenadas a OpenStreetMap una vez, y el nomenclátor las guarda para siempre.
 */
class RouteEndpointsResolver(
    private val index: PlaceIndexRepository,
    private val routes: RouteRepository
) {
    /** Caché en memoria: nombre normalizado → punto (o null si no se pudo ubicar). */
    private val cache = ConcurrentHashMap<String, Optional>()

    private data class Optional(val point: RouteSchedule.Point?)

    suspend fun resolve(route: TravelRoute?): RouteSchedule.Endpoints {
        if (route == null) return RouteSchedule.Endpoints.NONE
        val origen = point(route.originName)
        val destino = point(route.destinationName)
        val idaYVuelta = isRoundTrip(route.originName, route.destinationName, origen, destino)
        return RouteSchedule.Endpoints(
            origin = origen,
            finish = if (idaYVuelta) origen else destino,
            roundTrip = idaYVuelta
        )
    }

    private suspend fun point(nombre: String): RouteSchedule.Point? {
        val q = normalizeSearch(nombre)
        if (q.length < 2) return null
        cache[q]?.let { return it.point }

        val encontrado = runCatching { buscar(nombre, q) }.getOrNull()
        // Solo se recuerda lo que SÍ se ubicó: si falló por falta de señal se reintenta luego.
        if (encontrado != null) cache[q] = Optional(encontrado)
        return encontrado
    }

    private suspend fun buscar(nombre: String, q: String): RouteSchedule.Point? {
        val catalogo = runCatching { routes.allPlacesOnce() }.getOrDefault(emptyList())
        catalogo.firstOrNull { normalizeSearch(it.name) == q }?.let {
            return RouteSchedule.Point(nombre.trim(), it.latitude, it.longitude)
        }
        val sugerencias = runCatching { index.suggest(nombre, 5) }.getOrDefault(emptyList())
        val mejor = sugerencias.firstOrNull { normalizeSearch(it.name) == q } ?: sugerencias.firstOrNull()
            ?: return null
        val coords = index.resolveCoordinates(mejor) ?: return null
        if (!PlaceJsonParser.isInEcuador(coords.first, coords.second)) return null
        return RouteSchedule.Point(nombre.trim(), coords.first, coords.second)
    }

    companion object {
        /** Ida y vuelta: mismo nombre, o dos puntos a menos de 5 km. */
        fun isRoundTrip(
            originName: String, destinationName: String,
            origin: RouteSchedule.Point?, destination: RouteSchedule.Point?
        ): Boolean {
            val a = normalizeSearch(originName)
            val b = normalizeSearch(destinationName)
            if (a.isNotBlank() && a == b) return true
            if (origin != null && destination != null) {
                return RoutingService.haversineKm(origin.latitude, origin.longitude, destination.latitude, destination.longitude) < 5.0
            }
            return false
        }
    }
}

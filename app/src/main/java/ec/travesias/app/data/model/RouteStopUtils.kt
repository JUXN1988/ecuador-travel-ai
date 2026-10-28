package ec.travesias.app.data.model

/**
 * Las paradas de una ruta, sin los extremos.
 *
 * Al guardar una ruta, el origen y el destino se guardan como paradas reales: así el horario y
 * la carretera saben desde dónde se sale y hasta dónde se llega. Pero para el viajero NO son
 * "paradas que visitar": nadie marca "llegué a Quito" cuando sale de Quito. Si se contaran,
 * la app diría "0 de 6 paradas" y la siguiente parada sería la propia salida.
 *
 * Esta extensión quita la primera parada si coincide con el origen y la última si coincide con
 * el destino. Si la ruta no tiene extremos con nombre (una ruta hecha a mano), devuelve todas.
 */
fun TravelRoute.stopsInOrder(): List<RouteStop> =
    stops.sortedWith(compareBy({ it.dayNumber }, { it.orderInDay }))

fun TravelRoute.journeyStops(): List<RouteStop> {
    val ordenadas = stopsInOrder()
    if (ordenadas.isEmpty()) return ordenadas
    var desde = 0
    var hasta = ordenadas.size
    if (originName.isNotBlank() && ordenadas.first().placeName.equals(originName.trim(), ignoreCase = true)) {
        desde = 1
    }
    if (hasta > desde && destinationName.isNotBlank() &&
        ordenadas.last().placeName.equals(destinationName.trim(), ignoreCase = true)
    ) {
        hasta -= 1
    }
    return ordenadas.subList(desde, hasta)
}

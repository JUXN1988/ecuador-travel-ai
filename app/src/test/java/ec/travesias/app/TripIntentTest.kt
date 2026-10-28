package ec.travesias.app

import ec.travesias.app.data.model.DraftStop
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.RadarRequest
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.SearchArea
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.TripIntent
import ec.travesias.app.data.model.journeyStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas del modo "solo destino", que es el corazón del cambio de producto:
 * si el viajero no escribe de dónde sale, la app NO debe inventar una ruta ni buscar por todo
 * el país; debe buscar alrededor del destino y decirlo con claridad.
 */
class TripIntentTest {

    @Test
    fun `sin origen y con destino es modo solo destino`() {
        val intent = TripIntent(origin = "", destination = "Baños de Agua Santa")
        assertTrue(intent.destinationOnly)
        assertTrue(intent.ready)
        assertEquals("Alrededor de Baños de Agua Santa", intent.label)
    }

    @Test
    fun `con origen ya es una ruta normal`() {
        val intent = TripIntent(origin = "Quito", destination = "Baños")
        assertFalse(intent.destinationOnly)
        assertEquals("Quito → Baños", intent.label)
    }

    @Test
    fun `sin destino no se puede planear`() {
        assertFalse(TripIntent(origin = "Quito").ready)
        assertFalse(TripIntent().destinationOnly)
    }

    @Test
    fun `la consulta al radar distingue destino unico`() {
        val soloDestino = RadarRequest(originName = "", destinationName = "Cuenca", maxDetourKm = 30)
        assertTrue(soloDestino.onlyDestination)
        assertEquals("Alrededor de Cuenca", soloDestino.label)

        val conRuta = RadarRequest(originName = "Loja", destinationName = "Cuenca")
        assertFalse(conRuta.onlyDestination)
        assertEquals("Loja → Cuenca", conRuta.label)
    }

    @Test
    fun `el radio de busqueda se lee sin decimales cuando es entero`() {
        val area = SearchArea("Baños", -1.39, -78.42, 25.0)
        assertEquals("25 km", area.radiusLabel)
    }

    @Test
    fun `una parada no dice que esta fuera de la via aunque no haya via`() {
        val lugar = Place(
            name = "Cascada",
            category = PlaceCategory.NATURE,
            region = EcuadorRegion.ANDES,
            latitude = -1.0,
            longitude = -78.0
        )
        val parada = DraftStop(place = lugar, detourKm = 4.0)
        assertEquals("a 4.0 km", parada.detourLabel)
    }

    @Test
    fun `la salida y la llegada no cuentan como paradas del viaje`() {
        val salida = RouteStop(
            routeId = "r", placeId = "p-salida", placeName = "Quito",
            latitude = -0.18, longitude = -78.47, dayNumber = 1, orderInDay = 0
        )
        val visita = RouteStop(
            routeId = "r", placeId = "p-visita", placeName = "Cascada",
            latitude = -1.0, longitude = -78.0, dayNumber = 1, orderInDay = 1
        )
        val llegada = RouteStop(
            routeId = "r", placeId = "p-llegada", placeName = "Baños",
            latitude = -1.39, longitude = -78.42, dayNumber = 1, orderInDay = 2
        )
        val ruta = TravelRoute(
            name = "Quito → Baños", originName = "Quito", destinationName = "Baños",
            totalDays = 1, stops = listOf(salida, visita, llegada)
        )
        assertEquals(listOf("Cascada"), ruta.journeyStops().map { it.placeName })
    }

    @Test
    fun `una ruta sin extremos con nombre conserva todas sus paradas`() {
        val a = RouteStop(routeId = "r", placeId = "a", placeName = "A", latitude = 0.0, longitude = -78.0)
        val b = RouteStop(routeId = "r", placeId = "b", placeName = "B", latitude = 1.0, longitude = -78.0, orderInDay = 1)
        val ruta = TravelRoute(name = "A mano", stops = listOf(a, b))
        assertEquals(2, ruta.journeyStops().size)
    }

    @Test
    fun `una escapada con base deja solo las visitas`() {
        val base = RouteStop(
            routeId = "r", placeId = "p-base", placeName = "Baños",
            latitude = -1.39, longitude = -78.42, dayNumber = 1, orderInDay = 0
        )
        val visita = RouteStop(
            routeId = "r", placeId = "p-visita", placeName = "Cascada",
            latitude = -1.40, longitude = -78.43, dayNumber = 1, orderInDay = 1
        )
        val regreso = base.copy(id = "p-regreso", orderInDay = 2)
        val ruta = TravelRoute(
            name = "Escapadas desde Baños", originName = "Baños", destinationName = "Baños",
            totalDays = 1, stops = listOf(base, visita, regreso)
        )
        assertEquals(listOf("Cascada"), ruta.journeyStops().map { it.placeName })
    }
}

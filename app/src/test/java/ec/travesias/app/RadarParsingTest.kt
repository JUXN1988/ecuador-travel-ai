package ec.travesias.app

import ec.travesias.app.data.local.SeedData
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.remote.JsonExtract
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.remote.RoutingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadarParsingTest {

    private val sampleAiText = """
        Aquí tienes los resultados:
        ```json
        {
          "summary": "Baños está de moda por el Pailón del Diablo.",
          "places": [
            {
              "name": "Pailón del Diablo",
              "category": "adventure",
              "province": "Tungurahua",
              "city": "Río Verde",
              "latitude": -1.4030,
              "longitude": -78.3010,
              "short_description": "Cascada de 80 m.",
              "cost_min_usd": 2,
              "cost_max_usd": 5,
              "opening_hours": "09:00-17:00",
              "how_to_get_there": "Bus Baños-Puyo, bajar en Río Verde.",
              "transport_options": ["bus", "bicicleta"],
              "tags": ["cascada"],
              "contact": {"phone": "032 000 000", "instagram": "@pailondeldiablo"},
              "social_mentions": 24,
              "sources": [{"platform": "tiktok", "url": "https://www.tiktok.com/@x/video/1", "title": "Pailón"}]
            },
            { "name": "Lugar sin coordenadas", "category": "food", "province": "Pichincha" }
          ]
        }
        ```
    """.trimIndent()

    /**
     * El bug de "no hay internet": cuando gemini-2.5-flash se queda sin presupuesto de tokens
     * corta el JSON a la mitad. Antes se perdía la respuesta entera; ahora se rescatan los
     * lugares que sí llegaron completos.
     */
    private val respuestaCortada = """
        {
          "summary": "Paradas en la vía",
          "places": [
            {"name": "Cascada Manto de la Novia", "category": "nature", "latitude": -1.3970, "longitude": -78.3520},
            {"name": "Mirador de la Virgen", "category": "viewpoint", "latitude": -1.3960, "longitude": -78.4230},
            {"name": "Hueca de las tilapias", "category": "food", "latitude": -1.4
    """.trimIndent()

    @Test
    fun `rescata los lugares de un JSON que se corto a la mitad`() {
        // El parser estricto no puede: el objeto raíz nunca se cierra.
        assertNull(JsonExtract.firstObject(respuestaCortada))

        val objetos = JsonExtract.objectsInArray(respuestaCortada, "places")
        assertEquals(2, objetos.size)
        assertEquals("Cascada Manto de la Novia", objetos[0].optString("name"))
        assertEquals("Mirador de la Virgen", objetos[1].optString("name"))
        assertNotNull(PlaceJsonParser.parse(objetos[0]))
    }

    @Test
    fun `objectsInArray tambien sirve con el array suelto`() {
        val objetos = JsonExtract.objectsInArray(sampleAiText, "places")
        assertEquals(2, objetos.size)
        assertEquals("Pailón del Diablo", objetos[0].optString("name"))
    }

    @Test
    fun `extrae el JSON aunque venga envuelto en markdown`() {
        val root = JsonExtract.firstObject(sampleAiText)
        assertNotNull(root)
        assertEquals("Baños está de moda por el Pailón del Diablo.", root!!.optString("summary"))
        assertEquals(2, root.optJSONArray("places")!!.length())
    }

    @Test
    fun `convierte el JSON de la IA en un Place completo`() {
        val arr = JsonExtract.firstObject(sampleAiText)!!.getJSONArray("places")
        val place = PlaceJsonParser.parse(arr.getJSONObject(0))!!
        assertEquals("Pailón del Diablo", place.name)
        assertEquals(PlaceCategory.ADVENTURE, place.category)
        assertEquals(EcuadorRegion.ANDES, place.region)
        assertEquals(-1.4030, place.latitude, 0.0001)
        assertEquals("$2 - $5 USD", place.costLabel)
        assertEquals(2, place.transportOptions.size)
        assertEquals("032 000 000", place.contact.phone)
        assertEquals(1, place.sources.size)
        assertEquals(24, place.socialMentions)
        assertEquals(ec.travesias.app.data.model.VerificationLevel.VERIFIED, place.verification)
    }

    @Test
    fun `un lugar sin coordenadas queda fuera de Ecuador hasta geocodificar`() {
        val arr = JsonExtract.firstObject(sampleAiText)!!.getJSONArray("places")
        val place = PlaceJsonParser.parse(arr.getJSONObject(1))!!
        assertFalse(PlaceJsonParser.isInEcuador(place.latitude, place.longitude))
        assertEquals(PlaceCategory.FOOD, place.category)
    }

    @Test
    fun `el catalogo semilla tiene coordenadas validas y regiones coherentes`() {
        assertTrue(SeedData.places.size >= 25)
        SeedData.places.forEach { p ->
            assertTrue("${p.name} fuera de Ecuador", PlaceJsonParser.isInEcuador(p.latitude, p.longitude))
            assertTrue("${p.name} sin descripción", p.shortDescription.isNotBlank())
            assertTrue("${p.name} sin cómo llegar", p.howToGetThere.isNotBlank())
        }
        assertEquals(SeedData.places.size, SeedData.places.map { it.id }.distinct().size)
        assertEquals(EcuadorRegion.GALAPAGOS, EcuadorRegion.fromCoordinates(-0.7650, -90.3360))
        assertEquals(EcuadorRegion.COAST, EcuadorRegion.fromCoordinates(-2.1930, -79.8800))
        assertEquals(EcuadorRegion.ANDES, EcuadorRegion.fromCoordinates(-0.2202, -78.5123))
        assertEquals(EcuadorRegion.AMAZON, EcuadorRegion.fromCoordinates(-1.0330, -77.6670))
    }

    @Test
    fun `haversine y distancia a corredor`() {
        val quitoToGye = RoutingService.haversineKm(-0.2202, -78.5123, -2.1930, -79.8800)
        assertTrue(quitoToGye in 260.0..280.0)
        val corridor = listOf(Pair(-0.2202, -78.5123), Pair(-1.0, -78.6), Pair(-2.1930, -79.8800))
        val nearMid = RoutingService.distanceToPathKm(-1.0, -78.7, corridor)
        assertTrue(nearMid < 15.0)
        val farAway = RoutingService.distanceToPathKm(0.5, -80.0, corridor)
        assertTrue(farAway > 100.0)
    }
}

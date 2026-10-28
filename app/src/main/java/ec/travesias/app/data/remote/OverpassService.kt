package ec.travesias.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Overpass: el buscador de OpenStreetMap.
 *
 * QUÉ APORTA QUE NO TENÍA LA APP
 * Hasta ahora los lugares del camino salían de la IA, que sabe lo que está de moda pero se
 * inventa coordenadas, se olvida de pueblos y depende de que haya cuota y señal. Overpass es
 * la base de datos que hay DETRÁS del mapa: tiene cada pueblo, cada cascada, cada gasolinera y
 * cada hostal con sus coordenadas exactas, gratis, sin clave y sin límite de cuota.
 *
 * LA CLAVE: BUSCAR POR CORREDOR, NO POR CAJA
 * Overpass permite pedir "todo lo que esté a X metros de ESTA línea" pasándole la polilínea de
 * la carretera. Así no salen cosas de la otra punta de la provincia: sale lo que de verdad
 * está a mano yendo por esa vía.
 *
 * BUENA VECINDAD
 * Los servidores son públicos y gratuitos: se pide UNA sola consulta por búsqueda (no una por
 * pueblo), se limita el número de resultados, y si un servidor está saturado se prueba el
 * siguiente en vez de insistir.
 */
class OverpassService {

    /** Servidores públicos, en orden. Si uno está saturado se pasa al siguiente. */
    private val servidores = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.osm.ch/api/interpreter"
    )

    /** Un elemento de OpenStreetMap ya reducido a lo que la app necesita. */
    data class Elemento(
        val id: String,
        val lat: Double,
        val lng: Double,
        val tags: Map<String, String>
    ) {
        val nombre: String get() = tags["name"].orEmpty()
    }

    class SinRespuesta(mensaje: String) : Exception(mensaje)

    /**
     * Todo lo interesante que hay a [radioMetros] de la línea del camino.
     *
     * @param camino polilínea de la carretera (lat, lng). Se recorta a un número razonable de
     *   puntos antes de mandarla: con 60 basta para cubrir el corredor y la consulta no se hace
     *   gigante.
     */
    suspend fun alrededorDelCamino(
        camino: List<Pair<Double, Double>>,
        radioMetros: Int = 4000,
        maxResultados: Int = 700
    ): List<Elemento> = withContext(Dispatchers.IO) {
        if (camino.size < 2) return@withContext emptyList()
        val coords = comprimir(camino, 60).joinToString(",") {
            String.format(java.util.Locale.US, "%.5f,%.5f", it.first, it.second)
        }
        val r = radioMetros.coerceIn(500, 25_000)
        val consulta = buildString {
            append("[out:json][timeout:80];(")
            // Lo que un viajero para a ver
            append("nwr(around:$r,$coords)[\"tourism\"~\"attraction|viewpoint|museum|artwork|gallery|picnic_site|camp_site|hotel|hostel|guest_house|motel|information|zoo|theme_park\"];")
            append("nwr(around:$r,$coords)[\"natural\"~\"waterfall|hot_spring|peak|volcano|cave_entrance|beach|spring\"];")
            append("nwr(around:$r,$coords)[\"historic\"];")
            append("nwr(around:$r,$coords)[\"place\"~\"square|city_gate\"];")
            append("nwr(around:$r,$coords)[\"leisure\"~\"park|nature_reserve|water_park|swimming_pool\"];")
            // Comida y lo práctico del camino
            append("nwr(around:$r,$coords)[\"amenity\"~\"restaurant|cafe|fast_food|marketplace|place_of_worship|fuel|hospital|clinic|pharmacy|atm|bank|bus_station|drinking_water|toilets\"];")
            append("nwr(around:$r,$coords)[\"shop\"~\"bakery|supermarket|greengrocer\"];")
            append(");out center $maxResultados;")
        }
        pedir(consulta)
    }

    /**
     * Los pueblos del camino: ciudades, cabeceras, parroquias y caseríos. Es lo que ordena
     * toda la búsqueda ("primero pasas por X, luego por Y"), y lo que la IA se saltaba.
     */
    suspend fun pobladosDelCamino(
        camino: List<Pair<Double, Double>>,
        radioMetros: Int = 6000,
        maxResultados: Int = 300
    ): List<Elemento> = withContext(Dispatchers.IO) {
        if (camino.size < 2) return@withContext emptyList()
        val coords = comprimir(camino, 60).joinToString(",") {
            String.format(java.util.Locale.US, "%.5f,%.5f", it.first, it.second)
        }
        val r = radioMetros.coerceIn(500, 25_000)
        val consulta = "[out:json][timeout:60];" +
            "(node(around:$r,$coords)[\"place\"~\"city|town|village|hamlet|suburb\"][\"name\"];);" +
            "out $maxResultados;"
        pedir(consulta)
    }

    /**
     * Lo mismo que [alrededorDelCamino] pero alrededor de UN punto. Es lo que usa el modo
     * "solo destino": el viajero no dio origen, así que no hay vía que recorrer y lo correcto
     * es mirar qué hay en un radio alrededor del destino (una hora de carro, no una provincia).
     */
    suspend fun alrededorDelPunto(
        lat: Double,
        lng: Double,
        radioMetros: Int = 15_000,
        maxResultados: Int = 500
    ): List<Elemento> = withContext(Dispatchers.IO) {
        val punto = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng)
        val r = radioMetros.coerceIn(500, 25_000)
        val consulta = buildString {
            append("[out:json][timeout:80];(")
            append("nwr(around:$r,$punto)[\"tourism\"~\"attraction|viewpoint|museum|artwork|gallery|picnic_site|camp_site|hotel|hostel|guest_house|motel|information|zoo|theme_park\"];")
            append("nwr(around:$r,$punto)[\"natural\"~\"waterfall|hot_spring|peak|volcano|cave_entrance|beach|spring\"];")
            append("nwr(around:$r,$punto)[\"historic\"];")
            append("nwr(around:$r,$punto)[\"place\"~\"square|city_gate\"];")
            append("nwr(around:$r,$punto)[\"leisure\"~\"park|nature_reserve|water_park|swimming_pool\"];")
            append("nwr(around:$r,$punto)[\"amenity\"~\"restaurant|cafe|fast_food|marketplace|place_of_worship|fuel|hospital|clinic|pharmacy|atm|bank|bus_station|drinking_water|toilets\"];")
            append("nwr(around:$r,$punto)[\"shop\"~\"bakery|supermarket|greengrocer\"];")
            append(");out center $maxResultados;")
        }
        pedir(consulta)
    }

    /** Pueblos y parroquias alrededor de un punto, ordenables por cercanía. */
    suspend fun pobladosAlrededorDe(
        lat: Double,
        lng: Double,
        radioMetros: Int = 20_000,
        maxResultados: Int = 200
    ): List<Elemento> = withContext(Dispatchers.IO) {
        val punto = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng)
        val r = radioMetros.coerceIn(500, 25_000)
        val consulta = "[out:json][timeout:60];" +
            "(node(around:$r,$punto)[\"place\"~\"city|town|village|hamlet|suburb\"][\"name\"];);" +
            "out $maxResultados;"
        pedir(consulta)
    }

    // ======================================================================
    //  Internos
    // ======================================================================

    private suspend fun pedir(consulta: String): List<Elemento> {
        var ultimo: Exception? = null
        for (servidor in servidores) {
            val respuesta = runCatching { Http.postForm(servidor, mapOf("data" to consulta)) }
            respuesta.getOrNull()?.let { return leer(it) }
            ultimo = respuesta.exceptionOrNull() as? Exception
        }
        throw SinRespuesta(
            "OpenStreetMap no respondió: " + (ultimo?.message ?: "sin detalle") +
                ". Suele ser momentáneo; vuelve a intentarlo en un minuto."
        )
    }

    private fun leer(raw: String): List<Elemento> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("elements") ?: return emptyList()
        val salida = ArrayList<Elemento>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Un punto tiene lat/lon; una zona (un parque, un hotel grande) trae su centro.
            val centro = o.optJSONObject("center")
            val lat = if (o.has("lat")) o.optDouble("lat", 0.0) else centro?.optDouble("lat", 0.0) ?: 0.0
            val lng = if (o.has("lon")) o.optDouble("lon", 0.0) else centro?.optDouble("lon", 0.0) ?: 0.0
            if (lat == 0.0 && lng == 0.0) continue
            val tagsJson = o.optJSONObject("tags") ?: continue
            val tags = HashMap<String, String>()
            tagsJson.keys().forEach { k -> tags[k] = tagsJson.optString(k, "") }
            if (tags["name"].isNullOrBlank()) continue   // sin nombre no le sirve a nadie
            salida += Elemento(
                id = (o.optString("type", "n").take(1)) + o.optLong("id", 0L),
                lat = lat, lng = lng, tags = tags
            )
        }
        return salida
    }

    /**
     * Deja la polilínea en como mucho [maximo] puntos repartidos por igual. Mandar los 2.000
     * puntos que devuelve el ruteador haría la consulta enorme sin cubrir ni un metro más.
     */
    private fun comprimir(camino: List<Pair<Double, Double>>, maximo: Int): List<Pair<Double, Double>> {
        if (camino.size <= maximo) return camino
        val paso = camino.size.toDouble() / maximo
        val salida = ArrayList<Pair<Double, Double>>(maximo)
        var i = 0.0
        while (i < camino.size && salida.size < maximo) {
            salida += camino[i.toInt()]
            i += paso
        }
        if (salida.last() != camino.last()) salida[salida.size - 1] = camino.last()
        return salida
    }
}

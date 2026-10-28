package ec.travesias.app.data.remote

import ec.travesias.app.data.local.GazetteerData
import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.PlaceCategory
import ec.travesias.app.data.model.PlaceContact
import ec.travesias.app.data.model.PlaceSource
import ec.travesias.app.data.model.RadarRequest
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.SourcePlatform
import ec.travesias.app.data.model.VerificationLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * **Explorador de ruta**: barre el camino de verdad, pueblo por pueblo.
 *
 * QUÉ PROBLEMA RESUELVE
 * El Radar IA pregunta a un modelo qué hay en una ruta. Eso está muy bien para lo que se ha
 * puesto de moda, pero tiene tres agujeros conocidos: se salta pueblos enteros, se inventa
 * coordenadas, y si se acaba la cuota o falla la red no sale nada. Este explorador va por otro
 * lado: coge la carretera REAL (la misma polilínea que pinta el mapa), saca de OpenStreetMap
 * todos los poblados por los que se pasa y todo lo que hay alrededor de cada uno, y le suma lo
 * que ya tiene la app y lo que aportó la comunidad.
 *
 * RESULTADO
 *  - Sale MUCHO más que con la IA sola (cientos de sitios en vez de una docena).
 *  - Todo con coordenadas exactas: no hay que adivinar dónde queda.
 *  - Ordenado por avance del viaje: "primero pasas por Alluriquín, luego por Tandapi…".
 *  - Funciona aunque Gemini esté caído o sin cuota. La IA, cuando está, se suma encima para
 *    contar qué merece la pena; no es la que sostiene la búsqueda.
 */
class RouteScoutService(
    private val geocoding: GeocodingService,
    private val routing: RoutingService,
    private val overpass: OverpassService = OverpassService()
) {

    /** Un pueblo del camino con lo que tiene alrededor. */
    data class Tramo(
        val pueblo: String,
        val provincia: String,
        val latitude: Double,
        val longitude: Double,
        /** Kilómetro del viaje en el que aparece. Es lo que ordena todo. */
        val kmDelViaje: Double,
        val lugares: List<Place>
    )

    /** Lo que devuelve una exploración. */
    data class Exploracion(
        val corredor: List<Pair<Double, Double>>,
        val tramos: List<Tramo>,
        val lugares: List<Place>,
        val distanciaKm: Double,
        val desvioKmPorLugar: Map<String, Double>,
        val resumen: String,
        val problema: String = ""
    )

    // ======================================================================
    //  Lo que hace
    // ======================================================================

    /**
     * @param corredor la polilínea de la carretera si ya está trazada (la del mapa). Si viene
     *   vacía se traza aquí.
     * @param catalogo lugares que ya tiene la app (curados, del radar, de la comunidad): se
     *   suman a lo que trae OpenStreetMap si caen dentro del corredor.
     */
    suspend fun explorar(
        request: RadarRequest,
        corredor: List<Pair<Double, Double>> = emptyList(),
        catalogo: List<Place> = emptyList(),
        onProgress: (String) -> Unit = {}
    ): Exploracion = withContext(Dispatchers.IO) {
        // -------- 0. ¿Hay ruta o solo destino? --------
        // Sin salida no hay carretera que recorrer. Antes esto se intentaba trazar igual y
        // fallaba con "no se pudo trazar el camino", dejando al viajero sin nada. Ahora se
        // busca alrededor del destino, que es lo que de verdad pidió.
        val soloDestino = request.onlyDestination
        val centro: Pair<Double, Double>?
        val camino: List<Pair<Double, Double>>
        if (soloDestino) {
            onProgress("Ubicando " + request.destinationName + "…")
            centro = ubicarPunto(request.destinationName)
            if (centro == null) {
                return@withContext Exploracion(
                    emptyList(), emptyList(), emptyList(), 0.0, emptyMap(), "",
                    problema = "No pude ubicar \"" + request.destinationName + "\". Escríbelo como sale en el mapa (por ejemplo \"Baños de Agua Santa\")."
                )
            }
            camino = listOf(centro)
        } else {
            onProgress("Trazando el camino…")
            camino = corredor.takeIf { it.size >= 2 } ?: trazar(request)
            if (camino.size < 2) {
                return@withContext Exploracion(
                    emptyList(), emptyList(), emptyList(), 0.0, emptyMap(), "",
                    problema = "No se pudo trazar el camino entre \"" + request.originName + "\" y \"" +
                        request.destinationName + "\". Revisa los nombres o elígelos de la lista."
                )
            }
            centro = null
        }

        val acumulados = kilometrosAcumulados(camino)
        val largoKm = acumulados.lastOrNull() ?: 0.0
        // En modo destino el radio se limita a lo que Overpass puede cubrir de una sola vez
        // (25 km). En modo ruta se mantiene el comportamiento de siempre.
        val radio = (if (soloDestino) request.maxDetourKm.coerceIn(2, 25) else request.maxDetourKm.coerceIn(2, 40)) * 1000

        onProgress(if (soloDestino) "Buscando planes alrededor de " + request.destinationName + "…" else "Buscando pueblos y lugares del camino…")
        val (poblados, sitios) = coroutineScope {
            if (soloDestino && centro != null) {
                val a = async {
                    runCatching {
                        overpass.pobladosAlrededorDe(centro.first, centro.second, minOf(radio + 5000, 25_000))
                    }.getOrElse { emptyList() }
                }
                val b = async {
                    runCatching { overpass.alrededorDelPunto(centro.first, centro.second, radio) }
                }
                a.await() to b.await()
            } else {
                val a = async { runCatching { overpass.pobladosDelCamino(camino, minOf(radio + 2000, 25_000)) }.getOrElse { emptyList() } }
                val b = async { runCatching { overpass.alrededorDelCamino(camino, radio) } }
                a.await() to b.await()
            }
        }
        val fallo = sitios.exceptionOrNull()
        // El corredor trae lo de la vía, pero en una ruta larga el muestreo puede dejar el
        // destino con pocos puntos. Se pide aparte el centro del destino para que SIEMPRE haya
        // qué visitar al llegar (parques, iglesias, miradores, museos, mercados).
        val elementosDelDestino = if (!soloDestino && camino.size >= 2) {
            runCatching {
                overpass.alrededorDelPunto(camino.last().first, camino.last().second, 12_000, 300)
            }.getOrElse { emptyList() }
        } else emptyList()
        val elementos = sitios.getOrDefault(emptyList()) + elementosDelDestino

        onProgress("Ordenando " + elementos.size + " lugares por cercanía…")

        // En modo "solo destino" el orden es la distancia al centro; en modo ruta, el kilómetro
        // del viaje. Una sola función para que no se mezclen las dos escalas.
        fun kmDelPunto(lat: Double, lng: Double): Double =
            if (soloDestino && centro != null) RoutingService.haversineKm(centro.first, centro.second, lat, lng)
            else kmDe(lat, lng, camino, acumulados)

        // ---- Pueblos: los de OpenStreetMap + los del nomenclátor que lleva la app dentro
        // (así, sin señal o si Overpass falla, la ruta sigue teniendo pueblos).
        val pueblos = LinkedHashMap<String, Pueblo>()
        poblados.forEach { p ->
            val km = kmDelPunto(p.lat, p.lng)
            val clave = normal(p.nombre)
            val previo = pueblos[clave]
            val nuevo = Pueblo(
                nombre = p.nombre,
                provincia = p.tags["addr:province"].orEmpty(),
                lat = p.lat, lng = p.lng, km = km,
                peso = when (p.tags["place"]) {
                    "city" -> 4; "town" -> 3; "village" -> 2; "suburb" -> 1; else -> 0
                }
            )
            if (previo == null || nuevo.peso > previo.peso) pueblos[clave] = nuevo
        }
        GazetteerData.all.forEach { e ->
            if (e.latitude == 0.0 && e.longitude == 0.0) return@forEach
            if (e.kind == GazetteerData.KIND_PROVINCE) return@forEach
            val desvio = RoutingService.distanceToPathKm(e.latitude, e.longitude, camino)
            if (desvio > request.maxDetourKm + 5) return@forEach
            val clave = normal(e.name)
            if (pueblos.containsKey(clave)) {
                // Ya lo teníamos por OSM: le ponemos la provincia, que OSM casi nunca trae.
                pueblos[clave] = pueblos[clave]!!.copy(provincia = e.parent)
            } else {
                pueblos[clave] = Pueblo(
                    e.name, e.parent, e.latitude, e.longitude,
                    kmDelPunto(e.latitude, e.longitude),
                    peso = if (e.kind == GazetteerData.KIND_CANTON) 3 else 1
                )
            }
        }
        val ordenados = pueblos.values.sortedBy { it.km }

        // ---- Lugares: OpenStreetMap + lo que ya tenía la app dentro del corredor
        val centroNombre = if (soloDestino) request.destinationName.trim() else null
        val deOsm = elementos.mapNotNull { aLugar(it, ordenados, camino, request, centroNombre) }
        val delCatalogo = catalogo.filter { p ->
            p.latitude != 0.0 && RoutingService.distanceToPathKm(p.latitude, p.longitude, camino) <= request.maxDetourKm
        }
        // El catálogo manda: si un lugar ya estaba con fotos, tips y comentarios, no se pisa
        // con la ficha pelada de OpenStreetMap.
        val porClave = LinkedHashMap<String, Place>()
        delCatalogo.forEach { porClave[claveDe(it.name, it.latitude, it.longitude)] = it }
        deOsm.forEach { porClave.putIfAbsent(claveDe(it.name, it.latitude, it.longitude), it) }

        val desvios = HashMap<String, Double>()
        val conKm = porClave.values.map { p ->
            desvios[p.id] = RoutingService.distanceToPathKm(p.latitude, p.longitude, camino)
            p to kmDelPunto(p.latitude, p.longitude)
        }.sortedBy { it.second }

        val interesados = filtrarPorIntereses(conKm.map { it.first }, request.interests)
        val kmPorLugar = conKm.associate { it.first.id to it.second }

        // ---- Agrupar por pueblo: es como uno cuenta un viaje.
        // El pueblo de cada lugar se calcula UNA vez y se guarda. Hacerlo dentro del filtro de
        // cada pueblo multiplicaba el trabajo por cien y dejaba el teléfono pensando.
        val puebloDeCadaLugar = HashMap<String, String>(interesados.size)
        interesados.forEach { l ->
            puebloMasCercano(l.latitude, l.longitude, ordenados)?.let { puebloDeCadaLugar[l.id] = it.nombre }
        }
        val porPueblo = interesados.groupBy { puebloDeCadaLugar[it.id].orEmpty() }
        val tramos = ordenados.mapNotNull { pu ->
            val suyos = porPueblo[pu.nombre].orEmpty().sortedBy { kmPorLugar[it.id] ?: 0.0 }
            if (suyos.isEmpty()) null
            else Tramo(pu.nombre, pu.provincia, pu.lat, pu.lng, pu.km, suyos)
        }

        val resumen = buildString {
            append(interesados.size).append(" lugares en ").append(tramos.size)
            if (soloDestino) {
                append(" zonas alrededor de ").append(request.destinationName.trim())
                append(" (radio de ").append(request.maxDetourKm).append(" km)")
            } else {
                append(" pueblos")
                if (largoKm > 0) append(" a lo largo de ").append(largoKm.toInt()).append(" km")
            }
            append(", con sus coordenadas exactas de OpenStreetMap")
            if (delCatalogo.isNotEmpty()) append(" y ").append(delCatalogo.size).append(" que ya conocía la comunidad")
            append(".")
        }

        Exploracion(
            corredor = camino,
            tramos = tramos,
            lugares = interesados,
            distanciaKm = largoKm,
            desvioKmPorLugar = desvios,
            resumen = resumen,
            problema = if (interesados.isEmpty() && fallo != null) (fallo.message ?: "No se pudo consultar OpenStreetMap.") else ""
        )
    }

    // ======================================================================
    //  Piezas
    // ======================================================================

    private data class Pueblo(
        val nombre: String,
        val provincia: String,
        val lat: Double,
        val lng: Double,
        val km: Double,
        val peso: Int
    )

    /** Ubica un nombre del Ecuador. Devuelve null si no se encuentra o cae fuera del país. */
    private suspend fun ubicarPunto(nombre: String): Pair<Double, Double>? {
        val q = nombre.trim()
        if (q.length < 2) return null
        val hit = runCatching { geocoding.search(q, limit = 1).firstOrNull() }.getOrNull() ?: return null
        if (!PlaceJsonParser.isInEcuador(hit.latitude, hit.longitude)) return null
        return hit.latitude to hit.longitude
    }

    private suspend fun trazar(request: RadarRequest): List<Pair<Double, Double>> {
        val nombres = listOf(request.originName) + request.waypoints + listOf(request.destinationName)
        val puntos = nombres.mapNotNull { n ->
            runCatching { geocoding.search(n).firstOrNull() }.getOrNull()?.let { it.latitude to it.longitude }
        }
        if (puntos.size < 2) return puntos
        val paradas = puntos.mapIndexed { i, p ->
            RouteStop(id = "e$i", routeId = "explorador", placeId = "", placeName = nombres.getOrElse(i) { "" },
                latitude = p.first, longitude = p.second)
        }
        val via = runCatching { routing.routeBetween(paradas) }.getOrNull() ?: return puntos
        return via.legs.flatMap { it.geometry }.ifEmpty { puntos }
    }

    /** Distancia acumulada del camino, vértice a vértice. Sirve para decir "km 84 del viaje". */
    private fun kilometrosAcumulados(camino: List<Pair<Double, Double>>): List<Double> {
        val salida = ArrayList<Double>(camino.size)
        var total = 0.0
        salida += 0.0
        for (i in 1 until camino.size) {
            total += RoutingService.haversineKm(
                camino[i - 1].first, camino[i - 1].second, camino[i].first, camino[i].second
            )
            salida += total
        }
        return salida
    }

    /** En qué kilómetro del viaje queda un punto. */
    private fun kmDe(
        lat: Double, lng: Double,
        camino: List<Pair<Double, Double>>,
        acumulados: List<Double>
    ): Double {
        var mejor = 0
        var mejorD = Double.MAX_VALUE
        for (i in camino.indices) {
            val d = RoutingService.haversineKm(lat, lng, camino[i].first, camino[i].second)
            if (d < mejorD) { mejorD = d; mejor = i }
        }
        return acumulados.getOrElse(mejor) { 0.0 }
    }

    private fun puebloMasCercano(lat: Double, lng: Double, pueblos: List<Pueblo>): Pueblo? =
        pueblos.minByOrNull { RoutingService.haversineKm(lat, lng, it.lat, it.lng) }

    private fun claveDe(nombre: String, lat: Double, lng: Double): String =
        normal(nombre) + "@" + String.format(java.util.Locale.US, "%.3f,%.3f", lat, lng)

    private fun normal(t: String): String =
        Normalizer.normalize(t.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---------------------------------------------------------------- OSM -> Place

    private fun aLugar(
        e: OverpassService.Elemento,
        pueblos: List<Pueblo>,
        camino: List<Pair<Double, Double>>,
        request: RadarRequest,
        centroNombre: String? = null
    ): Place? {
        val nombre = e.nombre.trim()
        if (nombre.length < 3) return null
        val categoria = categoriaDe(e.tags) ?: return null
        val cerca = puebloMasCercano(e.lat, e.lng, pueblos)
        val provincia = cerca?.provincia.orEmpty()
        val desvio = RoutingService.distanceToPathKm(e.lat, e.lng, camino)

        val comoLlegar = buildString {
            if (centroNombre != null) {
                append("A ").append(String.format(java.util.Locale.US, "%.1f", desvio).replace('.', ',')).append(" km de ").append(centroNombre)
            } else {
                if (desvio < 0.6) append("Está pegado a la vía")
                else append("A ").append(String.format(java.util.Locale.US, "%.1f", desvio).replace('.', ',')).append(" km de la vía")
            }
            if (cerca != null) {
                val d = RoutingService.haversineKm(e.lat, e.lng, cerca.lat, cerca.lng)
                append(", cerca de ").append(cerca.nombre)
                if (d > 1) append(" (a ").append(d.toInt()).append(" km)")
            }
            append(".")
        }

        val etiquetas = ArrayList<String>()
        e.tags["cuisine"]?.split(";")?.forEach { c -> if (c.isNotBlank()) etiquetas += c.trim() }
        e.tags["tourism"]?.let { etiquetas += it }
        e.tags["natural"]?.let { etiquetas += it }
        e.tags["historic"]?.let { etiquetas += it }
        e.tags["amenity"]?.let { etiquetas += it }

        val web = e.tags["website"] ?: e.tags["contact:website"] ?: ""
        return Place(
            id = "osm-" + e.id,
            name = nombre,
            category = categoria,
            region = regionDe(provincia),
            province = provincia,
            city = cerca?.nombre.orEmpty(),
            address = listOfNotNull(e.tags["addr:street"], e.tags["addr:housenumber"]).joinToString(" "),
            latitude = e.lat,
            longitude = e.lng,
            shortDescription = descripcionDe(e.tags, categoria),
            openingHours = e.tags["opening_hours"].orEmpty(),
            howToGetThere = comoLlegar,
            tags = etiquetas.distinct().take(6),
            contact = PlaceContact(
                phone = e.tags["phone"] ?: e.tags["contact:phone"] ?: "",
                whatsapp = e.tags["contact:whatsapp"].orEmpty(),
                website = web,
                instagram = e.tags["contact:instagram"].orEmpty(),
                facebook = e.tags["contact:facebook"].orEmpty()
            ),
            sources = listOf(
                PlaceSource(
                    platform = SourcePlatform.WEB,
                    url = "https://www.openstreetmap.org/" + tipoOsm(e.id) + "/" + e.id.drop(1),
                    title = "Ficha en OpenStreetMap"
                )
            ),
            // Coordenadas de OSM: exactas, pero la ficha es la del mapa, no una reseña escrita
            // para un viajero. Se marca como tal para no llenar la lista del Catálogo con
            // gasolineras y farmacias: salen al buscar y al explorar una ruta.
            verification = VerificationLevel.OSM,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun tipoOsm(id: String): String = when (id.firstOrNull()) {
        'w' -> "way"
        'r' -> "relation"
        else -> "node"
    }

    private fun descripcionDe(tags: Map<String, String>, categoria: PlaceCategory): String {
        tags["description:es"]?.takeIf { it.isNotBlank() }?.let { return it.take(240) }
        tags["description"]?.takeIf { it.isNotBlank() }?.let { return it.take(240) }
        val que = when {
            tags["natural"] == "waterfall" -> "Cascada"
            tags["natural"] == "hot_spring" -> "Aguas termales"
            tags["natural"] == "peak" -> "Cumbre"
            tags["natural"] == "volcano" -> "Volcán"
            tags["natural"] == "beach" -> "Playa"
            tags["natural"] == "cave_entrance" -> "Entrada de cueva"
            tags["tourism"] == "viewpoint" -> "Mirador"
            tags["tourism"] == "museum" -> "Museo"
            tags["tourism"] == "camp_site" -> "Zona de camping"
            tags["tourism"] == "attraction" -> "Atractivo turístico"
            tags["amenity"] == "restaurant" -> "Restaurante"
            tags["amenity"] == "cafe" -> "Cafetería"
            tags["amenity"] == "fast_food" -> "Comida rápida"
            tags["amenity"] == "marketplace" -> "Mercado"
            tags["amenity"] == "place_of_worship" -> "Iglesia / templo"
            tags["place"] == "square" -> "Plaza"
            tags["place"] == "city_gate" -> "Puerta de la ciudad"
            tags["amenity"] == "fuel" -> "Gasolinera"
            tags["amenity"] == "hospital" -> "Hospital"
            tags["amenity"] == "pharmacy" -> "Farmacia"
            tags["amenity"] == "bus_station" -> "Terminal de buses"
            tags["historic"] != null -> "Sitio histórico"
            else -> categoria.displayName
        }
        val extra = tags["cuisine"]?.replace("_", " ")?.replace(";", ", ")
        return if (extra.isNullOrBlank()) que else "$que · $extra"
    }

    private fun categoriaDe(tags: Map<String, String>): PlaceCategory? {
        val t = tags["tourism"]
        val n = tags["natural"]
        val a = tags["amenity"]
        val l = tags["leisure"]
        val s = tags["shop"]
        return when {
            n == "beach" -> PlaceCategory.BEACH
            t == "viewpoint" || n == "peak" || n == "volcano" -> PlaceCategory.VIEWPOINT
            n != null || l == "park" || l == "nature_reserve" -> PlaceCategory.NATURE
            t == "museum" || t == "artwork" || t == "gallery" || tags.containsKey("historic") -> PlaceCategory.CULTURE
            tags["place"] == "square" || tags["place"] == "city_gate" -> PlaceCategory.CULTURE
            t == "hotel" || t == "hostel" || t == "guest_house" || t == "motel" || t == "camp_site" -> PlaceCategory.LODGING
            a == "place_of_worship" -> PlaceCategory.CULTURE
            a == "restaurant" || a == "cafe" || a == "fast_food" || a == "marketplace" ||
                s == "bakery" || s == "greengrocer" || s == "supermarket" -> PlaceCategory.FOOD
            a != null -> PlaceCategory.SERVICES
            t == "theme_park" || t == "zoo" || l == "water_park" -> PlaceCategory.ADVENTURE
            t == "attraction" || t == "picnic_site" || t == "information" -> PlaceCategory.NATURE
            else -> null
        }
    }

    /**
     * Los intereses del viajero NO esconden cosas: reordenan. Lo que pidió va primero y lo
     * demás detrás, porque en carretera una gasolinera o una farmacia salvan el día aunque
     * nadie las haya puesto en su lista de gustos.
     */
    private fun filtrarPorIntereses(lugares: List<Place>, intereses: List<String>): List<Place> {
        if (intereses.isEmpty()) return lugares
        val buscadas = intereses.mapNotNull { i ->
            val v = normal(i)
            when {
                "comida" in v || "gastro" in v || "hueca" in v -> PlaceCategory.FOOD
                "natur" in v || "cascad" in v || "selva" in v -> PlaceCategory.NATURE
                "mirador" in v || "foto" in v -> PlaceCategory.VIEWPOINT
                "playa" in v || "surf" in v || "buceo" in v -> PlaceCategory.BEACH
                "cultur" in v || "museo" in v || "histor" in v -> PlaceCategory.CULTURE
                "aventura" in v || "camping" in v || "sender" in v -> PlaceCategory.ADVENTURE
                "hospedaje" in v || "dormir" in v || "hostal" in v -> PlaceCategory.LODGING
                else -> null
            }
        }.toSet()
        if (buscadas.isEmpty()) return lugares
        val (dentro, fuera) = lugares.partition { it.category in buscadas }
        return dentro + fuera
    }

    private fun regionDe(provincia: String): EcuadorRegion = when (normal(provincia)) {
        "esmeraldas", "manabi", "santa elena", "guayas", "los rios", "el oro", "santo domingo de los tsachilas" -> EcuadorRegion.COAST
        "sucumbios", "orellana", "napo", "pastaza", "morona santiago", "zamora chinchipe" -> EcuadorRegion.AMAZON
        "galapagos" -> EcuadorRegion.GALAPAGOS
        else -> EcuadorRegion.ANDES
    }
}

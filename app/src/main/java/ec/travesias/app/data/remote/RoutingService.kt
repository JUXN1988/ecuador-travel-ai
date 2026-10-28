package ec.travesias.app.data.remote

import ec.travesias.app.data.model.RouteLeg
import ec.travesias.app.data.model.RouteStop
import org.json.JSONObject
import kotlin.math.*

/**
 * Cálculo de rutas por carretera con OSRM (servidor público de demostración de OpenStreetMap).
 * Si falla la red, devuelve tramos en línea recta con distancia haversine para no romper la UI.
 */
class RoutingService {

    data class RoadRoute(
        val legs: List<RouteLeg>,
        val totalDistanceKm: Double,
        val totalDurationMinutes: Int,
        val isEstimate: Boolean
    )

    suspend fun routeBetween(stops: List<RouteStop>): RoadRoute {
        if (stops.size < 2) return RoadRoute(emptyList(), 0.0, 0, false)
        val coords = stops.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"
        val raw = runCatching { Http.get(url) }.getOrNull()
        if (raw != null) {
            runCatching { parseOsrm(raw, stops) }.getOrNull()?.let { return it }
        }
        return straightLine(stops)
    }

    private fun parseOsrm(raw: String, stops: List<RouteStop>): RoadRoute? {
        val json = JSONObject(raw)
        if (json.optString("code") != "Ok") return null
        val route = json.getJSONArray("routes").getJSONObject(0)
        val legsJson = route.getJSONArray("legs")
        val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
        val fullPath = (0 until geometry.length()).map { i ->
            val c = geometry.getJSONArray(i)
            Pair(c.getDouble(1), c.getDouble(0))
        }
        // OSRM devuelve una sola geometría; la repartimos por tramos de forma proporcional a la distancia.
        val legDistances = (0 until legsJson.length()).map { legsJson.getJSONObject(it).optDouble("distance", 0.0) }
        val total = legDistances.sum().takeIf { it > 0 } ?: 1.0
        val legs = mutableListOf<RouteLeg>()
        var cursor = 0
        for (i in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(i)
            val share = legDistances[i] / total
            val count = max(2, (fullPath.size * share).roundToInt())
            val end = if (i == legsJson.length() - 1) fullPath.size else min(fullPath.size, cursor + count)
            val geo = if (cursor < end) fullPath.subList(cursor, end) else listOf(
                Pair(stops[i].latitude, stops[i].longitude), Pair(stops[i + 1].latitude, stops[i + 1].longitude)
            )
            legs.add(
                RouteLeg(
                    fromStopId = stops[i].id,
                    toStopId = stops[i + 1].id,
                    distanceKm = leg.optDouble("distance", 0.0) / 1000.0,
                    durationMinutes = (leg.optDouble("duration", 0.0) / 60.0).roundToInt(),
                    geometry = geo
                )
            )
            cursor = max(cursor, end - 1)
        }
        return RoadRoute(
            legs = legs,
            totalDistanceKm = route.optDouble("distance", 0.0) / 1000.0,
            totalDurationMinutes = (route.optDouble("duration", 0.0) / 60.0).roundToInt(),
            isEstimate = false
        )
    }

    private fun straightLine(stops: List<RouteStop>): RoadRoute {
        val legs = stops.zipWithNext().map { (a, b) ->
            val km = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) * 1.35 // factor carretera andina
            RouteLeg(a.id, b.id, km, (km / 45.0 * 60).roundToInt(), listOf(Pair(a.latitude, a.longitude), Pair(b.latitude, b.longitude)))
        }
        return RoadRoute(legs, legs.sumOf { it.distanceKm }, legs.sumOf { it.durationMinutes }, true)
    }

    companion object {
        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        /** Distancia mínima (km) de un punto a una polilínea. */
        fun distanceToPathKm(lat: Double, lng: Double, path: List<Pair<Double, Double>>): Double {
            if (path.isEmpty()) return Double.MAX_VALUE
            if (path.size == 1) return haversineKm(lat, lng, path[0].first, path[0].second)
            var best = Double.MAX_VALUE
            for (i in 0 until path.size - 1) {
                val d = pointToSegmentKm(lat, lng, path[i], path[i + 1])
                if (d < best) best = d
            }
            return best
        }

        private fun pointToSegmentKm(lat: Double, lng: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            // Proyección plana local (suficiente para distancias < 100 km)
            val kx = cos(Math.toRadians(lat)) * 111.32
            val ky = 110.57
            val px = lng * kx; val py = lat * ky
            val ax = a.second * kx; val ay = a.first * ky
            val bx = b.second * kx; val by = b.first * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            return sqrt((px - cx).pow(2) + (py - cy).pow(2))
        }
    }
}

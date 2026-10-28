package ec.travesias.app.data.repository

import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.remote.RoutingService

/**
 * Ordena y reparte las paradas SIN internet y de forma predecible:
 *
 *  1. Orden: sale del ORIGEN real (si se conoce), visita por cercanía y mejora el recorrido con
 *     2-opt (quita los cruces de "ir y volver"). Si hay destino o es ida y vuelta, el recorrido
 *     termina pegado a él.
 *  2. Días: parte la secuencia en tantos días como tenga la ruta equilibrando el TIEMPO (viaje +
 *     visita), no el número de paradas. Así no queda un día de 14 h y otro de 2 h.
 *
 * Lo usa "Ordenar mejor" y también sirve de árbitro para la propuesta de la IA: si la IA da un
 * orden mucho más largo, se queda este.
 */
object RouteOptimizer {

    data class Result(val stops: List<RouteStop>, val totalKm: Double, val maxDayMinutes: Int)

    fun optimize(route: TravelRoute, stops: List<RouteStop>, ends: RouteSchedule.Endpoints): Result {
        if (stops.isEmpty()) return Result(emptyList(), 0.0, 0)
        val inicio = ends.origin
        val fin = ends.finish

        // ---- 1. Orden por cercanía desde el origen (o desde la primera parada actual) ----
        val pendientes = stops.sortedWith(compareBy({ it.dayNumber }, { it.orderInDay })).toMutableList()
        val orden = ArrayList<RouteStop>()
        var lat: Double
        var lng: Double
        if (inicio != null) { lat = inicio.latitude; lng = inicio.longitude }
        else { val p = pendientes.removeAt(0); orden.add(p); lat = p.latitude; lng = p.longitude }
        while (pendientes.isNotEmpty()) {
            val sig = pendientes.minBy { km(lat, lng, it.latitude, it.longitude) }
            pendientes.remove(sig)
            orden.add(sig)
            lat = sig.latitude; lng = sig.longitude
        }

        // ---- 2-opt con extremos fijos ----
        val mejorado = dosOpt(orden, inicio, fin)

        // ---- 2. Reparto por días equilibrando el tiempo ----
        val dias = route.totalDays.coerceAtLeast(1)
        val pesos = mejorado.mapIndexed { i, s ->
            val (pLat, pLng) = if (i == 0) (inicio?.let { it.latitude to it.longitude } ?: (s.latitude to s.longitude))
            else mejorado[i - 1].latitude to mejorado[i - 1].longitude
            val modo = s.transportMode ?: route.transportMode
            RouteSchedule.travelMinutes(modo, 0, km(pLat, pLng, s.latitude, s.longitude) * 1.3) + s.durationMinutes
        }.toMutableList()
        // El tramo final (llegada/regreso) pesa sobre el último día.
        if (fin != null && mejorado.isNotEmpty()) {
            val u = mejorado.last()
            pesos[pesos.lastIndex] = pesos.last() + RouteSchedule.travelMinutes(route.transportMode, 0, km(u.latitude, u.longitude, fin.latitude, fin.longitude) * 1.3)
        }
        val grupos = particionLineal(pesos, minOf(dias, mejorado.size))

        val salida = ArrayList<RouteStop>()
        var k = 0
        grupos.forEachIndexed { g, tam ->
            for (o in 0 until tam) {
                val s = mejorado[k++]
                salida.add(
                    s.copy(
                        dayNumber = g + 1,
                        orderInDay = o,
                        // La hora sugerida vieja ya no vale tras reordenar; las fijas se respetan.
                        plannedTime = if (s.timeLocked) s.plannedTime else ""
                    )
                )
            }
        }
        val maxDia = grupos.foldIndexed(Pair(0, 0)) { _, acc, tam ->
            val suma = pesos.subList(acc.first, acc.first + tam).sum()
            Pair(acc.first + tam, maxOf(acc.second, suma))
        }.second
        return Result(salida, pathKm(salida, inicio, fin), maxDia)
    }

    /** Km totales de la secuencia (línea recta × 1,3), extremos incluidos. */
    fun pathKm(stops: List<RouteStop>, inicio: RouteSchedule.Point?, fin: RouteSchedule.Point?): Double {
        val seq = stops.sortedWith(compareBy({ it.dayNumber }, { it.orderInDay }))
        var total = 0.0
        var lat = inicio?.latitude
        var lng = inicio?.longitude
        for (s in seq) {
            if (lat != null && lng != null) total += km(lat, lng, s.latitude, s.longitude) * 1.3
            lat = s.latitude; lng = s.longitude
        }
        if (fin != null && lat != null && lng != null) total += km(lat, lng, fin.latitude, fin.longitude) * 1.3
        return total
    }

    /** Minutos (viaje + visita) del día más cargado, con la secuencia tal como está. */
    fun maxDayMinutes(route: TravelRoute, stops: List<RouteStop>, inicio: RouteSchedule.Point?): Int {
        val seq = stops.sortedWith(compareBy({ it.dayNumber }, { it.orderInDay }))
        val porDia = HashMap<Int, Int>()
        var lat = inicio?.latitude
        var lng = inicio?.longitude
        for (s in seq) {
            val viaje = if (lat != null && lng != null)
                RouteSchedule.travelMinutes(s.transportMode ?: route.transportMode, 0, km(lat, lng, s.latitude, s.longitude) * 1.3) else 0
            porDia[s.dayNumber] = (porDia[s.dayNumber] ?: 0) + viaje + s.durationMinutes
            lat = s.latitude; lng = s.longitude
        }
        return porDia.values.maxOrNull() ?: 0
    }

    // ------------------------------------------------------------------ apoyo

    private fun km(a: Double, b: Double, c: Double, d: Double) = RoutingService.haversineKm(a, b, c, d)

    /** 2-opt clásico sobre la secuencia, con el inicio y el final (si los hay) fijos. */
    private fun dosOpt(orden: List<RouteStop>, inicio: RouteSchedule.Point?, fin: RouteSchedule.Point?): List<RouteStop> {
        if (orden.size < 3) return orden
        val ruta = orden.toMutableList()
        fun coste(lista: List<RouteStop>): Double {
            var t = 0.0
            var lat = inicio?.latitude ?: lista.first().latitude
            var lng = inicio?.longitude ?: lista.first().longitude
            for (s in lista) { t += km(lat, lng, s.latitude, s.longitude); lat = s.latitude; lng = s.longitude }
            if (fin != null) t += km(lat, lng, fin.latitude, fin.longitude)
            return t
        }
        // Sin origen conocido la primera parada actúa de origen y no se mueve.
        val desde = if (inicio == null) 1 else 0
        var mejor = coste(ruta)
        var mejoro = true
        var vueltas = 0
        while (mejoro && vueltas < 60) {
            mejoro = false
            vueltas++
            for (i in desde until ruta.size - 1) {
                for (j in i + 1 until ruta.size) {
                    val prueba = ruta.subList(0, i) + ruta.subList(i, j + 1).reversed() + ruta.subList(j + 1, ruta.size)
                    val c = coste(prueba)
                    if (c + 0.01 < mejor) {
                        mejor = c
                        ruta.clear(); ruta.addAll(prueba)
                        mejoro = true
                    }
                }
            }
        }
        return ruta
    }

    /**
     * Parte [pesos] (en orden) en [k] grupos contiguos minimizando el grupo más pesado.
     * Devuelve el tamaño de cada grupo. Programación dinámica: n ≤ ~60, k ≤ 30 → instantáneo.
     */
    fun particionLineal(pesos: List<Int>, k: Int): List<Int> {
        val n = pesos.size
        if (n == 0) return emptyList()
        val grupos = k.coerceIn(1, n)
        val pref = IntArray(n + 1)
        for (i in 0 until n) pref[i + 1] = pref[i] + pesos[i]
        val inf = Int.MAX_VALUE / 2
        // dp[g][i] = mejor máximo usando g grupos para los primeros i elementos
        val dp = Array(grupos + 1) { IntArray(n + 1) { inf } }
        val corte = Array(grupos + 1) { IntArray(n + 1) }
        dp[0][0] = 0
        for (g in 1..grupos) {
            for (i in g..n) {
                for (j in (g - 1) until i) {
                    if (dp[g - 1][j] >= inf) continue
                    val v = maxOf(dp[g - 1][j], pref[i] - pref[j])
                    if (v < dp[g][i]) { dp[g][i] = v; corte[g][i] = j }
                }
            }
        }
        val tam = IntArray(grupos)
        var i = n
        for (g in grupos downTo 1) {
            val j = corte[g][i]
            tam[g - 1] = i - j
            i = j
        }
        return tam.toList()
    }
}

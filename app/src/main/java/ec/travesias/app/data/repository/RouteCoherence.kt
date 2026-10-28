package ec.travesias.app.data.repository

import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.journeyStops
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Revisa si una ruta "tiene sentido" para un viajero real y explica lo que no.
 *
 * La usan dos sitios:
 *  - la pantalla Mi Ruta, para avisar en claro (sin jerga) qué conviene arreglar;
 *  - la Guía IA, que después de armar o editar la ruta la pasa por aquí y, si hay problemas
 *    serios, hace UNA ronda más para corregirlos antes de contestar.
 */
object RouteCoherence {

    enum class Level { INFO, WARN, SERIOUS }

    data class Issue(val level: Level, val text: String, val day: Int? = null)

    fun check(
        route: TravelRoute,
        schedule: List<RouteSchedule.DayPlan>,
        ends: RouteSchedule.Endpoints = RouteSchedule.Endpoints.NONE
    ): List<Issue> {
        val out = ArrayList<Issue>()
        if (route.startDate.isNotBlank() && !isValidDate(route.startDate)) {
            out.add(Issue(Level.INFO, "La fecha de inicio \"${route.startDate}\" no es una fecha válida. Usa el formato 2026-11-15."))
        }
        if (route.stops.isEmpty()) return out

        val conParadas = route.stops.map { it.dayNumber }.toSet()
        val vacios = (1..route.totalDays).filter { it !in conParadas }
        if (vacios.isNotEmpty()) {
            val ultimoConParadas = conParadas.maxOrNull() ?: 0
            val alFinal = vacios.all { it > ultimoConParadas }
            val texto = if (alFinal)
                "${diasTexto(vacios)} sin paradas después de terminar el recorrido. Añade paradas o baja la ruta a $ultimoConParadas día(s)."
            else
                "${diasTexto(vacios)} sin paradas. Si es día libre está bien; si no, reparte paradas con «Ordenar mejor»."
            out.add(Issue(if (alFinal) Level.SERIOUS else Level.WARN, texto))
        }
        val fuera = route.stops.filter { it.dayNumber > route.totalDays }
        if (fuera.isNotEmpty()) {
            out.add(Issue(Level.SERIOUS, "${fuera.size} parada(s) están en días que ya no existen en la ruta."))
        }

        schedule.forEach { d ->
            if (d.isHeavyTravel) {
                out.add(Issue(Level.SERIOUS, "Día ${d.day}: ${RouteSchedule.durationLabel(d.travelMinutes)} en camino. Es demasiado para un día; parte el trayecto con una noche intermedia.", d.day))
            } else if (d.isLongDay) {
                out.add(Issue(Level.WARN, "Día ${d.day}: jornada de ${RouteSchedule.durationLabel(d.endMinutes - d.startMinutes)}. Mueve alguna parada a otro día.", d.day))
            }
            if (d.endMinutes > 22 * 60) {
                out.add(Issue(Level.WARN, "Día ${d.day}: terminas a las ${d.end}. Viajar de noche por carretera no es recomendable.", d.day))
            }
        }

        // ¿El orden hace ir y volver? Se compara con el orden óptimo calculado sin internet.
        // Se miran solo las visitas: la salida y la llegada son extremos, no paradas.
        val visitas = route.journeyStops()
        if (visitas.size >= 3) {
            val actual = RouteOptimizer.pathKm(visitas, ends.origin, ends.finish)
            val optimo = RouteOptimizer.optimize(route, visitas, ends).totalKm
            if (optimo > 0 && actual > optimo * 1.30 && actual - optimo > 40) {
                out.add(Issue(Level.WARN, "El orden actual hace ${(actual - optimo).toInt()} km de más (ir y volver). Toca «Ordenar mejor»."))
            }
        }
        return out
    }

    /** "El día 3" / "Los días 3, 4 y 5". */
    private fun diasTexto(dias: List<Int>): String = when (dias.size) {
        1 -> "El día ${dias[0]} está"
        else -> "Los días " + dias.dropLast(1).joinToString(", ") + " y " + dias.last() + " están"
    }

    /** Fecha real en formato AAAA-MM-DD (no acepta "AAAA-MM-DD" ni 2026-02-30). */
    fun isValidDate(text: String?): Boolean {
        val t = text?.trim() ?: return false
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t)) return false
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            f.isLenient = false
            f.parse(t) != null
        }.getOrDefault(false)
    }

    /** Devuelve la fecha si es válida y vacío si no (lo que escribe la IA o el viajero). */
    fun cleanDate(text: String?): String = text?.trim()?.takeIf { isValidDate(it) }.orEmpty()

    /** "2026-11-15" + 2 días → "lun 17 nov". Vacío si no hay fecha válida. */
    fun dayLabel(startDate: String, day: Int, english: Boolean = false): String {
        if (!isValidDate(startDate)) return ""
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance().apply { time = f.parse(startDate)!!; add(Calendar.DAY_OF_MONTH, day - 1) }
            val loc = if (english) Locale.US else Locale("es", "EC")
            SimpleDateFormat("EEE d MMM", loc).format(cal.time).replace(".", "")
        }.getOrDefault("")
    }

    /** Hoy en AAAA-MM-DD (para darle contexto a la IA). */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
}

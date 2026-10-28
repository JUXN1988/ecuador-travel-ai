package ec.travesias.app.data.remote

import ec.travesias.app.data.model.FuelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Busca en internet los precios VIGENTES del combustible en Ecuador (cambian cada mes, el día 12,
 * por el sistema de bandas). Usa la búsqueda con fuentes de Gemini; si no hay clave o la
 * respuesta no se entiende, devuelve un error claro y la app sigue con los precios guardados.
 *
 * Los valores se VALIDAN: nada fuera de un rango razonable entra a las cuentas.
 */
class FuelPriceService(private val client: GeminiClient) {

    data class Prices(
        val prices: Map<FuelType, Double>,
        val validFrom: String,
        val validUntil: String,
        val source: String
    )

    sealed class Outcome {
        data class Ok(val prices: Prices) : Outcome()
        data class Error(val message: String) : Outcome()
    }

    suspend fun fetchCurrent(today: String): Outcome = withContext(Dispatchers.IO) {
        if (!client.isAvailable) return@withContext Outcome.Error("Sin clave de la IA: escribe el precio a mano.")
        val prompt = """
            Hoy es $today. Busca el precio oficial VIGENTE por galón en Ecuador de:
            gasolina Extra, gasolina Ecopaís, diésel (Diésel Premium) y gasolina Súper (precio referencial).
            Usa fuentes oficiales (Petroecuador, ARCERNNR) o prensa ecuatoriana reciente.
            Responde SOLO con este JSON, sin texto extra:
            {"extra":0.00,"ecopais":0.00,"diesel":0.00,"super":0.00,"desde":"AAAA-MM-DD","hasta":"AAAA-MM-DD","fuente":"nombre del medio"}
        """.trimIndent()
        val r = runCatching {
            client.generate(prompt, useSearch = true, temperature = 0.1, maxOutputTokens = 8192)
        }.getOrElse { e ->
            val red = e.message.orEmpty().let { it.contains("resolve host", true) || it.contains("timeout", true) }
            return@withContext Outcome.Error(if (red) "Sin conexión a internet." else "No se pudo consultar: " + e.message.orEmpty().take(80))
        }
        val o = JsonExtract.firstObject(r.text)
            ?: return@withContext Outcome.Error("La búsqueda no devolvió precios legibles. Intenta más tarde.")

        val extra = o.optDouble("extra", Double.NaN)
        val ecopais = o.optDouble("ecopais", Double.NaN)
        val diesel = o.optDouble("diesel", Double.NaN)
        val superP = o.optDouble("super", Double.NaN)
        val mapa = HashMap<FuelType, Double>()
        // Extra y Ecopaís tienen el mismo precio; si solo llega uno, vale para los dos.
        val extraOk = listOf(extra, ecopais).firstOrNull { !it.isNaN() && it in FuelType.EXTRA.validRange }
        extraOk?.let { mapa[FuelType.EXTRA] = redondear(it) }
        if (!diesel.isNaN() && diesel in FuelType.DIESEL.validRange) mapa[FuelType.DIESEL] = redondear(diesel)
        if (!superP.isNaN() && superP in FuelType.SUPER.validRange) mapa[FuelType.SUPER] = redondear(superP)
        if (mapa.isEmpty()) return@withContext Outcome.Error("Los precios encontrados no parecen correctos; se mantienen los guardados.")

        val fuente = o.optString("fuente", "").ifBlank { r.sources.firstOrNull()?.title.orEmpty() }.ifBlank { "internet" }
        Outcome.Ok(
            Prices(
                prices = mapa,
                validFrom = o.optString("desde", "").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: today,
                validUntil = o.optString("hasta", "").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }.orEmpty(),
                source = fuente.take(60)
            )
        )
    }

    private fun redondear(v: Double) = Math.round(v * 100.0) / 100.0
}

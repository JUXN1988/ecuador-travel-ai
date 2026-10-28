package ec.travesias.app.data.local

import android.content.Context
import android.content.SharedPreferences
import ec.travesias.app.data.model.FuelType
import ec.travesias.app.data.model.VehicleProfile
import ec.travesias.app.data.model.VehicleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El vehículo del viajero y los precios del combustible, guardados en el teléfono.
 *
 * Va en su propio archivo de preferencias (no en la base Room): así no hace falta una
 * migración de base de datos y vale para todas las rutas. Todo lo que se lee se valida;
 * un valor raro (corrupto o escrito mal) cae al valor de fábrica en vez de romper cuentas.
 */
object VehicleSettings {
    private const val PREFS = "travesias_vehiculo"
    private const val K_TYPE = "tipo"
    private const val K_FUEL = "combustible"
    private const val K_KMU = "km_por_unidad"
    private const val K_EXTRA = "extra_pct"
    private const val K_TOLL = "peaje_usd"
    private const val K_FROM = "precios_desde"
    private const val K_UNTIL = "precios_hasta"
    private const val K_SOURCE = "precios_fuente"
    private fun kPrice(f: FuelType) = "precio_" + f.name

    private var prefs: SharedPreferences? = null

    private val _profile = MutableStateFlow(VehicleProfile())
    val profile: StateFlow<VehicleProfile> = _profile.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val type = VehicleType.from(p.getString(K_TYPE, null))
        val fuel = p.getString(K_FUEL, null)?.let { FuelType.from(it) } ?: type.defaultFuel
        val precios = FuelType.entries.associateWith { f ->
            val v = p.getFloat(kPrice(f), f.defaultPrice.toFloat()).toDouble()
            if (v in f.validRange) v else f.defaultPrice
        }
        val kmu = p.getFloat(K_KMU, type.kmPerUnit.toFloat()).toDouble()
        val guardadoDesde = p.getString(K_FROM, null)
        // Si el viajero nunca actualizó precios, o los que tiene son MÁS VIEJOS que los de
        // fábrica de esta versión, se usan los de fábrica (vienen de Petroecuador).
        val usarFabrica = guardadoDesde == null || guardadoDesde < VehicleProfile.DEFAULT_PRICES_FROM
        _profile.value = VehicleProfile(
            type = type,
            fuel = fuel,
            kmPerUnit = if (kmu in 1.0..400.0) kmu else type.kmPerUnit,
            extraConsumptionPct = p.getInt(K_EXTRA, 10).coerceIn(0, 60),
            tollUsd = p.getFloat(K_TOLL, 1.0f).toDouble().takeIf { it in 0.0..20.0 } ?: 1.0,
            prices = if (usarFabrica) FuelType.entries.associateWith { it.defaultPrice } else precios,
            pricesValidFrom = if (usarFabrica) VehicleProfile.DEFAULT_PRICES_FROM else guardadoDesde.orEmpty(),
            pricesValidUntil = if (usarFabrica) VehicleProfile.DEFAULT_PRICES_UNTIL else p.getString(K_UNTIL, "").orEmpty(),
            pricesSource = if (usarFabrica) "Petroecuador (precio oficial)" else p.getString(K_SOURCE, "").orEmpty()
        )
    }

    /** Guarda el vehículo (tipo, combustible, rendimiento, extra y peaje). */
    fun save(profile: VehicleProfile) {
        val limpio = profile.copy(
            kmPerUnit = profile.kmPerUnit.takeIf { it in 1.0..400.0 } ?: profile.type.kmPerUnit,
            extraConsumptionPct = profile.extraConsumptionPct.coerceIn(0, 60),
            tollUsd = profile.tollUsd.takeIf { it in 0.0..20.0 } ?: 1.0,
            prices = profile.prices.mapValues { (f, v) -> if (v in f.validRange) v else f.defaultPrice }
        )
        _profile.value = limpio
        prefs?.edit()?.apply {
            putString(K_TYPE, limpio.type.name)
            putString(K_FUEL, limpio.fuel.name)
            putFloat(K_KMU, limpio.kmPerUnit.toFloat())
            putInt(K_EXTRA, limpio.extraConsumptionPct)
            putFloat(K_TOLL, limpio.tollUsd.toFloat())
            limpio.prices.forEach { (f, v) -> putFloat(kPrice(f), v.toFloat()) }
            putString(K_FROM, limpio.pricesValidFrom)
            putString(K_UNTIL, limpio.pricesValidUntil)
            putString(K_SOURCE, limpio.pricesSource)
            apply()
        }
    }

    /** Pone precios nuevos (de internet o escritos a mano) sin tocar el resto del vehículo. */
    fun updatePrices(prices: Map<FuelType, Double>, validFrom: String, validUntil: String, source: String) {
        val actual = _profile.value
        save(
            actual.copy(
                prices = actual.prices + prices.filter { (f, v) -> v in f.validRange },
                pricesValidFrom = validFrom,
                pricesValidUntil = validUntil,
                pricesSource = source
            )
        )
    }
}

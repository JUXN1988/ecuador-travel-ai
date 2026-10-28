package ec.travesias.app.data.repository

import ec.travesias.app.data.model.FuelType
import ec.travesias.app.data.model.TransportMode
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.model.VehicleProfile
import ec.travesias.app.data.model.VehicleType
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Cuánto cuesta MOVERSE en la ruta, además de las entradas de cada parada.
 *
 * - Carro propio: km recorridos × consumo del vehículo × precio del combustible, más peajes.
 * - Moto: lo mismo con el consumo de una moto (o el del vehículo si el viajero tiene moto).
 * - Bus interprovincial: pasaje por persona (≈ USD 0,028/km, Quito–Guayaquil ≈ 11-12 USD).
 * - Taxi: ≈ USD 0,50/km por cada carro (caben 4).
 * - Tour, bici y a pie: 0 aquí (el tour ya va en el costo de la parada).
 *
 * Todo es ESTIMADO y así se muestra. No inventa precios: usa los de [VehicleProfile], que el
 * viajero puede corregir o actualizar desde internet.
 */
object TripCostEstimator {

    private const val BUS_USD_PER_KM = 0.028
    private const val BUS_MIN_FARE = 0.35
    private const val TAXI_USD_PER_KM = 0.50
    private const val TAXI_MIN_FARE = 1.50
    /** En vías principales hay, en promedio, un peaje cada ~90 km. */
    private const val KM_PER_TOLL = 90.0

    /** Gasto de transporte de un día (para todo el grupo). */
    data class DayCost(
        val day: Int,
        val vehicleKm: Double,
        val fuelUnits: Double,
        val fuelUsd: Double,
        val tollsUsd: Double,
        val faresUsd: Double
    ) {
        val totalUsd: Double get() = fuelUsd + tollsUsd + faresUsd
    }

    data class TripCost(
        val totalKm: Double,
        /** km hechos en carro propio o moto (los que gastan combustible del viajero). */
        val vehicleKm: Double,
        /** Galones (o kWh) que se gastarán. */
        val fuelUnits: Double,
        val fuelUnit: String,
        val fuelName: String,
        val pricePerUnit: Double,
        val fuelUsd: Double,
        val tollsUsd: Double,
        /** Pasajes de bus y taxi de todo el grupo. */
        val faresUsd: Double,
        /** Entradas y gastos de las paradas, POR PERSONA. */
        val entriesPerPersonUsd: Double,
        val travelers: Int,
        val days: List<DayCost>,
        /** Aviso si el grupo no cabe en el vehículo elegido. */
        val seatsWarning: String = ""
    ) {
        val transportUsd: Double get() = fuelUsd + tollsUsd + faresUsd
        val transportPerPersonUsd: Double get() = transportUsd / travelers.coerceAtLeast(1)
        val perPersonUsd: Double get() = entriesPerPersonUsd + transportPerPersonUsd
        val groupTotalUsd: Double get() = entriesPerPersonUsd * travelers.coerceAtLeast(1) + transportUsd
        fun day(n: Int): DayCost? = days.firstOrNull { it.day == n }
    }

    /** Gasto de combustible y pasajes de UN tramo, para mostrarlo en la línea de tiempo. */
    data class LegCost(val fuelUnits: Double, val fuelUsd: Double, val faresUsd: Double) {
        val totalUsd: Double get() = fuelUsd + faresUsd
    }

    fun legCost(mode: TransportMode, km: Double, vehicle: VehicleProfile, travelers: Int): LegCost {
        if (km <= 0.0) return LegCost(0.0, 0.0, 0.0)
        val personas = travelers.coerceAtLeast(1)
        return when (mode) {
            TransportMode.CAR -> {
                val u = km / vehicle.effectiveKmPerUnit
                LegCost(u, u * vehicle.pricePerUnit, 0.0)
            }
            TransportMode.MOTO -> {
                val moto = motoProfile(vehicle)
                val u = km / moto.effectiveKmPerUnit
                // una moto lleva a 2: si son más, van varias motos
                val motos = ceil(personas / 2.0)
                LegCost(u * motos, u * motos * moto.pricePerUnit, 0.0)
            }
            TransportMode.BUS, TransportMode.MIXED ->
                LegCost(0.0, 0.0, maxOf(BUS_MIN_FARE, km * BUS_USD_PER_KM) * personas)
            TransportMode.TAXI -> {
                val carros = ceil(personas / 4.0)
                LegCost(0.0, 0.0, maxOf(TAXI_MIN_FARE, km * TAXI_USD_PER_KM) * carros)
            }
            TransportMode.TOUR, TransportMode.BIKE, TransportMode.WALK -> LegCost(0.0, 0.0, 0.0)
        }
    }

    /** Si el viajero tiene moto se usa la suya; si no, una moto típica con gasolina Extra. */
    private fun motoProfile(v: VehicleProfile): VehicleProfile =
        if (v.type == VehicleType.MOTORCYCLE) v
        else VehicleProfile(
            type = VehicleType.MOTORCYCLE, fuel = FuelType.EXTRA,
            kmPerUnit = VehicleType.MOTORCYCLE.kmPerUnit, extraConsumptionPct = v.extraConsumptionPct,
            prices = v.prices
        )

    fun estimate(route: TravelRoute, schedule: List<RouteSchedule.DayPlan>, vehicle: VehicleProfile): TripCost {
        val personas = route.travelers.coerceAtLeast(1)
        val dias = schedule.map { plan ->
            var kmVehiculo = 0.0
            var kmCarro = 0.0
            var unidades = 0.0
            var combustible = 0.0
            var pasajes = 0.0
            fun suma(modo: TransportMode, km: Double) {
                val c = legCost(modo, km, vehicle, personas)
                unidades += c.fuelUnits
                combustible += c.fuelUsd
                pasajes += c.faresUsd
                if (modo == TransportMode.CAR || modo == TransportMode.MOTO) kmVehiculo += km
                if (modo == TransportMode.CAR) kmCarro += km
            }
            plan.stops.forEach { s -> suma(s.transport, s.distanceKm) }
            plan.finishLeg?.let { l -> suma(l.transport, l.distanceKm) }
            // Peajes solo para carro: uno cada ~90 km de vía principal (estimado).
            val peajes = floor(kmCarro / KM_PER_TOLL) * vehicle.tollUsd
            DayCost(plan.day, kmVehiculo, unidades, combustible, peajes, pasajes)
        }

        val usaCarro = route.transportMode == TransportMode.CAR ||
            route.stops.any { it.transportMode == TransportMode.CAR }
        val aviso = if (usaCarro && personas > vehicle.type.seats)
            "Son $personas viajeros y un ${vehicle.type.displayName.lowercase()} lleva ${vehicle.type.seats}: hará falta otro vehículo (el combustible se duplicaría)."
        else ""

        return TripCost(
            totalKm = schedule.sumOf { it.distanceKm },
            vehicleKm = dias.sumOf { it.vehicleKm },
            fuelUnits = dias.sumOf { it.fuelUnits },
            fuelUnit = vehicle.fuel.unit,
            fuelName = vehicle.fuel.displayName,
            pricePerUnit = vehicle.pricePerUnit,
            fuelUsd = dias.sumOf { it.fuelUsd },
            tollsUsd = dias.sumOf { it.tollsUsd },
            faresUsd = dias.sumOf { it.faresUsd },
            entriesPerPersonUsd = route.totalEstimatedCostUsd,
            travelers = personas,
            days = dias,
            seatsWarning = aviso
        )
    }

    /** "12,4" con coma decimal, como se escribe en Ecuador. */
    fun fmt1(v: Double): String = String.format(java.util.Locale("es", "EC"), "%.1f", v)

    /** "39,30" con dos decimales y coma. */
    fun fmtUsd(v: Double): String = String.format(java.util.Locale("es", "EC"), "%.2f", v)
}

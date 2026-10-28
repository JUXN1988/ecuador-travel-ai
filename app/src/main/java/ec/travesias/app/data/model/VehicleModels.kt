package ec.travesias.app.data.model

/**
 * Combustible del vehículo. En Ecuador se vende por GALÓN (EE. UU., 3,785 L); la electricidad por kWh.
 *
 * [defaultPrice] son los precios oficiales vigentes del 12-oct-2026 al 11-nov-2026 (Petroecuador):
 *   Extra y Ecopaís USD 3,17 · Diésel USD 3,11 · Súper USD 5,42 (referencial; cada gasolinera fija el suyo).
 * Cambian cada mes (el día 12) por el sistema de bandas, por eso el viajero puede actualizarlos
 * desde la app (búsqueda en internet) o escribirlos a mano.
 */
enum class FuelType(
    val displayName: String,
    val displayNameEn: String,
    /** "galón" o "kWh": la unidad en que se vende. */
    val unit: String,
    val unitEn: String,
    val defaultPrice: Double
) {
    EXTRA("Extra / Ecopaís", "Extra / Ecopaís (87)", "galón", "gallon", 3.17),
    SUPER("Súper (95)", "Super (95)", "galón", "gallon", 5.42),
    DIESEL("Diésel", "Diesel", "galón", "gallon", 3.11),
    ELECTRIC("Electricidad", "Electricity", "kWh", "kWh", 0.10);

    /** Rango razonable para validar precios que llegan de internet o que escribe el viajero. */
    val validRange: ClosedFloatingPointRange<Double>
        get() = if (this == ELECTRIC) 0.02..1.0 else 1.0..12.0

    companion object {
        fun from(value: String?): FuelType = entries.firstOrNull { it.name == value } ?: EXTRA
    }
}

/**
 * Tipos de vehículo con su rendimiento REAL de uso mixto en vías ecuatorianas
 * (subidas de la sierra, curvas, algo de ciudad). Se expresa en km por galón porque así se
 * habla en Ecuador; para el eléctrico es km por kWh.
 */
enum class VehicleType(
    val displayName: String,
    val displayNameEn: String,
    val examples: String,
    val defaultFuel: FuelType,
    /** km por galón (o km por kWh si es eléctrico). */
    val kmPerUnit: Double,
    /** Pasajeros que caben cómodos: sirve para avisar si el grupo no entra. */
    val seats: Int
) {
    COMPACT("Auto compacto", "Compact car", "Spark, Picanto, Aveo, i10", FuelType.EXTRA, 48.0, 4),
    SEDAN("Sedán", "Sedan", "Sail, Rio, Corolla, Versa", FuelType.EXTRA, 42.0, 5),
    SUV_SMALL("SUV compacto", "Compact SUV", "Tucson, Sportage, CX-5, Creta", FuelType.EXTRA, 36.0, 5),
    SUV_LARGE("SUV grande / 4x4 gasolina", "Large SUV / 4x4", "Fortuner, Grand Vitara, Prado", FuelType.EXTRA, 29.0, 7),
    PICKUP_DIESEL("Camioneta diésel", "Diesel pickup", "Hilux, D-Max, BT-50, Ranger", FuelType.DIESEL, 37.0, 5),
    VAN_DIESEL("Furgoneta / van", "Van", "H1, Sprinter, Hiace", FuelType.DIESEL, 32.0, 12),
    HYBRID("Híbrido", "Hybrid", "Prius, Corolla Cross HEV", FuelType.EXTRA, 68.0, 5),
    MOTORCYCLE("Moto 150–250 cc", "Motorcycle 150–250 cc", "Suzuki GN, Honda XR", FuelType.EXTRA, 105.0, 2),
    ELECTRIC("Eléctrico", "Electric", "BYD, Kia EV, Dongfeng", FuelType.ELECTRIC, 6.5, 5);

    companion object {
        fun from(value: String?): VehicleType = entries.firstOrNull { it.name == value } ?: SEDAN
    }
}

/**
 * El vehículo del viajero y los precios con los que se calcula el viaje.
 * Se guarda en el teléfono (VehicleSettings) y vale para todas las rutas.
 */
data class VehicleProfile(
    val type: VehicleType = VehicleType.SEDAN,
    val fuel: FuelType = type.defaultFuel,
    /** Rendimiento que escribe el viajero (km/galón o km/kWh). Por defecto el del tipo. */
    val kmPerUnit: Double = type.kmPerUnit,
    /** % extra de consumo por montaña, aire acondicionado y carro cargado. */
    val extraConsumptionPct: Int = 10,
    /** Lo que cuesta cada peaje para vehículo liviano (USD). */
    val tollUsd: Double = 1.00,
    /** Precio por unidad de cada combustible. */
    val prices: Map<FuelType, Double> = FuelType.entries.associateWith { it.defaultPrice },
    /** Desde cuándo valen esos precios ("2026-10-12"). */
    val pricesValidFrom: String = DEFAULT_PRICES_FROM,
    /** Hasta cuándo valen ("2026-11-11"); vacío si no se sabe. */
    val pricesValidUntil: String = DEFAULT_PRICES_UNTIL,
    /** De dónde salió el precio: "Petroecuador", "escrito por ti", o la web consultada. */
    val pricesSource: String = "Petroecuador (precio oficial)"
) {
    val pricePerUnit: Double get() = prices[fuel] ?: fuel.defaultPrice

    /** Rendimiento real ya descontado el extra de montaña/carga. */
    val effectiveKmPerUnit: Double
        get() = (kmPerUnit.coerceAtLeast(0.5)) / (1.0 + extraConsumptionPct.coerceIn(0, 60) / 100.0)

    /** Costo de mover el vehículo 1 km, solo combustible. */
    val costPerKm: Double get() = pricePerUnit / effectiveKmPerUnit

    /** L/100 km, para quien piensa en litros. Solo tiene sentido con combustible líquido. */
    val litersPer100Km: Double get() = if (fuel == FuelType.ELECTRIC) 0.0 else 100.0 / (kmPerUnit / 3.785)

    companion object {
        const val DEFAULT_PRICES_FROM = "2026-10-12"
        const val DEFAULT_PRICES_UNTIL = "2026-11-11"
    }
}

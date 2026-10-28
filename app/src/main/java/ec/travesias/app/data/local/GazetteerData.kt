package ec.travesias.app.data.local

/**
 * Nomenclátor del Ecuador: las 24 provincias y sus 221 cantones, más las localidades
 * turísticas que la gente busca por su nombre (playas, parroquias, parques, lagunas).
 *
 * Va incluido en la app, así que el autocompletado funciona SIN internet. Las coordenadas
 * están puestas donde se conocen con certeza; las demás se resuelven la primera vez que
 * alguien elige ese lugar (OpenStreetMap) y quedan guardadas en el teléfono.
 */
object GazetteerData {

    const val KIND_PROVINCE = "provincia"
    const val KIND_CANTON = "cantón"
    const val KIND_LOCALITY = "localidad"

    data class Entry(
        val name: String,
        val kind: String,
        val parent: String,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0
    )

    /** 24 provincias con la coordenada de su capital. */
    val provinces: List<Entry> = listOf(
        Entry("Azuay", KIND_PROVINCE, "Ecuador", -2.9001, -79.0059),
        Entry("Bolívar", KIND_PROVINCE, "Ecuador", -1.5905, -79.0009),
        Entry("Cañar", KIND_PROVINCE, "Ecuador", -2.7395, -78.8489),
        Entry("Carchi", KIND_PROVINCE, "Ecuador", 0.8117, -77.7178),
        Entry("Chimborazo", KIND_PROVINCE, "Ecuador", -1.6636, -78.6546),
        Entry("Cotopaxi", KIND_PROVINCE, "Ecuador", -0.9333, -78.6167),
        Entry("El Oro", KIND_PROVINCE, "Ecuador", -3.2581, -79.9554),
        Entry("Esmeraldas", KIND_PROVINCE, "Ecuador", 0.9592, -79.6539),
        Entry("Galápagos", KIND_PROVINCE, "Ecuador", -0.7435, -90.3126),
        Entry("Guayas", KIND_PROVINCE, "Ecuador", -2.1709, -79.9224),
        Entry("Imbabura", KIND_PROVINCE, "Ecuador", 0.3517, -78.1223),
        Entry("Loja", KIND_PROVINCE, "Ecuador", -3.9931, -79.2042),
        Entry("Los Ríos", KIND_PROVINCE, "Ecuador", -1.8022, -79.5345),
        Entry("Manabí", KIND_PROVINCE, "Ecuador", -1.0546, -80.4525),
        Entry("Morona Santiago", KIND_PROVINCE, "Ecuador", -2.3050, -78.1192),
        Entry("Napo", KIND_PROVINCE, "Ecuador", -0.9938, -77.8156),
        Entry("Orellana", KIND_PROVINCE, "Ecuador", -0.4625, -76.9871),
        Entry("Pastaza", KIND_PROVINCE, "Ecuador", -1.4881, -78.0028),
        Entry("Pichincha", KIND_PROVINCE, "Ecuador", -0.1807, -78.4678),
        Entry("Santa Elena", KIND_PROVINCE, "Ecuador", -2.2267, -80.8583),
        Entry("Santo Domingo de los Tsáchilas", KIND_PROVINCE, "Ecuador", -0.2542, -79.1750),
        Entry("Sucumbíos", KIND_PROVINCE, "Ecuador", 0.0856, -76.8931),
        Entry("Tungurahua", KIND_PROVINCE, "Ecuador", -1.2491, -78.6167),
        Entry("Zamora Chinchipe", KIND_PROVINCE, "Ecuador", -4.0689, -78.9558)
    )

    /** Los 221 cantones, agrupados por provincia. */
    private val cantonsByProvince: Map<String, List<String>> = linkedMapOf(
        "Azuay" to listOf("Cuenca", "Girón", "Gualaceo", "Nabón", "Paute", "Pucará", "San Fernando",
            "Santa Isabel", "Sígsig", "Oña", "Chordeleg", "El Pan", "Sevilla de Oro", "Guachapala",
            "Camilo Ponce Enríquez"),
        "Bolívar" to listOf("Guaranda", "Chillanes", "Chimbo", "Echeandía", "San Miguel", "Caluma", "Las Naves"),
        "Cañar" to listOf("Azogues", "Biblián", "Cañar", "La Troncal", "El Tambo", "Déleg", "Suscal"),
        "Carchi" to listOf("Tulcán", "Bolívar", "Espejo", "Mira", "Montúfar", "San Pedro de Huaca"),
        "Chimborazo" to listOf("Riobamba", "Alausí", "Colta", "Chambo", "Chunchi", "Guamote", "Guano",
            "Pallatanga", "Penipe", "Cumandá"),
        "Cotopaxi" to listOf("Latacunga", "La Maná", "Pangua", "Pujilí", "Salcedo", "Saquisilí", "Sigchos"),
        "El Oro" to listOf("Machala", "Arenillas", "Atahualpa", "Balsas", "Chilla", "El Guabo", "Huaquillas",
            "Marcabelí", "Pasaje", "Piñas", "Portovelo", "Santa Rosa", "Zaruma", "Las Lajas"),
        "Esmeraldas" to listOf("Esmeraldas", "Eloy Alfaro", "Muisne", "Quinindé", "San Lorenzo", "Atacames", "Rioverde"),
        "Galápagos" to listOf("San Cristóbal", "Isabela", "Santa Cruz"),
        "Guayas" to listOf("Guayaquil", "Alfredo Baquerizo Moreno", "Balao", "Balzar", "Colimes", "Daule",
            "Durán", "El Empalme", "El Triunfo", "Milagro", "Naranjal", "Naranjito", "Palestina",
            "Pedro Carbo", "Samborondón", "Santa Lucía", "Salitre", "San Jacinto de Yaguachi", "Playas",
            "Simón Bolívar", "Coronel Marcelino Maridueña", "Lomas de Sargentillo", "Nobol",
            "General Antonio Elizalde", "Isidro Ayora"),
        "Imbabura" to listOf("Ibarra", "Antonio Ante", "Cotacachi", "Otavalo", "Pimampiro", "San Miguel de Urcuquí"),
        "Loja" to listOf("Loja", "Calvas", "Catamayo", "Celica", "Chaguarpamba", "Espíndola", "Gonzanamá",
            "Macará", "Paltas", "Puyango", "Saraguro", "Sozoranga", "Zapotillo", "Pindal", "Quilanga", "Olmedo"),
        "Los Ríos" to listOf("Babahoyo", "Baba", "Montalvo", "Puebloviejo", "Quevedo", "Urdaneta", "Ventanas",
            "Vinces", "Palenque", "Buena Fe", "Valencia", "Mocache", "Quinsaloma"),
        "Manabí" to listOf("Portoviejo", "Bolívar", "Chone", "El Carmen", "Flavio Alfaro", "Jipijapa", "Junín",
            "Manta", "Montecristi", "Paján", "Pichincha", "Rocafuerte", "Santa Ana", "Sucre", "Tosagua",
            "24 de Mayo", "Pedernales", "Olmedo", "Puerto López", "Jama", "Jaramijó", "San Vicente"),
        "Morona Santiago" to listOf("Morona", "Gualaquiza", "Limón Indanza", "Palora", "Santiago", "Sucúa",
            "Huamboya", "San Juan Bosco", "Taisha", "Logroño", "Pablo Sexto", "Tiwintza"),
        "Napo" to listOf("Tena", "Archidona", "El Chaco", "Quijos", "Carlos Julio Arosemena Tola"),
        "Orellana" to listOf("Orellana", "Aguarico", "La Joya de los Sachas", "Loreto"),
        "Pastaza" to listOf("Pastaza", "Mera", "Santa Clara", "Arajuno"),
        "Pichincha" to listOf("Quito", "Cayambe", "Mejía", "Pedro Moncayo", "Rumiñahui",
            "San Miguel de los Bancos", "Pedro Vicente Maldonado", "Puerto Quito"),
        "Santa Elena" to listOf("Santa Elena", "La Libertad", "Salinas"),
        "Santo Domingo de los Tsáchilas" to listOf("Santo Domingo", "La Concordia"),
        "Sucumbíos" to listOf("Lago Agrio", "Gonzalo Pizarro", "Putumayo", "Shushufindi", "Sucumbíos",
            "Cascales", "Cuyabeno"),
        "Tungurahua" to listOf("Ambato", "Baños de Agua Santa", "Cevallos", "Mocha", "Patate", "Quero",
            "San Pedro de Pelileo", "Santiago de Píllaro", "Tisaleo"),
        "Zamora Chinchipe" to listOf("Zamora", "Chinchipe", "Nangaritza", "Yacuambi", "Yantzaza", "El Pangui",
            "Centinela del Cóndor", "Palanda", "Paquisha")
    )

    val cantons: List<Entry> = cantonsByProvince.flatMap { (province, list) ->
        list.map { Entry(it, KIND_CANTON, province) }
    }

    /**
     * Las 24 capitales de provincia CON coordenadas.
     *
     * Los cantones de [cantonsByProvince] son solo nombres, sin coordenadas: valen para
     * autocompletar pero no para saber dónde está uno. Estas entradas llevan la coordenada de
     * la ciudad y, como comparten id con su cantón homónimo ("cantón:cuenca"), lo REEMPLAZAN
     * al sembrar el índice — por eso van después de [cantons] en [all].
     *
     * Sin esto, buscar la ciudad más cercana al GPS devolvía la provincia ("Azuay" en vez de
     * "Cuenca") o, peor, un sitio turístico ("Cerro Santa Ana" estando en Guayaquil).
     */
    val capitals: List<Entry> = listOf(
        Entry("Cuenca", KIND_CANTON, "Azuay", -2.9001, -79.0059),
        Entry("Guaranda", KIND_CANTON, "Bolívar", -1.5905, -79.0009),
        Entry("Azogues", KIND_CANTON, "Cañar", -2.7395, -78.8489),
        Entry("Tulcán", KIND_CANTON, "Carchi", 0.8117, -77.7178),
        Entry("Riobamba", KIND_CANTON, "Chimborazo", -1.6636, -78.6546),
        Entry("Latacunga", KIND_CANTON, "Cotopaxi", -0.9333, -78.6167),
        Entry("Machala", KIND_CANTON, "El Oro", -3.2581, -79.9554),
        Entry("Esmeraldas", KIND_CANTON, "Esmeraldas", 0.9592, -79.6539),
        Entry("Puerto Baquerizo Moreno", KIND_CANTON, "Galápagos", -0.9022, -89.6086),
        Entry("Guayaquil", KIND_CANTON, "Guayas", -2.1709, -79.9224),
        Entry("Ibarra", KIND_CANTON, "Imbabura", 0.3517, -78.1223),
        Entry("Loja", KIND_CANTON, "Loja", -3.9931, -79.2042),
        Entry("Babahoyo", KIND_CANTON, "Los Ríos", -1.8022, -79.5345),
        Entry("Portoviejo", KIND_CANTON, "Manabí", -1.0546, -80.4525),
        Entry("Macas", KIND_CANTON, "Morona Santiago", -2.3050, -78.1192),
        Entry("Tena", KIND_CANTON, "Napo", -0.9938, -77.8156),
        Entry("Puerto Francisco de Orellana", KIND_CANTON, "Orellana", -0.4625, -76.9871),
        Entry("Puyo", KIND_CANTON, "Pastaza", -1.4881, -78.0028),
        Entry("Quito", KIND_CANTON, "Pichincha", -0.1807, -78.4678),
        Entry("Santa Elena", KIND_CANTON, "Santa Elena", -2.2267, -80.8583),
        Entry("Santo Domingo", KIND_CANTON, "Santo Domingo de los Tsáchilas", -0.2542, -79.1750),
        Entry("Nueva Loja", KIND_CANTON, "Sucumbíos", 0.0856, -76.8931),
        Entry("Ambato", KIND_CANTON, "Tungurahua", -1.2491, -78.6167),
        Entry("Zamora", KIND_CANTON, "Zamora Chinchipe", -4.0689, -78.9558),
        // Ciudades grandes que no son capital de provincia pero de las que se sale mucho.
        Entry("Manta", KIND_CANTON, "Manabí", -0.9677, -80.7089),
        Entry("Durán", KIND_CANTON, "Guayas", -2.1700, -79.8300),
        Entry("Milagro", KIND_CANTON, "Guayas", -2.1344, -79.5939),
        Entry("Quevedo", KIND_CANTON, "Los Ríos", -1.0286, -79.4636),
        Entry("Otavalo", KIND_CANTON, "Imbabura", 0.2340, -78.2610),
        Entry("Baños de Agua Santa", KIND_CANTON, "Tungurahua", -1.3928, -78.4269),
        Entry("Salinas", KIND_CANTON, "Santa Elena", -2.2139, -80.9581),
        Entry("Ibarra", KIND_CANTON, "Imbabura", 0.3517, -78.1223)
    )

    /** Localidades, playas, parroquias y parques que la gente busca por su nombre. */
    val localities: List<Entry> = listOf(
        // Costa
        Entry("Montañita", KIND_LOCALITY, "Santa Elena", -1.8281, -80.7539),
        Entry("Olón", KIND_LOCALITY, "Santa Elena", -1.7936, -80.7597),
        Entry("Ayangue", KIND_LOCALITY, "Santa Elena", -1.9797, -80.7539),
        Entry("Ballenita", KIND_LOCALITY, "Santa Elena"),
        Entry("Punta Carnero", KIND_LOCALITY, "Santa Elena"),
        Entry("Playas (General Villamil)", KIND_LOCALITY, "Guayas", -2.6333, -80.3833),
        Entry("Puerto López", KIND_LOCALITY, "Manabí", -1.5567, -80.8069),
        Entry("Los Frailes", KIND_LOCALITY, "Manabí", -1.4869, -80.7911),
        Entry("Las Tunas", KIND_LOCALITY, "Manabí"),
        Entry("Puerto Cayo", KIND_LOCALITY, "Manabí"),
        Entry("Isla de la Plata", KIND_LOCALITY, "Manabí", -1.2733, -81.0736),
        Entry("Canoa", KIND_LOCALITY, "Manabí", -0.4667, -80.4500),
        Entry("Bahía de Caráquez", KIND_LOCALITY, "Manabí", -0.5975, -80.4239),
        Entry("Crucita", KIND_LOCALITY, "Manabí", -0.8667, -80.5333),
        Entry("San Clemente", KIND_LOCALITY, "Manabí"),
        Entry("Cojimíes", KIND_LOCALITY, "Manabí"),
        Entry("Mompiche", KIND_LOCALITY, "Esmeraldas", 0.5000, -80.0333),
        Entry("Atacames", KIND_LOCALITY, "Esmeraldas", 0.8722, -79.8514),
        Entry("Same", KIND_LOCALITY, "Esmeraldas", 0.8331, -79.9169),
        Entry("Tonsupa", KIND_LOCALITY, "Esmeraldas"),
        Entry("Súa", KIND_LOCALITY, "Esmeraldas"),
        Entry("Canoa Beach", KIND_LOCALITY, "Manabí"),
        Entry("Puerto Cabuyal", KIND_LOCALITY, "Manabí"),
        // Sierra
        Entry("Mindo", KIND_LOCALITY, "Pichincha", -0.0519, -78.7756),
        Entry("Papallacta", KIND_LOCALITY, "Napo", -0.3672, -78.1428),
        Entry("Quilotoa", KIND_LOCALITY, "Cotopaxi", -0.8592, -78.9003),
        Entry("Zumbahua", KIND_LOCALITY, "Cotopaxi", -0.9333, -78.9000),
        Entry("Chugchilán", KIND_LOCALITY, "Cotopaxi"),
        Entry("Isinliví", KIND_LOCALITY, "Cotopaxi"),
        Entry("Parque Nacional Cotopaxi", KIND_LOCALITY, "Cotopaxi", -0.6803, -78.4378),
        Entry("Laguna de Limpiopungo", KIND_LOCALITY, "Cotopaxi", -0.6167, -78.4667),
        Entry("Volcán Chimborazo", KIND_LOCALITY, "Chimborazo", -1.4692, -78.8175),
        Entry("Nariz del Diablo", KIND_LOCALITY, "Chimborazo", -2.2000, -78.8500),
        Entry("Laguna de Colta", KIND_LOCALITY, "Chimborazo"),
        Entry("Baños de Agua Santa", KIND_LOCALITY, "Tungurahua", -1.3928, -78.4269),
        Entry("Casa del Árbol", KIND_LOCALITY, "Tungurahua", -1.4133, -78.4200),
        Entry("Pailón del Diablo", KIND_LOCALITY, "Tungurahua", -1.3958, -78.3236),
        Entry("Ruta de las Cascadas", KIND_LOCALITY, "Tungurahua"),
        Entry("Laguna de Cuicocha", KIND_LOCALITY, "Imbabura", 0.3072, -78.3661),
        Entry("Peguche", KIND_LOCALITY, "Imbabura", 0.2333, -78.2333),
        Entry("Laguna de San Pablo", KIND_LOCALITY, "Imbabura", 0.2044, -78.2172),
        Entry("Mitad del Mundo", KIND_LOCALITY, "Pichincha", -0.0022, -78.4558),
        Entry("Pululahua", KIND_LOCALITY, "Pichincha", -0.0331, -78.4964),
        Entry("Teleférico de Quito", KIND_LOCALITY, "Pichincha", -0.1836, -78.5150),
        Entry("Centro Histórico de Quito", KIND_LOCALITY, "Pichincha", -0.2201, -78.5123),
        Entry("La Ronda", KIND_LOCALITY, "Pichincha"),
        Entry("Cotacachi", KIND_LOCALITY, "Imbabura", 0.3000, -78.2667),
        Entry("Vilcabamba", KIND_LOCALITY, "Loja", -4.2619, -79.2247),
        Entry("Parque Nacional Podocarpus", KIND_LOCALITY, "Loja", -4.1167, -79.1000),
        Entry("Parque Nacional Cajas", KIND_LOCALITY, "Azuay", -2.8333, -79.2167),
        Entry("Ingapirca", KIND_LOCALITY, "Cañar", -2.5442, -78.8728),
        Entry("Chordeleg", KIND_LOCALITY, "Azuay"),
        Entry("Saraguro", KIND_LOCALITY, "Loja"),
        Entry("Salinas de Guaranda", KIND_LOCALITY, "Bolívar", -1.4000, -79.0000),
        // Amazonía
        Entry("Puyo", KIND_LOCALITY, "Pastaza", -1.4881, -78.0028),
        Entry("Misahuallí", KIND_LOCALITY, "Napo", -1.0356, -77.6631),
        Entry("Tena", KIND_LOCALITY, "Napo", -0.9938, -77.8156),
        Entry("Archidona", KIND_LOCALITY, "Napo"),
        Entry("Macas", KIND_LOCALITY, "Morona Santiago", -2.3050, -78.1192),
        Entry("Reserva Cuyabeno", KIND_LOCALITY, "Sucumbíos", -0.2500, -76.1667),
        Entry("Parque Nacional Yasuní", KIND_LOCALITY, "Orellana", -0.9000, -75.9000),
        Entry("Cascada San Rafael", KIND_LOCALITY, "Sucumbíos"),
        // Galápagos
        Entry("Puerto Ayora", KIND_LOCALITY, "Galápagos", -0.7433, -90.3100),
        Entry("Puerto Baquerizo Moreno", KIND_LOCALITY, "Galápagos", -0.9022, -89.6106),
        Entry("Isla Isabela", KIND_LOCALITY, "Galápagos", -0.9333, -91.0000),
        Entry("Tortuga Bay", KIND_LOCALITY, "Galápagos", -0.7594, -90.3306),
        Entry("Las Grietas", KIND_LOCALITY, "Galápagos", -0.7522, -90.3175),
        Entry("Las Tintoreras", KIND_LOCALITY, "Galápagos", -0.9636, -90.9694),
        Entry("Isla Genovesa", KIND_LOCALITY, "Galápagos", 0.3196, -89.9583),
        Entry("Playa de los Alemanes", KIND_LOCALITY, "Galápagos"),
        Entry("El Garrapatero", KIND_LOCALITY, "Galápagos"),
        // Añadidos tras la investigación del catastro turístico
        Entry("Cementerio de Tulcán", KIND_LOCALITY, "Carchi", 0.8197, -77.7236),
        Entry("Runtún", KIND_LOCALITY, "Tungurahua", -1.4108, -78.4161),
        Entry("Manos de Dios", KIND_LOCALITY, "Tungurahua", -1.4108, -78.4161),
        Entry("Nido del Cóndor", KIND_LOCALITY, "Tungurahua", -1.4108, -78.4161),
        Entry("Río Verde", KIND_LOCALITY, "Tungurahua", -1.3958, -78.3236),
        Entry("Reloj Solar Quitsato", KIND_LOCALITY, "Pichincha", 0.0037, -78.1994),
        Entry("Zaruma", KIND_LOCALITY, "El Oro", -3.6931, -79.6122),
        Entry("Cerro Santa Ana", KIND_LOCALITY, "Guayas", -2.1875, -79.8756),
        Entry("Las Peñas", KIND_LOCALITY, "Guayas", -2.1889, -79.8747),
        Entry("Ayampe", KIND_LOCALITY, "Manabí", -1.6725, -80.7847),
        Entry("Machalilla", KIND_LOCALITY, "Manabí"),
        Entry("Mirador Indichuris", KIND_LOCALITY, "Pastaza", -1.6523, -77.9171),
        Entry("Pomona", KIND_LOCALITY, "Pastaza", -1.6523, -77.9171),
        Entry("Cavernas de Jumandy", KIND_LOCALITY, "Napo", -0.8753, -77.7927),
        Entry("Laguna Azul (Waysa Yaku)", KIND_LOCALITY, "Napo", -1.0089, -77.8942),
        Entry("Talag", KIND_LOCALITY, "Napo", -1.0089, -77.8942),
        Entry("Pano", KIND_LOCALITY, "Napo"),
        Entry("Cueva de los Tayos", KIND_LOCALITY, "Morona Santiago", -1.9333, -77.7833),
        Entry("Sibambe", KIND_LOCALITY, "Chimborazo", -2.2240, -78.8720),
        Entry("Refugio Carrel", KIND_LOCALITY, "Chimborazo", -1.4700, -78.8170),
        Entry("Laguna Toreadora", KIND_LOCALITY, "Azuay", -2.7794, -79.2236),
        Entry("Quiroga", KIND_LOCALITY, "Imbabura"),
        Entry("Machachi", KIND_LOCALITY, "Pichincha", -0.5108, -78.5675),
        Entry("Sangolquí", KIND_LOCALITY, "Pichincha", -0.3308, -78.4475)
    )

    /** Todo el nomenclátor junto. */
    // Orden importante: [capitals] va DESPUÉS de [cantons] para que sus coordenadas ganen al
    // sembrar (comparten id), y antes de [localities] para no pisar los sitios turísticos.
    val all: List<Entry> get() = provinces + cantons + capitals + localities
}

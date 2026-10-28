package ec.travesias.app.data.local

import ec.travesias.app.data.model.*

/**
 * Catálogo inicial curado de Travesías en Ecuador.
 * Coordenadas reales (WGS84). Los costos son referenciales y la IA / Google Places
 * pueden actualizarlos. Todo lo aquí incluido funciona 100% offline.
 */
object SeedData {

    private fun p(
        id: String, name: String, cat: PlaceCategory, region: EcuadorRegion, province: String, city: String,
        lat: Double, lng: Double, short: String, full: String, costMin: Double, costMax: Double,
        hours: String, howTo: String, transport: List<String>, alt: Int?, temp: Int?,
        tags: List<String>, tips: List<String>, contact: PlaceContact = PlaceContact(), images: List<String> = emptyList(),
        sources: List<PlaceSource> = emptyList()
    ) = Place(
        id = id, name = name, category = cat, region = region, province = province, city = city,
        latitude = lat, longitude = lng, shortDescription = short, fullDescription = full,
        costMinUsd = costMin, costMaxUsd = costMax, openingHours = hours, howToGetThere = howTo,
        transportOptions = transport, altitudeMeters = alt, averageTempCelsius = temp, tags = tags,
        localTips = tips, contact = contact, imageUrls = images, sources = sources,
        verification = VerificationLevel.CURATED, createdAt = 1L
    )

    /** Fuente web/social que respalda la ficha. */
    private fun src(url: String, title: String = "") = PlaceSource(SourcePlatform.fromUrl(url), url, title)

    val places: List<Place> = listOf(
        // ---------------- SIERRA ----------------
        p("quito_centro", "Centro Histórico de Quito", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Pichincha", "Quito",
            -0.2202, -78.5123,
            "Primer Patrimonio Cultural de la Humanidad (UNESCO 1978): iglesias barrocas, plazas y balcones coloniales.",
            "El centro histórico mejor conservado de América Latina. Imperdibles: Iglesia de la Compañía (pan de oro), Plaza Grande, San Francisco, La Ronda y el mirador del Panecillo.",
            0.0, 25.0, "Plazas 24h · Iglesias 09:00–17:00 (varían)",
            "Metro de Quito hasta estación San Francisco ($0.45). Desde el aeropuerto: taxi/Uber 45–60 min ($25–30) o bus Aeroservicios a Río Coca.",
            listOf("Metro de Quito", "Trole (parada Plaza del Teatro)", "Taxi / Uber", "A pie"),
            2850, 16, listOf("Patrimonio", "Arquitectura", "Historia", "Cafés"),
            listOf("Sube a la torre de la Basílica del Voto Nacional ($3) para la mejor panorámica.", "Lleva paraguas: llueve casi todas las tardes.", "Al Panecillo ve en taxi, no a pie."),
            PlaceContact(website = "https://www.quito.com.ec", phone = "1800 887 476")),

        p("mitad_del_mundo", "Ciudad Mitad del Mundo", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Pichincha", "San Antonio de Pichincha",
            -0.0022, -78.4558,
            "El monumento sobre la línea ecuatorial y el museo Intiñan con experimentos de latitud 0.",
            "Complejo turístico con el monumento de 30 m, planetario, museos etnográficos y el Museo Intiñan (línea GPS real) a 200 m.",
            5.0, 15.0, "09:00–18:00 todos los días",
            "Desde Quito: bus 'Mitad del Mundo' desde la estación La Ofelia (~$0.50, 1 h) o taxi/Uber ($15–20, 40 min).",
            listOf("Bus desde La Ofelia", "Taxi / Uber", "Tour de medio día"),
            2483, 18, listOf("Ícono", "Familiar", "Museo"),
            listOf("Combínalo con el cráter del Pululahua (15 min más).", "Entrada básica $5; con planetario y museos hasta $15."),
            PlaceContact(website = "https://mitaddelmundo.gob.ec", phone = "(02) 239 4803")),

        p("teleferico_quito", "TelefériQo y Cruz Loma", PlaceCategory.VIEWPOINT, EcuadorRegion.ANDES, "Pichincha", "Quito",
            -0.1897, -78.5188,
            "Sube en teleférico a 3.945 msnm para la mejor vista de Quito y los volcanes.",
            "Uno de los teleféricos más altos del mundo. Arriba hay senderos hacia el Rucu Pichincha, columpio con vista a la ciudad y cafeterías.",
            9.0, 9.0, "Lun–Jue 09:00–18:00 · Vie–Dom 08:00–19:00",
            "Taxi/Uber desde cualquier punto de Quito ($5–8). No hay transporte público directo hasta la estación.",
            listOf("Taxi / Uber", "Vehículo propio (parqueadero $2)"),
            3945, 9, listOf("Mirador", "Fotografía", "Senderismo"),
            listOf("Ve temprano y en día despejado.", "Aclimátate al menos un día en Quito antes de subir."),
            PlaceContact(website = "https://teleferico.com.ec")),

        p("otavalo_mercado", "Mercado de Otavalo (Plaza de Ponchos)", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Imbabura", "Otavalo",
            0.2340, -78.2620,
            "El mercado indígena más famoso de Sudamérica: textiles, artesanías y cultura kichwa otavalo.",
            "Funciona todos los días, pero el sábado es el gran día de feria con cientos de puestos. Cerca: Cascada de Peguche y lagunas de Mojanda.",
            0.0, 40.0, "Todos los días 08:00–18:00 · Feria grande sábados",
            "Bus desde Quito (Terminal Carcelén) cada 15 min, ~2 h, $2.50–3. Combina con la Laguna de Cuicocha.",
            listOf("Bus interprovincial", "Vehículo propio (Panamericana Norte)", "Tour de un día"),
            2550, 14, listOf("Artesanías", "Cultura Kichwa", "Compras"),
            listOf("Regatea con respeto, ~20–30 % es normal.", "Prueba el hornado en el mercado 24 de Mayo.")),

        p("cuicocha", "Laguna de Cuicocha", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Imbabura", "Cotacachi",
            0.3050, -78.3640,
            "Laguna volcánica con dos islotes dentro de un cráter, sendero de 14 km por el borde.",
            "Parte de la Reserva Ecológica Cotacachi-Cayapas. Sendero circular (4–5 h) con vistas al Cotacachi e Imbabura. Paseos en lancha disponibles.",
            0.0, 5.0, "08:00–17:00",
            "Desde Otavalo taxi/camioneta $10–12 (25 min). Bus a Quiroga y camioneta hasta la laguna.",
            listOf("Camioneta desde Otavalo", "Vehículo propio"),
            3068, 12, listOf("Volcán", "Trekking", "Laguna"),
            listOf("Entrada gratuita; lancha $3–4.", "Empieza el sendero antes de las 10:00 por la neblina.")),

        p("mindo", "Mindo (Bosque Nublado)", PlaceCategory.ADVENTURE, EcuadorRegion.ANDES, "Pichincha", "Mindo",
            -0.0530, -78.7760,
            "Bosque nublado con colibríes, mariposarios, tarabita a cascadas, tubing y canopy.",
            "A 2 h de Quito. Más de 500 especies de aves. Actividades: Santuario de Cascadas (tarabita), mariposario, tour de chocolate, canopy y tubing en el río Mindo.",
            5.0, 30.0, "Atracciones 09:00–17:00",
            "Bus Flor del Valle desde La Ofelia (Quito) $3.10, 2h15. Vehículo propio por la vía Calacalí–La Independencia.",
            listOf("Bus Flor del Valle", "Vehículo propio", "Tour"),
            1250, 19, listOf("Aves", "Cascadas", "Chocolate", "Aventura"),
            listOf("Tarabita $5 + entrada a cascadas $5.", "Lleva repelente y ropa de secado rápido.")),

        p("cotopaxi_pn", "Parque Nacional Cotopaxi", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Cotopaxi", "Latacunga",
            -0.6800, -78.4378,
            "Volcán activo de 5.897 m, páramo con caballos salvajes y la Laguna de Limpiopungo.",
            "Se puede subir en vehículo hasta el parqueadero (4.600 m) y caminar 45 min al Refugio José Rivas (4.864 m). Bicicleta de descenso y cabalgatas disponibles.",
            0.0, 45.0, "08:00–15:00 (último ingreso) · Cierra 17:00",
            "Entrada Control Caspi por la Panamericana Sur (E35), 1h30 desde Quito. Sin vehículo: camioneta desde Machachi o Lasso ($40–60 ida y vuelta).",
            listOf("Vehículo propio 4x4 recomendado", "Camioneta desde Machachi", "Tour desde Quito"),
            4864, 8, listOf("Volcán", "Trekking", "Páramo", "Fotografía"),
            listOf("Entrada gratuita, registro obligatorio.", "Ropa térmica, guantes y gafas UV.", "No subas si tienes síntomas de soroche.")),

        p("quilotoa", "Laguna del Quilotoa", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Cotopaxi", "Zumbahua",
            -0.8580, -78.9010,
            "Cráter volcánico con laguna turquesa a 3.914 m; descenso a la orilla y kayaks.",
            "Caldera de 3 km de diámetro formada hace unos 800 años por una erupción de nivel VEI-6. La laguna tiene 250 m de profundidad y su color verde esmeralda viene de los minerales disueltos y las fumarolas submarinas. El descenso a la orilla toma 30–40 min; la subida de vuelta son 280 m verticales y más de una hora de esfuerzo real por la altura. El mirador de Shalalá tiene piso de vidrio templado sobre el abismo.",
            2.0, 12.0, "Todo el día · Mirador Shalalá 08:00–17:00",
            "Está a 179 km de Quito, 66 km de Latacunga y 17 km al sur de Zumbahua, por carretera asfaltada en buen estado. Desde Latacunga: bus a Zumbahua ($2, 1 h 30) y camioneta a Quilotoa ($5 por vehículo). En vehículo propio, vía Pujilí–Zumbahua. Es la parada estrella del Quilotoa Loop.",
            listOf("Bus + camioneta", "Vehículo propio vía Pujilí", "Tour Quilotoa Loop"),
            3914, 10, listOf("Laguna", "Volcán", "Trekking", "Kayak"),
            listOf(
                "Entrada comunitaria $2 en efectivo.",
                "A 3.914 m el ascenso de vuelta cansa mucho más de lo que parece: si dudas, sube en mula ($10).",
                "Viento fuerte y helado todo el año: cortavientos obligatorio.",
                "Kayak $3 por 30 min sobre las fumarolas."
            ),
            sources = listOf(src("https://www.quilotoa.com/mapa.html", "Mapa y guía del Quilotoa"))),

        p("banos_pailon", "Pailón del Diablo", PlaceCategory.ADVENTURE, EcuadorRegion.ANDES, "Tungurahua", "Río Verde (Baños)",
            -1.4030, -78.3010,
            "Cascada de 80 m con escaleras talladas en la roca y puentes colgantes; la más visitada del país.",
            "Caída de 80 m sobre roca volcánica de unos 5.000 años, donde el río Pastaza se estrella formando el 'rostro del diablo'. Hay dos entradas: la clásica, con escalinatas talladas a mano en la roca viva, puente colgante y el balcón que se mete bajo la cascada; y la nueva 'Grieta al Cielo', con plataformas de cristal y acero ancladas sobre el abismo. En temporada alta hay iluminación nocturna de colores.",
            2.0, 5.0, "09:00–17:00 (la entrada nueva cierra 17:30)",
            "Vía Baños–Puyo, kilómetro 17, junto a la iglesia de la parroquia Río Verde, a 18 km de Baños. Desde Baños: bus interprovincial a Puyo ($0,50, 30 min) y pides bajarte en Río Verde; bicicleta por la Ruta de las Cascadas (18 km casi todo en bajada, alquiler $5–8 al día); o la chiva turística que cruza los túneles viales.",
            listOf("Bus Baños–Puyo", "Bicicleta (alquiler $5–8/día)", "Chiva turística", "Taxi ($15–20 ida y vuelta)"),
            1450, 20, listOf("Cascadas", "Ruta de las Cascadas", "Puente de cristal", "Foto Icónica"),
            listOf(
                "Llega antes de las 09:30 para evitar filas y encontrar el arcoíris del rocío.",
                "Poncho plástico o chaqueta impermeable: en el balcón te empapas seguro.",
                "Las dos entradas cobran por separado ($2 cada una); si solo eliges una, la nueva tiene los miradores de cristal."
            ),
            sources = listOf(
                src("https://www.youtube.com/watch?v=HyzrORcaVZ8", "Pailón del Diablo en video"),
                src("https://www.youtube.com/watch?v=CqFl6KjRF1k", "Recorrido completo del Pailón"))),

        p("banos_casa_arbol", "Casa del Árbol (Columpio del Fin del Mundo)", PlaceCategory.VIEWPOINT, EcuadorRegion.ANDES, "Tungurahua", "Baños de Agua Santa",
            -1.4370, -78.4340,
            "El columpio viral de Ecuador con vista directa al volcán Tungurahua.",
            "Estación de monitoreo volcánico convertida en atractivo. Varios columpios, tirolesa y miradores. Mejor con cielo despejado en la mañana.",
            1.0, 2.0, "08:00–18:00",
            "Desde Baños: bus 'Bellavista–Casa del Árbol' desde el terminal ($1, 40 min), taxi $8–10 o caminata de 2h30.",
            listOf("Bus local", "Taxi", "Caminata"),
            2660, 15, listOf("Viral", "Columpio", "Volcán", "Fotografía"),
            listOf("Ve entre 07:00 y 11:00 para ver el volcán sin nubes.", "Combínalo con las termas de La Virgen por la noche.")),

        p("banos_termas", "Termas de la Virgen", PlaceCategory.SERVICES, EcuadorRegion.ANDES, "Tungurahua", "Baños de Agua Santa",
            -1.3985, -78.4180,
            "Aguas termales volcánicas al pie de la cascada Cabellera de la Virgen, en pleno centro de Baños.",
            "Piscinas de 18 a 54 °C. Horario nocturno muy popular. Nuevo complejo Termas de la Virgen con toboganes.",
            3.0, 12.0, "04:30–17:00 y 18:00–22:00",
            "A pie desde cualquier punto del centro de Baños (5–10 min).",
            listOf("A pie"),
            1820, 20, listOf("Termas", "Relax", "Nocturno"),
            listOf("Lleva gorro de baño (obligatorio).", "Las piscinas nocturnas son menos concurridas entre semana.")),

        p("chimborazo", "Reserva Chimborazo (Refugios Carrel y Whymper)", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Chimborazo", "Riobamba",
            -1.4700, -78.8170,
            "El punto más cercano al sol: vicuñas, páramo y refugios a 4.850 y 5.000 msnm.",
            "El Chimborazo mide 6.263 m y su cumbre Whymper es el punto de la corteza terrestre más alejado del núcleo del planeta, es decir, el más cercano al sol. En vehículo convencional se llega hasta el refugio Hermanos Carrel (4.850 m), una de las carreteras más altas del mundo; de ahí son 45 min a pie al refugio Whymper (5.042 m) y algo más a la laguna Cóndor Cocha (5.100 m). Alrededor, el arenal con manadas de vicuñas.",
            2.0, 40.0, "08:00–16:00 · Entrada $2 nacionales, $10 extranjeros",
            "Desde Riobamba (a 180 km de Quito) se toma la vía Riobamba–Guaranda y a los 30 km está el desvío al centro de servicios El Arenal; desde ahí una vía de lastre sube hasta el parqueadero del refugio. Camioneta o tour desde Riobamba $40–50 ida y vuelta con espera.",
            listOf("Camioneta desde Riobamba", "Tour con descenso en bicicleta", "Vehículo propio vía Guaranda"),
            4850, 3, listOf("Volcán", "Vicuñas", "Altura Extrema", "Refugio"),
            listOf(
                "Aclimatación previa obligatoria: no subas el mismo día que llegas de la costa.",
                "El refugio cobra unos $30 la noche con cena y desayuno, para aclimatar antes de cumbre.",
                "Para la cumbre se necesita guía ASEGUIM y equipo de glaciar; no es una caminata.",
                "Prueba el helado de paila del hielero del Chimborazo en Riobamba."
            ),
            sources = listOf(src("https://riobamba.com.ec/es-ec/chimborazo/riobamba/escalada/ruta-alta-montana-chimborazo-a51c67c2a", "Ruta de alta montaña al Chimborazo"))),

        p("alausi_nariz", "Nariz del Diablo (Alausí)", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Chimborazo", "Alausí",
            -2.2000, -78.8480,
            "El tramo ferroviario en zigzag más famoso de los Andes y el pueblo patrimonial de Alausí.",
            "Pueblo pintoresco a orillas de la Panamericana. Verifica en la app si el tren turístico está operando; mientras, hay miradores y caminatas a la Nariz del Diablo desde Pistishí.",
            0.0, 35.0, "Miradores todo el día",
            "Bus Riobamba–Alausí ($2.50, 2 h) o Cuenca–Alausí (4 h). Vehículo por la E35.",
            listOf("Bus interprovincial", "Vehículo propio"),
            2340, 14, listOf("Tren", "Pueblo Mágico", "Historia"),
            listOf("Sube al mirador de San Pedro para la foto del pueblo.", "Domingo es día de feria.")),

        p("ingapirca", "Complejo Arqueológico Ingapirca", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Cañar", "Ingapirca",
            -2.5456, -78.8730,
            "El sitio inca más importante de Ecuador: Templo del Sol y ruinas cañari-incas.",
            "Templo elíptico del Sol, museo de sitio, Cara del Inca y sendero arqueológico. Guías locales incluidos en la entrada.",
            2.0, 6.0, "08:30–17:30",
            "Desde Cuenca: bus Transportes Cañar directo (2 h, $3) o tour. Vehículo por la E35 hasta El Tambo.",
            listOf("Bus directo desde Cuenca", "Tour", "Vehículo propio"),
            3160, 12, listOf("Inca", "Arqueología", "Historia"),
            listOf("Lleva efectivo; no hay cajeros cerca.", "Combínalo con el pueblo de El Tambo.")),

        p("cuenca_centro", "Centro Histórico de Cuenca", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Azuay", "Cuenca",
            -2.8974, -79.0045,
            "La 'Atenas del Ecuador': catedral de cúpulas azules, ríos, sombreros de paja toquilla.",
            "Patrimonio de la Humanidad. Catedral de la Inmaculada, Parque Calderón, Barranco del Tomebamba, Museo Pumapungo (gratis), mercado 10 de Agosto.",
            0.0, 20.0, "Ciudad 24h · Museos 09:00–17:00",
            "Aeropuerto Mariscal Lamar a 10 min del centro. Bus desde Quito 8–9 h ($12) o Guayaquil 4 h ($8). Tranvía urbano $0.35.",
            listOf("Tranvía de Cuenca", "Taxi", "A pie", "Bus interprovincial"),
            2550, 15, listOf("Patrimonio", "Catedral", "Paja Toquilla", "Gastronomía"),
            listOf("Sube a las cúpulas de la catedral ($2).", "Prueba mote pillo y cuy en el mercado 10 de Agosto."),
            PlaceContact(website = "https://cuenca.com.ec")),

        p("cajas", "Parque Nacional Cajas", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Azuay", "Cuenca",
            -2.8438, -79.2260,
            "Más de 230 lagunas glaciares, bosque de polylepis y senderos entre 3.100 y 4.450 m.",
            "28.544 hectáreas entre los 3.010 y los 4.500 m, modeladas por los glaciares del Pleistoceno: 232 lagunas glaciares, tundra de páramo y bosques de polylepis (el 'árbol de papel', de corteza rojiza descascarada). El centro de interpretación está en la Laguna Toreadora, con rutas señalizadas de dificultad variable: Qhapaq Ñan, Avilahuayco y Taitachungo, de 1 a 8 horas.",
            0.0, 5.0, "08:00–16:30 · Registro obligatorio y gratuito",
            "Está a 33 km al noroccidente de Cuenca (unos 40 min) por la carretera de alta montaña Cuenca–Molleturo–Puerto Inca (E582). El bus Cuenca–Guayaquil de la cooperativa Occidental te deja en La Toreadora ($2, 45 min). En vehículo propio el acceso es directo desde la vía.",
            listOf("Bus Cuenca–Guayaquil (Occidental)", "Vehículo propio por la E582", "Tour desde Cuenca"),
            3900, 8, listOf("Lagunas", "Trekking", "Páramo", "Polylepis"),
            listOf(
                "Regístrate en el centro de visitantes: es gratis y es como te buscan si te pierdes.",
                "La neblina baja en minutos y desorienta: para las zonas sur y oeste contrata guía sí o sí.",
                "Se permite pesca deportiva de trucha regulada.",
                "Clima de páramo: capas, poncho y guantes aunque salgas con sol de Cuenca."
            ),
            sources = listOf(src("https://www.globalnationalparks.com/es/ecuador/cajas/", "Parque Nacional Cajas"))),

        p("papallacta", "Termas de Papallacta", PlaceCategory.SERVICES, EcuadorRegion.ANDES, "Napo", "Papallacta",
            -0.3760, -78.1440,
            "Las mejores aguas termales de Ecuador, entre el páramo y la entrada a la Amazonía.",
            "Complejo con balneario público ($10) y spa. Ideal como parada entre Quito y Tena/Baeza. Cerca: sendero a la laguna de Papallacta.",
            10.0, 25.0, "06:00–21:00",
            "Desde Quito por la E20 (Vía Interoceánica), 1h30. Bus a Tena/Baeza te deja en Papallacta ($3).",
            listOf("Bus Quito–Tena", "Vehículo propio"),
            3300, 10, listOf("Termas", "Relax", "Parada de Ruta"),
            listOf("Entre semana está casi vacío.", "Lleva gorro de baño."),
            PlaceContact(website = "https://www.termaspapallacta.com", phone = "(02) 250 4787")),

        p("vilcabamba", "Vilcabamba (Valle de la Longevidad)", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Loja", "Vilcabamba",
            -4.2610, -79.2220,
            "Valle de clima perfecto, cabalgatas al Podocarpus y el cerro Mandango.",
            "Pueblo tranquilo al sur de Loja famoso por la longevidad de sus habitantes. Sendero al Mandango (3 h) y cabalgatas a la reserva Podocarpus.",
            0.0, 40.0, "Todo el día",
            "Bus/buseta desde Loja cada 15 min ($1.50, 1 h). Loja se conecta con Cuenca (4–5 h).",
            listOf("Buseta desde Loja", "Vehículo propio"),
            1500, 20, listOf("Relax", "Senderismo", "Cabalgatas"),
            listOf("El Mandango se hace temprano, sin sombra.", "Prueba el café lojano de altura.")),

        // ---------------- COSTA ----------------
        p("gye_malecon", "Malecón 2000 y Las Peñas", PlaceCategory.CULTURE, EcuadorRegion.COAST, "Guayas", "Guayaquil",
            -2.1930, -79.8800,
            "Paseo ribereño de 2.5 km y el barrio colonial de Las Peñas con sus 444 escalones al faro.",
            "Malecón con jardines, La Perla (rueda moscovita), museos y muelles. Al norte, el cerro Santa Ana y el barrio pintoresco de Las Peñas.",
            0.0, 5.0, "Malecón 07:00–24:00",
            "Metrovía hasta Las Peñas o Biblioteca Municipal ($0.30). Taxi/Uber desde el aeropuerto 20 min ($5–7).",
            listOf("Metrovía", "Taxi / Uber", "A pie"),
            4, 27, listOf("Malecón", "Barrio Histórico", "Nocturno"),
            listOf("Sube los 444 escalones al atardecer.", "Prueba encebollado en la mañana y bolón de verde.")),

        p("gye_encebollado", "Encebollados de la Bahía (Hueca tradicional)", PlaceCategory.FOOD, EcuadorRegion.COAST, "Guayas", "Guayaquil",
            -2.1965, -79.8845,
            "El desayuno guayaco por excelencia: encebollado de albacora con chifles y limón.",
            "En los alrededores de la Bahía y el centro hay decenas de huecas que abren desde las 07:00. Pregunta por 'el más concurrido'.",
            3.5, 5.0, "07:00–14:00",
            "En el centro de Guayaquil, cerca del Malecón. Metrovía parada Biblioteca Municipal.",
            listOf("Metrovía", "A pie desde el Malecón"),
            4, 27, listOf("Comida Típica", "Hueca", "Must Eat"),
            listOf("Pide 'mixto' (con camarón).", "Acompáñalo con chifles y pan.")),

        p("montanita", "Montañita", PlaceCategory.BEACH, EcuadorRegion.COAST, "Santa Elena", "Montañita",
            -1.8270, -80.7530,
            "La capital del surf y la fiesta de la Ruta del Spondylus.",
            "Playa de surf con ola de punta internacional, hostales, calle de los cocteles y ambiente mochilero. Al lado, Olón, más tranquilo y familiar.",
            0.0, 30.0, "Todo el día",
            "Bus CLP desde Guayaquil (3 h, $6) o desde Santa Elena/Salinas. Vía Ruta del Spondylus (E15).",
            listOf("Bus CLP", "Vehículo propio Ruta del Spondylus"),
            5, 26, listOf("Surf", "Fiesta", "Mochilero", "Playa"),
            listOf("Clases de surf $15–20/hora.", "Para dormir tranquilo elige Olón o Ayampe.")),

        p("los_frailes", "Playa Los Frailes (P.N. Machalilla)", PlaceCategory.BEACH, EcuadorRegion.COAST, "Manabí", "Puerto López",
            -1.4940, -80.7920,
            "Considerada la playa más bella de Ecuador continental: arena blanca, bosque seco y mirador.",
            "Medialuna cerrada de 3 km de arena blanquísima entre acantilados de roca calcárea, dentro del Parque Nacional Machalilla. Está prohibido el comercio ambulante, la música y los restaurantes en la arena: por eso sigue intacta. El sendero interpretativo de madera pasa por bosque seco tropical (palo santo, cactus columnares) y sube al mirador Las Fragatas, a 110 m, con vista a la bahía y a las calas de arena negra La Tortuguita y Playita Prieta. Hay piqueros de patas azules, fragatas y pelícanos.",
            0.0, 3.0, "08:00–16:00 (salida hasta las 17:00) · Registro gratuito con guardaparques",
            "Está en el cantón Jipijapa, 12 km al norte de Puerto López por la E15 (Ruta del Spondylus). Desde Puerto López: mototaxi $5 (15 min) o cualquier bus a Jipijapa/Manta pidiendo bajarse en la entrada del parque. Puedes entrar en vehículo hasta la playa o hacer la caminata de 20–40 min por el bosque seco, que es lo que vale la pena.",
            listOf("Mototaxi desde Puerto López", "Bus por la E15", "Vehículo propio", "Caminata por el bosque seco"),
            5, 27, listOf("Playa", "Parque Nacional", "Naturaleza", "Mirador"),
            listOf(
                "No hay sombra, ni comida, ni agua a la venta: lleva todo desde Puerto López.",
                "Entre 25 y 29 °C todo el año y sin nubes: bloqueador e hidratación en serio.",
                "Combínalo con avistamiento de ballenas jorobadas (junio–septiembre) desde Puerto López."
            )),

        p("puerto_lopez_ballenas", "Puerto López – Ballenas jorobadas e Isla de la Plata", PlaceCategory.ADVENTURE, EcuadorRegion.COAST, "Manabí", "Puerto López",
            -1.5560, -80.8140,
            "De junio a septiembre, ballenas jorobadas; todo el año, la 'Galápagos de los pobres': Isla de la Plata.",
            "Tours diarios salen del malecón a las 09:00. Isla de la Plata: piqueros de patas azules, fragatas y snorkel.",
            25.0, 50.0, "Tours 09:00–17:00",
            "Bus desde Manta (2 h) o Guayaquil vía Jipijapa (4 h). Ruta del Spondylus.",
            listOf("Bus interprovincial", "Vehículo propio"),
            5, 26, listOf("Ballenas", "Isla", "Snorkel", "Temporada"),
            listOf("Reserva el tour la tarde anterior en el malecón.", "Pastilla para el mareo: el mar se mueve."),
            PlaceContact()),

        p("canoa", "Canoa", PlaceCategory.BEACH, EcuadorRegion.COAST, "Manabí", "Canoa",
            0.4640, -80.4580,
            "Playa extensa con acantilados, parapente y ambiente relajado al norte de Manabí.",
            "17 km de playa. Parapente desde los acantilados, surf para principiantes y cuevas al norte. Cerca de San Vicente y Bahía de Caráquez.",
            0.0, 40.0, "Todo el día",
            "Bus desde Pedernales, Bahía de Caráquez o San Vicente ($1, 30 min). Desde Quito: bus a San Vicente (8 h).",
            listOf("Bus", "Vehículo propio"),
            3, 26, listOf("Playa", "Parapente", "Relax"),
            listOf("Parapente en tándem ~$30–40.", "Marea baja para caminar hasta las cuevas.")),

        p("mompiche", "Mompiche", PlaceCategory.BEACH, EcuadorRegion.COAST, "Esmeraldas", "Mompiche",
            0.5080, -80.0250,
            "Bahía tranquila con ola de surf famosa y la Playa Negra con arena volcánica.",
            "Pequeño pueblo pesquero al sur de Esmeraldas. Playa Negra y Portete a 15 min en lancha o caminando. Muy popular entre surfistas.",
            0.0, 35.0, "Todo el día",
            "Desde Esmeraldas o Atacames: bus a Mompiche ($3, 2 h). Desde Quito: bus a Esmeraldas (6 h) o vía Santo Domingo–Pedernales.",
            listOf("Bus", "Vehículo propio"),
            2, 27, listOf("Surf", "Playa Negra", "Tranquilo"),
            listOf("Lleva efectivo: no hay cajeros.", "Encocado de pescado es el plato local.")),

        p("salinas", "Salinas y La Chocolatera", PlaceCategory.BEACH, EcuadorRegion.COAST, "Santa Elena", "Salinas",
            -2.2140, -80.9560,
            "El balneario más desarrollado del país y el punto más occidental del Ecuador continental.",
            "Malecón con hoteles y restaurantes. La Chocolatera (dentro de la base naval, entrada gratis) con lobos marinos y acantilados. Museo de ballenas.",
            0.0, 30.0, "La Chocolatera 08:00–17:00",
            "Bus CLP desde Guayaquil (2h30, $4.50). Vía E40.",
            listOf("Bus CLP", "Vehículo propio"),
            5, 25, listOf("Playa", "Malecón", "Lobos Marinos"),
            listOf("Yates para ver ballenas jul–sep.", "Deja documento en la garita para entrar a La Chocolatera.")),

        p("santo_domingo_bomboli", "Santo Domingo – Jardín Botánico La Carolina y Bomboli", PlaceCategory.NATURE, EcuadorRegion.COAST, "Santo Domingo de los Tsáchilas", "Santo Domingo",
            -0.2530, -79.1720,
            "Parada estratégica entre Quito y la Costa: cultura Tsáchila, botánico y bosque protector.",
            "Ciudad de paso obligado hacia Esmeraldas, Manabí y Guayaquil. Comunas Tsáchila (Chigüilpe), Jardín Botánico La Carolina y Bosque Protector Bomboli con miradores.",
            0.0, 10.0, "Botánico 08:00–17:00",
            "Buses desde Quito (3 h, $4), Guayaquil (5 h) y Esmeraldas (3 h). Terminal Terrestre al norte de la ciudad.",
            listOf("Bus interprovincial", "Vehículo propio (E20 / E25)"),
            550, 24, listOf("Parada de Ruta", "Cultura Tsáchila", "Naturaleza"),
            listOf("Visita la comuna Tsáchila Chigüilpe (30 min).", "Prueba los bolones y el 'encebollado de la 29 de Mayo'.")),

        // ---------------- AMAZONÍA ----------------
        p("tena_misahualli", "Tena y Puerto Misahuallí", PlaceCategory.ADVENTURE, EcuadorRegion.AMAZON, "Napo", "Tena",
            -1.0330, -77.6670,
            "Puerta de la Amazonía: rafting clase III–IV, cavernas, monos en la playa de Misahuallí.",
            "Tena es capital del rafting en Ecuador (ríos Jatunyacu y Misahuallí). Puerto Misahuallí: playa de río con monos capuchinos, canoas a comunidades Kichwa.",
            10.0, 80.0, "Todo el día · Tours 08:00",
            "Bus desde Quito (Quitumbe) 5 h, $8. Desde Baños vía Puyo 3 h. Misahuallí está a 30 min de Tena en bus ($1).",
            listOf("Bus interprovincial", "Bus Tena–Misahuallí", "Vehículo propio E45"),
            510, 25, listOf("Rafting", "Selva", "Comunidades", "Río Napo"),
            listOf("Rafting día completo $60–80.", "Guayusa al amanecer y maito de tilapia.")),

        p("puyo", "Puyo – Paseo Turístico del Río Puyo", PlaceCategory.NATURE, EcuadorRegion.AMAZON, "Pastaza", "Puyo",
            -1.4920, -77.9990,
            "Ciudad amazónica con parque etnobotánico Omaere, malecón y acceso a comunidades Shuar y Kichwa.",
            "Parada natural después de Baños (1 h). Parque Omaere, Zoo Refugio Tarqui, Paseo Turístico junto al río y cascadas cercanas (Hola Vida).",
            0.0, 15.0, "Parques 08:00–17:00",
            "Bus desde Baños cada 30 min ($2, 1h30) o desde Quito (5 h). Vía E30.",
            listOf("Bus Baños–Puyo", "Vehículo propio"),
            950, 24, listOf("Selva", "Etnobotánica", "Parada de Ruta"),
            listOf("Cascada Hola Vida a 30 min: $2.", "Prueba el pincho de chontacuro si te atreves.")),

        p("yasuni", "Reserva de Biosfera Yasuní", PlaceCategory.NATURE, EcuadorRegion.AMAZON, "Orellana", "Coca (Francisco de Orellana)",
            -0.9000, -75.9000,
            "El lugar más biodiverso del planeta: lodges en la selva primaria, delfines rosados y comunidades Waorani.",
            "Se visita desde El Coca en lodges (3–5 días). Lagunas, torres de observación, saladeros de loros y canoas nocturnas.",
            250.0, 900.0, "Programas de 3 a 5 días",
            "Vuelo Quito–Coca (40 min) o bus (8–10 h). Los lodges recogen en el muelle del Coca (2–3 h en canoa motorizada).",
            listOf("Vuelo + canoa", "Bus + canoa"),
            250, 27, listOf("Biodiversidad", "Lodge", "Selva Primaria"),
            listOf("Reserva con antelación; los lodges son limitados.", "Botas de caucho y repelente son imprescindibles.")),

        // ---------------- GALÁPAGOS ----------------
        p("gps_tortuga_bay", "Tortuga Bay (Santa Cruz)", PlaceCategory.BEACH, EcuadorRegion.GALAPAGOS, "Galápagos", "Puerto Ayora",
            -0.7650, -90.3360,
            "Playa de arena blanca con iguanas marinas, tiburones de arrecife y una laguna de manglar para nadar.",
            "Caminata de 2.5 km por sendero adoquinado desde Puerto Ayora. Playa Brava (surf, no nadar) y Playa Mansa (laguna tranquila, kayak).",
            0.0, 0.0, "06:00–18:30 (registro obligatorio)",
            "A pie desde Puerto Ayora (45 min) o taxi acuático a Playa Mansa ($10).",
            listOf("A pie", "Taxi acuático"),
            5, 26, listOf("Playa", "Fauna Única", "Snorkel", "Gratis"),
            listOf("Entrada gratuita, sin sombra: agua y protector biodegradable.", "Mantén 2 m de distancia con la fauna.")),

        p("gps_darwin", "Estación Científica Charles Darwin", PlaceCategory.CULTURE, EcuadorRegion.GALAPAGOS, "Galápagos", "Puerto Ayora",
            -0.7420, -90.3030,
            "Centro de crianza de tortugas gigantes y museo del legado del Solitario George.",
            "Camina desde Puerto Ayora (20 min). Tortugas de todas las edades, iguanas terrestres y exhibición del Solitario George. Continúa a la Playa de la Estación.",
            0.0, 10.0, "08:00–12:30 y 14:30–17:30",
            "A pie o taxi ($2) desde el centro de Puerto Ayora.",
            listOf("A pie", "Taxi"),
            5, 26, listOf("Tortugas", "Ciencia", "Familiar"),
            listOf("Ve temprano cuando alimentan a las tortugas.", "Playa de la Estación al final: gratis y con iguanas.")),

        p("gps_tuneles", "Los Túneles (Isabela)", PlaceCategory.ADVENTURE, EcuadorRegion.GALAPAGOS, "Galápagos", "Puerto Villamil",
            -0.9800, -91.1000,
            "Snorkel entre arcos de lava con tortugas marinas, tiburones punta blanca, caballitos de mar y pingüinos.",
            "Tour de medio día desde Puerto Villamil (isla Isabela). Considerado el mejor snorkel de Galápagos.",
            120.0, 150.0, "Tours 08:00 y 12:30",
            "Lancha Santa Cruz–Isabela (2h30, $30–35) y tour desde Puerto Villamil.",
            listOf("Lancha interislas + tour"),
            2, 25, listOf("Snorkel", "Tortugas", "Pingüinos"),
            listOf("Reserva un día antes en el pueblo.", "Cámara acuática recomendada.")),

        // ================= AÑADIDOS DESDE LA INVESTIGACIÓN (datos.md + verificación) =================

        // ---------------- SIERRA ----------------
        p("banos_runtun", "Miradores de Runtún: Manos de Dios y Nido del Cóndor", PlaceCategory.VIEWPOINT, EcuadorRegion.ANDES, "Tungurahua", "Baños de Agua Santa",
            -1.4108, -78.4161,
            "Columpios, manos gigantes y nidos de madera colgados sobre el valle, con el Tungurahua de fondo.",
            "En las lomas de Runtún, sobre Baños, se concentran una decena de miradores privados que compiten por la foto más arriesgada: las Manos de Dios, el Nido del Cóndor, columpios al vacío y pasarelas de cristal. Todos cobran entrada aparte y todos tienen arnés obligatorio.",
            2.0, 8.0, "08:00–18:00 (algunos abren para el amanecer)",
            "Desde el centro de Baños son unos 20 minutos subiendo a Runtún por vía asfaltada y tramos de lastre. Taxi $8–12 ida, tour en cuadrón (ATV) $25–40, o bus comunitario desde el mercado. La chiva turística los recorre en circuito.",
            listOf("Taxi desde Baños", "Cuadrón / ATV", "Chiva turística", "Bus comunitario"),
            2360, 16, listOf("Columpio", "Fotografía", "Volcán", "Viral"),
            listOf(
                "Cada mirador cobra su propia entrada: lleva efectivo en billetes pequeños.",
                "El Tungurahua se despeja normalmente entre 06:00 y 09:00; después se nubla.",
                "Hay fotógrafos residentes que conocen el ángulo exacto: cuestan $2–5 y valen la pena."
            ),
            sources = listOf(src("https://www.youtube.com/watch?v=8WbsL2JRpz4", "Miradores alternativos de Baños"))),

        p("tulcan_cementerio", "Cementerio de Tulcán (José María Azael Franco)", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Carchi", "Tulcán",
            0.8197, -77.7236,
            "El cementerio esculpido: 8 hectáreas de cipreses tallados en figuras precolombinas. Patrimonio del Ecuador.",
            "Declarado Patrimonio Cultural. Sobre 8 hectáreas y 12.400 nichos, el arte topiario convierte los cipreses en figuras de varios metros inspiradas en las culturas pasto, tuza y huaca, además de motivos grecorromanos y geometrías fractales. Los arcos y laberintos vegetales forman pasillos irreales, ideales para fotografía de simetría.",
            0.0, 0.0, "Todos los días 08:00–18:00 · Entrada gratuita",
            "Tulcán está a 240 km al norte de Quito, junto al puente fronterizo de Rumichaca. Bus desde la Terminal de Carcelén (Quito) unas 5 horas, $7–9. Desde el centro de Tulcán se llega caminando desde el Parque Ayora, en la avenida del Centenario y Transversal Cotopaxi.",
            listOf("Bus interprovincial Quito–Tulcán", "A pie desde el centro", "Taxi urbano ($1,50)"),
            2950, 12, listOf("Patrimonio", "Arte topiario", "Fotografía", "Gratis"),
            listOf(
                "Entra por la mañana: la luz lateral marca mejor el relieve de las figuras.",
                "En Semana Santa y Día de los Difuntos hay recorridos nocturnos iluminados.",
                "Tulcán está a 2.950 m y hace frío de verdad: lleva abrigo."
            ),
            sources = listOf(src("https://www.ecuador-turistico.com/2026/03/turismo-semana-santa-en-tulcan-carchi.html", "Turismo cultural en Tulcán"))),

        p("peguche", "Cascada de Peguche", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Imbabura", "Otavalo",
            0.2264, -78.2400,
            "Caída de 18 m dentro de un bosque protector, sitio ceremonial del Inti Raymi a 10 minutos de Otavalo.",
            "Bosque protector comunitario con senderos, puente colgante y la cascada donde cada junio se hacen los baños rituales del Inti Raymi. En la entrada hay talleres de instrumentos andinos y tejedores de la comunidad de Peguche.",
            0.0, 2.0, "07:00–18:00 · Aporte voluntario / $1 al centro comunitario",
            "Desde Otavalo son 3 km: taxi $2–3, camioneta desde la Plaza de Ponchos, o caminata de 40 minutos por la vía a Peguche. Desde Quito, bus a Otavalo (2 h, $2,50–3) y luego taxi.",
            listOf("Taxi desde Otavalo", "Caminata desde la Plaza de Ponchos", "Bus Quito–Otavalo"),
            2600, 14, listOf("Cascada", "Inti Raymi", "Cultura Kichwa", "Sendero"),
            listOf(
                "Combínala con el Mercado de Otavalo y la Laguna de San Pablo en el mismo día.",
                "El sendero es corto y plano: apto para ir con niños.",
                "En junio (Inti Raymi) es sitio ceremonial: respeta los baños rituales y no fotografíes sin permiso."
            )),

        p("san_pablo", "Laguna de San Pablo (Imbakucha)", PlaceCategory.NATURE, EcuadorRegion.ANDES, "Imbabura", "Otavalo",
            0.2044, -78.2172,
            "La laguna a los pies del volcán Imbabura: totoras, deportes acuáticos y el mejor amanecer de Otavalo.",
            "Con el Imbabura reflejado en el agua, es el corazón del paisaje otavaleño. Alrededor hay muelles comunitarios, kayaks, paseos en lancha, hosterías y el mirador del Lechero. Cada septiembre se corre el Yamor y se navega en balsas de totora.",
            0.0, 25.0, "Acceso libre todo el día · Actividades 08:00–17:00",
            "A 4 km de Otavalo por la vía a Araque/San Rafael. Taxi $3–4, o buses locales desde el terminal de Otavalo hacia San Pablo del Lago. Desde Quito, 2 h por la Panamericana Norte.",
            listOf("Taxi desde Otavalo", "Bus local a San Pablo del Lago", "Vehículo propio"),
            2660, 14, listOf("Laguna", "Kayak", "Amanecer", "Imbabura"),
            listOf(
                "El reflejo del Imbabura sale entre 06:00 y 07:30, antes de que se levante el viento.",
                "El mirador del Lechero, sobre la laguna, es el mejor encuadre y es gratis.",
                "Prueba las tortillas de papa con fritada en San Rafael."
            )),

        p("quitsato", "Reloj Solar Quitsato (Latitud 0 real)", PlaceCategory.CULTURE, EcuadorRegion.ANDES, "Pichincha", "Cayambe",
            0.0037, -78.1994,
            "El monumento sobre la línea ecuatorial verdadera, medida por GPS, en la Panamericana rumbo a Cayambe.",
            "Un disco de 54 m de diámetro con un gnomon de 10 m marca la latitud 0 real (a diferencia del monumento de San Antonio, trazado en el siglo XVIII). El guía explica cómo las culturas preincaicas usaban el paso cenital del sol para su calendario agrícola.",
            2.0, 5.0, "09:00–17:00 todos los días",
            "En la Panamericana Norte, sector Guachalá, a 1 h 15 de Quito rumbo a Cayambe. Cualquier bus Quito–Cayambe (desde La Ofelia, $1,50–2) te deja en la puerta; pide al ayudante que te avise en 'la mitad del mundo de Cayambe'.",
            listOf("Bus Quito–Cayambe", "Vehículo propio (Panamericana Norte)", "Taxi desde Cayambe ($5)"),
            2800, 14, listOf("Latitud 0", "Arqueoastronomía", "Parada de ruta"),
            listOf(
                "En los equinoccios (21 de marzo y 23 de septiembre) el gnomon no proyecta sombra al mediodía.",
                "Está de camino a Otavalo: es una parada de 40 minutos que vale la pena.",
                "Aprovecha y compra bizcochos de Cayambe con queso de hoja a 10 minutos."
            )),

        p("zaruma", "Zaruma (ciudad de oro y madera)", PlaceCategory.CULTURE, EcuadorRegion.COAST, "El Oro", "Zaruma",
            -3.6931, -79.6122,
            "Pueblo minero de casas republicanas de madera policromada, colgado en la montaña a 1.200 m.",
            "Construida literalmente sobre galerías de minas de oro precolombinas y coloniales. Su casco histórico de calles inclinadas y casas de madera fina pintada es candidato a Patrimonio de la Humanidad. Se visitan minas turísticas, el museo municipal y las fincas de café de altura.",
            0.0, 20.0, "Ciudad abierta · Museos 09:00–17:00 · Minas turísticas con guía",
            "Desde Machala son 90 km (2 h) por vía asfaltada vía Piñas; buses cada hora, $3–4. Desde Guayaquil, 5–6 h en bus directo ($9–11) o vía Machala. Desde Loja, 4 h por Portovelo.",
            listOf("Bus Machala–Zaruma", "Bus directo desde Guayaquil", "Vehículo propio vía Piñas"),
            1200, 20, listOf("Patrimonio", "Madera", "Café", "Minería"),
            listOf(
                "Prueba el tigrillo (plátano verde majado con queso y huevo) en el desayuno: es de aquí.",
                "Las calles son empinadísimas: calzado con suela agarrada.",
                "El café zarumeño de altura es de los mejores del país; cómpralo en las fincas."
            )),

        // ---------------- COSTA ----------------
        p("gye_santa_ana", "Cerro Santa Ana y Escalinata Diego Noboa", PlaceCategory.VIEWPOINT, EcuadorRegion.COAST, "Guayas", "Guayaquil",
            -2.1875, -79.8756,
            "444 escalones de colores entre bares y galerías hasta el faro con vista total al río Guayas.",
            "El cerro más fotografiado de Guayaquil: casas de madera pintadas en colores saturados a lo largo del zigzag de la escalinata, con bares, galerías, restaurantes de cangrejo y plazas de descanso. Arriba, el faro, la capilla y el Museo Naval Abierto miran al río Guayas y a Las Peñas.",
            0.0, 15.0, "Escalinata abierta 24 h · Locales 10:00–24:00 · Faro 09:00–18:00",
            "Se sube a pie desde el extremo norte del Malecón 2000, junto al barrio Las Peñas. Metrovía o taxi hasta el Malecón; desde ahí el ascenso son unos 15–20 minutos con paradas. Hay policía turística permanente en toda la escalinata.",
            listOf("A pie desde el Malecón 2000", "Metrovía (parada Las Peñas)", "Taxi / Uber"),
            60, 27, listOf("Mirador", "Vida nocturna", "Fotografía", "Gratis"),
            listOf(
                "Sube al atardecer: la luz sobre el río y las luces del puente Unidad Nacional valen la caminata.",
                "Los escalones están numerados del 1 al 444: es la foto clásica.",
                "Hidrátate; en Guayaquil hace 30 °C con humedad alta casi todo el año."
            )),

        p("ayampe", "Ayampe", PlaceCategory.BEACH, EcuadorRegion.COAST, "Manabí", "Puerto López",
            -1.6725, -80.7847,
            "Pueblo de surf y yoga donde el bosque húmedo baja hasta la rompiente. El refugio tranquilo de la Ruta del Spondylus.",
            "La cordillera Chongón-Colonche choca contra el mar y crea un microclima de selva a pie de playa. Concentra hostales boutique de bambú, escuelas de surf, estudios de yoga y restaurantes orgánicos. Punto de partida para el bosque nublado de Las Tunas y para avistar ballenas entre junio y septiembre.",
            0.0, 40.0, "Playa abierta · Clases de surf 07:00–17:00",
            "Está en la Ruta del Spondylus (E15), 15 km al sur de Puerto López. Desde Guayaquil, bus a Puerto López (3 h 30, $8) y luego camioneta o bus local 20 min. Desde Manta, 2 h 30 por la costa. Los buses interprovinciales te dejan en el desvío de la vía principal.",
            listOf("Bus a Puerto López + camioneta", "Ruta del Spondylus en vehículo propio", "Bus interprovincial (desvío)"),
            5, 26, listOf("Surf", "Yoga", "Tranquilo", "Ballenas"),
            listOf(
                "El oleaje es constante todo el año: es de los mejores sitios para aprender a surfear.",
                "Casi no hay cajeros: lleva efectivo desde Puerto López o Manta.",
                "Entre junio y septiembre salen tours de ballenas desde Puerto López (15 min al norte)."
            )),

        // ---------------- AMAZONÍA ----------------
        p("indichuris", "Mirador Indichuris", PlaceCategory.VIEWPOINT, EcuadorRegion.AMAZON, "Pastaza", "Puyo",
            -1.6523, -77.9171,
            "Columpio sobre el cañón donde se juntan los ríos Puyo y Pastaza, con la selva hasta el horizonte.",
            "Mirador comunitario en la parroquia Pomona, sobre un filo que domina la unión de los ríos Puyo y Pastaza. Tiene columpios tallados en troncos con arnés, hamacas, casas típicas de la nacionalidad kichwa y venta de artesanía en semillas. La comunidad ofrece guianza y degustación de chicha de yuca.",
            3.0, 5.0, "Todos los días 08:00–17:00 · Entrada $3",
            "Desde Puyo se toma la vía a Macas y en el kilómetro 15 el desvío hacia Pomona; en total unos 25 km y 30 minutos. Se llega en carro, taxi ($20–25 ida y vuelta con espera) o bus a Pomona. Al llegar se pagan las entradas y se sube por escalinatas de tierra unos 10 minutos.",
            listOf("Taxi desde Puyo", "Bus Puyo–Pomona", "Vehículo propio (vía a Macas km 15)"),
            920, 24, listOf("Mirador", "Columpio", "Comunitario", "Selva"),
            listOf(
                "Ve temprano: después del mediodía la neblina tapa el cañón.",
                "Zapato cerrado y repelente; el suelo resbala si llovió.",
                "Coordenadas verificadas en OpenStreetMap (1°39′08″S, 77°55′02″O)."
            ),
            sources = listOf(
                src("https://guiapuyo.com/mirador-indichuris/", "Guía Puyo: Mirador Indichuris"),
                src("https://www.youtube.com/watch?v=ToT6w2FQijM", "Indichuris en video"))),

        p("laguna_azul_tena", "Laguna Azul (Waysa Yaku)", PlaceCategory.NATURE, EcuadorRegion.AMAZON, "Napo", "Tena",
            -1.0089, -77.8942,
            "Piscinas naturales de agua turquesa junto al río Jatunyacu, manejadas por la comunidad kichwa Alukus.",
            "Conocida en kichwa como Amorongachi, es una sucesión de pozas de agua clarísima formadas por un afluente del río Jatunyacu. Hay una laguna principal con chozas alrededor y lagunas altas a las que se llega por un puente. La comunidad Alukus ofrece además caminatas guiadas y charlas de plantas medicinales.",
            2.0, 3.0, "08:00–17:00 · $2 nacionales, $3 extranjeros",
            "A unos 30 minutos de Tena pasando las poblaciones de Pano y Talag. Se llega en carro propio o taxi ($20–25 ida y vuelta); hay buses a Pano y Talag pero desde ahí falta un tramo. Las agencias de Tena la incluyen en tours de un día con tubing en el Jatunyacu.",
            listOf("Taxi desde Tena", "Vehículo propio vía Pano–Talag", "Tour desde Tena"),
            550, 25, listOf("Balneario", "Turismo comunitario", "Agua turquesa"),
            listOf(
                "Lleva efectivo: no hay cajeros ni datáfono.",
                "Hay vestidores de caña guadua y baños, pero no comida: compra en Tena.",
                "La ubicación es aproximada (referencia Talag): pregunta en el pueblo por Waysa Yaku."
            ),
            sources = listOf(src("https://www.goraymi.com/es-ec/napo/tena/lagunas/laguna-azul-tena-a2wjytd26", "Goraymi: Laguna Azul Tena"))),

        p("jumandy", "Cavernas de Jumandy", PlaceCategory.ADVENTURE, EcuadorRegion.AMAZON, "Napo", "Archidona",
            -0.8753, -77.7927,
            "Galerías kársticas con estalactitas y ríos subterráneos que se recorren con casco, linterna y agua a la cintura.",
            "Las cuevas más visitadas de la Amazonía ecuatoriana, refugio histórico del cacique Jumandy. El recorrido entra por galerías con estalactitas y estalagmitas, cruza cursos de agua subterráneos y termina en una cascada interior. En el exterior hay un complejo turístico con piscinas.",
            5.0, 15.0, "09:00–17:00 · Guía obligatorio",
            "Está a 3 km de Archidona por la vía a Cotundo, unos 25 minutos desde Tena. Bus Tena–Archidona cada 15 minutos ($0,50) y luego camioneta ($2–3), o taxi directo desde Tena ($12–15).",
            listOf("Bus Tena–Archidona + camioneta", "Taxi desde Tena", "Vehículo propio vía Cotundo"),
            600, 24, listOf("Espeleología", "Cuevas", "Aventura", "Familiar"),
            listOf(
                "Vas a salir empapado y con lodo: ropa que puedas mojar y una muda de recambio.",
                "El guía y el casco con linterna son obligatorios; están incluidos en la entrada.",
                "Coordenadas verificadas en OpenStreetMap; el informe original tenía un error de 45 km."
            ),
            sources = listOf(src("https://mapcarta.com/N941918425", "Cavernas de Jumandy en OpenStreetMap"))),

        p("tayos", "Cueva de los Tayos", PlaceCategory.ADVENTURE, EcuadorRegion.AMAZON, "Morona Santiago", "Limón Indanza",
            -1.9333, -77.7833,
            "La cueva del misterio: descenso vertical de 63 m por cuerdas a un sistema de galerías con cortes geométricos.",
            "Sistema espeleológico en territorio shuar, célebre por la expedición de 1976 en la que participó Neil Armstrong y por las teorías sobre sus pasillos de cortes casi megalíticos. Alberga colonias de tayos (Steatornis caripensis), aves nocturnas que se orientan por eco. No es una visita turística normal: es una expedición.",
            150.0, 400.0, "Solo con expedición organizada · Varios días",
            "No hay acceso libre. Se organiza desde Macas o Limón Indanza con operadores que negocian el paso con la comunidad shuar; incluye traslado en 4x4, caminata de varias horas por selva y un descenso vertical de unos 63 m con cuerdas o grúa artesanal. Mínimo 2–3 días.",
            listOf("Expedición guiada desde Macas", "Expedición desde Limón Indanza", "4x4 + caminata + rápel"),
            800, 24, listOf("Espeleología", "Expedición", "Misterio", "Shuar"),
            listOf(
                "Exige condición física real y no tener vértigo: el descenso es a plomo.",
                "Se necesita permiso de la comunidad shuar; nunca vayas por tu cuenta.",
                "Ubicación aproximada: el acceso exacto lo fija el operador."
            )),

        // ---------------- GALÁPAGOS ----------------
        p("gps_las_grietas", "Las Grietas (Santa Cruz)", PlaceCategory.NATURE, EcuadorRegion.GALAPAGOS, "Galápagos", "Puerto Ayora",
            -0.7522, -90.3175,
            "Grieta de lava inundada de agua turquesa entre paredes verticales de roca volcánica. Snorkel y clavados.",
            "Una fractura volcánica seccionada por túneles de lava que aloja tres pozas escalonadas de agua salobre. El sol entra entre las paredes de piedra ígnea y enciende el agua en turquesa. Se llega cruzando un bosque árido de cactus candelabro y palo santo. Hay plataformas de madera para entrar al agua.",
            0.0, 10.0, "08:00–17:00 · El sendero es gratuito",
            "Desde el muelle municipal de Puerto Ayora se toma un taxi acuático (panga) de $0,60–1 hasta el sector de Playa de los Alemanes / Hotel Angermeyer. Desde ahí son 15–30 minutos de caminata por un sendero de piedra volcánica y pasarelas.",
            listOf("Taxi acuático desde el muelle ($0,60)", "Caminata 15–30 min", "Tour de bahía"),
            5, 26, listOf("Snorkel", "Agua turquesa", "Sendero", "Galápagos"),
            listOf(
                "Ve temprano o a media tarde: al mediodía se llena y el sol pega durísimo en el sendero.",
                "El último taxi acuático de regreso sale alrededor de las 18:00: no te quedes al otro lado.",
                "Combínalo con la Playa de los Alemanes, que está en el mismo camino."
            ),
            sources = listOf(src("https://ecuadorgalapagosinfo.com/turismo-en-galapagos/lugares-turisticos/las-grietas/", "Cómo llegar a Las Grietas"))),

        p("gps_tintoreras", "Las Tintoreras (Isabela)", PlaceCategory.NATURE, EcuadorRegion.GALAPAGOS, "Galápagos", "Puerto Villamil",
            -0.9636, -90.9694,
            "Canales de lava donde duermen amontonados los tiburones de punta blanca, a 10 minutos en bote de Puerto Villamil.",
            "Islote frente a Puerto Villamil con un canal estrecho de agua cristalina donde reposan tiburones tintorera (punta blanca). El sendero de lava atraviesa colonias de iguanas marinas, lobos y pingüinos de Galápagos. El tour suele incluir snorkel y la visita al Centro de Crianza Arnaldo Tupiza.",
            35.0, 60.0, "Tours 08:00 y 14:00 · Guía naturalista obligatorio",
            "Se sale del muelle de Puerto Villamil en bote, 10–15 minutos. Solo se entra con guía naturalista certificado: se contrata en las agencias del pueblo el día anterior. A Isabela se llega en lancha desde Puerto Ayora (2 h 30, $30–35) o en avioneta.",
            listOf("Lancha Santa Cruz–Isabela", "Bote desde el muelle de Puerto Villamil", "Tour con guía naturalista"),
            2, 25, listOf("Tiburones", "Snorkel", "Fauna", "Galápagos"),
            listOf(
                "Reserva un día antes: los cupos diarios son limitados por el Parque Nacional.",
                "Mar picado por la tarde: si te mareas, ve en el turno de la mañana.",
                "Lleva protector solar biodegradable; el común está prohibido."
            )),

        p("gps_genovesa", "Isla Genovesa (Bahía Darwin)", PlaceCategory.NATURE, EcuadorRegion.GALAPAGOS, "Galápagos", "Isla Genovesa",
            0.3196, -89.9583,
            "La Isla de los Pájaros: la única donde anidan los piqueros de patas rojas sobre arbustos. Solo por crucero.",
            "Caldera volcánica marina de 14 km² cuyo borde colapsó formando una bahía en herradura. Alberga millones de aves marinas: piqueros de patas rojas (únicos anidando en arbustos), piqueros enmascarados, fragatas y golondrinas de Galápagos. Sus acantilados de basalto de El Barranco caen 25 m sobre el oleaje.",
            1200.0, 4500.0, "Solo con crucero autorizado · Cupos rotativos del Parque Nacional",
            "No tiene acceso independiente ni infraestructura: está a más de 8 horas de navegación al norte de Santa Cruz. Únicamente se llega integrándose a un crucero de expedición de 4 a 8 días; el desembarco es en panga zodiac (seco o mojado) según la marea.",
            listOf("Crucero de expedición (4–8 días)", "Desembarco en zodiac"),
            0, 25, listOf("Aves", "Crucero", "Snorkel", "Remoto"),
            listOf(
                "Es de los sitios más caros del país: presupuéstalo con meses de anticipación.",
                "El snorkel es en agua turbia por el plancton, pero es donde se ven tiburones martillo.",
                "El sendero de Bahía Darwin son solo 750 m, pero se camina sobre roca y coral fósil."
            ))

    )

    val offlinePackages: List<OfflinePackageEntity> = listOf(
        OfflinePackageEntity("quito_pichincha", "Quito, Mitad del Mundo y Mindo", "Pichincha", 85, false, 0f, "Pendiente de descarga", north = 0.10, south = -0.45, east = -78.25, west = -78.90),
        OfflinePackageEntity("imbabura_otavalo", "Otavalo, Cuicocha e Ibarra", "Imbabura", 40, false, 0f, "Pendiente de descarga", north = 0.45, south = 0.15, east = -78.05, west = -78.45),
        OfflinePackageEntity("cotopaxi_quilotoa", "Cotopaxi, Quilotoa y Latacunga", "Cotopaxi", 45, false, 0f, "Pendiente de descarga", north = -0.55, south = -1.05, east = -78.30, west = -79.00),
        OfflinePackageEntity("banos_tungurahua", "Baños, Ruta de las Cascadas y Puyo", "Tungurahua / Pastaza", 48, false, 0f, "Pendiente de descarga", north = -1.30, south = -1.55, east = -77.95, west = -78.50),
        OfflinePackageEntity("chimborazo_alausi", "Riobamba, Chimborazo y Alausí", "Chimborazo", 45, false, 0f, "Pendiente de descarga", north = -1.40, south = -2.25, east = -78.55, west = -78.95),
        OfflinePackageEntity("cuenca_azuay", "Cuenca, Cajas e Ingapirca", "Azuay / Cañar", 65, false, 0f, "Pendiente de descarga", north = -2.50, south = -3.05, east = -78.80, west = -79.35),
        OfflinePackageEntity("loja_vilcabamba", "Loja y Vilcabamba", "Loja", 35, false, 0f, "Pendiente de descarga", north = -3.90, south = -4.35, east = -79.10, west = -79.35),
        OfflinePackageEntity("costa_guayas", "Guayaquil, Salinas y Montañita", "Guayas / Santa Elena", 70, false, 0f, "Pendiente de descarga", north = -1.75, south = -2.35, east = -79.80, west = -81.05),
        OfflinePackageEntity("costa_manabi", "Puerto López, Los Frailes, Manta y Canoa", "Manabí", 70, false, 0f, "Pendiente de descarga", north = 0.55, south = -1.65, east = -80.35, west = -80.95),
        OfflinePackageEntity("esmeraldas", "Esmeraldas, Atacames y Mompiche", "Esmeraldas", 45, false, 0f, "Pendiente de descarga", north = 1.00, south = 0.45, east = -79.60, west = -80.10),
        OfflinePackageEntity("santo_domingo", "Santo Domingo de los Tsáchilas", "Santo Domingo de los Tsáchilas", 30, false, 0f, "Pendiente de descarga", north = -0.10, south = -0.45, east = -79.00, west = -79.40),
        OfflinePackageEntity("amazonia_napo", "Tena, Misahuallí y Papallacta", "Napo", 55, false, 0f, "Pendiente de descarga", north = -0.30, south = -1.15, east = -77.55, west = -78.20),
        OfflinePackageEntity("galapagos_islands", "Galápagos (Santa Cruz, San Cristóbal e Isabela)", "Galápagos", 60, false, 0f, "Pendiente de descarga", north = -0.55, south = -1.05, east = -89.55, west = -91.20),
        OfflinePackageEntity("carchi_tulcan", "Tulcán, El Ángel y la frontera norte", "Carchi", 28, false, 0f, "Pendiente de descarga", north = 0.95, south = 0.55, east = -77.55, west = -78.10),
        OfflinePackageEntity("el_oro_zaruma", "Machala, Zaruma y Piñas", "El Oro", 34, false, 0f, "Pendiente de descarga", north = -3.20, south = -3.85, east = -79.45, west = -80.05),
        OfflinePackageEntity("pastaza_puyo", "Puyo, Pomona e Indichuris", "Pastaza", 32, false, 0f, "Pendiente de descarga", north = -1.35, south = -1.80, east = -77.75, west = -78.15),
        OfflinePackageEntity("morona_macas", "Macas, Sucúa y Limón Indanza", "Morona Santiago", 30, false, 0f, "Pendiente de descarga", north = -1.75, south = -2.60, east = -77.55, west = -78.35)
    )

    val packingItems: List<PackingChecklistEntity> = listOf(
        PackingChecklistEntity(name = "Pasaporte / Cédula y seguro de viaje", category = "Documentos", isChecked = false),
        PackingChecklistEntity(name = "Dólares en efectivo (billetes de $1, $5, $10 y $20 y monedas)", category = "Documentos", isChecked = false),
        PackingChecklistEntity(name = "Mapas offline descargados en la app", category = "Documentos", isChecked = false),
        PackingChecklistEntity(name = "Chaqueta impermeable / cortavientos para frío andino", category = "Ropa Sierra", isChecked = false),
        PackingChecklistEntity(name = "Zapatos de trekking resistentes al lodo", category = "Ropa Sierra", isChecked = false),
        PackingChecklistEntity(name = "Traje de baño, sandalias y gorro de baño (termas)", category = "Ropa Costa/Galápagos", isChecked = false),
        PackingChecklistEntity(name = "Protector solar FPS 50+ biodegradable (latitud 0)", category = "Salud/Botiquín", isChecked = false),
        PackingChecklistEntity(name = "Pastillas para el soroche / mal de altura (consulta a tu médico)", category = "Salud/Botiquín", isChecked = false),
        PackingChecklistEntity(name = "Repelente de mosquitos (Amazonía / Costa)", category = "Salud/Botiquín", isChecked = false),
        PackingChecklistEntity(name = "Bolsa seca para celular y cámara", category = "Aventura", isChecked = false),
        PackingChecklistEntity(name = "Batería externa (power bank)", category = "Aventura", isChecked = false),
        PackingChecklistEntity(name = "Adaptador de enchufe tipo A/B (110 V)", category = "Documentos", isChecked = false)
    )
}

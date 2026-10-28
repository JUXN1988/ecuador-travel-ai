package ec.travesias.app.data.ai

import ec.travesias.app.data.model.EcuadorRegion
import ec.travesias.app.data.model.ItineraryDay
import ec.travesias.app.data.model.ItineraryPlan
import ec.travesias.app.data.model.ActivityItem
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.RouteStop
import ec.travesias.app.data.model.TravelRoute
import ec.travesias.app.data.local.AppLanguage
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.remote.GeminiClient
import ec.travesias.app.data.remote.JsonExtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Servicio de Inteligencia Artificial Gemini para Travesías en Ecuador
 * Admite generación en línea mediante Google Gemini API y fallback offline inteligente de alta precisión turística.
 */
class GeminiTravelService(
    private val apiKey: String = "",
    private val client: GeminiClient = GeminiClient(apiKeyProvider = { apiKey })
) {
    // Misma clave y el mismo GeminiClient que usan el Radar IA y el agente de rutas
    // (ver AppContainer.gemini): si una funciona, funcionan todas; si falla, fallan todas igual.
    private val online: Boolean get() = client.isAvailable

    /**
     * Genera un itinerario inteligente según los parámetros del usuario
     */
    suspend fun generateItinerary(
        region: EcuadorRegion,
        days: Int,
        budgetStyle: String,
        travelers: Int,
        customPrompt: String
    ): ItineraryPlan = withContext(Dispatchers.IO) {
        if (online) {
            try {
                val prompt = """
                    Actúa como un experto guía turístico de Ecuador. Genera un itinerario para la región ${region.displayName}.
                    Detalles: $days días, estilo $budgetStyle, $travelers persona(s).
                    Petición del viajero: ${customPrompt.ifBlank { "sin preferencias adicionales" }}.
                    Responde ÚNICAMENTE con JSON válido con esta estructura:
                    {
                      "title": "string", "summary": "string", "total_budget_usd": number,
                      "days": [ { "day": 1, "title": "string", "region": "string", "temperature_c": number, "weather": "string",
                                  "activities": [ { "time": "08:30", "title": "string", "description": "string", "location": "string",
                                                    "cost_usd": number, "weather_alert": false, "alternative_plan": "string|null" } ] } ]
                    }
                """.trimIndent()

                val responseText = client.generate(prompt, temperature = 0.5, jsonOnly = true).text
                if (responseText.isNotBlank()) {
                    return@withContext parseOrFallbackItinerary(region, days, budgetStyle, travelers, customPrompt, responseText)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Fallback offline inteligente con curaduría especializada
        return@withContext generateOfflineItinerary(region, days, budgetStyle, travelers, customPrompt)
    }

    /**
     * Responde preguntas del chat conversacional sobre Ecuador
     */
    suspend fun askAssistant(userMessage: String, contextRegion: String = "Ecuador"): String = withContext(Dispatchers.IO) {
        if (online) {
            try {
                val idioma = if (AppSettings.language.value == AppLanguage.EN)
                    "\nThe traveler set the app to English: reply ONLY in English, from greeting to sign-off."
                else
                    "\nResponde en español."
                val system = """
                    Eres el "Guía IA" de la app Travesías en Ecuador, un asistente para viajeros que recorren Ecuador.
                    Responde de forma concisa, amable y práctica (máximo ~180 palabras salvo que pidan detalle).
                    Usa la búsqueda web cuando la pregunta dependa de datos actuales (precios, horarios, estado de vías, clima, eventos).
                    Incluye consejos sobre altitud (soroche), transporte (buses interprovinciales, Metro de Quito, taxis por app), seguridad y gastronomía cuando sea relevante.
                """.trimIndent() + idioma
                val prompt = "Región/contexto del viajero: $contextRegion\nPregunta: $userMessage"
                val answer = client.generate(prompt, systemInstruction = system, useSearch = true, temperature = 0.5).text
                if (answer.isNotBlank()) return@withContext answer
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext generateOfflineAssistantReply(userMessage)
    }

    /**
     * Genera micro-itinerario de emergencia o cambio por clima
     */
    suspend fun generateWeatherAlternative(currentLocation: String, weatherAlert: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val title = "Plan B Adaptado por Clima en $currentLocation"
        val desc = when {
            weatherAlert.contains("lluvia", ignoreCase = true) -> 
                "Lluvias intensas detectadas. Se sugiere cambiar caminatas al aire libre por visita a museos patrimoniales, termas cubiertas o degustación de chocolate fino de aroma."
            weatherAlert.contains("frío", ignoreCase = true) || weatherAlert.contains("viento", ignoreCase = true) ->
                "Bajas temperaturas en páramo andino. Se aconseja aclimatación en cafeterías de altura con té de coca o canelazo sin alcohol y visita a mercados artesanales."
            else -> 
                "Condiciones variables. Ruta optimizada con paradas intermedias protegidas y transporte coordinado."
        }
        return@withContext Pair(title, desc)
    }

    private fun parseOrFallbackItinerary(
        region: EcuadorRegion,
        days: Int,
        budgetStyle: String,
        travelers: Int,
        customPrompt: String,
        aiText: String
    ): ItineraryPlan {
        val basePlan = generateOfflineItinerary(region, days, budgetStyle, travelers, customPrompt)
        val root = JsonExtract.firstObject(aiText) ?: return basePlan.copy(summary = aiText.take(200))
        val daysArr = root.optJSONArray("days") ?: return basePlan.copy(summary = root.optString("summary", basePlan.summary))
        val parsedDays = mutableListOf<ItineraryDay>()
        for (i in 0 until daysArr.length()) {
            val d = daysArr.optJSONObject(i) ?: continue
            val acts = d.optJSONArray("activities")
            val activities = mutableListOf<ActivityItem>()
            if (acts != null) for (j in 0 until acts.length()) {
                val a = acts.optJSONObject(j) ?: continue
                activities.add(
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = a.optString("time", ""),
                        title = a.optString("title", "Actividad"),
                        description = a.optString("description", ""),
                        location = a.optString("location", ""),
                        isNext = i == 0 && j == 0,
                        hasWeatherAlert = a.optBoolean("weather_alert", false),
                        alternativePlan = a.optString("alternative_plan", "").takeIf { it.isNotBlank() && it != "null" },
                        costUsd = a.optDouble("cost_usd", 0.0)
                    )
                )
            }
            parsedDays.add(
                ItineraryDay(
                    dayNumber = d.optInt("day", i + 1),
                    title = d.optString("title", "Día ${i + 1}"),
                    region = d.optString("region", region.displayName),
                    temperatureC = d.optInt("temperature_c", 18),
                    weatherCondition = d.optString("weather", "Variable"),
                    activities = activities
                )
            )
        }
        if (parsedDays.isEmpty()) return basePlan.copy(summary = root.optString("summary", basePlan.summary))
        val total = root.optDouble("total_budget_usd", 0.0).takeIf { it > 0 }
            ?: parsedDays.sumOf { day -> day.activities.sumOf { it.costUsd } } * travelers
        return basePlan.copy(
            title = root.optString("title", basePlan.title),
            summary = root.optString("summary", basePlan.summary),
            totalDays = parsedDays.size,
            totalBudgetUsd = total,
            remainingBudgetUsd = total,
            days = parsedDays
        )
    }

    /**
     * Pide a la IA que reordene las paradas de una ruta por días y orden lógico (geografía, horarios, tiempos).
     * Devuelve la lista de paradas con dayNumber/orderInDay/plannedTime actualizados. Offline: orden por cercanía.
     */
    /**
     * Propuesta de la IA para ordenar y repartir las paradas. Devuelve null si no hay IA o la
     * respuesta no sirve: quien llama ya tiene el orden calculado sin internet ([RouteOptimizer])
     * y decide cuál usar. Las horas sugeridas se descartan (la app las calcula según transporte);
     * solo se respetan las que el viajero fijó a mano.
     */
    suspend fun optimizeStops(
        route: TravelRoute,
        stops: List<RouteStop>,
        placesById: Map<String, Place>,
        ends: ec.travesias.app.data.repository.RouteSchedule.Endpoints =
            ec.travesias.app.data.repository.RouteSchedule.Endpoints.NONE
    ): List<RouteStop>? = withContext(Dispatchers.IO) {
        if (stops.size < 2 || !online) return@withContext null
        try {
            val listing = stops.joinToString("\n") { s ->
                val p = placesById[s.placeId]
                "- id=${s.id} | ${s.placeName} | lat=${s.latitude}, lng=${s.longitude} | horario=${p?.openingHours?.ifBlank { "?" } ?: "?"} | duración=${s.durationMinutes} min | categoría=${p?.category?.displayName ?: "?"}" +
                    (if (s.timeLocked) " | HORA FIJA ${s.plannedTime}" else "")
            }
            val salida = ends.origin?.let { "${it.name} (lat=${it.latitude}, lng=${it.longitude})" } ?: route.originName.ifBlank { "la primera parada" }
            val llegada = when {
                ends.roundTrip -> "de vuelta en $salida (ida y vuelta)"
                ends.finish != null -> "${ends.finish.name} (lat=${ends.finish.latitude}, lng=${ends.finish.longitude})"
                else -> route.destinationName.ifBlank { "la última parada" }
            }
            val prompt = """
                Ruta "${route.name}" en Ecuador: ${route.totalDays} día(s), ${route.travelers} viajero(s), estilo ${route.travelStyle},
                transporte ${route.transportMode.displayName}, cada día se sale a las ${route.dailyStartTime}.
                El viaje SALE de $salida y TERMINA $llegada.
                Paradas:
                $listing
                Ordena TODAS las paradas y repártelas en los ${route.totalDays} días con estas reglas:
                - Recorrido continuo desde la salida hasta el final, sin ir y volver.
                - Ningún día con más de 10 horas entre viaje y visitas; usa TODOS los días si hay paradas suficientes.
                - Respeta horarios de apertura y las horas fijas; miradores temprano, comida a mediodía.
                - La última parada de cada día debe ser un pueblo o ciudad donde se pueda dormir.
                Responde ÚNICAMENTE con JSON: {"stops":[{"id":"...", "day":1, "order":0, "reason":"..."}]}
            """.trimIndent()
            val text = client.generate(prompt, temperature = 0.2, jsonOnly = true).text
            val arr = JsonExtract.firstObject(text)?.optJSONArray("stops") ?: return@withContext null
            val byId = stops.associateBy { it.id }
            val vistos = HashSet<String>()
            val out = mutableListOf<RouteStop>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = byId[o.optString("id")] ?: continue
                if (!vistos.add(s.id)) continue
                out.add(s.copy(
                    dayNumber = o.optInt("day", s.dayNumber).coerceIn(1, maxOf(1, route.totalDays)),
                    orderInDay = o.optInt("order", i).coerceAtLeast(0),
                    plannedTime = if (s.timeLocked) s.plannedTime else ""
                ))
            }
            // Si la IA olvidó alguna, no se pierde: va al final de su día.
            val faltan = stops.filter { it.id !in vistos }.map { it.copy(orderInDay = 999) }
            if (out.isEmpty()) return@withContext null
            // Órdenes limpios (0,1,2…) por día, sin repetidos.
            (out + faltan).groupBy { it.dayNumber }.toSortedMap().flatMap { (_, delDia) ->
                delDia.sortedBy { it.orderInDay }.mapIndexed { idx, st -> st.copy(orderInDay = idx) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateOfflineItinerary(
        region: EcuadorRegion,
        days: Int,
        budgetStyle: String,
        travelers: Int,
        customPrompt: String
    ): ItineraryPlan {
        val budgetPerDay = when (budgetStyle) {
            "Mochilero" -> 35.0
            "Lujo" -> 180.0
            else -> 75.0
        }
        val totalBudget = budgetPerDay * days * travelers

        val itineraryDays = mutableListOf<ItineraryDay>()

        for (day in 1..days) {
            val dayActivities = when (region) {
                EcuadorRegion.ANDES -> listOf(
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "08:30 AM",
                        title = if (day == 1) "Centro Histórico de Quito & Café de Altura" else "Parque Nacional Cotopaxi y Refugio",
                        description = "Explora la arquitectura barroca en Plaza Grande y la Basílica del Voto Nacional con degustación de café lojano.",
                        location = "Quito / Pichincha",
                        isCompleted = false,
                        isNext = day == 1,
                        costUsd = 12.0
                    ),
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "14:00 PM",
                        title = "Laguna del Quilotoa & Mirador de Cristal",
                        description = "Caminata por el cráter volcánico de agua color turquesa. Alquiler de mulas para el ascenso disponible.",
                        location = "Pujilí / Cotopaxi",
                        hasWeatherAlert = true,
                        alternativePlan = "Visita a talleres de pintura en cuero de Tigua y degustación de queso de hoja en Latacunga.",
                        costUsd = 20.0
                    ),
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "19:00 PM",
                        title = "Cena Típica Tradicional: Locro de Papa & Empanadas de Viento",
                        description = "Sabores autóctonos en restaurante patrimonial.",
                        location = "La Ronda / Quito",
                        costUsd = 15.0
                    )
                )
                EcuadorRegion.GALAPAGOS -> listOf(
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "08:00 AM",
                        title = "Snorkel en Los Túneles / León Dormido",
                        description = "Nado con tortugas marinas gigantes, tiburones punta blanca y rayas águila en aguas cristalinas.",
                        location = "Isabela / San Cristóbal",
                        costUsd = 90.0,
                        isNext = true
                    ),
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "15:00 PM",
                        title = "Estación Científica Charles Darwin",
                        description = "Centro de crianza de tortugas terrestres gigantes y aprendizaje sobre la evolución de pinzones.",
                        location = "Puerto Ayora / Santa Cruz",
                        costUsd = 0.0
                    )
                )
                EcuadorRegion.AMAZON -> listOf(
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "07:30 AM",
                        title = "Navegación en Canoa por el Río Napo",
                        description = "Observación de aves exóticas, monos aulladores y llegada a comunidad Kichwa de la Amazonía.",
                        location = "Tena / Misahuallí",
                        costUsd = 25.0,
                        isNext = true
                    ),
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "13:00 PM",
                        title = "Almuerzo Amazónico: Maito de Tilapia en Hoja de Bijao",
                        description = "Gastronomía ancestral cocida a la leña con yuca y guayusa energizante fresca.",
                        location = "Puerto Misahuallí",
                        costUsd = 10.0
                    )
                )
                EcuadorRegion.COAST -> listOf(
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "09:00 AM",
                        title = "Playa Los Frailes & Parque Nacional Machalilla",
                        description = "Una de las playas más hermosas de arena blanca del Pacífico Sur, rodeada de bosque seco tropical.",
                        location = "Puerto López / Manabí",
                        costUsd = 10.0,
                        isNext = true
                    ),
                    ActivityItem(
                        id = UUID.randomUUID().toString(),
                        time = "13:30 PM",
                        title = "Festín Costero: Ceviche de Camarón y Encebollado Mixto",
                        description = "Degustación del plato insignia ecuatoriano con chifles y tostado.",
                        location = "Manta / Manabí",
                        costUsd = 12.0
                    )
                )
            }

            itineraryDays.add(
                ItineraryDay(
                    dayNumber = day,
                    title = "Día $day: Maravillas de ${region.displayName}",
                    region = region.displayName,
                    temperatureC = when (region) {
                        EcuadorRegion.ANDES -> 17
                        EcuadorRegion.COAST -> 28
                        EcuadorRegion.AMAZON -> 26
                        EcuadorRegion.GALAPAGOS -> 25
                    },
                    weatherCondition = if (region == EcuadorRegion.ANDES) "Parcialmente Nublado / Sol Andino" else "Cálido y Húmedo",
                    activities = dayActivities
                )
            )
        }

        return ItineraryPlan(
            id = UUID.randomUUID().toString(),
            title = "Aventura en ${region.displayName} ($days Días)",
            summary = "Itinerario optimizado para $travelers viajero(s) con estilo $budgetStyle. Incluye reservas naturales, gastronomía local y planes de contingencia.",
            targetRegion = region,
            totalDays = days,
            travelStyle = budgetStyle,
            travelers = travelers,
            totalBudgetUsd = totalBudget,
            remainingBudgetUsd = totalBudget * 0.75,
            days = itineraryDays
        )
    }

    private fun generateOfflineAssistantReply(msg: String): String {
        val lower = msg.lowercase()
        return when {
            lower.contains("soroche") || lower.contains("altura") || lower.contains("mareo") ->
                "Para el mal de altura en la Sierra (Quito 2850m, Cotopaxi 4800m):\n" +
                "1. Tómate el primer día con calma y evita esfuerzos físicos intensos.\n" +
                "2. Bebe té de hoja de coca o agua de sunfo.\n" +
                "3. Mantente muy bien hidratado (mínimo 2.5 litros de agua al día).\n" +
                "4. Come ligero (sopas calientes como locro de papa) y evita comidas pesadas o alcohol la primera noche."

            lower.contains("moneda") || lower.contains("dolar") || lower.contains("pago") || lower.contains("efectivo") ->
                "En Ecuador la moneda oficial es el Dólar Estadounidense (USD).\n" +
                "Consejo clave: Lleva siempre billetes de baja denominación (\$1, \$5, \$10 y \$20) y monedas. En mercados, taxis y puestos callejeros los billetes de \$50 y \$100 casi nunca son aceptados."

            lower.contains("transporte") || lower.contains("bus") || lower.contains("taxi") || lower.contains("uber") ->
                "Transporte en Ecuador:\n" +
                "• En Quito y Guayaquil puedes usar Uber, Cabify o taxis amarillos oficiales (asegúrate de que usen taxímetro o acuerda el precio antes).\n" +
                "• El Metro de Quito conecta el norte y sur en 34 minutos ($0.45 el pasaje).\n" +
                "• Para viajar entre provincias, los buses interprovinciales salen desde las terminales terrestres (Quitumbe/Carcelén en Quito) con costos aproximados de \$1.50 a \$2.00 por hora de viaje."

            lower.contains("comida") || lower.contains("plato") || lower.contains("gastronomia") || lower.contains("comer") ->
                "¡La gastronomía de Ecuador es espectacular! Te recomiendo probar:\n" +
                "• Costa: Encebollado (nombrada una de las mejores sopas del mundo), ceviche de camarón, corviche y tigrillo con café zarumeño.\n" +
                "• Sierra: Locro de papa con aguacate y queso, hornado pastuso/riobambeño, fritada con llapingachos y mote pillo.\n" +
                "• Amazonía: Maito de tilapia o chonta y chicha de yuca.\n" +
                "• Galápagos: Langosta espinosa (en temporada) y brujo frito."

            lower.contains("seguridad") || lower.contains("seguro") || lower.contains("peligro") ->
                "Consejos de seguridad:\n" +
                "• Mantén tus pertenencias a la vista en aglomeraciones y transporte público.\n" +
                "• Usa taxis por aplicación de noche.\n" +
                "• Guarda el número de emergencia nacional: 911 (ECU 911 opera en todo el país gratis)."

            else ->
                "¡Hola! Soy tu Asistente IA de Ecuador. Puedo ayudarte con itinerarios en Galápagos, volcanes de la Sierra, playas de la Costa o expediciones en la Amazonía. ¿Qué destino o actividad te gustaría planificar hoy?"
        }
    }
}

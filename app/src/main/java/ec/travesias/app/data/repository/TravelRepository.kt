package ec.travesias.app.data.repository

import ec.travesias.app.data.ai.GeminiTravelService
import ec.travesias.app.data.local.*
import ec.travesias.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TravelRepository(
    private val database: AppDatabase,
    private val aiService: GeminiTravelService
) {
    private val itineraryDao = database.itineraryDao()
    private val offlinePackageDao = database.offlinePackageDao()
    private val packingDao = database.packingDao()

    // Directorio de contactos de emergencia en Ecuador
    val emergencyContacts: List<EmergencyContact> = listOf(
        EmergencyContact("ECU 911 (Emergencias Nacionales)", "911", "Policía, Bomberos, Ambulancias y Tránsito en todo el Ecuador", "emergency"),
        EmergencyContact("Policía Turística del Ecuador", "(02) 254-3983", "Atención especializada y protección a viajeros extranjeros y nacionales", "police"),
        EmergencyContact("Cruz Roja Ecuatoriana", "(02) 258-2480", "Asistencia médica de primeros auxilios y traslados de urgencia", "hospital"),
        EmergencyContact("Ministerio de Turismo (InfoTur)", "1800 887-476", "Información oficial de rutas abiertas, alertas y guías certificados", "info")
    )

    // Guía de altitud y prevención de Soroche
    val altitudeGuides: List<AltitudeGuide> = listOf(
        AltitudeGuide("Guayaquil / Manta (Costa)", 5, "Bajo", listOf("Sin riesgo de altitud.", "Usa protector solar y mantente hidratado por el calor.")),
        AltitudeGuide("Baños de Agua Santa", 1820, "Bajo / Moderado", listOf("Excelente punto de transición entre la Sierra y el Oriente.", "Clima templado primaveral.")),
        AltitudeGuide("Cuenca Colonial", 2550, "Moderado", listOf("Primeras horas: camina pausadamente.", "Disfruta de infusiones calientes.")),
        AltitudeGuide("Quito (Capital)", 2850, "Moderado / Alto", listOf("Altitud considerable.", "Evita alcohol y comidas pesadas la primera noche.", "Bebe té de coca.")),
        AltitudeGuide("Laguna del Quilotoa", 3914, "Alto", listOf("Viento frío en el cráter.", "Si desciendes a la orilla, reserva energía para subir o alquila una mula.")),
        AltitudeGuide("Refugio Volcán Cotopaxi", 4864, "Extremo", listOf("Aclimatación previa obligatoria de 2-3 días en Quito.", "Uso estricto de ropa de alta montaña y lentes con filtro UV."))
    )

    // Room Flows
    val savedItinerariesFlow: Flow<List<SavedItineraryEntity>> = itineraryDao.getAllItineraries()
    val offlinePackagesFlow: Flow<List<OfflinePackageEntity>> = offlinePackageDao.getAllPackages()
    val packingItemsFlow: Flow<List<PackingItem>> = packingDao.getAllItems().map { list ->
        list.map { PackingItem(it.id, it.name, it.category, it.isChecked) }
    }

    suspend fun saveItinerary(plan: ItineraryPlan) {
        itineraryDao.insertItinerary(
            SavedItineraryEntity(
                id = plan.id,
                title = plan.title,
                summary = plan.summary,
                region = plan.targetRegion.name,
                totalDays = plan.totalDays,
                travelStyle = plan.travelStyle,
                travelers = plan.travelers,
                totalBudgetUsd = plan.totalBudgetUsd,
                remainingBudgetUsd = plan.remainingBudgetUsd,
                daysJson = ItineraryJson.encodeDays(plan.days)
            )
        )
    }

    suspend fun updatePackageDownload(id: String, isDownloaded: Boolean, progress: Float = if (isDownloaded) 1f else 0f) {
        val label = if (isDownloaded) "Descargado " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("es")).format(java.util.Date()) else if (progress > 0f) "Descargando…" else "Pendiente de descarga"
        offlinePackageDao.setDownloadStatus(id, isDownloaded, progress, label)
    }

    suspend fun getOfflinePackage(id: String): OfflinePackageEntity? = offlinePackageDao.getById(id)

    suspend fun togglePackingItem(item: PackingItem) {
        packingDao.updateItem(
            PackingChecklistEntity(
                id = item.id,
                name = item.name,
                category = item.category,
                isChecked = !item.isChecked
            )
        )
    }

    suspend fun addPackingItem(name: String, category: String) {
        packingDao.insertItem(
            PackingChecklistEntity(name = name, category = category, isChecked = false)
        )
    }

    // Integración de IA
    suspend fun generateAiItinerary(
        region: EcuadorRegion,
        days: Int,
        budgetStyle: String,
        travelers: Int,
        prompt: String
    ): ItineraryPlan {
        return aiService.generateItinerary(region, days, budgetStyle, travelers, prompt)
    }

    suspend fun askAssistant(message: String, region: String): String {
        return aiService.askAssistant(message, region)
    }
}

/** Serialización de los días de un itinerario (para que no se pierdan al guardar). */
object ItineraryJson {
    fun encodeDays(days: List<ItineraryDay>): String {
        val arr = org.json.JSONArray()
        days.forEach { d ->
            arr.put(org.json.JSONObject().apply {
                put("day", d.dayNumber); put("title", d.title); put("region", d.region)
                put("temp", d.temperatureC); put("weather", d.weatherCondition)
                put("activities", org.json.JSONArray().apply {
                    d.activities.forEach { a ->
                        put(org.json.JSONObject().apply {
                            put("id", a.id); put("time", a.time); put("title", a.title); put("description", a.description)
                            put("location", a.location); put("completed", a.isCompleted); put("next", a.isNext)
                            put("weatherAlert", a.hasWeatherAlert); put("alt", a.alternativePlan ?: ""); put("cost", a.costUsd)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    fun decodeDays(json: String): List<ItineraryDay> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val acts = d.optJSONArray("activities")
                ItineraryDay(
                    dayNumber = d.optInt("day", i + 1), title = d.optString("title"), region = d.optString("region"),
                    temperatureC = d.optInt("temp", 18), weatherCondition = d.optString("weather"),
                    activities = if (acts == null) emptyList() else (0 until acts.length()).mapNotNull { j ->
                        val a = acts.optJSONObject(j) ?: return@mapNotNull null
                        ActivityItem(
                            id = a.optString("id"), time = a.optString("time"), title = a.optString("title"),
                            description = a.optString("description"), location = a.optString("location"),
                            isCompleted = a.optBoolean("completed"), isNext = a.optBoolean("next"),
                            hasWeatherAlert = a.optBoolean("weatherAlert"),
                            alternativePlan = a.optString("alt").ifBlank { null }, costUsd = a.optDouble("cost", 0.0)
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
    }
}

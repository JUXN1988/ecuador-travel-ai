package ec.travesias.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ec.travesias.app.TravesiasApp
import ec.travesias.app.data.model.*
import ec.travesias.app.data.remote.Secrets
import ec.travesias.app.data.repository.ItineraryJson
import ec.travesias.app.data.repository.TravelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel de planificador IA, guía (chat), kit de viaje y paquetes offline.
 */
class TravelViewModel(application: Application) : AndroidViewModel(application) {

    private val container = TravesiasApp.from(application)
    private val repository: TravelRepository = container.travelRepository
    private val agent = container.agent
    private val offlineMaps = container.offlineMaps

    val hasGeminiKey: Boolean = Secrets.hasGemini

    // --- PLANIFICADOR IA ---
    private val _currentItinerary = MutableStateFlow<ItineraryPlan?>(null)
    val currentItinerary: StateFlow<ItineraryPlan?> = _currentItinerary.asStateFlow()

    private val _isGeneratingPlan = MutableStateFlow(false)
    val isGeneratingPlan: StateFlow<Boolean> = _isGeneratingPlan.asStateFlow()

    /** Avisos del planificador y el kit, para el snackbar de MainActivity. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _selectedPlanAlternative = MutableStateFlow("A")
    val selectedPlanAlternative: StateFlow<String> = _selectedPlanAlternative.asStateFlow()

    val savedItineraries: StateFlow<List<ItineraryPlan>> = repository.savedItinerariesFlow.map { list ->
        list.map { e ->
            ItineraryPlan(
                id = e.id, title = e.title, summary = e.summary,
                targetRegion = runCatching { EcuadorRegion.valueOf(e.region) }.getOrDefault(EcuadorRegion.ANDES),
                totalDays = e.totalDays, travelStyle = e.travelStyle, travelers = e.travelers,
                totalBudgetUsd = e.totalBudgetUsd, remainingBudgetUsd = e.remainingBudgetUsd,
                days = ItineraryJson.decodeDays(e.daysJson)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- CHAT GUÍA IA ---
    private val _chatMessages = MutableStateFlow(
        listOf(
            ChatMessage(
                id = "welcome_msg",
                sender = MessageSender.AI,
                text = "¡Hola! 🇪🇨 Soy tu Guía IA de Travesías en Ecuador.\n\nNo solo respondo: puedo **armar y editar tu ruta**. Dime a dónde quieres ir y cuántos días tienes, y la creo con paradas, días y horarios. También puedo añadir o quitar paradas, moverlas de día y buscar lugares virales en redes.",
                suggestions = listOf(
                    "Arma una ruta de 5 días de Quito a Baños",
                    "Busca lugares virales en mi ruta",
                    "Agrega el Quilotoa al día 2",
                    "¿Cómo evitar el mal de altura (soroche)?"
                )
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isAiThinking = MutableStateFlow(false)
    val isAiThinking: StateFlow<Boolean> = _isAiThinking.asStateFlow()

    private val _chatRegion = MutableStateFlow<EcuadorRegion?>(null)
    val chatRegion: StateFlow<EcuadorRegion?> = _chatRegion.asStateFlow()

    // --- OFFLINE & KIT ---
    val offlinePackages = repository.offlinePackagesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mapDownloads = offlineMaps.downloads
    val packingItems = repository.packingItemsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val emergencyContacts = repository.emergencyContacts
    val altitudeGuides = repository.altitudeGuides

    private val _cacheSizeMb = MutableStateFlow(0L)
    val cacheSizeMb: StateFlow<Long> = _cacheSizeMb.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _cacheSizeMb.value = offlineMaps.cacheSizeMb() }
    }

    // --- ACCIONES ---

    fun generateItineraryWithAi(region: EcuadorRegion, days: Int, budgetStyle: String, travelers: Int, prompt: String) {
        viewModelScope.launch {
            _isGeneratingPlan.value = true
            try {
                val plan = repository.generateAiItinerary(region, days, budgetStyle, travelers, prompt)
                _currentItinerary.value = plan
                repository.saveItinerary(plan)
            } catch (e: Exception) {
                _events.emit(e.message ?: "No se pudo generar el itinerario. Revisa la conexión y la clave de Gemini.")
            } finally {
                _isGeneratingPlan.value = false
            }
        }
    }

    fun loadItinerary(plan: ItineraryPlan) {
        _currentItinerary.value = plan
    }

    fun setPlanAlternative(alt: String) {
        _selectedPlanAlternative.value = alt
    }

    fun setChatRegion(region: EcuadorRegion?) {
        _chatRegion.value = region
    }

    /**
     * Envía el mensaje al agente. El agente puede usar herramientas (catálogo offline,
     * radar de redes sociales, mapa) y modificar la ruta del viajero de verdad.
     */
    fun sendChatMessage(text: String) {
        if (text.isBlank()) return
        val userMsg = ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.USER, text = text)
        _chatMessages.update { it + userMsg }
        _isAiThinking.value = true

        viewModelScope.launch {
            try {
                val historial = _chatMessages.value
                    .filter { it.sender == MessageSender.USER || it.sender == MessageSender.AI }
                    .takeLast(7)
                    .map { m -> (if (m.sender == MessageSender.USER) "user" else "ai") to m.text }

                val res = agent.handle(
                    userMessage = text,
                    history = historial,
                    contextRegion = _chatRegion.value?.displayName ?: "Ecuador"
                )
                _chatMessages.update {
                    it + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = res.reply,
                        suggestions = res.suggestions,
                        steps = res.steps,
                        routeChanged = res.routeChanged,
                        offline = res.usedOffline
                    )
                }
            } catch (e: Exception) {
                _chatMessages.update {
                    it + ChatMessage(
                        id = UUID.randomUUID().toString(), sender = MessageSender.SYSTEM,
                        text = "No pude conectar con la IA (${e.message ?: "sin red"}). Puedo seguir armando tu ruta con los lugares descargados: pídeme por ejemplo «arma una ruta de 4 días de Quito a Baños»."
                    )
                }
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    /** Descarga real de teselas para un paquete regional. */
    fun downloadOfflinePackage(packageId: String) {
        viewModelScope.launch {
            val pkg = repository.getOfflinePackage(packageId) ?: return@launch
            if (pkg.north == 0.0 && pkg.south == 0.0) return@launch
            repository.updatePackageDownload(packageId, isDownloaded = false, progress = 0.01f)
            offlineMaps.downloadArea(packageId, pkg.north, pkg.south, pkg.east, pkg.west) { ok ->
                viewModelScope.launch {
                    repository.updatePackageDownload(packageId, isDownloaded = ok, progress = if (ok) 1f else 0f)
                    refreshCacheSize()
                }
            }
        }
    }

    fun deleteOfflinePackage(packageId: String) {
        viewModelScope.launch {
            val pkg = repository.getOfflinePackage(packageId) ?: return@launch
            offlineMaps.deleteArea(pkg.north, pkg.south, pkg.east, pkg.west)
            repository.updatePackageDownload(packageId, isDownloaded = false, progress = 0f)
            refreshCacheSize()
        }
    }

    fun togglePackingCheck(item: PackingItem) {
        viewModelScope.launch { repository.togglePackingItem(item) }
    }

    fun addPackingItem(name: String, category: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addPackingItem(name, category) }
    }
}

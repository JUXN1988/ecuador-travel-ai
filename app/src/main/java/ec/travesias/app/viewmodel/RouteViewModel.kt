package ec.travesias.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ec.travesias.app.TravesiasApp
import ec.travesias.app.data.model.*
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.remote.RouteScoutService
import ec.travesias.app.data.remote.RoutingService
import ec.travesias.app.data.remote.Secrets
import ec.travesias.app.data.remote.LinkExtractorService
import ec.travesias.app.data.repository.PlaceIndexRepository
import ec.travesias.app.data.repository.RouteSchedule
import ec.travesias.app.data.repository.PlaceSuggestion
import ec.travesias.app.data.repository.RouteDraftMath
import ec.travesias.app.data.repository.RouteRepository
import ec.travesias.app.data.repository.RouteCoherence
import ec.travesias.app.data.repository.TripCostEstimator
import ec.travesias.app.data.local.VehicleSettings
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.remote.FuelPriceService
import ec.travesias.app.data.social.CommentCategory
import ec.travesias.app.data.social.DeviceLocation
import ec.travesias.app.data.social.PhotoStore
import ec.travesias.app.data.social.PlaceComment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel del mapa, del constructor de rutas y del Radar IA.
 */
class RouteViewModel(application: Application) : AndroidViewModel(application) {

    private val container = TravesiasApp.from(application)
    private val repo: RouteRepository = container.routeRepository
    private val offlineMaps = container.offlineMaps
    private val agent = container.agent
    private val community = container.community

    val hasGeminiKey: Boolean = Secrets.hasGemini

    // ---------- Lugares ----------
    val allPlaces: StateFlow<List<Place>> = repo.allPlaces.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow(PlaceCategory.ALL)
    val selectedCategory: StateFlow<PlaceCategory> = _selectedCategory.asStateFlow()

    private val _selectedRegion = MutableStateFlow<EcuadorRegion?>(null)
    val selectedRegion: StateFlow<EcuadorRegion?> = _selectedRegion.asStateFlow()

    val filteredPlaces: StateFlow<List<Place>> = combine(allPlaces, _searchQuery, _selectedCategory, _selectedRegion) { places, q, cat, region ->
        // El explorador de ruta trae CIENTOS de puntos del mapa de OpenStreetMap (gasolineras,
        // farmacias, terminales…). Son utilísimos cuando uno busca algo concreto o va por una
        // ruta, pero si se dejaran sueltos taparían el catálogo curado al abrir la pestaña.
        // Solución: no aparecen en la lista "en reposo", y sí en cuanto el viajero busca o
        // filtra. Buscar sigue encontrando TODO.
        val buscando = q.isNotBlank() || cat != PlaceCategory.ALL || region != null
        places.filter { p ->
            (buscando || p.verification != VerificationLevel.OSM) &&
                (q.isBlank() || p.name.contains(q, true) || p.city.contains(q, true) || p.province.contains(q, true) ||
                    p.shortDescription.contains(q, true) || p.tags.any { it.contains(q, true) }) &&
                (cat == PlaceCategory.ALL || p.category == cat) &&
                (region == null || p.region == region)
        }.sortedWith(
            compareByDescending<Place> { it.verification == VerificationLevel.CURATED }
                .thenBy { it.verification == VerificationLevel.OSM }
                .thenByDescending { it.socialMentions }
                .thenBy { it.name }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlace = MutableStateFlow<Place?>(null)
    val selectedPlace: StateFlow<Place?> = _selectedPlace.asStateFlow()

    // ---------- Ruta activa ----------
    val activeRoute: StateFlow<TravelRoute?> = repo.activeRoute.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val allRoutes: StateFlow<List<TravelRoute>> = repo.allRoutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Lugares de la ruta activa (para tarjetas y mapa). */
    val activeRoutePlaces: StateFlow<Map<String, Place>> = combine(activeRoute, allPlaces) { route, places ->
        val ids = route?.stops?.map { it.placeId }?.toSet() ?: emptySet()
        places.filter { it.id in ids }.associateBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _roadRoute = MutableStateFlow<RoutingService.RoadRoute?>(null)
    val roadRoute: StateFlow<RoutingService.RoadRoute?> = _roadRoute.asStateFlow()

    /** Origen y destino de la ruta activa ya ubicados en el mapa (para sumar salida y regreso). */
    private val _endpoints = MutableStateFlow(RouteSchedule.Endpoints.NONE)
    val endpoints: StateFlow<RouteSchedule.Endpoints> = _endpoints.asStateFlow()

    /** Horario del viaje: horas de salida y llegada, tiempos de viaje y de visita por día. */
    val schedule: StateFlow<List<RouteSchedule.DayPlan>> = combine(activeRoute, _roadRoute, _endpoints) { r, road, ends ->
        if (r == null) emptyList() else RouteSchedule.build(r, road, ends)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Vehículo del viajero y precios del combustible (se guardan en el teléfono). */
    val vehicle = VehicleSettings.profile

    /** Cuánto cuesta moverse: combustible, peajes y pasajes, además de las entradas. */
    val tripCost: StateFlow<TripCostEstimator.TripCost?> = combine(activeRoute, schedule, vehicle) { r, plan, v ->
        if (r == null) null else TripCostEstimator.estimate(r, plan, v)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Lo que no cuadra en la ruta (días vacíos, jornadas eternas, ir y volver…), en palabras claras. */
    val routeIssues: StateFlow<List<RouteCoherence.Issue>> = combine(activeRoute, schedule, _endpoints) { r, plan, ends ->
        if (r == null) emptyList() else RouteCoherence.check(r, plan, ends)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Estado de "Actualizar precios del combustible". */
    private val _fuelUpdating = MutableStateFlow(false)
    val fuelUpdating: StateFlow<Boolean> = _fuelUpdating.asStateFlow()

    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing: StateFlow<Boolean> = _isOptimizing.asStateFlow()

    private var roadJob: Job? = null
    private var radarJob: Job? = null
    private var scoutJob: Job? = null
    private var pinJob: Job? = null

    // ---------- Radar IA ----------
    private val _radarState = MutableStateFlow<RadarUiState>(RadarUiState.Idle)
    val radarState: StateFlow<RadarUiState> = _radarState.asStateFlow()

    private val _radarCorridor = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val radarCorridor: StateFlow<List<Pair<Double, Double>>> = _radarCorridor.asStateFlow()

    private val _radarResults = MutableStateFlow<List<Place>>(emptyList())
    val radarResults: StateFlow<List<Place>> = _radarResults.asStateFlow()

    /** Km de desvío respecto a la vía, por id de lugar. Se muestra en cada tarjeta del Radar. */
    private val _radarDetours = MutableStateFlow<Map<String, Double>>(emptyMap())
    val radarDetours: StateFlow<Map<String, Double>> = _radarDetours.asStateFlow()

    /**
     * Zona de búsqueda cuando el viajero no indicó salida. El mapa la pinta como un círculo y
     * las tarjetas cambian "desvío de la vía" por "a X km de tu base". Null en modo ruta.
     */
    private val _searchArea = MutableStateFlow<SearchArea?>(null)
    val searchArea: StateFlow<SearchArea?> = _searchArea.asStateFlow()

    // ---------- Intención de viaje (la primera pregunta de la app) ----------
    /** Lo que el viajero contestó en la portada: de dónde a dónde y cuántos días. */
    private val _tripIntent = MutableStateFlow(AppSettings.lastTrip.value)
    val tripIntent: StateFlow<TripIntent> = _tripIntent.asStateFlow()

    // ---------- Explorador de ruta (OpenStreetMap + catálogo, sin depender de la IA) ----------
    private val _scoutState = MutableStateFlow<ScoutUiState>(ScoutUiState.Idle)
    val scoutState: StateFlow<ScoutUiState> = _scoutState.asStateFlow()

    /** Los pueblos del camino con lo que hay en cada uno, en orden de avance del viaje. */
    private val _scoutTramos = MutableStateFlow<List<RouteScoutService.Tramo>>(emptyList())
    val scoutTramos: StateFlow<List<RouteScoutService.Tramo>> = _scoutTramos.asStateFlow()

    // ---------- Capas del Mapa ----------
    private val _showLayer1 = MutableStateFlow(true)
    val showLayer1: StateFlow<Boolean> = _showLayer1.asStateFlow()
    
    private val _showLayer2 = MutableStateFlow(true)
    val showLayer2: StateFlow<Boolean> = _showLayer2.asStateFlow()
    
    private val _showLayer3 = MutableStateFlow(true)
    val showLayer3: StateFlow<Boolean> = _showLayer3.asStateFlow()

    fun toggleLayer1() { _showLayer1.value = !_showLayer1.value }
    fun toggleLayer2() { _showLayer2.value = !_showLayer2.value }
    fun toggleLayer3() { _showLayer3.value = !_showLayer3.value }

    /** Capa 1: Mis lugares guardados o en la ruta activa */
    val layer1Places: StateFlow<List<Place>> = combine(allPlaces, activeRoute) { places, route ->
        val routeIds = route?.stops?.map { it.placeId }?.toSet() ?: emptySet()
        places.filter { it.isSaved || it.id in routeIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Capa 2: Catálogo offline cercano al corredor del Radar IA (máx 15km) */
    val layer2Places: StateFlow<List<Place>> = combine(allPlaces, _radarCorridor, layer1Places, _radarResults) { all, corridor, l1, l3 ->
        if (corridor.isEmpty()) return@combine emptyList<Place>()
        val excludeIds = l1.map { it.id }.toSet() + l3.map { it.id }.toSet()
        all.filter { p -> 
            p.id !in excludeIds && RoutingService.distanceToPathKm(p.latitude, p.longitude, corridor) <= 15.0 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------- Mensajes UI ----------
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // ---------- Mapa: enfoque ----------
    private val _mapFocus = MutableStateFlow<Pair<Double, Double>?>(null)
    val mapFocus: StateFlow<Pair<Double, Double>?> = _mapFocus.asStateFlow()

    init {
        // Origen/destino: solo se vuelven a ubicar si cambian los nombres.
        viewModelScope.launch {
            activeRoute.map { r -> r?.let { Triple(it.id, it.originName, it.destinationName) } }
                .distinctUntilChanged()
                .collectLatest { _ -> _endpoints.value = repo.endpointsFor(activeRoute.value) }
        }
        // Carretera: paradas + salida desde el origen + llegada/regreso.
        viewModelScope.launch {
            combine(activeRoute, _endpoints) { r, ends -> if (r == null) emptyList() else RouteSchedule.roadSequence(r, ends) }
                .map { seq -> seq.map { s -> listOf(s.id, s.latitude, s.longitude) } to seq }
                .distinctUntilChangedBy { it.first }
                .collect { (_, seq) -> recomputeRoad(seq) }
        }
        // Si hay una ruta activa y el viajero todavía no escribió nada en la portada, se
        // rellena la pregunta con esa ruta: al volver a abrir la app no se empieza de cero.
        viewModelScope.launch {
            activeRoute.collect { r ->
                if (r != null && r.destinationName.isNotBlank() && _tripIntent.value.destination.isBlank()) {
                    _tripIntent.value = TripIntent(
                        // Ida y vuelta desde la misma base: se mantiene el modo "solo destino".
                        origin = if (r.originName.equals(r.destinationName, true)) "" else r.originName,
                        destination = r.destinationName,
                        days = r.totalDays, travelers = r.travelers
                    )
                }
            }
        }
    }

    // ================= Acciones: lugares =================

    fun onSearchQueryChanged(q: String) { _searchQuery.value = q }
    fun onCategorySelected(c: PlaceCategory) { _selectedCategory.value = c }
    fun onRegionSelected(r: EcuadorRegion?) { _selectedRegion.value = r }

    fun selectPlace(place: Place?) {
        _selectedPlace.value = place
        if (place != null && place.googlePlaceId.isBlank() && Secrets.hasPlaces) {
            viewModelScope.launch {
                val enriched = runCatching { repo.enrichPlace(place) }.getOrNull()
                if (enriched != null && _selectedPlace.value?.id == place.id) _selectedPlace.value = enriched
            }
        }
    }

    fun selectPlaceById(id: String) {
        viewModelScope.launch { repo.getPlace(id)?.let { selectPlace(it) } }
    }

    fun toggleSaved(place: Place) {
        viewModelScope.launch {
            repo.setSaved(place.id, !place.isSaved)
            if (_selectedPlace.value?.id == place.id) _selectedPlace.value = place.copy(isSaved = !place.isSaved)
        }
    }

    fun focusOnMap(lat: Double, lng: Double) { _mapFocus.value = Pair(lat, lng) }
    fun consumeMapFocus() { _mapFocus.value = null }

    // ================= Buscador de direcciones (estilo Google) =================

    private val geocoding = container.geocoding
    private val placeIndex: PlaceIndexRepository = container.placeIndex

    private val _addressQuery = MutableStateFlow("")
    val addressQuery: StateFlow<String> = _addressQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MapSearchResult>>(emptyList())
    val searchResults: StateFlow<List<MapSearchResult>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var addressJob: Job? = null

    /**
     * Se llama en cada tecla. Primero muestra coincidencias del catálogo (instantáneo)
     * y, si el usuario deja de escribir, consulta direcciones reales en OpenStreetMap.
     */
    fun onAddressQueryChanged(query: String) {
        _addressQuery.value = query
        addressJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _searching.value = false
            _searchResults.value = emptyList()
            return
        }
        addressJob = viewModelScope.launch {
            // 1) Al instante, desde el índice del teléfono (provincias, cantones, lugares…)
            val local = suggestions(clean)
            _searchResults.value = reindex(local)
            // 2) Si sigue escribiendo no molestamos a OpenStreetMap
            delay(500)
            _searching.value = true
            val remote = runCatching { geocoding.search(clean, limit = 8) }.getOrDefault(emptyList())
            val fromWeb = remote.map { r ->
                val parts = r.displayName.split(",").map { it.trim() }.filter { it.isNotBlank() }
                MapSearchResult(
                    id = "osm:" + r.latitude + "," + r.longitude,
                    title = parts.firstOrNull() ?: r.displayName,
                    subtitle = parts.drop(1).joinToString(", ").ifBlank {
                        listOf(r.city, r.province).filter { it.isNotBlank() }.joinToString(", ")
                    },
                    latitude = r.latitude,
                    longitude = r.longitude
                )
            }
            _searchResults.value = reindex(
                (local + fromWeb).distinctBy {
                    it.title.lowercase() + "|" + String.format(java.util.Locale.US, "%.4f", it.latitude)
                }.take(12)
            )
            _searching.value = false
        }
    }

    /** El id debe ser único: LazyColumn falla si se repite una clave. */
    private fun reindex(list: List<MapSearchResult>): List<MapSearchResult> =
        list.mapIndexed { index, r -> r.copy(id = index.toString() + ":" + r.id) }

    /** Sugerencias del índice local (nomenclátor + catálogo + direcciones ya buscadas). */
    private suspend fun suggestions(query: String): List<MapSearchResult> =
        runCatching { placeIndex.suggest(query, 6) }.getOrDefault(emptyList()).map { it.toMapResult() }

    private fun PlaceSuggestion.toMapResult() = MapSearchResult(
        id = id, title = name, subtitle = subtitle,
        latitude = latitude, longitude = longitude,
        placeId = placeId.ifBlank { null },
        gazetteerId = id
    )

    /** Cuando el usuario elige una sugerencia en cualquier buscador de la app. */
    fun onSuggestionPicked(suggestion: PlaceSuggestion) {
        _searchQuery.value = suggestion.name
        viewModelScope.launch {
            runCatching { placeIndex.markUsed(suggestion) }
            if (suggestion.placeId.isNotBlank()) {
                selectPlaceById(suggestion.placeId)
            }
            val coords = runCatching { placeIndex.resolveCoordinates(suggestion) }.getOrNull()
            if (coords != null) focusOnMap(coords.first, coords.second)
        }
    }

    fun openSearchResult(result: MapSearchResult) {
        addressJob?.cancel()
        _searching.value = false
        _addressQuery.value = ""
        _searchResults.value = emptyList()

        if (result.placeId != null) {
            selectPlaceById(result.placeId)
            if (result.latitude != 0.0 || result.longitude != 0.0) focusOnMap(result.latitude, result.longitude)
            return
        }

        if (result.latitude != 0.0 || result.longitude != 0.0) {
            focusOnMap(result.latitude, result.longitude)
            _droppedPin.value = result
            viewModelScope.launch {
                runCatching { placeIndex.learnAddress(result.title, result.subtitle, result.latitude, result.longitude) }
            }
            return
        }

        // Del nomenclátor sin coordenadas todavía: se resuelven una sola vez y quedan guardadas.
        viewModelScope.launch {
            _searching.value = true
            val coords = runCatching {
                placeIndex.resolveCoordinates(
                    PlaceSuggestion(
                        id = result.gazetteerId ?: result.id, name = result.title, kind = "",
                        parent = result.subtitle, latitude = 0.0, longitude = 0.0, placeId = ""
                    )
                )
            }.getOrNull()
            _searching.value = false
            if (coords == null) {
                _events.emit("No se encontraron las coordenadas de " + result.title)
            } else {
                focusOnMap(coords.first, coords.second)
                _droppedPin.value = result.copy(latitude = coords.first, longitude = coords.second)
            }
        }
    }

    fun clearAddressSearch() {
        addressJob?.cancel()
        _searching.value = false
        _addressQuery.value = ""
        _searchResults.value = emptyList()
    }

    // ================= Punto marcado en el mapa =================

    private val _droppedPin = MutableStateFlow<MapSearchResult?>(null)
    val droppedPin: StateFlow<MapSearchResult?> = _droppedPin.asStateFlow()

    /** Mantén pulsado el mapa: dice qué dirección hay en ese punto. */
    fun describePoint(latitude: Double, longitude: Double) {
        pinJob?.cancel()
        _droppedPin.value = MapSearchResult(
            id = "pin", title = "Buscando dirección…",
            subtitle = String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude),
            latitude = latitude, longitude = longitude
        )
        pinJob = viewModelScope.launch {
            val r = runCatching { geocoding.reverse(latitude, longitude) }.getOrNull()
            val parts = (r?.displayName ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
            _droppedPin.value = MapSearchResult(
                id = "pin",
                title = parts.firstOrNull() ?: "Punto del mapa",
                subtitle = parts.drop(1).joinToString(", ").ifBlank {
                    String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)
                },
                latitude = latitude,
                longitude = longitude
            )
            // Se guarda en el índice para que la próxima vez salga sola al escribirla.
            val nombre = parts.firstOrNull()
            if (!nombre.isNullOrBlank()) {
                runCatching {
                    placeIndex.learnAddress(nombre, parts.drop(1).take(2).joinToString(", "), latitude, longitude)
                }
            }
        }
    }

    fun clearDroppedPin() { _droppedPin.value = null }

    /** Centra el mapa en la ubicación real del teléfono. */
    fun goToMyLocation() {
        val app = getApplication<Application>()
        if (!DeviceLocation.hasPermission(app)) return
        viewModelScope.launch {
            val quick = DeviceLocation.lastKnown(app)
            if (quick != null) focusOnMap(quick.latitude, quick.longitude)
            val precise = runCatching { DeviceLocation.current(app) }.getOrNull()
            if (precise != null) focusOnMap(precise.latitude, precise.longitude)
            else if (quick == null) _events.emit("No se pudo obtener tu ubicación. Activa el GPS.")
        }
    }

    // ================= Acciones: ruta =================

    fun addToRoute(place: Place, day: Int = 1) {
        addToRoute(place, day, null)
    }

    /**
     * Suma el lugar a la ruta activa, o a [routeId] si se indica (otra travesía ya guardada).
     */
    fun addToRoute(place: Place, day: Int, routeId: String?) {
        viewModelScope.launch {
            val stop = repo.addStop(place, day, routeId)
            val donde = routeId?.let { id -> allRoutes.value.firstOrNull { it.id == id }?.name }
            _events.emit(
                when {
                    stop == null -> "'${place.name}' ya está en esa ruta"
                    !donde.isNullOrBlank() -> "'${place.name}' añadido a «$donde»"
                    else -> "'${place.name}' añadido al día $day de tu ruta"
                }
            )
        }
    }

    /** Mete el lugar en el itinerario que la IA está armando, sin guardarlo todavía. */
    fun addPlaceToDraft(place: Place, day: Int = 1) {
        val actual = _routeDraft.value
        if (actual == null) {
            viewModelScope.launch { _events.emit("No hay un itinerario en armado. Usa Radar IA y luego súmalo.") }
            return
        }
        editarBorrador { RouteDraftMath.agregarLugar(it, place, day) }
        viewModelScope.launch { _events.emit("'${place.name}' entra al itinerario que estás armando") }
    }

    /**
     * Añade una parada escribiendo su nombre. Resuelve contra el catálogo, el nomenclátor
     * del Ecuador y, si hace falta, geocodifica el sitio y lo guarda.
     */
    fun addStopByName(name: String, day: Int = 1) {
        val limpio = name.trim()
        if (limpio.isBlank()) return
        viewModelScope.launch {
            val lugar = runCatching { agent.resolverLugar(limpio) }.getOrNull()
            if (lugar == null) {
                _events.emit("No encontré \"$limpio\". Prueba con el nombre de la ciudad o del lugar.")
                return@launch
            }
            val parada = repo.addStop(lugar, day)
            _events.emit(
                if (parada != null) "'${lugar.name}' añadido al día $day"
                else "'${lugar.name}' ya está en tu ruta"
            )
        }
    }

    fun removeStop(stop: RouteStop) {
        viewModelScope.launch { repo.removeStop(stop.id); _events.emit("Parada eliminada") }
    }

    fun moveStop(stop: RouteStop, newDay: Int, newOrder: Int) {
        viewModelScope.launch { repo.moveStop(stop, newDay, newOrder) }
    }

    fun updateStop(stop: RouteStop) {
        viewModelScope.launch { repo.updateStop(stop) }
    }

    fun toggleStopDone(stop: RouteStop) {
        viewModelScope.launch { repo.updateStop(stop.copy(isDone = !stop.isDone)) }
    }

    fun createRoute(name: String, origin: String, destination: String, days: Int, travelers: Int, style: String, startDate: String,
                    transport: TransportMode = TransportMode.BUS) {
        viewModelScope.launch {
            repo.createRoute(
                TravelRoute(name = name.ifBlank { "Travesía $origin – $destination" }, originName = origin, destinationName = destination,
                    totalDays = days.coerceIn(1, 30), travelers = travelers.coerceIn(1, 20), travelStyle = style,
                    startDate = RouteCoherence.cleanDate(startDate), transportMode = transport),
                makeActive = true
            )
            _events.emit("Ruta creada")
        }
    }

    /** Guarda el vehículo del viajero (tipo, combustible, rendimiento, peajes y precios). */
    fun saveVehicle(profile: ec.travesias.app.data.model.VehicleProfile) {
        VehicleSettings.save(profile)
        viewModelScope.launch { _events.emit("Vehículo guardado: ${profile.type.displayName}") }
    }

    // ================= Intención de viaje (la primera pregunta) =================

    /**
     * Guarda lo que el viajero escribió en la portada. Se persiste en el teléfono para que la
     * próxima vez la app abra con la pregunta ya contestada (proactividad, no memorizar datos).
     */
    fun updateTripIntent(intent: TripIntent, persist: Boolean = true) {
        val limpio = intent.copy(
            origin = intent.origin.trim(),
            destination = intent.destination.trim(),
            days = intent.days.coerceIn(1, 30),
            travelers = intent.travelers.coerceIn(1, 20)
        )
        _tripIntent.value = limpio
        if (persist) AppSettings.setLastTrip(limpio)
    }

    /**
     * "Estoy aquí": pone la ciudad más cercana como punto de salida. Primero el nomenclátor del
     * teléfono (instantáneo y sin señal); si no, la dirección inversa de OpenStreetMap.
     */
    fun useCurrentPlaceAsOrigin() {
        val app = getApplication<Application>()
        if (!DeviceLocation.hasPermission(app)) {
            viewModelScope.launch { _events.emit("Concede el permiso de ubicación para usar dónde estás") }
            return
        }
        viewModelScope.launch {
            val loc = DeviceLocation.lastKnown(app)
                ?: runCatching { DeviceLocation.current(app) }.getOrNull()
            if (loc == null) {
                _events.emit("No pude leer tu ubicación. Activa el GPS o escribe la salida.")
                return@launch
            }
            val cercano = runCatching { placeIndex.nearestName(loc.latitude, loc.longitude) }.getOrNull()
            val hit = if (cercano.isNullOrBlank()) runCatching { geocoding.reverse(loc.latitude, loc.longitude) }.getOrNull() else null
            val nombre = cercano?.takeIf { it.isNotBlank() }
                ?: hit?.city?.takeIf { it.isNotBlank() }
                ?: hit?.province?.takeIf { it.isNotBlank() }
            if (nombre.isNullOrBlank()) {
                _events.emit("No pude reconocer el nombre del lugar. Escribe desde dónde sales.")
            } else {
                updateTripIntent(_tripIntent.value.copy(origin = nombre))
                _events.emit("Salida: $nombre")
            }
        }
    }

    /**
     * El botón principal de la portada: arma el viaje completo con IA y deja el borrador listo
     * para revisar. Si no hay salida, el planificador arma una escapada alrededor del destino.
     */
    fun startTripPlan(intent: TripIntent) {
        if (intent.destination.isBlank()) {
            viewModelScope.launch { _events.emit("Escribe a dónde quieres llegar") }
            return
        }
        updateTripIntent(intent)
        val r = activeRoute.value
        val request = RadarRequest(
            originName = intent.origin.trim(),
            destinationName = intent.destination.trim(),
            interests = listOf("comida típica", "naturaleza", "miradores para fotos"),
            maxDetourKm = 25,
            travelStyle = r?.travelStyle ?: "Moderado"
        )
        planRouteWithAi(request, intent.days, intent.travelers, r?.transportMode ?: TransportMode.BUS)
    }

    /** Solo la lista de lugares (sin itinerario): mapa + IA desde la portada. */
    fun startDestinationDiscovery(intent: TripIntent) {
        if (intent.destination.isBlank()) {
            viewModelScope.launch { _events.emit("Escribe a dónde quieres llegar") }
            return
        }
        updateTripIntent(intent)
        // Primero OpenStreetMap (funciona sin clave de IA y trae lugares reales); la IA suma
        // encima lo que se comenta en redes. Así "solo ver lugares" nunca se queda vacío.
        exploreRoute(
            RadarRequest(
                originName = intent.origin.trim(),
                destinationName = intent.destination.trim(),
                interests = listOf("comida típica", "naturaleza", "miradores para fotos"),
                maxDetourKm = 25
            )
        )
    }

    /** Busca en internet los precios vigentes del combustible en Ecuador. */
    fun refreshFuelPrices() {
        if (_fuelUpdating.value) return
        viewModelScope.launch {
            _fuelUpdating.value = true
            try {
                when (val r = container.fuelPrices.fetchCurrent(RouteCoherence.today())) {
                    is FuelPriceService.Outcome.Ok -> {
                        VehicleSettings.updatePrices(r.prices.prices, r.prices.validFrom, r.prices.validUntil, r.prices.source)
                        val extra = r.prices.prices[FuelType.EXTRA]?.let { " · Extra $" + TripCostEstimator.fmtUsd(it) } ?: ""
                        _events.emit("Precios actualizados (fuente: " + r.prices.source + ")" + extra)
                    }
                    is FuelPriceService.Outcome.Error -> _events.emit(r.message)
                }
            } finally {
                _fuelUpdating.value = false
            }
        }
    }

    /** Cambia el transporte por defecto de toda la travesía. */
    fun setTransportMode(mode: TransportMode) {
        val r = activeRoute.value ?: return
        viewModelScope.launch {
            repo.updateRoute(r.copy(transportMode = mode))
            _events.emit("Transporte del viaje: ${mode.displayName}")
        }
    }

    /** Cambia la hora a la que arranca cada día. */
    fun setDailyStart(time: String) {
        val r = activeRoute.value ?: return
        val limpio = time.trim()
        if (!RouteSchedule.isValidHhmm(limpio)) {
            viewModelScope.launch { _events.emit("Usa el formato HH:MM, por ejemplo 07:30") }
            return
        }
        viewModelScope.launch { repo.updateRoute(r.copy(dailyStartTime = limpio)) }
    }

    /** Transporte solo para el tramo que llega a esa parada. */
    fun setStopTransport(stop: RouteStop, mode: TransportMode?) {
        viewModelScope.launch { repo.updateStop(stop.copy(transportMode = mode)) }
    }

    fun updateRouteInfo(route: TravelRoute) {
        viewModelScope.launch {
            val dias = route.totalDays.coerceIn(1, 30)
            repo.updateRoute(route.copy(totalDays = dias, travelers = route.travelers.coerceIn(1, 20), startDate = RouteCoherence.cleanDate(route.startDate)))
            // Si se bajan los días, las paradas de días que ya no existen pasan al último día
            // (antes quedaban invisibles y seguían sumando costos).
            val huerfanas = route.stops.filter { it.dayNumber > dias }
            huerfanas.forEach { repo.moveStop(it, dias, 999) }
            if (huerfanas.isNotEmpty()) _events.emit("${huerfanas.size} parada(s) pasaron al día $dias")
        }
    }

    fun setActiveRoute(id: String) {
        viewModelScope.launch {
            repo.setActiveRoute(id)
            // Al cambiar de viaje, la portada se actualiza con ese viaje. Si es una escapada
            // con base (origen = destino), se mantiene el modo "solo destino".
            repo.getRoute(id)?.let { r ->
                _tripIntent.value = TripIntent(
                    origin = if (r.originName.equals(r.destinationName, true)) "" else r.originName,
                    destination = r.destinationName,
                    days = r.totalDays,
                    travelers = r.travelers
                )
            }
        }
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch { repo.deleteRoute(id); _events.emit("Ruta eliminada") }
    }

    fun optimizeRoute() {
        viewModelScope.launch {
            _isOptimizing.value = true
            try {
                val ok = repo.optimizeActiveRoute()
                _events.emit(if (ok) "Ruta reorganizada por días" else "Añade al menos 2 paradas para optimizar")
            } catch (e: Exception) {
                _events.emit("No se pudo optimizar: ${e.message}")
            } finally {
                _isOptimizing.value = false
            }
        }
    }

    suspend fun exportRouteText(): String = repo.exportActiveRouteAsText()
    suspend fun exportRouteJson(): String? = repo.exportActiveRouteAsJson()

    fun importSharedRoute(json: String, sharedBy: String) {
        viewModelScope.launch {
            val r = repo.importRouteFromJson(json, sharedBy)
            _events.emit(if (r != null) "Ruta '${r.name}' importada y activada" else "No se pudo importar la ruta")
        }
    }

    fun importSharedPlace(place: Place) {
        viewModelScope.launch {
            repo.upsertPlace(place.copy(verification = VerificationLevel.SHARED))
            _events.emit("'${place.name}' guardado en tus lugares")
        }
    }

    /** [stops] es la secuencia completa (origen + paradas + llegada) de RouteSchedule.roadSequence. */
    private fun recomputeRoad(stops: List<RouteStop>) {
        roadJob?.cancel()
        if (stops.size < 2) { _roadRoute.value = null; return }
        roadJob = viewModelScope.launch {
            _roadRoute.value = runCatching { repo.roadRouteFor(stops) }.getOrNull()
        }
    }

    /** Descarga el mapa offline del área que cubre la ruta activa (+ margen). */
    fun downloadRouteMap() {
        val route = activeRoute.value ?: return
        val stops = route.stops
        if (stops.isEmpty()) { viewModelScope.launch { _events.emit("Añade paradas a la ruta primero") }; return }
        val pad = 0.08
        val north = stops.maxOf { it.latitude } + pad
        val south = stops.minOf { it.latitude } - pad
        val east = stops.maxOf { it.longitude } + pad
        val west = stops.minOf { it.longitude } - pad
        val mb = offlineMaps.estimateSizeMb(north, south, east, west)
        viewModelScope.launch { _events.emit("Descargando mapa de la ruta (~$mb MB)…") }
        offlineMaps.downloadArea("route_${route.id}", north, south, east, west) { ok ->
            viewModelScope.launch { _events.emit(if (ok) "Mapa de la ruta disponible offline" else "La descarga del mapa falló parcialmente") }
        }
    }

    val routeMapDownload = combine(offlineMaps.downloads, activeRoute) { m, r -> r?.let { m["route_${it.id}"] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ================= Acciones: Radar IA =================

    fun runRadar(request: RadarRequest, forceRefresh: Boolean = false) {
        // El único dato imprescindible es el destino: sin salida la app busca alrededor de él.
        if (request.destinationName.isBlank()) {
            viewModelScope.launch { _events.emit("Escribe a dónde quieres llegar") }
            return
        }
        radarJob?.cancel()
        radarJob = viewModelScope.launch {
            // Los resultados viejos se limpian al empezar: si no, el mapa sigue mostrando
            // pines de la búsqueda anterior mientras la nueva está en curso.
            _radarResults.value = emptyList()
            _radarDetours.value = emptyMap()
            _radarState.value = RadarUiState.Loading(
                if (request.onlyDestination) "Buscando planes alrededor de ${request.destinationName.trim()}…"
                else "Trazando el corredor ${request.originName} → ${request.destinationName}…"
            )
            try {
                val area = repo.radarArea(request)
                if (request.onlyDestination && area == null) {
                    _radarState.value = RadarUiState.Error(
                        "No pude ubicar \"" + request.destinationName + "\". Escríbelo como sale en el mapa " +
                            "(por ejemplo \"Baños de Agua Santa\")."
                    )
                    return@launch
                }
                _searchArea.value = area
                val corridor = if (area != null) emptyList() else repo.radarCorridor(request)
                _radarCorridor.value = corridor
                _radarState.value = RadarUiState.Loading(
                    if (area != null) "Mirando qué hay en ${area.radiusKm.toInt()} km a la redonda…"
                    else "Rastreando TikTok, Instagram, Facebook, YouTube y web…"
                )
                val result = repo.radarDiscover(request, useCache = !forceRefresh, corridor = corridor, area = area) { paso ->
                    _radarState.value = RadarUiState.Loading(paso)
                }
                _radarResults.value = result.places
                _radarDetours.value = result.detourKmByPlace
                _searchArea.value = result.area ?: area
                _radarState.value = if (result.places.isEmpty()) RadarUiState.Error(result.summary.ifBlank { "Sin resultados. Intenta con otros intereses." })
                else RadarUiState.Success(result)
            } catch (e: Exception) {
                _radarState.value = RadarUiState.Error(friendlyError(e))
            }
        }
    }

    // ================= Acciones: Explorador de ruta =================

    /**
     * Barre el camino de verdad: todos los pueblos por los que se pasa y todo lo que hay
     * alrededor de la vía, sacado de OpenStreetMap y del catálogo de la comunidad.
     *
     * Va en dos tiempos a propósito:
     *  1. Primero lo seguro y completo (OSM + catálogo). Sale en segundos, con coordenadas
     *     exactas, y funciona aunque la IA esté caída o sin cuota.
     *  2. Después, si la IA está disponible, se le suma lo que se comenta en redes. Si falla,
     *     no pasa nada: la búsqueda ya está hecha.
     */
    fun exploreRoute(request: RadarRequest, conIa: Boolean = true) {
        if (request.destinationName.isBlank()) {
            viewModelScope.launch { _events.emit("Escribe a dónde quieres llegar") }
            return
        }
        scoutJob?.cancel()
        scoutJob = viewModelScope.launch {
            _scoutTramos.value = emptyList()
            _radarResults.value = emptyList()
            _radarDetours.value = emptyMap()
            _scoutState.value = ScoutUiState.Loading(
                if (request.onlyDestination) "Mirando alrededor de " + request.destinationName.trim() + "…"
                else "Trazando el camino " + request.originName + " → " + request.destinationName + "…"
            )
            // El buscador de Descubrir también muestra el progreso: antes explorar en el mapa
            // se veía como si no pasara nada.
            _radarState.value = RadarUiState.Loading(
                if (request.onlyDestination) "Buscando lugares reales alrededor de " + request.destinationName.trim() + "…"
                else "Explorando pueblos y lugares de la vía…"
            )
            try {
                val area = repo.radarArea(request)
                if (request.onlyDestination && area == null) {
                    val msg = "No pude ubicar \"" + request.destinationName + "\". Escríbelo como sale en el mapa " +
                        "(por ejemplo \"Baños de Agua Santa\")."
                    _scoutState.value = ScoutUiState.Error(msg)
                    _radarState.value = RadarUiState.Error(msg)
                    return@launch
                }
                _searchArea.value = area
                val corridor = if (area != null) emptyList() else repo.radarCorridor(request)
                _radarCorridor.value = corridor
                val exploracion = repo.exploreRoute(request, corridor) { paso ->
                    _scoutState.value = ScoutUiState.Loading(paso)
                }
                _scoutTramos.value = exploracion.tramos
                _radarResults.value = exploracion.lugares
                _radarDetours.value = exploracion.desvioKmPorLugar
                _scoutState.value = if (exploracion.lugares.isEmpty())
                    ScoutUiState.Error(exploracion.problema.ifBlank { "No se encontró nada en este tramo. Prueba a subir el desvío máximo." })
                else ScoutUiState.Success(exploracion)

                // El buscador de Descubrir pinta el mismo resultado que el explorador. Así
                // "solo ver lugares" funciona incluso si la IA no tiene clave o está caída.
                _radarState.value = if (exploracion.lugares.isEmpty())
                    RadarUiState.Error(exploracion.problema.ifBlank { "No se encontró nada en esta zona." })
                else RadarUiState.Success(
                    RadarResult(
                        places = exploracion.lugares,
                        summary = exploracion.resumen,
                        groundingSources = emptyList(),
                        detourKmByPlace = exploracion.desvioKmPorLugar,
                        corridor = corridor,
                        area = area
                    )
                )

                // Segundo tiempo: lo que se comenta en redes, encima de lo que ya está.
                if (conIa && exploracion.lugares.isNotEmpty() && container.gemini.isAvailable) {
                    runCatching {
                        val ia = repo.radarDiscover(request, useCache = true, corridor = corridor, area = area)
                        if (ia.places.isNotEmpty()) {
                            val yaEstan = _radarResults.value.map { it.name.trim().lowercase() }.toSet()
                            val nuevos = ia.places.filter { it.name.trim().lowercase() !in yaEstan }
                            _radarResults.value = _radarResults.value + nuevos
                            _radarDetours.value = _radarDetours.value + ia.detourKmByPlace
                            _scoutState.value = ScoutUiState.Success(
                                exploracion.copy(
                                    lugares = exploracion.lugares + nuevos,
                                    resumen = exploracion.resumen +
                                        (if (nuevos.isEmpty()) "" else " La IA añadió " + nuevos.size + " sitios de los que se habla en redes.")
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = friendlyError(e)
                _scoutState.value = ScoutUiState.Error(msg)
                _radarState.value = RadarUiState.Error(msg)
            }
        }
    }

    fun clearScout() {
        _scoutState.value = ScoutUiState.Idle
        _scoutTramos.value = emptyList()
    }

    /** Usa la ruta activa (origen/destino) para el radar sin volver a escribir. */
    fun radarRequestFromActiveRoute(interests: List<String>, detourKm: Int, freeText: String): RadarRequest? {
        val r = activeRoute.value ?: return null
        val origin = r.originName.ifBlank { r.stops.firstOrNull()?.placeName ?: "" }
        val destination = r.destinationName.ifBlank { r.stops.lastOrNull()?.placeName ?: "" }
        if (origin.isBlank() || destination.isBlank()) return null
        val waypoints = r.stops.map { it.placeName }.filter { it != origin && it != destination }.take(4)
        return RadarRequest(origin, destination, waypoints, interests, detourKm, r.travelStyle, freeText)
    }

    /**
     * Dónde está el viajero ahora, en nombre de ciudad ("Santo Domingo"). Sirve para rellenar
     * solo el campo "Desde" del Radar: la mayoría de las veces uno sale de donde está.
     * Es null mientras no se sepa (sin permiso de ubicación, o sin señal para el reverso).
     */
    private val _currentPlaceName = MutableStateFlow<String?>(null)
    val currentPlaceName: StateFlow<String?> = _currentPlaceName.asStateFlow()

    /** Se llama al abrir el Radar. Silencioso: si no se puede, no molesta al viajero. */
    fun detectCurrentPlace() {
        if (_currentPlaceName.value != null) return
        val app = getApplication<Application>()
        if (!DeviceLocation.hasPermission(app)) return
        viewModelScope.launch {
            val loc = DeviceLocation.lastKnown(app) ?: return@launch
            // Primero el nomenclátor del teléfono: es instantáneo y funciona sin señal.
            val cercano = runCatching { placeIndex.nearestName(loc.latitude, loc.longitude) }.getOrNull()
            if (!cercano.isNullOrBlank()) { _currentPlaceName.value = cercano; return@launch }
            val hit = runCatching { geocoding.reverse(loc.latitude, loc.longitude) }.getOrNull()
            val nombre = hit?.city?.ifBlank { hit.province }.orEmpty()
            if (nombre.isNotBlank()) _currentPlaceName.value = nombre
        }
    }

    // ---------- Diagnóstico de la IA ----------
    private val _aiDiagnosis = MutableStateFlow<String?>(null)
    val aiDiagnosis: StateFlow<String?> = _aiDiagnosis.asStateFlow()

    /** Hace una petición mínima a Gemini y explica exactamente por qué falla (o que va bien). */
    fun diagnoseAi() {
        viewModelScope.launch {
            _aiDiagnosis.value = "Probando la conexión con la IA…"
            _aiDiagnosis.value = runCatching { container.gemini.diagnose() }
                .getOrElse { "No se pudo ejecutar la prueba: " + (it.message ?: "sin detalle") }
        }
    }

    fun clearAiDiagnosis() { _aiDiagnosis.value = null }

    fun clearRadar() {
        _radarState.value = RadarUiState.Idle
        _radarResults.value = emptyList()
        _radarCorridor.value = emptyList()
        // El mapa pinta en la misma capa lo del Radar y lo del explorador de ruta: limpiar
        // una sin la otra dejaba marcadores sueltos que ya no se podían quitar.
        _scoutState.value = ScoutUiState.Idle
        _scoutTramos.value = emptyList()
        _radarDetours.value = emptyMap()
        _searchArea.value = null
    }

    // ================= Catálogo común de la comunidad =================
    //
    // Lo que descubre un viajero (un lugar agregado a mano, una parada del Radar, un sitio
    // sacado de un TikTok) se comparte con todos. Lo personal —perfil, rutas, lugares
    // guardados, fotos propias— nunca sale del teléfono.

    private val catalogSync = container.catalogSync

    private val _catalogBusy = MutableStateFlow(false)
    val catalogBusy: StateFlow<Boolean> = _catalogBusy.asStateFlow()

    private val _catalogPending = MutableStateFlow(0)
    /** Aportes míos que esperan señal para compartirse. */
    val catalogPending: StateFlow<Int> = _catalogPending.asStateFlow()

    fun refreshCatalogPending() {
        viewModelScope.launch { _catalogPending.value = catalogSync.pendingCount() }
    }

    /** Sube mis aportes y baja lo que agregaron los demás. */
    fun syncCatalog() {
        if (_catalogBusy.value) return
        viewModelScope.launch {
            _catalogBusy.value = true
            try {
                val fs = container.community.firestoreOrNull()
                if (fs == null) {
                    _events.emit("Sin conexión con la comunidad. Tus aportes se guardan y se comparten al reconectar.")
                } else {
                    val (subidos, bajados) = catalogSync.sync(fs)
                    _events.emit(
                        when {
                            subidos == 0 && bajados == 0 -> "El catálogo ya estaba al día"
                            subidos > 0 && bajados > 0 -> "Compartí " + subidos + " lugar(es) y llegaron " + bajados
                            subidos > 0 -> "Compartí " + subidos + " lugar(es) con la comunidad"
                            else -> "Llegaron " + bajados + " lugar(es) nuevos de otros viajeros"
                        }
                    )
                }
                _catalogPending.value = catalogSync.pendingCount()
            } finally {
                _catalogBusy.value = false
            }
        }
    }

    // ================= Armar la ruta completa con IA =================
    //
    // Es el mismo motor del Radar (Gemini + búsqueda en Google, que es lo que indexa TikTok,
    // Instagram, Facebook y YouTube), pero en vez de una lista suelta devuelve un ITINERARIO
    // por días que se puede editar y recién después se guarda.

    private val planner = container.routePlanner

    private val _planState = MutableStateFlow<RoutePlanUiState>(RoutePlanUiState.Idle)
    val planState: StateFlow<RoutePlanUiState> = _planState.asStateFlow()

    private val _routeDraft = MutableStateFlow<RouteDraft?>(null)
    val routeDraft: StateFlow<RouteDraft?> = _routeDraft.asStateFlow()

    /**
     * "Abre Descubrir en la pestaña de planear". Lo piden la portada, el mapa y Mi Ruta cuando
     * el viajero toca "armar con IA": sin esto aterrizaba en el catálogo y parecía que el botón
     * no había hecho nada.
     */
    private val _openPlanTab = MutableStateFlow(false)
    val openPlanTab: StateFlow<Boolean> = _openPlanTab.asStateFlow()

    fun requestPlanTab() { _openPlanTab.value = true }
    fun consumePlanTab() { _openPlanTab.value = false }

    private var planJob: Job? = null

    /** Arranca el armado. El borrador aparece solo cuando está listo; nada se guarda todavía. */
    fun planRouteWithAi(request: RadarRequest, days: Int, travelers: Int, transport: TransportMode) {
        if (request.destinationName.isBlank()) {
            viewModelScope.launch { _events.emit("Escribe a dónde quieres llegar") }
            return
        }
        planJob?.cancel()
        // El borrador anterior se descarta en cuanto se pide uno nuevo: si no, el viajero ve
        // la ruta vieja encima del progreso de la nueva y no sabe cuál está mirando.
        _routeDraft.value = null
        _searchArea.value = null
        planJob = viewModelScope.launch {
            _planState.value = RoutePlanUiState.Loading(
                if (request.onlyDestination) "Armando una escapada alrededor de ${request.destinationName.trim()}…"
                else "Preparando tu ruta…"
            )
            try {
                val draft = planner.plan(request, days, travelers, transport) { paso ->
                    _planState.value = RoutePlanUiState.Loading(paso)
                }
                _routeDraft.value = draft
                // El mapa aprovecha el mismo corredor y las mismas paradas.
                _radarCorridor.value = draft.corridor
                _searchArea.value = draft.area
                _radarResults.value = (draft.stops.map { it.place } + draft.nearby.map { it.place }).distinctBy { it.id }
                _planState.value = RoutePlanUiState.Ready(draft)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                _planState.value = RoutePlanUiState.Error(
                    if (msg.startsWith("No pude ubicar")) msg else friendlyError(e)
                )
            }
        }
    }

    private fun editarBorrador(cambio: (RouteDraft) -> RouteDraft) {
        val actual = _routeDraft.value ?: return
        val nuevo = cambio(actual)
        _routeDraft.value = nuevo
        _planState.value = RoutePlanUiState.Ready(nuevo)
    }

    fun removeDraftStop(stopId: String) = editarBorrador { RouteDraftMath.quitar(it, stopId) }
    fun moveDraftStop(stopId: String, arriba: Boolean) = editarBorrador { RouteDraftMath.mover(it, stopId, arriba) }
    fun setDraftStopDay(stopId: String, dia: Int) = editarBorrador { RouteDraftMath.cambiarDia(it, stopId, dia) }
    fun addNearbyToDraft(stopId: String, dia: Int? = null) = editarBorrador { RouteDraftMath.agregarCercano(it, stopId, dia) }
    fun setDraftDays(dias: Int) = editarBorrador { RouteDraftMath.cambiarDias(it, dias) }
    fun setDraftTransport(modo: TransportMode) = editarBorrador { RouteDraftMath.cambiarTransporte(it, modo) }
    fun setDraftName(nombre: String) = editarBorrador { it.copy(name = nombre) }
    fun setDraftTravelers(viajeros: Int) = editarBorrador { it.copy(travelers = viajeros.coerceIn(1, 20)) }
    fun setDraftStartTime(hora: String) = editarBorrador { it.copy(dailyStartTime = hora) }

    /** Recién aquí se crea la ruta de verdad, se activa y se guardan sus lugares. */
    fun saveDraftAsRoute() {
        val draft = _routeDraft.value ?: return
        viewModelScope.launch {
            val ruta = runCatching { planner.guardar(draft) }.getOrNull()
            if (ruta == null) {
                _events.emit("No se pudo guardar la ruta. Inténtalo otra vez.")
                return@launch
            }
            _routeDraft.value = null
            _planState.value = RoutePlanUiState.Idle
            // El viaje recién guardado pasa a ser la respuesta de la portada, y el círculo de
            // búsqueda se limpia: ya no estamos explorando, ahora hay una ruta real.
            updateTripIntent(
                TripIntent(
                    // Si la ruta nació sin salida (escapada alrededor de una base), la portada
                    // sigue en modo "solo destino"; si no, el próximo plan sería "base → base"
                    // y el planificador lo rechazaría por ser ida y vuelta sin intermedios.
                    origin = if (draft.originName.isBlank()) "" else ruta.originName,
                    destination = ruta.destinationName,
                    days = ruta.totalDays, travelers = ruta.travelers
                )
            )
            _searchArea.value = null
            // Se cuentan las visitas, no los extremos.
            _events.emit("Ruta \"" + ruta.name + "\" guardada con " + draft.discoveredCount + " paradas")
        }
    }

    fun discardDraft() {
        planJob?.cancel()
        _routeDraft.value = null
        _planState.value = RoutePlanUiState.Idle
    }

    // ================= Comentarios y tips de un lugar =================

    private val _placeComments = MutableStateFlow<List<PlaceComment>>(emptyList())
    val placeComments: StateFlow<List<PlaceComment>> = _placeComments.asStateFlow()

    private var commentsJob: Job? = null
    private var commentsPlaceId: String? = null

    /** Se llama al abrir la ficha de un lugar: carga y escucha sus comentarios en vivo. */
    fun openPlaceComments(placeId: String) {
        if (commentsPlaceId == placeId) return
        closePlaceComments()
        if (placeId.isBlank()) return
        commentsPlaceId = placeId
        community.listenComments(placeId)
        commentsJob = viewModelScope.launch {
            community.comments(placeId).collect { _placeComments.value = it }
        }
    }

    /** Cierra la escucha del lugar anterior: si no, cada ficha abierta deja un listener vivo. */
    fun closePlaceComments() {
        commentsJob?.cancel()
        commentsJob = null
        commentsPlaceId?.let { community.stopListeningComments(it) }
        commentsPlaceId = null
        _placeComments.value = emptyList()
    }

    /** Cierra listeners de Firestore y cancela jobs que el scope del VM no conoce. */
    override fun onCleared() {
        closePlaceComments()
        radarJob?.cancel()
        scoutJob?.cancel()
        pinJob?.cancel()
        planJob?.cancel()
        linkJob?.cancel()
        roadJob?.cancel()
        addressJob?.cancel()
        super.onCleared()
    }

    fun addPlaceComment(placeId: String, text: String, category: CommentCategory, photoUri: Uri?) {
        viewModelScope.launch {
            try {
                community.addComment(placeId, text, category, photoUri)
            } catch (e: Exception) {
                _events.emit(e.message ?: "No se pudo publicar el comentario")
            }
        }
    }

    // ================= Agregar un lugar nuevo (aporte del viajero) =================

    data class NewPlaceDraft(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val hasCoords: Boolean = false,
        val province: String = "",
        val city: String = "",
        val resolvingLocation: Boolean = false
    )

    private val _newPlaceDraft = MutableStateFlow(NewPlaceDraft())
    val newPlaceDraft: StateFlow<NewPlaceDraft> = _newPlaceDraft.asStateFlow()

    // ======================================================================
    //  Lector de enlaces: pegar un TikTok / Reel / video de Facebook o YouTube
    // ======================================================================

    /** Estado de la pestaña "Enlace" de Descubrir. */
    sealed class LinkUi {
        object Idle : LinkUi()
        data class Loading(val message: String) : LinkUi()
        data class Success(val place: Place) : LinkUi()
        /** Se leyó la publicación pero no se ubicó el lugar: se le pide una pista al viajero. */
        data class NeedsHint(
            val motivo: String,
            val titulo: String,
            val autor: String,
            val plataforma: String,
            val miniatura: String
        ) : LinkUi()
        data class Error(val message: String) : LinkUi()
    }

    private val linkExtractor: LinkExtractorService = container.linkExtractor

    private val _linkUrl = MutableStateFlow("")
    val linkUrl: StateFlow<String> = _linkUrl.asStateFlow()

    private val _linkHint = MutableStateFlow("")
    val linkHint: StateFlow<String> = _linkHint.asStateFlow()

    private val _linkState = MutableStateFlow<LinkUi>(LinkUi.Idle)
    val linkState: StateFlow<LinkUi> = _linkState.asStateFlow()

    private var linkJob: Job? = null

    fun onLinkUrlChanged(v: String) { _linkUrl.value = v }
    fun onLinkHintChanged(v: String) { _linkHint.value = v }

    fun resetLink() {
        linkJob?.cancel()
        _linkUrl.value = ""
        _linkHint.value = ""
        _linkState.value = LinkUi.Idle
    }

    /**
     * Analiza el enlace pegado. [hint] es la pista que escribe el viajero cuando el primer
     * intento no identificó el lugar ("es la laguna del Quilotoa").
     */
    fun analyzeLink(hint: String = "") {
        val texto = _linkUrl.value.trim()
        if (texto.isBlank()) {
            viewModelScope.launch { _events.emit("Pega el enlace del TikTok, Reel o video primero") }
            return
        }
        if (!hasGeminiKey) {
            _linkState.value = LinkUi.Error("Falta la clave de Gemini: el análisis de enlaces necesita internet e IA.")
            return
        }
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            _linkState.value = LinkUi.Loading("Abriendo la publicación…")
            try {
                val (place, _) = linkExtractor.extractPlace(texto, hint.trim()) { msg ->
                    _linkState.value = LinkUi.Loading(msg)
                }
                // Lo que uno saca de un video le sirve a todos: entra al catálogo común.
                repo.upsertPlace(place, share = true)
                _linkState.value = LinkUi.Success(place)
                _events.emit("'" + place.name + "' añadido al catálogo")
            } catch (e: LinkExtractorService.NeedsHint) {
                _linkState.value = LinkUi.NeedsHint(
                    motivo = e.motivo,
                    titulo = e.meta.title,
                    autor = e.meta.author,
                    plataforma = e.meta.platform.displayName,
                    miniatura = e.meta.thumbnail
                )
            } catch (e: Exception) {
                _linkState.value = LinkUi.Error(
                    when {
                        e.message?.contains("GEMINI_API_KEY") == true -> "Configura GEMINI_API_KEY para analizar enlaces."
                        e.message?.contains("Unable to resolve host") == true -> "Sin conexión a internet."
                        else -> e.message ?: "No se pudo analizar el enlace."
                    }
                )
            }
        }
    }

    private val _showAddPlace = MutableStateFlow(false)
    val showAddPlace: StateFlow<Boolean> = _showAddPlace.asStateFlow()

    /** Abre el formulario de "agregar lugar nuevo". Si ya tienes coordenadas (mapa), se pasan de una vez. */
    fun openAddPlace(latitude: Double? = null, longitude: Double? = null) {
        if (latitude != null && longitude != null) {
            resolveNewPlaceLocation(latitude, longitude)
        } else {
            _newPlaceDraft.value = NewPlaceDraft()
        }
        _showAddPlace.value = true
    }

    fun closeAddPlace() {
        _showAddPlace.value = false
    }

    /** La forma más fácil de obtener coordenadas si ya estás en el lugar: tu GPS real. */
    fun useMyLocationForNewPlace() {
        val app = getApplication<Application>()
        if (!DeviceLocation.hasPermission(app)) {
            viewModelScope.launch { _events.emit("Concede el permiso de ubicación para usar tu GPS") }
            return
        }
        _newPlaceDraft.value = _newPlaceDraft.value.copy(resolvingLocation = true)
        viewModelScope.launch {
            val loc = runCatching { DeviceLocation.current(app) }.getOrNull() ?: DeviceLocation.lastKnown(app)
            if (loc == null) {
                _events.emit("No se pudo obtener tu ubicación. Activa el GPS e inténtalo de nuevo.")
                _newPlaceDraft.value = _newPlaceDraft.value.copy(resolvingLocation = false)
            } else {
                resolveNewPlaceLocation(loc.latitude, loc.longitude)
            }
        }
    }

    /** Cuando el usuario elige un resultado del buscador dentro del formulario de lugar nuevo. */
    fun setNewPlaceFromSuggestion(suggestion: PlaceSuggestion) {
        _newPlaceDraft.value = _newPlaceDraft.value.copy(resolvingLocation = true)
        viewModelScope.launch {
            val coords = runCatching { placeIndex.resolveCoordinates(suggestion) }.getOrNull()
            if (coords == null) {
                _events.emit("No se encontraron las coordenadas de " + suggestion.name)
                _newPlaceDraft.value = _newPlaceDraft.value.copy(resolvingLocation = false)
            } else {
                resolveNewPlaceLocation(coords.first, coords.second)
            }
        }
    }

    private fun resolveNewPlaceLocation(latitude: Double, longitude: Double) {
        _newPlaceDraft.value = _newPlaceDraft.value.copy(
            latitude = latitude, longitude = longitude, hasCoords = true, resolvingLocation = true
        )
        viewModelScope.launch {
            val r = runCatching { geocoding.reverse(latitude, longitude) }.getOrNull()
            val province = r?.province?.takeIf { it.isNotBlank() }
                ?: placeIndex.nearestProvince(latitude, longitude).orEmpty()
            val city = r?.city.orEmpty()
            if (_newPlaceDraft.value.latitude == latitude && _newPlaceDraft.value.longitude == longitude) {
                _newPlaceDraft.value = _newPlaceDraft.value.copy(province = province, city = city, resolvingLocation = false)
            }
        }
    }

    /** Cantones conocidos de la provincia detectada, offline, por si la geocodificación no acertó del todo. */
    fun cantonsForNewPlaceProvince(province: String): List<String> = placeIndex.cantonsOf(province)

    /**
     * Guarda el lugar nuevo que aporta el viajero: nombre, categoría, coordenadas (ya resueltas
     * en el borrador), provincia/cantón y fotos propias. Si no está seguro de la ubicación exacta,
     * queda marcado para que otros viajeros ayuden a confirmarla con un comentario.
     */
    fun submitNewPlace(
        name: String,
        category: PlaceCategory,
        province: String,
        city: String,
        description: String,
        photoUris: List<Uri>,
        locationApproximate: Boolean
    ) {
        val draft = _newPlaceDraft.value
        val limpio = name.trim()
        if (limpio.isBlank()) {
            viewModelScope.launch { _events.emit("Ponle un nombre al lugar") }
            return
        }
        if (!draft.hasCoords) {
            viewModelScope.launch { _events.emit("Indica dónde está: usa tu GPS, búscalo o márcalo en el mapa") }
            return
        }
        // El catálogo es solo de Ecuador: un GPS con mala señal o un punto marcado fuera del país
        // metería un lugar imposible al mapa y a las rutas de todos.
        if (!PlaceJsonParser.isInEcuador(draft.latitude, draft.longitude)) {
            viewModelScope.launch { _events.emit("Ese punto está fuera de Ecuador. Revisa la ubicación antes de guardar.") }
            return
        }
        viewModelScope.launch {
            val fotos = photoUris.mapNotNull { uri ->
                runCatching { PhotoStore.save(getApplication(), uri, "place") }.getOrNull()
            }
            val place = Place(
                name = limpio,
                category = category,
                region = EcuadorRegion.fromCoordinates(draft.latitude, draft.longitude),
                province = province.trim(),
                city = city.trim(),
                latitude = draft.latitude,
                longitude = draft.longitude,
                shortDescription = description.trim(),
                localPhotoPaths = fotos.map { it.localPath },
                verification = VerificationLevel.USER,
                locationApproximate = locationApproximate
            )
            // El aporte del viajero se comparte con el resto de la comunidad.
            repo.upsertPlace(place, share = true)
            runCatching {
                placeIndex.learnAddress(
                    limpio, listOf(city, province).filter { it.isNotBlank() }.joinToString(", "),
                    draft.latitude, draft.longitude
                )
            }
            _showAddPlace.value = false
            _newPlaceDraft.value = NewPlaceDraft()
            _events.emit("'$limpio' se añadió al catálogo. ¡Gracias por aportar!")
            selectPlace(place)
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            "GEMINI_API_KEY" in msg -> "Configura GEMINI_API_KEY en el archivo .env para activar el Radar IA."
            "HTTP 429" in msg -> "Cuota de la IA agotada por ahora. Intenta en unos minutos."
            "API_KEY_INVALID" in msg -> "La clave de Gemini no es válida. Genera una nueva en aistudio.google.com/apikey (empieza por \"AIza\") y ponla en el archivo .env."
            "HTTP 403" in msg -> "Acceso denegado: la clave existe pero no tiene habilitada la API de Gemini, o está restringida. Revísala en Google AI Studio."
            "HTTP 404" in msg -> "El modelo de IA no está disponible para tu clave. Prueba a generar una clave nueva en aistudio.google.com/apikey."
            "HTTP 400" in msg -> "La API de Gemini rechazó la petición: ${msg.substringAfter("HTTP 400:").take(140)}"
            "Unable to resolve host" in msg || "timeout" in msg.lowercase() -> "Sin conexión. El Radar necesita internet; tus lugares guardados siguen disponibles offline."
            else -> "No se pudo completar el radar: ${msg.take(120)}"
        }
    }
}

sealed class RadarUiState {
    object Idle : RadarUiState()
    data class Loading(val message: String) : RadarUiState()
    data class Success(val result: RadarResult) : RadarUiState()
    data class Error(val message: String) : RadarUiState()
}

/** Estado del explorador de ruta (el que barre el camino pueblo por pueblo). */
sealed class ScoutUiState {
    object Idle : ScoutUiState()
    data class Loading(val message: String) : ScoutUiState()
    data class Success(val exploracion: RouteScoutService.Exploracion) : ScoutUiState()
    data class Error(val message: String) : ScoutUiState()
}

/** Resultado del buscador del mapa: un lugar del catálogo o una dirección de OpenStreetMap. */
data class MapSearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    /** Si viene del catálogo de la app, el id del lugar; null si es una dirección. */
    val placeId: String? = null,
    /** Id dentro del índice local, para resolver sus coordenadas si aún no las tiene. */
    val gazetteerId: String? = null
)

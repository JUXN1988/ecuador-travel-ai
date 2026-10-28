package ec.travesias.app.data.social

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import ec.travesias.app.data.local.AppDatabase
import ec.travesias.app.data.local.CatalogOutboxEntity
import ec.travesias.app.data.local.toDomain
import ec.travesias.app.data.local.toEntity
import ec.travesias.app.data.model.Place
import ec.travesias.app.data.model.VerificationLevel
import ec.travesias.app.data.remote.PlaceJsonParser
import ec.travesias.app.data.repository.JsonCodecPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EL CATÁLOGO COMÚN: lo que descubre un viajero le sirve a todos.
 *
 * Hasta ahora, un lugar agregado a mano, encontrado por el Radar o sacado de un TikTok se
 * quedaba en ese teléfono. Aquí se comparte: cada aporte sube a una colección `catalog` y
 * cada app se trae lo nuevo. Si alguien pierde el teléfono, el catálogo no se pierde.
 *
 * QUÉ SE COMPARTE: el lugar en sí — nombre, categoría, coordenadas, provincia y cantón,
 * descripción, costos, horario, cómo llegar, contactos del negocio, etiquetas, consejos,
 * fuentes y fotos de internet.
 *
 * QUÉ NO SALE NUNCA DEL TELÉFONO: nada personal. Ni el perfil, ni las rutas, ni los días
 * planificados, ni las notas, ni qué lugares tiene guardados uno, ni las fotos que uno tomó
 * (esas viven en el teléfono; solo se comparten las que ya venían de internet). Eso se
 * limpia en [paraCompartir] antes de subir y se respeta al recibir.
 *
 * CÓMO EVITA GASTAR CUOTA: solo se piden los lugares con `updatedAt` posterior a la última
 * sincronización, en páginas de 200. Un teléfono al día no lee casi nada.
 */
class CatalogSync(
    context: Context,
    private val db: AppDatabase
) {

    private val placeDao = db.placeDao()
    private val outbox = db.catalogOutboxDao()
    private val prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    private val _lastResult = MutableStateFlow<String>("")
    /** Último resumen de sincronización, para poder mostrarlo si hace falta. */
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    companion object {
        const val COL = "catalog"
        private const val ARCHIVO = "travesias_catalogo"
        private const val K_ULTIMA = "ultima_sync"
        private const val PAGINA = 200L

        /** Formato del documento. Si algún día cambia, se sube este número. */
        const val VERSION = 1
    }

    /** Quién sube (la huella de la cuenta), solo para saber de dónde vino el aporte. */
    var authorProvider: () -> String = { "" }

    // ======================================================================
    //  Subir
    // ======================================================================

    /** Apunta un lugar para compartirlo en cuanto haya señal. */
    suspend fun queue(place: Place) {
        if (!esCompartible(place)) return
        outbox.queue(CatalogOutboxEntity(place.id, System.currentTimeMillis()))
    }

    suspend fun queueAll(places: List<Place>) {
        places.filter { esCompartible(it) }.forEach {
            outbox.queue(CatalogOutboxEntity(it.id, System.currentTimeMillis()))
        }
    }

    /**
     * Un lugar se comparte si es un sitio real de Ecuador con nombre. Lo que agrega un
     * compañero por un enlace privado o lo que no tiene coordenadas, no.
     */
    private fun esCompartible(p: Place): Boolean =
        p.name.trim().length >= 3 && PlaceJsonParser.isInEcuador(p.latitude, p.longitude)

    /** Sube lo que esté en la cola. Se llama al conectar. */
    suspend fun push(firestore: FirebaseFirestore?): Int = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext 0
        val pendientes = outbox.pending(40)
        if (pendientes.isEmpty()) return@withContext 0

        val subidos = ArrayList<String>()
        for (fila in pendientes) {
            val place = placeDao.getById(fila.placeId)?.toDomain()
            if (place == null || !esCompartible(place)) {
                subidos.add(fila.placeId)   // ya no existe o no se comparte: fuera de la cola
                continue
            }

            val ok = withTimeoutOrNull(20_000L) {
                runCatching {
                    fs.collection(COL).document(place.id).set(aDocumento(place)).await()
                }.isSuccess
            }
            if (ok == true) subidos.add(fila.placeId)
        }
        if (subidos.isNotEmpty()) outbox.clear(subidos)
        subidos.size
    }

    private fun aDocumento(place: Place): Map<String, Any> = mapOf(
        "v" to VERSION,
        "p" to JsonCodecPlace.encode(paraCompartir(place)),
        "name" to place.name,
        "lat" to place.latitude,
        "lng" to place.longitude,
        "updatedAt" to System.currentTimeMillis(),
        "by" to authorProvider()
    )

    /** Quita del lugar TODO lo que es personal antes de que salga del teléfono. */
    private fun paraCompartir(p: Place): Place = p.copy(
        isSaved = false,             // que yo lo tenga guardado no le importa a nadie
        localPhotoPaths = emptyList() // mis fotos se quedan en mi teléfono
    )

    // ======================================================================
    //  Bajar
    // ======================================================================

    /**
     * Trae los lugares nuevos o corregidos desde la última vez. Devuelve cuántos entraron.
     */
    suspend fun pull(firestore: FirebaseFirestore?): Int = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext 0
        var desde = prefs.getLong(K_ULTIMA, 0L)
        var total = 0
        var vueltas = 0

        while (vueltas < 5) {
            vueltas++
            val snap = withTimeoutOrNull(25_000L) {
                runCatching {
                    fs.collection(COL)
                        .whereGreaterThan("updatedAt", desde)
                        .orderBy("updatedAt", Query.Direction.ASCENDING)
                        .limit(PAGINA)
                        .get().await()
                }.getOrNull()
            } ?: break

            val docs = snap.documents
            if (docs.isEmpty()) break

            val entrantes = docs.mapNotNull { doc ->
                val json = doc.getString("p") ?: return@mapNotNull null
                val place = JsonCodecPlace.decode(json) ?: return@mapNotNull null
                if (!esCompartible(place)) null else place
            }
            entrantes.forEach { remoto -> fusionar(remoto) }
            total += entrantes.size

            desde = docs.mapNotNull { it.getLong("updatedAt") }.maxOrNull() ?: desde
            prefs.edit().putLong(K_ULTIMA, desde).apply()
            if (docs.size < PAGINA) break
        }
        _lastResult.value = if (total > 0) "Se actualizaron " + total + " lugares del catálogo" else ""
        total
    }

    /**
     * Junta lo que llega con lo que ya hay, SIN pisar lo mío:
     *  - lo que yo tengo guardado sigue guardado;
     *  - mis fotos siguen siendo mías;
     *  - un lugar curado por el equipo no lo degrada nadie;
     *  - los campos vacíos se rellenan y las listas se suman sin repetir.
     */
    private suspend fun fusionar(remoto: Place) {
        val local = placeDao.getById(remoto.id)?.toDomain()
        if (local == null) {
            placeDao.upsert(remoto.copy(isSaved = false, localPhotoPaths = emptyList()).toEntity())
            return
        }
        val curado = local.verification == VerificationLevel.CURATED
        val fusion = local.copy(
            name = if (curado) local.name else local.name.ifBlank { remoto.name },
            category = if (curado) local.category else local.category,
            province = local.province.ifBlank { remoto.province },
            city = local.city.ifBlank { remoto.city },
            address = local.address.ifBlank { remoto.address },
            latitude = if (!PlaceJsonParser.isInEcuador(local.latitude, local.longitude) &&
                PlaceJsonParser.isInEcuador(remoto.latitude, remoto.longitude)
            ) remoto.latitude else local.latitude,
            longitude = if (!PlaceJsonParser.isInEcuador(local.latitude, local.longitude) &&
                PlaceJsonParser.isInEcuador(remoto.latitude, remoto.longitude)
            ) remoto.longitude else local.longitude,
            shortDescription = local.shortDescription.ifBlank { remoto.shortDescription },
            fullDescription = local.fullDescription.ifBlank { remoto.fullDescription },
            costMinUsd = if (local.costMinUsd == 0.0 && local.costMaxUsd == 0.0) remoto.costMinUsd else local.costMinUsd,
            costMaxUsd = if (local.costMinUsd == 0.0 && local.costMaxUsd == 0.0) remoto.costMaxUsd else local.costMaxUsd,
            costNotes = local.costNotes.ifBlank { remoto.costNotes },
            openingHours = local.openingHours.ifBlank { remoto.openingHours },
            howToGetThere = local.howToGetThere.ifBlank { remoto.howToGetThere },
            transportOptions = (local.transportOptions + remoto.transportOptions).distinct(),
            altitudeMeters = local.altitudeMeters ?: remoto.altitudeMeters,
            rating = local.rating ?: remoto.rating,
            ratingCount = local.ratingCount ?: remoto.ratingCount,
            imageUrls = (local.imageUrls + remoto.imageUrls).distinct().take(8),
            tags = (local.tags + remoto.tags).distinct().take(10),
            localTips = (local.localTips + remoto.localTips).distinct().take(8),
            contact = local.contact.let { c ->
                if (!c.isEmpty()) c else remoto.contact
            },
            sources = (local.sources + remoto.sources).distinctBy { it.url }.take(12),
            socialMentions = maxOf(local.socialMentions, remoto.socialMentions),
            googlePlaceId = local.googlePlaceId.ifBlank { remoto.googlePlaceId },
            verification = mejorVerificacion(local.verification, remoto.verification),
            // Lo mío se queda como está:
            isSaved = local.isSaved,
            localPhotoPaths = local.localPhotoPaths,
            createdAt = local.createdAt
        )
        if (fusion != local) placeDao.upsert(fusion.toEntity())
    }

    private fun mejorVerificacion(a: VerificationLevel, b: VerificationLevel): VerificationLevel {
        val orden = listOf(
            // De menos a más fiable. OSM va abajo: las coordenadas son exactas, pero la ficha
            // la escribió el mapa, no un viajero.
            VerificationLevel.OSM,
            VerificationLevel.AI_SUGGESTED,
            VerificationLevel.SHARED,
            VerificationLevel.USER,
            VerificationLevel.VERIFIED,
            VerificationLevel.CURATED
        )
        return if (orden.indexOf(a) >= orden.indexOf(b)) a else b
    }

    // ======================================================================
    //  Todo junto
    // ======================================================================

    /** Sube lo pendiente y baja lo nuevo. Devuelve (subidos, bajados). */
    suspend fun sync(firestore: FirebaseFirestore?): Pair<Int, Int> {
        val subidos = runCatching { push(firestore) }.getOrDefault(0)
        val bajados = runCatching { pull(firestore) }.getOrDefault(0)
        return subidos to bajados
    }

    /** Cuántos aportes esperan señal para compartirse. */
    suspend fun pendingCount(): Int = runCatching { outbox.count() }.getOrDefault(0)

    /** Vuelve a traerse el catálogo entero (por ejemplo tras reinstalar). */
    fun forgetSyncPoint() {
        prefs.edit().putLong(K_ULTIMA, 0L).apply()
    }
}

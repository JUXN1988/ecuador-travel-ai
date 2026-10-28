package ec.travesias.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY createdAt DESC")
    fun getAllPlaces(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE isSaved = 1 ORDER BY createdAt DESC")
    fun getSavedPlaces(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PlaceEntity>

    @Query(
        "SELECT * FROM places WHERE latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east"
    )
    suspend fun getInBounds(south: Double, north: Double, west: Double, east: Double): List<PlaceEntity>

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(places: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity)

    @Query("UPDATE places SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY createdAt DESC")
    fun getAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    fun getActiveRoute(): Flow<RouteEntity?>

    @Query("SELECT * FROM routes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    @Query("UPDATE routes SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE routes SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun setActive(id: String) {
        deactivateAll()
        activate(id)
    }
}

@Dao
interface RouteStopDao {
    @Query("SELECT * FROM route_stops WHERE routeId = :routeId ORDER BY dayNumber ASC, orderInDay ASC")
    fun getStopsForRoute(routeId: String): Flow<List<RouteStopEntity>>

    @Query("SELECT * FROM route_stops WHERE routeId = :routeId ORDER BY dayNumber ASC, orderInDay ASC")
    suspend fun getStopsForRouteOnce(routeId: String): List<RouteStopEntity>

    @Query("SELECT * FROM route_stops ORDER BY dayNumber ASC, orderInDay ASC")
    fun getAllStops(): Flow<List<RouteStopEntity>>

    @Query("SELECT COUNT(*) FROM route_stops WHERE routeId = :routeId AND placeId = :placeId")
    suspend fun countPlaceInRoute(routeId: String, placeId: String): Int

    @Query("SELECT COALESCE(MAX(orderInDay), -1) + 1 FROM route_stops WHERE routeId = :routeId AND dayNumber = :day")
    suspend fun nextOrderForDay(routeId: String, day: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stop: RouteStopEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stops: List<RouteStopEntity>)

    @Query("DELETE FROM route_stops WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM route_stops WHERE routeId = :routeId")
    suspend fun deleteForRoute(routeId: String)
}

@Dao
interface RadarCacheDao {
    @Query("SELECT * FROM radar_cache WHERE queryKey = :key LIMIT 1")
    suspend fun get(key: String): RadarCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: RadarCacheEntity)

    @Query("DELETE FROM radar_cache WHERE createdAt < :olderThan")
    suspend fun purge(olderThan: Long)
}

// ---------- DAOs heredados ----------

@Dao
interface ItineraryDao {
    @Query("SELECT * FROM saved_itineraries ORDER BY createdAtTimestamp DESC")
    fun getAllItineraries(): Flow<List<SavedItineraryEntity>>

    @Query("SELECT * FROM saved_itineraries WHERE id = :id LIMIT 1")
    suspend fun getItineraryById(id: String): SavedItineraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItinerary(itinerary: SavedItineraryEntity)

    @Query("DELETE FROM saved_itineraries WHERE id = :id")
    suspend fun deleteItineraryById(id: String)
}

@Dao
interface OfflinePackageDao {
    @Query("SELECT * FROM offline_packages")
    fun getAllPackages(): Flow<List<OfflinePackageEntity>>

    @Query("SELECT * FROM offline_packages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OfflinePackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packages: List<OfflinePackageEntity>)

    @Update
    suspend fun updatePackage(pkg: OfflinePackageEntity)

    @Query("UPDATE offline_packages SET isDownloaded = :downloaded, downloadProgress = :progress, lastUpdated = :lastUpdated WHERE id = :id")
    suspend fun setDownloadStatus(id: String, downloaded: Boolean, progress: Float, lastUpdated: String)
}

@Dao
interface PackingDao {
    @Query("SELECT * FROM packing_checklist ORDER BY id ASC")
    fun getAllItems(): Flow<List<PackingChecklistEntity>>

    @Query("SELECT COUNT(*) FROM packing_checklist")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PackingChecklistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: PackingChecklistEntity)

    @Update
    suspend fun updateItem(item: PackingChecklistEntity)

    @Delete
    suspend fun deleteItem(item: PackingChecklistEntity)

    @Query("DELETE FROM packing_checklist")
    suspend fun clearAll()
}


/** Cola de lugares pendientes de compartir con la comunidad. */
@Dao
interface CatalogOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun queue(item: CatalogOutboxEntity)

    @Query("SELECT * FROM catalog_outbox ORDER BY queuedAt LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<CatalogOutboxEntity>

    @Query("DELETE FROM catalog_outbox WHERE placeId IN (:ids)")
    suspend fun clear(ids: List<String>)

    @Query("SELECT COUNT(*) FROM catalog_outbox")
    suspend fun count(): Int
}

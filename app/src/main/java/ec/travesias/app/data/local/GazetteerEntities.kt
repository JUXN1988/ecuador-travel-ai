package ec.travesias.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Índice de nombres buscables: provincias, cantones, localidades turísticas y los lugares
 * del catálogo. Vive en el teléfono, así que el autocompletado funciona sin internet, y
 * crece solo cada vez que alguien busca una dirección nueva.
 */
@Entity(tableName = "gazetteer", indices = [Index("normalized"), Index("usageCount")])
data class GazetteerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Nombre en minúsculas y sin tildes, para que "banos" encuentre "Baños". */
    val normalized: String,
    val kind: String,
    val parent: String,
    val latitude: Double,
    val longitude: Double,
    /** Id del lugar del catálogo, si lo es. */
    val placeId: String,
    val usageCount: Int,
    val updatedAt: Long
)

@Dao
interface GazetteerDao {

    @Query("SELECT COUNT(*) FROM gazetteer")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<GazetteerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: GazetteerEntity)

    @Query("SELECT * FROM gazetteer WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GazetteerEntity?

    /** Los que empiezan por lo escrito: son las mejores coincidencias. */
    @Query(
        "SELECT * FROM gazetteer WHERE normalized LIKE :prefix || '%' " +
            "ORDER BY usageCount DESC, LENGTH(name) ASC LIMIT :limit"
    )
    suspend fun startsWith(prefix: String, limit: Int): List<GazetteerEntity>

    /** Los que contienen lo escrito en cualquier posición. */
    @Query(
        "SELECT * FROM gazetteer WHERE normalized LIKE '%' || :term || '%' " +
            "ORDER BY usageCount DESC, LENGTH(name) ASC LIMIT :limit"
    )
    suspend fun contains(term: String, limit: Int): List<GazetteerEntity>

    @Query("UPDATE gazetteer SET usageCount = usageCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun bump(id: String, now: Long)

    @Query("UPDATE gazetteer SET latitude = :lat, longitude = :lng, updatedAt = :now WHERE id = :id")
    suspend fun setCoordinates(id: String, lat: Double, lng: Double, now: Long)

    @Query("DELETE FROM gazetteer WHERE kind = :kind")
    suspend fun deleteKind(kind: String)
}

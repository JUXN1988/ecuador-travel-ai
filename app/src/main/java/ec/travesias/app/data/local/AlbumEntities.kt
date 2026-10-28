package ec.travesias.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import ec.travesias.app.data.social.AlbumPhoto
import kotlinx.coroutines.flow.Flow

/**
 * Álbum de fotos de viaje, guardado en el teléfono.
 *
 * Una fila por foto. Lo único que se replica a Firebase es la miniatura, el lugar y las
 * menciones: la foto original nunca sale de aquí.
 */
@Entity(tableName = "album_photos", indices = [Index("ownerFingerprint"), Index("pendingSync")])
data class AlbumPhotoEntity(
    @PrimaryKey val id: String,
    val ownerFingerprint: String,
    val photoPath: String,
    val photoThumb: String,
    val caption: String,
    val placeName: String,
    val placeId: String,
    val taggedUsernamesJson: String,
    val createdAt: Long,
    val pendingSync: Boolean,
    val setId: String = "",
    val indexInSet: Int = 0
)

@Dao
interface AlbumPhotoDao {

    /** Mi álbum, las más nuevas primero. */
    @Query("SELECT * FROM album_photos WHERE ownerFingerprint = :owner ORDER BY createdAt DESC")
    fun observeMine(owner: String): Flow<List<AlbumPhotoEntity>>

    @Query("SELECT * FROM album_photos WHERE ownerFingerprint = :owner ORDER BY createdAt DESC")
    suspend fun listMine(owner: String): List<AlbumPhotoEntity>

    /** Caché del álbum de otra persona (miniaturas que ya bajamos). */
    @Query("SELECT * FROM album_photos WHERE ownerFingerprint = :owner ORDER BY createdAt DESC LIMIT 60")
    suspend fun listOf(owner: String): List<AlbumPhotoEntity>

    @Query("SELECT * FROM album_photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlbumPhotoEntity?

    @Query("SELECT * FROM album_photos WHERE pendingSync = 1 ORDER BY createdAt ASC")
    suspend fun getPending(): List<AlbumPhotoEntity>

    /** Todas las fotos cacheadas, para armar el feed. */
    @Query("SELECT * FROM album_photos ORDER BY createdAt DESC LIMIT 200")
    fun observeAll(): Flow<List<AlbumPhotoEntity>>

    /** Fotos de una misma publicación (carrusel). */
    @Query("SELECT * FROM album_photos WHERE setId = :setId OR (setId = '' AND id = :setId)")
    suspend fun listBySet(setId: String): List<AlbumPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: AlbumPhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<AlbumPhotoEntity>)

    @Query("UPDATE album_photos SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    /** Ruta local de una foto ajena ya bajada por el puente. */
    @Query("UPDATE album_photos SET photoPath = :path WHERE id = :id")
    suspend fun setPath(id: String, path: String)

    @Query("DELETE FROM album_photos WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Al ver el álbum de otro, se reemplaza la caché de miniaturas (nunca las fotos locales propias). */
    @Query("DELETE FROM album_photos WHERE ownerFingerprint = :owner AND photoPath = ''")
    suspend fun clearRemoteCache(owner: String)
}

fun AlbumPhotoEntity.toDomain(): AlbumPhoto = AlbumPhoto(
    id = id,
    ownerFingerprint = ownerFingerprint,
    photoPath = photoPath,
    photoThumb = photoThumb,
    caption = caption,
    placeName = placeName,
    placeId = placeId,
    taggedUsernames = JsonCodec.parseStringList(taggedUsernamesJson),
    createdAt = createdAt,
    pendingSync = pendingSync,
    setId = setId.ifBlank { id },
    indexInSet = indexInSet
)

fun AlbumPhoto.toEntity(): AlbumPhotoEntity = AlbumPhotoEntity(
    id = id,
    ownerFingerprint = ownerFingerprint,
    photoPath = photoPath,
    photoThumb = photoThumb,
    caption = caption,
    placeName = placeName,
    placeId = placeId,
    taggedUsernamesJson = JsonCodec.stringList(taggedUsernames),
    createdAt = createdAt,
    pendingSync = pendingSync,
    setId = setId.ifBlank { id },
    indexInSet = indexInSet
)

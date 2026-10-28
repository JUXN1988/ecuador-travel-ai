package ec.travesias.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reacciones y comentarios de las publicaciones (y del muro de cada perfil).
 *
 * Se guardan aquí para que el feed se pinte al instante y siga teniendo sentido sin
 * internet: Firestore solo trae lo que falta. La clave de todo es [postKey]:
 *  - una publicación de fotos -> su `setId`
 *  - el muro de un perfil      -> "muro:<huella del dueño>"
 *
 * Así una sola pareja de tablas sirve para las dos cosas.
 */
@Entity(tableName = "post_reactions", indices = [Index("postKey"), Index("ownerFingerprint")])
data class PostReactionEntity(
    /** postKey + "|" + huella de quien reacciona: una reacción por persona y publicación. */
    @PrimaryKey val id: String,
    val postKey: String,
    /** Dueño de la publicación (bajo su álbum vive todo esto en Firestore). */
    val ownerFingerprint: String,
    val quien: String,
    val usuario: String,
    val emoji: String,
    val at: Long
)

@Entity(tableName = "post_comments", indices = [Index("postKey"), Index("ownerFingerprint")])
data class PostCommentEntity(
    @PrimaryKey val id: String,
    val postKey: String,
    val ownerFingerprint: String,
    val quien: String,
    val usuario: String,
    val texto: String,
    val at: Long,
    /** true mientras no haya salido de este teléfono (se escribió sin internet). */
    val pendingSync: Boolean = false
)

@Dao
interface PostReactionDao {

    @Query("SELECT * FROM post_reactions")
    fun observeAll(): Flow<List<PostReactionEntity>>

    @Query("SELECT * FROM post_reactions WHERE postKey = :postKey")
    suspend fun listOf(postKey: String): List<PostReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fila: PostReactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(filas: List<PostReactionEntity>)

    @Query("DELETE FROM post_reactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM post_reactions WHERE postKey = :postKey")
    suspend fun clearPost(postKey: String)
}

@Dao
interface PostCommentDao {

    @Query("SELECT * FROM post_comments ORDER BY at ASC")
    fun observeAll(): Flow<List<PostCommentEntity>>

    @Query("SELECT * FROM post_comments WHERE postKey = :postKey ORDER BY at ASC")
    suspend fun listOf(postKey: String): List<PostCommentEntity>

    @Query("SELECT * FROM post_comments WHERE pendingSync = 1 ORDER BY at ASC")
    suspend fun getPending(): List<PostCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fila: PostCommentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(filas: List<PostCommentEntity>)

    @Query("UPDATE post_comments SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM post_comments WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Al refrescar de Firestore se limpia lo bajado antes, nunca lo que aún no ha salido. */
    @Query("DELETE FROM post_comments WHERE postKey = :postKey AND pendingSync = 0")
    suspend fun clearPost(postKey: String)
}

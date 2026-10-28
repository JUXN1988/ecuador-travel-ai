package ec.travesias.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import ec.travesias.app.data.social.CommentCategory
import ec.travesias.app.data.social.GroupPost
import ec.travesias.app.data.social.PlaceComment
import ec.travesias.app.data.social.PostType
import ec.travesias.app.data.social.ProfileVisibility
import ec.travesias.app.data.social.TravelGroup
import ec.travesias.app.data.social.TravelerProfile
import kotlinx.coroutines.flow.Flow

// ============================================================================
//  Comunidad guardada en el teléfono. Firebase solo replica estas mismas filas.
// ============================================================================

/** Mi perfil. Siempre una única fila (id = 1). */
@Entity(tableName = "my_profile")
data class MyProfileEntity(
    @PrimaryKey val id: Int = 1,
    val uid: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val interestsJson: String,
    val region: String,
    val routeOrigin: String,
    val routeDestination: String,
    val travelMonth: String,
    val groupIdsJson: String,
    val avatarPath: String,
    val avatarThumb: String,
    val createdAt: Long,
    val travelTagsJson: String = "[]",
    val publicKey: String = "",
    val showcase: Boolean = false,
    /** PUBLIC / FRIENDS / PRIVATE. Por defecto solo amigos. */
    val visibility: String = "FRIENDS",
    /**
     * Correo de recuperación, OPCIONAL. No es el correo con el que se entra (ese es interno
     * y sale de la llave): sirve para recuperar el @usuario y para avisos, si algún día se
     * encienden. Vacío mientras el viajero no lo ponga.
     */
    val recoveryEmail: String = "",
    /** true cuando ese correo quedó confirmado. */
    val recoveryVerified: Boolean = false
)

@Entity(tableName = "social_groups")
data class SocialGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val inviteCode: String,
    val ownerUid: String,
    val memberUidsJson: String,
    val memberNamesJson: String,
    val createdAt: Long,
    val lastMessageAt: Long,
    val lastMessagePreview: String,
    val pendingSync: Boolean,
    /** Llave del grupo. Nunca sale del teléfono. */
    val secretKey: String = "",
    val encrypted: Boolean = false,
    /** Invitados que aún no aceptan, en JSON. Si estoy yo, esto es una solicitud para mí. */
    val pendingJson: String = "[]",
    /** Huella de quien me invitó. */
    val invitedBy: String = "",
    /** true si es un chat de dos. */
    val direct: Boolean = false,
    val muted: Boolean = false
)

@Entity(tableName = "social_posts", indices = [Index("groupId"), Index("pendingSync")])
data class SocialPostEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val type: String,
    val authorUid: String,
    val authorName: String,
    val authorThumb: String,
    val text: String,
    val payloadJson: String,
    val photoPath: String,
    val photoThumb: String,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String,
    val createdAt: Long,
    val pendingSync: Boolean,
    val authorKey: String = "",
    val trust: String = "LEGACY"
)

/**
 * Llaves públicas de la gente con la que hablo, aprendidas de sus propios mensajes.
 * Es lo que permite verificar sin preguntarle nada a Firebase: la primera vez que veo a
 * alguien me quedo con su llave, y si después llega un mensaje con su nombre pero otra
 * llave, la app avisa.
 */
@Entity(tableName = "known_identities")
data class KnownIdentityEntity(
    @PrimaryKey val fingerprint: String,
    val username: String,
    val publicKey: String,
    val displayName: String,
    val thumb: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** CONOCIDO / CONTACTO / BLOQUEADO. */
    val state: String = "CONOCIDO",
    /** Cuántos mensajes suyos he recibido. Es la señal honesta de "esta persona existe". */
    val messagesSeen: Int = 0,
    /** Nota privada que le pongo yo ("el de la camioneta de Baños"). No sale de este teléfono. */
    val note: String = "",
    /** true si alguna vez llegó un mensaje suyo con la firma mal: queda avisado para siempre. */
    val suspicious: Boolean = false
)

/** Caché de perfiles de otros viajeros, para poder verlos sin conexión. */
@Entity(tableName = "social_buddies")
data class SocialBuddyEntity(
    @PrimaryKey val uid: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val interestsJson: String,
    val region: String,
    val routeOrigin: String,
    val routeDestination: String,
    val travelMonth: String,
    val avatarThumb: String,
    val createdAt: Long,
    val cachedAt: Long,
    val travelTagsJson: String = "[]"
)

// ---------------------------------------------------------------- DAOs

@Dao
interface MyProfileDao {
    @Query("SELECT * FROM my_profile WHERE id = 1 LIMIT 1")
    fun observe(): Flow<MyProfileEntity?>

    @Query("SELECT * FROM my_profile WHERE id = 1 LIMIT 1")
    suspend fun get(): MyProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: MyProfileEntity)

    @Query("UPDATE my_profile SET uid = :newUid WHERE id = 1")
    suspend fun updateUid(newUid: String)

    @Query("DELETE FROM my_profile")
    suspend fun clear()
}

@Dao
interface SocialGroupDao {
    @Query("SELECT * FROM social_groups ORDER BY lastMessageAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<SocialGroupEntity>>

    @Query("SELECT * FROM social_groups ORDER BY createdAt DESC")
    suspend fun getAll(): List<SocialGroupEntity>

    @Query("SELECT * FROM social_groups WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SocialGroupEntity?

    @Query("SELECT * FROM social_groups WHERE pendingSync = 1")
    suspend fun getPending(): List<SocialGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: SocialGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(groups: List<SocialGroupEntity>)

    @Query("UPDATE social_groups SET lastMessageAt = :at, lastMessagePreview = :preview WHERE id = :id")
    suspend fun touch(id: String, at: Long, preview: String)

    @Query("UPDATE social_groups SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM social_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM social_groups")
    suspend fun clear()
}

@Dao
interface SocialPostDao {
    @Query("SELECT * FROM social_posts WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT 300")
    fun observeForGroup(groupId: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE pendingSync = 1 ORDER BY createdAt ASC")
    suspend fun getPending(): List<SocialPostEntity>

    @Query("SELECT * FROM social_posts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SocialPostEntity?

    /** Todos los mensajes de un grupo (para pasar un grupo `local-*` a id remoto). */
    @Query("SELECT * FROM social_posts WHERE groupId = :groupId")
    suspend fun listForGroup(groupId: String): List<SocialPostEntity>

    @Query("DELETE FROM social_posts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM social_posts WHERE groupId = :groupId AND authorUid = :author")
    suspend fun countByAuthor(groupId: String, author: String): Int

    /** Mensajes de la otra persona: si hay alguno, el chat de solicitud ya está contestado. */
    @Query("SELECT COUNT(*) FROM social_posts WHERE groupId = :groupId AND authorUid != :author")
    suspend fun countFromOthers(groupId: String, author: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: SocialPostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(posts: List<SocialPostEntity>)

    @Query("UPDATE social_posts SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE social_posts SET authorUid = :newUid WHERE authorUid = :oldUid")
    suspend fun remapAuthor(oldUid: String, newUid: String)

    @Query("DELETE FROM social_posts WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM social_posts")
    suspend fun clear()
}

@Dao
interface SocialBuddyDao {
    @Query("SELECT * FROM social_buddies ORDER BY cachedAt DESC LIMIT 200")
    suspend fun getAll(): List<SocialBuddyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buddies: List<SocialBuddyEntity>)

    @Query("DELETE FROM social_buddies WHERE cachedAt < :olderThan")
    suspend fun purge(olderThan: Long)
}

/**
 * Comentario/tip de un viajero sobre un lugar (dónde comer, precios, cómo llegar...).
 * Mismo patrón que los mensajes de grupo: vive en el teléfono, Firebase solo lo replica.
 */
@Dao
interface KnownIdentityDao {
    @Query("SELECT * FROM known_identities WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun get(fingerprint: String): KnownIdentityEntity?

    @Query("SELECT * FROM known_identities WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): KnownIdentityEntity?

    @Query("SELECT * FROM known_identities ORDER BY lastSeenAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<KnownIdentityEntity>

    @Query("SELECT * FROM known_identities WHERE state != 'BLOQUEADO' ORDER BY state DESC, lastSeenAt DESC LIMIT :limit")
    fun observeContacts(limit: Int = 300): Flow<List<KnownIdentityEntity>>

    @Query("SELECT * FROM known_identities WHERE state = 'BLOQUEADO' ORDER BY lastSeenAt DESC")
    fun observeBlocked(): Flow<List<KnownIdentityEntity>>

    @Query("SELECT fingerprint FROM known_identities WHERE state = 'BLOQUEADO'")
    suspend fun blockedFingerprints(): List<String>

    @Query("UPDATE known_identities SET state = :state, lastSeenAt = :now WHERE fingerprint = :fingerprint")
    suspend fun setState(fingerprint: String, state: String, now: Long)

    @Query("UPDATE known_identities SET note = :note WHERE fingerprint = :fingerprint")
    suspend fun setNote(fingerprint: String, note: String)

    @Query("UPDATE known_identities SET messagesSeen = messagesSeen + 1, lastSeenAt = :now WHERE fingerprint = :fingerprint")
    suspend fun countMessage(fingerprint: String, now: Long)

    @Query("UPDATE known_identities SET suspicious = 1 WHERE fingerprint = :fingerprint")
    suspend fun markSuspicious(fingerprint: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: KnownIdentityEntity)

    @Query("DELETE FROM known_identities")
    suspend fun clear()
}

@Entity(tableName = "place_comments", indices = [Index("placeId"), Index("pendingSync")])
data class PlaceCommentEntity(
    @PrimaryKey val id: String,
    val placeId: String,
    val authorUid: String,
    val authorName: String,
    val authorThumb: String,
    val text: String,
    val category: String,
    val photoPath: String,
    val photoThumb: String,
    val createdAt: Long,
    val pendingSync: Boolean
)

@Dao
interface PlaceCommentDao {
    @Query("SELECT * FROM place_comments WHERE placeId = :placeId ORDER BY createdAt DESC LIMIT 200")
    fun observeForPlace(placeId: String): Flow<List<PlaceCommentEntity>>

    @Query("SELECT * FROM place_comments WHERE pendingSync = 1 ORDER BY createdAt ASC")
    suspend fun getPending(): List<PlaceCommentEntity>

    @Query("SELECT * FROM place_comments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlaceCommentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(comment: PlaceCommentEntity)

    @Query("UPDATE place_comments SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)
}

// ---------------------------------------------------------------- Mapeos

fun MyProfileEntity.toDomain(): TravelerProfile = TravelerProfile(
    uid = uid,
    username = username,
    displayName = displayName,
    bio = bio,
    interests = JsonCodec.parseStringList(interestsJson),
    region = region,
    routeOrigin = routeOrigin,
    routeDestination = routeDestination,
    travelMonth = travelMonth,
    groupIds = JsonCodec.parseStringList(groupIdsJson),
    avatarPath = avatarPath,
    avatarThumb = avatarThumb,
    createdAt = createdAt,
    travelTags = JsonCodec.parseStringList(travelTagsJson),
    publicKey = publicKey,
    showcase = showcase,
    visibility = ProfileVisibility.from(visibility),
    recoveryEmail = recoveryEmail,
    recoveryVerified = recoveryVerified
)

fun TravelerProfile.toEntity(): MyProfileEntity = MyProfileEntity(
    id = 1,
    uid = uid,
    username = username,
    displayName = displayName,
    bio = bio,
    interestsJson = JsonCodec.stringList(interests),
    region = region,
    routeOrigin = routeOrigin,
    routeDestination = routeDestination,
    travelMonth = travelMonth,
    groupIdsJson = JsonCodec.stringList(groupIds),
    avatarPath = avatarPath,
    avatarThumb = avatarThumb,
    createdAt = createdAt,
    travelTagsJson = JsonCodec.stringList(travelTags),
    publicKey = publicKey,
    showcase = showcase,
    visibility = visibility.clave,
    recoveryEmail = recoveryEmail,
    recoveryVerified = recoveryVerified
)

fun SocialGroupEntity.toDomain(): TravelGroup = TravelGroup(
    id = id,
    name = name,
    description = description,
    inviteCode = inviteCode,
    ownerUid = ownerUid,
    memberUids = JsonCodec.parseStringList(memberUidsJson),
    memberNames = JsonCodec.parseStringList(memberNamesJson),
    createdAt = createdAt,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    pendingSync = pendingSync,
    secretKey = secretKey,
    encrypted = encrypted,
    pendingMembers = JsonCodec.parseStringList(pendingJson),
    invitedBy = invitedBy,
    direct = direct,
    muted = muted
)

fun TravelGroup.toEntity(): SocialGroupEntity = SocialGroupEntity(
    id = id,
    name = name,
    description = description,
    inviteCode = inviteCode,
    ownerUid = ownerUid,
    memberUidsJson = JsonCodec.stringList(memberUids),
    memberNamesJson = JsonCodec.stringList(memberNames),
    createdAt = createdAt,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    pendingSync = pendingSync,
    secretKey = secretKey,
    encrypted = encrypted,
    pendingJson = JsonCodec.stringList(pendingMembers),
    invitedBy = invitedBy,
    direct = direct,
    muted = muted
)

fun SocialPostEntity.toDomain(): GroupPost = GroupPost(
    id = id,
    groupId = groupId,
    type = runCatching { PostType.valueOf(type) }.getOrDefault(PostType.MESSAGE),
    authorUid = authorUid,
    authorName = authorName,
    authorThumb = authorThumb,
    text = text,
    payloadJson = payloadJson,
    photoPath = photoPath,
    photoThumb = photoThumb,
    latitude = latitude,
    longitude = longitude,
    locationLabel = locationLabel,
    createdAt = createdAt,
    pendingSync = pendingSync,
    authorKey = authorKey,
    trust = runCatching { ec.travesias.app.data.social.PostTrust.valueOf(trust) }
        .getOrDefault(ec.travesias.app.data.social.PostTrust.LEGACY)
)

fun GroupPost.toEntity(): SocialPostEntity = SocialPostEntity(
    id = id,
    groupId = groupId,
    type = type.name,
    authorUid = authorUid,
    authorName = authorName,
    authorThumb = authorThumb,
    text = text,
    payloadJson = payloadJson,
    photoPath = photoPath,
    photoThumb = photoThumb,
    latitude = latitude,
    longitude = longitude,
    locationLabel = locationLabel,
    createdAt = createdAt,
    pendingSync = pendingSync,
    authorKey = authorKey,
    trust = trust.name
)

fun SocialBuddyEntity.toDomain(): TravelerProfile = TravelerProfile(
    uid = uid,
    username = username,
    displayName = displayName,
    bio = bio,
    interests = JsonCodec.parseStringList(interestsJson),
    region = region,
    routeOrigin = routeOrigin,
    routeDestination = routeDestination,
    travelMonth = travelMonth,
    avatarThumb = avatarThumb,
    createdAt = createdAt,
    travelTags = JsonCodec.parseStringList(travelTagsJson)
)

fun TravelerProfile.toBuddyEntity(now: Long): SocialBuddyEntity = SocialBuddyEntity(
    uid = uid,
    username = username,
    displayName = displayName,
    bio = bio,
    interestsJson = JsonCodec.stringList(interests),
    region = region,
    routeOrigin = routeOrigin,
    routeDestination = routeDestination,
    travelMonth = travelMonth,
    avatarThumb = avatarThumb,
    createdAt = createdAt,
    cachedAt = now,
    travelTagsJson = JsonCodec.stringList(travelTags)
)

fun PlaceCommentEntity.toDomain(): PlaceComment = PlaceComment(
    id = id,
    placeId = placeId,
    authorUid = authorUid,
    authorName = authorName,
    authorThumb = authorThumb,
    text = text,
    category = CommentCategory.from(category),
    photoPath = photoPath,
    photoThumb = photoThumb,
    createdAt = createdAt,
    pendingSync = pendingSync
)

fun PlaceComment.toEntity(): PlaceCommentEntity = PlaceCommentEntity(
    id = id,
    placeId = placeId,
    authorUid = authorUid,
    authorName = authorName,
    authorThumb = authorThumb,
    text = text,
    category = category.name,
    photoPath = photoPath,
    photoThumb = photoThumb,
    createdAt = createdAt,
    pendingSync = pendingSync
)

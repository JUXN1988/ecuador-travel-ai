package ec.travesias.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaceEntity::class,
        RouteEntity::class,
        RouteStopEntity::class,
        RadarCacheEntity::class,
        SavedItineraryEntity::class,
        OfflinePackageEntity::class,
        PackingChecklistEntity::class,
        MyProfileEntity::class,
        SocialGroupEntity::class,
        SocialPostEntity::class,
        SocialBuddyEntity::class,
        GazetteerEntity::class,
        PlaceCommentEntity::class,
        KnownIdentityEntity::class,
        CatalogOutboxEntity::class,
        AlbumPhotoEntity::class,
        PostReactionEntity::class,
        PostCommentEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun placeDao(): PlaceDao
    abstract fun routeDao(): RouteDao
    abstract fun routeStopDao(): RouteStopDao
    abstract fun radarCacheDao(): RadarCacheDao
    abstract fun itineraryDao(): ItineraryDao
    abstract fun offlinePackageDao(): OfflinePackageDao
    abstract fun packingDao(): PackingDao

    // Comunidad guardada en el teléfono
    abstract fun myProfileDao(): MyProfileDao
    abstract fun socialGroupDao(): SocialGroupDao
    abstract fun socialPostDao(): SocialPostDao
    abstract fun socialBuddyDao(): SocialBuddyDao

    /** Llaves públicas de la gente con la que hablo, para verificar sus mensajes sin depender de nadie. */
    abstract fun knownIdentityDao(): KnownIdentityDao

    /** Lugares pendientes de compartir con el catálogo común. */
    abstract fun catalogOutboxDao(): CatalogOutboxDao

    // Comentarios y tips de viajeros por lugar (dónde comer, precios, cómo llegar...)
    abstract fun placeCommentDao(): PlaceCommentDao

    // Índice de nombres buscables (provincias, cantones, localidades, lugares)
    abstract fun gazetteerDao(): GazetteerDao

    /** Álbum de fotos de viaje del perfil (lugares visitados y menciones a compañeros). */
    abstract fun albumPhotoDao(): AlbumPhotoDao

    /** Reacciones ("me gusta") de las publicaciones. */
    abstract fun postReactionDao(): PostReactionDao

    /** Comentarios de las publicaciones y del muro de los perfiles. */
    abstract fun postCommentDao(): PostCommentDao

    /**
     * Inserta datos iniciales si las tablas están vacías.
     * Se llama desde el contenedor de la app al arrancar (en IO).
     */
    suspend fun seedIfEmpty() {
        // Siembra incremental: en cada versión se añaden los lugares nuevos del catálogo
        // sin tocar los que el viajero ya guardó ni los que enriqueció la IA.
        val existentes = placeDao().getByIds(SeedData.places.map { it.id }).map { it.id }.toSet()
        val nuevos = SeedData.places.filter { it.id !in existentes }
        if (nuevos.isNotEmpty()) placeDao().upsertAll(nuevos.map { it.toEntity() })
        val packages = offlinePackageDao()
        if (packages.getById(SeedData.offlinePackages.first().id) == null) {
            packages.insertAll(SeedData.offlinePackages)
        }
        if (packingDao().count() == 0) {
            packingDao().insertItems(SeedData.packingItems)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 → v3: tablas de la comunidad (perfil, grupos, mensajes y caché de compañeros).
         * Se añaden sin tocar nada de lo anterior, así que los viajes y lugares se conservan.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `my_profile` (`id` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `bio` TEXT NOT NULL, " +
                        "`interestsJson` TEXT NOT NULL, `region` TEXT NOT NULL, `routeOrigin` TEXT NOT NULL, " +
                        "`routeDestination` TEXT NOT NULL, `travelMonth` TEXT NOT NULL, `groupIdsJson` TEXT NOT NULL, " +
                        "`avatarPath` TEXT NOT NULL, `avatarThumb` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `social_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `inviteCode` TEXT NOT NULL, `ownerUid` TEXT NOT NULL, " +
                        "`memberUidsJson` TEXT NOT NULL, `memberNamesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`lastMessageAt` INTEGER NOT NULL, `lastMessagePreview` TEXT NOT NULL, " +
                        "`pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `social_posts` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `authorUid` TEXT NOT NULL, `authorName` TEXT NOT NULL, " +
                        "`authorThumb` TEXT NOT NULL, `text` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, " +
                        "`photoPath` TEXT NOT NULL, `photoThumb` TEXT NOT NULL, `latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, `locationLabel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_posts_groupId` ON `social_posts` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_posts_pendingSync` ON `social_posts` (`pendingSync`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `social_buddies` (`uid` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `bio` TEXT NOT NULL, `interestsJson` TEXT NOT NULL, " +
                        "`region` TEXT NOT NULL, `routeOrigin` TEXT NOT NULL, `routeDestination` TEXT NOT NULL, " +
                        "`travelMonth` TEXT NOT NULL, `avatarThumb` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))"
                )
            }
        }

        /** v3 → v4: índice de nombres buscables (provincias, cantones, localidades, lugares). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gazetteer` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`normalized` TEXT NOT NULL, `kind` TEXT NOT NULL, `parent` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `placeId` TEXT NOT NULL, " +
                        "`usageCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gazetteer_normalized` ON `gazetteer` (`normalized`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gazetteer_usageCount` ON `gazetteer` (`usageCount`)")
            }
        }

        /** v4 → v5: caché local de la foto de cada lugar (se guarda dentro de imageUrlsJson). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Sin cambios de esquema: la ruta local de la foto viaja dentro de imageUrlsJson.
            }
        }

        /** v5 → v6: transporte y horario de la ruta (columnas nuevas, nada se borra). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `routes` ADD COLUMN `transportMode` TEXT")
                db.execSQL("ALTER TABLE `routes` ADD COLUMN `dailyStartTime` TEXT")
                db.execSQL("ALTER TABLE `route_stops` ADD COLUMN `transportMode` TEXT")
            }
        }

        /**
         * v6 → v7: distingue una hora SUGERIDA (se recalcula sola según el transporte) de una
         * hora FIJADA a mano (`timeLocked`). Antes cualquier hora guardada congelaba la llegada
         * y por eso cambiar de carro a bus/taxi/moto no movía nada.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `route_stops` ADD COLUMN `timeLocked` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v7 → v8: lugares aportados por viajeros (fotos propias, ubicación aproximada),
         * etiquetas de viaje del perfil (mochilero, busco/ofrezco aventón) y comentarios/tips
         * públicos por lugar (dónde comer, precios, cómo llegar, seguridad).
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `places` ADD COLUMN `localPhotoPathsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `places` ADD COLUMN `locationApproximate` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `travelTagsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `social_buddies` ADD COLUMN `travelTagsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place_comments` (`id` TEXT NOT NULL, `placeId` TEXT NOT NULL, " +
                        "`authorUid` TEXT NOT NULL, `authorName` TEXT NOT NULL, `authorThumb` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `category` TEXT NOT NULL, `photoPath` TEXT NOT NULL, " +
                        "`photoThumb` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_comments_placeId` ON `place_comments` (`placeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_comments_pendingSync` ON `place_comments` (`pendingSync`)")
            }
        }

        /**
         * v9: seguridad de la comunidad. Cada viajero tiene ahora un par de llaves y los
         * mensajes van firmados y cifrados, así que hacen falta columnas para la llave del
         * grupo, la llave pública del autor y el nivel de confianza de cada mensaje.
         * NADA se pierde: los grupos y mensajes que ya existían siguen ahí, marcados como
         * "sin firma" hasta que se creen grupos nuevos.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `publicKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `showcase` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `secretKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `social_posts` ADD COLUMN `authorKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `social_posts` ADD COLUMN `trust` TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `known_identities` (`fingerprint` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`thumb` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`fingerprint`))"
                )
                // Cola de lugares por compartir con el catálogo común de todos los viajeros.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalog_outbox` (`placeId` TEXT NOT NULL, " +
                        "`queuedAt` INTEGER NOT NULL, PRIMARY KEY(`placeId`))"
                )
            }
        }

        /**
         * v9 → v10: la Comunidad pasa de "grupos sueltos" a red de viajeros.
         *
         * QUÉ SE AÑADE Y POR QUÉ
         *  - `pendingJson` en los grupos: quién fue invitado y todavía no acepta. Mientras uno
         *    esté ahí, para él eso no es un chat sino una SOLICITUD. Esto es lo que faltaba
         *    para que se pudiera aceptar o rechazar a alguien.
         *  - `invitedBy`: quién me invitó, para poder enseñar su perfil antes de decidir.
         *  - `direct`: si es un chat de dos. Antes se adivinaba mirando si el nombre empezaba
         *    por un emoji, que se rompía en cuanto alguien ponía ese emoji en el nombre.
         *  - `muted`: silenciar sin salirse.
         *  - En las identidades conocidas: `state` (conocido/contacto/bloqueado), `messagesSeen`,
         *    `note` privada y `suspicious`.
         * NADA se pierde: los grupos, chats y mensajes que ya existían siguen igual.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `pendingJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `invitedBy` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `direct` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `social_groups` ADD COLUMN `muted` INTEGER NOT NULL DEFAULT 0")
                // Los chats de dos que ya existían se reconocen por el prefijo que se usaba antes.
                db.execSQL("UPDATE `social_groups` SET `direct` = 1 WHERE `name` LIKE '👤%'")
                db.execSQL("ALTER TABLE `known_identities` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'CONOCIDO'")
                db.execSQL("ALTER TABLE `known_identities` ADD COLUMN `messagesSeen` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `known_identities` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `known_identities` ADD COLUMN `suspicious` INTEGER NOT NULL DEFAULT 0")
                // Con quien ya hablé es contacto: no tiene sentido pedirle permiso otra vez.
                db.execSQL("UPDATE `known_identities` SET `state` = 'CONTACTO'")
            }
        }

        /**
         * v10 → v11: álbum de fotos del perfil de viajero.
         *
         * Cada foto vive en el teléfono; Firebase solo replica miniatura, lugar etiquetado y
         * menciones (@usuario). NADA de lo anterior se toca: chats, grupos y perfil siguen igual.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_photos` (`id` TEXT NOT NULL, " +
                        "`ownerFingerprint` TEXT NOT NULL, `photoPath` TEXT NOT NULL, " +
                        "`photoThumb` TEXT NOT NULL, `caption` TEXT NOT NULL, `placeName` TEXT NOT NULL, " +
                        "`placeId` TEXT NOT NULL, `taggedUsernamesJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_photos_ownerFingerprint` ON `album_photos` (`ownerFingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_photos_pendingSync` ON `album_photos` (`pendingSync`)")
            }
        }

        /**
         * v11 → v12: visibilidad del perfil (público / solo amigos / privado).
         * Quien ya tenía la vitrina encendida pasa a PÚBLICO; el resto a SOLO AMIGOS.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `visibility` TEXT NOT NULL DEFAULT 'FRIENDS'")
                db.execSQL("UPDATE `my_profile` SET `visibility` = 'PUBLIC' WHERE `showcase` = 1")
            }
        }

        /**
         * v12 → v13: varias fotos en una misma publicación (carrusel).
         * Cada foto vieja queda como publicación de una sola imagen.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `album_photos` ADD COLUMN `setId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `album_photos` ADD COLUMN `indexInSet` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `album_photos` SET `setId` = `id` WHERE `setId` = ''")
            }
        }

        /** Correo de recuperación opcional del perfil. Nadie pierde nada: entra vacío. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `recoveryEmail` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `my_profile` ADD COLUMN `recoveryVerified` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Reacciones y comentarios: la red social de verdad dentro de Viajeros.
         * Son tablas nuevas, así que nadie pierde nada al actualizar.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_reactions` (`id` TEXT NOT NULL, " +
                        "`postKey` TEXT NOT NULL, `ownerFingerprint` TEXT NOT NULL, `quien` TEXT NOT NULL, " +
                        "`usuario` TEXT NOT NULL, `emoji` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_reactions_postKey` ON `post_reactions` (`postKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_reactions_ownerFingerprint` ON `post_reactions` (`ownerFingerprint`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_comments` (`id` TEXT NOT NULL, " +
                        "`postKey` TEXT NOT NULL, `ownerFingerprint` TEXT NOT NULL, `quien` TEXT NOT NULL, " +
                        "`usuario` TEXT NOT NULL, `texto` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`pendingSync` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_comments_postKey` ON `post_comments` (`postKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_comments_ownerFingerprint` ON `post_comments` (`ownerFingerprint`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "travesias_ecuador.db"
                )
                    // Migraciones reales: al actualizar la app NO se pierden perfil, grupos,
                    // mensajes, rutas ni lugares guardados.
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                    // Solo se recrea si alguien instala una versión más antigua encima.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.iskezis.myst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iskezis.myst.BuildConfig

@Database(
    entities = [
        UserEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        HistoryEntity::class,
        ArtistAffinityEntity::class,
        YtPlaylistEntity::class,
        YtPlaylistTrackEntity::class,
        YtHistoryEntity::class,
        YtSyncEntity::class,
        PairTombstoneEntity::class,
        PlayEventEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun affinityDao(): AffinityDao
    abstract fun youTubeLibraryDao(): YouTubeLibraryDao
    abstract fun pairTombstoneDao(): PairTombstoneDao
    abstract fun playEventDao(): PlayEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            if (BuildConfig.FLAVOR == "tv") {
                // A TV não guarda playlists nem biblioteca em disco. O banco
                // em memória existe só enquanto o processo está vivo; ao
                // fechar o app ele é descartado e o próximo sync do celular
                // repovoa a tela. O arquivo antigo, se existir, é removido
                // para não deixar dados de versões anteriores.
                runCatching { context.deleteDatabase("mystmusic.db") }
                return Room.inMemoryDatabaseBuilder(
                    context,
                    AppDatabase::class.java,
                ).build()
            }
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "mystmusic.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }

        /**
         * 0.1.0 -> 0.2.0: foto de perfil local e vínculo com a conta YouTube.
         * Nenhuma coluna existente é removida e nenhuma tabela é recriada.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN avatarPath TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN youtubeAccountId TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN youtubeDisplayName TEXT")
            }
        }

        /**
         * 0.2.0-dev.2 -> 0.2.0-dev.3: cache local da biblioteca do YouTube Music.
         * São apenas tabelas novas; playlists, histórico e contas existentes
         * permanecem intactos.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `yt_playlists` (
                        `userId` INTEGER NOT NULL,
                        `ytId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `trackCount` INTEGER NOT NULL,
                        `liked` INTEGER NOT NULL,
                        `syncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `ytId`),
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_yt_playlists_userId` " +
                        "ON `yt_playlists` (`userId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `yt_playlist_tracks` (
                        `userId` INTEGER NOT NULL,
                        `ytId` TEXT NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `syncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `ytId`, `videoId`),
                        FOREIGN KEY(`userId`, `ytId`)
                            REFERENCES `yt_playlists`(`userId`, `ytId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_yt_playlist_tracks_userId_ytId_position` " +
                        "ON `yt_playlist_tracks` (`userId`, `ytId`, `position`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `yt_history` (
                        `userId` INTEGER NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `videoId`),
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_yt_history_userId_playedAt` " +
                        "ON `yt_history` (`userId`, `playedAt`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `yt_sync_state` (
                        `userId` INTEGER NOT NULL,
                        `section` TEXT NOT NULL,
                        `syncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `section`),
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_yt_sync_state_userId` " +
                        "ON `yt_sync_state` (`userId`)",
                )
            }
        }

        /**
         * 0.2.0-dev.6: lápides de playlists para a sincronização bidirecional
         * celular↔TV. Só cria uma tabela nova; nenhum dado existente muda.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pair_tombstones` (
                        `userId` INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `key`),
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pair_tombstones_userId` " +
                        "ON `pair_tombstones` (`userId`)",
                )
            }
        }

        /**
         * 0.2.0-dev.10: eventos de reprodução para as Estatísticas e auditoria
         * das tabelas da conta. Nenhuma linha do usuário é apagada:
         *  - playlist_tracks ganha `userId` (preenchido a partir da playlist) e
         *    passa a ter FK explícita para `users`;
         *  - history e artist_affinity ganham FK para `users`;
         *  - yt_playlist_tracks e yt_history ganham a coluna opcional `album`;
         *  - é criada a tabela play_events.
         *
         * As tabelas são recriadas porque o SQLite não adiciona FK com ALTER
         * TABLE. Os INSERTs usam JOIN com `users` para não introduzir órfãos.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `yt_playlist_tracks` " +
                        "ADD COLUMN `album` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `yt_history` " +
                        "ADD COLUMN `album` TEXT NOT NULL DEFAULT ''",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (
                        `userId` INTEGER NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL DEFAULT '',
                        `thumbnailUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`, `videoId`),
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_tracks_new`
                        (`userId`, `playlistId`, `videoId`, `title`, `artist`, `album`,
                         `thumbnailUrl`, `durationMs`, `position`)
                    SELECT p.`userId`, t.`playlistId`, t.`videoId`, t.`title`, t.`artist`, '',
                           t.`thumbnailUrl`, t.`durationMs`, t.`position`
                    FROM `playlist_tracks` t
                    INNER JOIN `playlists` p ON p.`id` = t.`playlistId`
                    INNER JOIN `users` u ON u.`id` = p.`userId`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `playlist_tracks`")
                db.execSQL("ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_userId` " +
                        "ON `playlist_tracks` (`userId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` " +
                        "ON `playlist_tracks` (`playlistId`, `position`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` INTEGER NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL DEFAULT '',
                        `thumbnailUrl` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `playedMs` INTEGER NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `history_new`
                        (`id`, `userId`, `videoId`, `title`, `artist`, `album`,
                         `thumbnailUrl`, `durationMs`, `playedMs`, `playedAt`, `completed`)
                    SELECT h.`id`, h.`userId`, h.`videoId`, h.`title`, h.`artist`, '',
                           h.`thumbnailUrl`, h.`durationMs`, h.`playedMs`, h.`playedAt`,
                           h.`completed`
                    FROM `history` h
                    INNER JOIN `users` u ON u.`id` = h.`userId`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `history`")
                db.execSQL("ALTER TABLE `history_new` RENAME TO `history`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_userId_playedAt` " +
                        "ON `history` (`userId`, `playedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_videoId` " +
                        "ON `history` (`videoId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `artist_affinity_new` (
                        `userId` INTEGER NOT NULL,
                        `artist` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `artist`),
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `artist_affinity_new`
                        (`userId`, `artist`, `score`, `updatedAt`)
                    SELECT a.`userId`, a.`artist`, a.`score`, a.`updatedAt`
                    FROM `artist_affinity` a
                    INNER JOIN `users` u ON u.`id` = a.`userId`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `artist_affinity`")
                db.execSQL("ALTER TABLE `artist_affinity_new` RENAME TO `artist_affinity`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_artist_affinity_userId` " +
                        "ON `artist_affinity` (`userId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` INTEGER NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `startedAt` INTEGER NOT NULL,
                        `playedMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_events_userId_startedAt` " +
                        "ON `play_events` (`userId`, `startedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_events_userId_artist` " +
                        "ON `play_events` (`userId`, `artist`)",
                )
            }
        }
    }
}

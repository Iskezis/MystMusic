package com.iskezis.myst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun affinityDao(): AffinityDao
    abstract fun youTubeLibraryDao(): YouTubeLibraryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mystmusic.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
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
    }
}

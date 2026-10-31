package com.iskezis.myst.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE accountType = 'myst' ORDER BY createdAt DESC")
    suspend fun localAccounts(): List<UserEntity>

    @Query("UPDATE users SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("UPDATE users SET avatarPath = :path WHERE id = :id")
    suspend fun updateAvatar(id: Long, path: String?)

    @Query("UPDATE users SET youtubeAccountId = :accountId, youtubeDisplayName = :name WHERE id = :id")
    suspend fun updateYouTubeLink(id: Long, accountId: String?, name: String?)

    @Query("UPDATE users SET salt = :salt, passwordHash = :hash WHERE id = :id")
    suspend fun updatePassword(id: Long, salt: String?, hash: String?)

    @Query("SELECT * FROM users WHERE youtubeAccountId = :accountId LIMIT 1")
    suspend fun findByYouTubeAccountId(accountId: String): UserEntity?
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE userId = :userId ORDER BY createdAt DESC")
    fun observePlaylists(userId: Long): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun playlists(userId: Long): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlists SET coverUri = :cover WHERE id = :id")
    suspend fun updateCover(id: Long, cover: String?)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlists WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun tracks(playlistId: Long): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    fun observeTrackCount(playlistId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: PlaylistTrackEntity): Long

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun deleteTrack(playlistId: Long, videoId: String)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun updatePosition(playlistId: Long, videoId: String, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET userId = :to WHERE userId = :from")
    suspend fun reassignUser(from: Long, to: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(history: HistoryEntity): Long

    @Query(
        """
        SELECT h.* FROM history h
        INNER JOIN (
            SELECT videoId, MAX(playedAt) AS latest FROM history
            WHERE userId = :userId GROUP BY videoId
        ) recent ON h.videoId = recent.videoId AND h.playedAt = recent.latest
        WHERE h.userId = :userId
        ORDER BY h.playedAt DESC LIMIT :limit
        """,
    )
    fun observeRecent(userId: Long, limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE userId = :userId ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(userId: Long, limit: Int): List<HistoryEntity>

    /** Chaves `videoId|playedAt` para importar um backup sem duplicar reproduções. */
    @Query("SELECT videoId || '|' || playedAt FROM history WHERE userId = :userId")
    suspend fun keys(userId: Long): List<String>

    @Query("SELECT DISTINCT videoId FROM history WHERE userId = :userId AND playedAt > :since")
    suspend fun playedSince(userId: Long, since: Long): List<String>

    @Query("SELECT COUNT(*) FROM history WHERE userId = :userId")
    suspend fun totalPlays(userId: Long): Int

    @Query("UPDATE history SET userId = :to WHERE userId = :from")
    suspend fun reassignUser(from: Long, to: Long)
}

@Dao
interface AffinityDao {
    @Query("SELECT * FROM artist_affinity WHERE userId = :userId AND artist = :artist LIMIT 1")
    suspend fun get(userId: Long, artist: String): ArtistAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(affinity: ArtistAffinityEntity)

    @Query("SELECT * FROM artist_affinity WHERE userId = :userId ORDER BY score DESC LIMIT :limit")
    suspend fun top(userId: Long, limit: Int): List<ArtistAffinityEntity>

    @Query("SELECT COALESCE(MAX(score), 1.0) FROM artist_affinity WHERE userId = :userId")
    suspend fun maxScore(userId: Long): Double

    @Query("DELETE FROM artist_affinity WHERE userId = :userId")
    suspend fun clear(userId: Long)

    @Query(
        """
        INSERT OR REPLACE INTO artist_affinity (userId, artist, score, updatedAt)
        SELECT :to, artist, score, updatedAt FROM artist_affinity WHERE userId = :from
        """,
    )
    suspend fun copyToUser(from: Long, to: Long)

    @Query("DELETE FROM artist_affinity WHERE userId = :from")
    suspend fun deleteUser(from: Long)
}

@Dao
interface PairTombstoneDao {
    @Query("SELECT * FROM pair_tombstones WHERE userId = :userId ORDER BY deletedAt DESC")
    suspend fun all(userId: Long): List<PairTombstoneEntity>

    @Query("SELECT * FROM pair_tombstones WHERE userId = :userId AND key = :key LIMIT 1")
    suspend fun get(userId: Long, key: String): PairTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: PairTombstoneEntity)

    @Query("DELETE FROM pair_tombstones WHERE userId = :userId AND key = :key")
    suspend fun delete(userId: Long, key: String)

    @Query("DELETE FROM pair_tombstones WHERE userId = :userId")
    suspend fun clear(userId: Long)
}

@Dao
interface YouTubeLibraryDao {
    @Query(
        """
        SELECT * FROM yt_playlists WHERE userId = :userId
        ORDER BY liked DESC, title COLLATE NOCASE ASC
        """,
    )
    fun observePlaylists(userId: Long): Flow<List<YtPlaylistEntity>>

    @Query(
        """
        SELECT * FROM yt_playlists WHERE userId = :userId
        ORDER BY liked DESC, title COLLATE NOCASE ASC
        """,
    )
    suspend fun playlists(userId: Long): List<YtPlaylistEntity>

    @Query("SELECT * FROM yt_playlists WHERE userId = :userId AND ytId = :ytId LIMIT 1")
    fun observePlaylist(userId: Long, ytId: String): Flow<YtPlaylistEntity?>

    @Query("SELECT * FROM yt_playlists WHERE userId = :userId AND ytId = :ytId LIMIT 1")
    suspend fun playlist(userId: Long, ytId: String): YtPlaylistEntity?

    @Upsert
    suspend fun upsertPlaylists(playlists: List<YtPlaylistEntity>)

    @Query("DELETE FROM yt_playlists WHERE userId = :userId AND ytId NOT IN (:keep)")
    suspend fun deletePlaylistsNotIn(userId: Long, keep: List<String>)

    @Query("DELETE FROM yt_playlists WHERE userId = :userId")
    suspend fun deletePlaylists(userId: Long)

    @Query(
        "UPDATE yt_playlists SET trackCount = :count, syncedAt = :syncedAt " +
            "WHERE userId = :userId AND ytId = :ytId",
    )
    suspend fun updatePlaylistCount(
        userId: Long,
        ytId: String,
        count: Int,
        syncedAt: Long,
    )

    @Query(
        """
        SELECT * FROM yt_playlist_tracks WHERE userId = :userId AND ytId = :ytId
        ORDER BY position ASC
        """,
    )
    fun observePlaylistTracks(userId: Long, ytId: String): Flow<List<YtPlaylistTrackEntity>>

    @Query(
        """
        SELECT * FROM yt_playlist_tracks WHERE userId = :userId AND ytId = :ytId
        ORDER BY position ASC
        """,
    )
    suspend fun playlistTracks(userId: Long, ytId: String): List<YtPlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTracks(tracks: List<YtPlaylistTrackEntity>)

    @Query(
        """
        SELECT * FROM yt_playlist_tracks WHERE userId = :userId
        ORDER BY ytId ASC, position ASC
        """,
    )
    suspend fun allPlaylistTracks(userId: Long): List<YtPlaylistTrackEntity>

    @Query("DELETE FROM yt_playlist_tracks WHERE userId = :userId AND ytId = :ytId")
    suspend fun deletePlaylistTracks(userId: Long, ytId: String)

    @Query(
        """
        SELECT * FROM yt_history WHERE userId = :userId
        ORDER BY playedAt DESC LIMIT :limit
        """,
    )
    fun observeHistory(userId: Long, limit: Int): Flow<List<YtHistoryEntity>>

    @Query(
        """
        SELECT * FROM yt_history WHERE userId = :userId
        ORDER BY playedAt DESC LIMIT :limit
        """,
    )
    suspend fun history(userId: Long, limit: Int): List<YtHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(history: List<YtHistoryEntity>)

    @Query("DELETE FROM yt_history WHERE userId = :userId")
    suspend fun deleteHistory(userId: Long)

    @Query("SELECT * FROM yt_sync_state WHERE userId = :userId AND section = :section LIMIT 1")
    suspend fun sync(userId: Long, section: String): YtSyncEntity?

    @Query("SELECT * FROM yt_sync_state WHERE userId = :userId")
    suspend fun syncStates(userId: Long): List<YtSyncEntity>

    @Upsert
    suspend fun upsertSync(sync: YtSyncEntity)

    @Query("DELETE FROM yt_sync_state WHERE userId = :userId")
    suspend fun deleteSync(userId: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE userId = :userId ORDER BY addedAt DESC")
    fun observe(userId: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE userId = :userId AND status = :status ORDER BY addedAt DESC")
    fun observeByStatus(userId: Long, status: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE userId = :userId AND videoId = :videoId LIMIT 1")
    fun observeOne(userId: Long, videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE userId = :userId AND videoId = :videoId LIMIT 1")
    suspend fun find(userId: Long, videoId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE userId = :userId ORDER BY addedAt DESC")
    suspend fun all(userId: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE userId = :userId AND status = :status ORDER BY addedAt DESC")
    suspend fun byStatus(userId: Long, status: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE userId = :userId AND status = 'QUEUED' " +
            "ORDER BY addedAt ASC",
    )
    suspend fun queued(userId: Long): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query(
        "UPDATE downloads SET title = :title, artist = :artist, album = :album, " +
            "thumbnailUrl = :thumbnailUrl, durationMs = :durationMs, " +
            "artistId = :artistId, albumId = :albumId, status = 'QUEUED', " +
            "bytes = 0, totalBytes = 0, path = NULL, coverPath = NULL, error = NULL, " +
            "addedAt = :addedAt, updatedAt = :addedAt " +
            "WHERE userId = :userId AND videoId = :videoId",
    )
    suspend fun requeue(
        userId: Long,
        videoId: String,
        title: String,
        artist: String,
        album: String,
        thumbnailUrl: String?,
        durationMs: Long,
        artistId: String?,
        albumId: String?,
        addedAt: Long,
    )

    @Query(
        "UPDATE downloads SET status = 'DOWNLOADING', error = NULL, updatedAt = :now " +
            "WHERE userId = :userId AND videoId = :videoId AND status = 'QUEUED'",
    )
    suspend fun markDownloading(userId: Long, videoId: String, now: Long): Int

    @Query(
        "UPDATE downloads SET bytes = :bytes, totalBytes = :totalBytes, updatedAt = :now " +
            "WHERE userId = :userId AND videoId = :videoId AND status = 'DOWNLOADING'",
    )
    suspend fun progress(userId: Long, videoId: String, bytes: Long, totalBytes: Long, now: Long)

    @Query(
        "UPDATE downloads SET status = 'DONE', bytes = :bytes, totalBytes = :totalBytes, " +
            "path = :path, coverPath = :coverPath, error = NULL, updatedAt = :now " +
            "WHERE userId = :userId AND videoId = :videoId",
    )
    suspend fun markDone(
        userId: Long,
        videoId: String,
        bytes: Long,
        totalBytes: Long,
        path: String,
        coverPath: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE downloads SET status = 'FAILED', error = :error, updatedAt = :now " +
            "WHERE userId = :userId AND videoId = :videoId",
    )
    suspend fun markFailed(userId: Long, videoId: String, error: String, now: Long)

    @Query(
        "UPDATE downloads SET status = 'QUEUED', bytes = 0, totalBytes = 0, error = NULL, " +
            "updatedAt = :now WHERE status = 'DOWNLOADING'",
    )
    suspend fun resetInterrupted(now: Long)

    @Query(
        "UPDATE downloads SET status = 'FAILED', error = :reason, updatedAt = :now " +
            "WHERE userId = :userId AND status IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun cancelActive(userId: Long, reason: String, now: Long)

    @Query("DELETE FROM downloads WHERE userId = :userId AND videoId = :videoId")
    suspend fun delete(userId: Long, videoId: String)

    @Query("DELETE FROM downloads WHERE userId = :userId")
    suspend fun deleteAll(userId: Long)

    @Query("SELECT COUNT(*) FROM downloads WHERE path = :path AND status = 'DONE'")
    suspend fun referencesToPath(path: String): Int

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloads WHERE userId = :userId AND status = 'DONE'")
    suspend fun usedBytes(userId: Long): Long

    @Query("SELECT COUNT(*) FROM downloads WHERE userId = :userId AND status = 'DONE'")
    suspend fun doneCount(userId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM downloads WHERE userId = :userId " +
            "AND status IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun activeCount(userId: Long): Int
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    /**
     * Atualiza o trecho ouvido de um evento já criado aos 10 s. A linha é a
     * mesma durante toda a reprodução, então trocar de faixa não cria uma
     * segunda reprodução nem perde o tempo escutado depois do limiar.
     */
    @Query(
        "UPDATE play_events SET playedMs = :playedMs, durationMs = :durationMs, " +
            "completed = :completed WHERE id = :id",
    )
    suspend fun updateProgress(
        id: Long,
        playedMs: Long,
        durationMs: Long,
        completed: Boolean,
    ): Int

    /**
     * Localiza a reprodução que já existe ao mesclar um retrato da TV ou um
     * backup. O tempo ouvido pode ter aumentado na mesma linha (10 s → fim da
     * faixa), então a mesclagem precisa atualizá-la em vez de descartá-la.
     */
    @Query(
        "SELECT * FROM play_events WHERE userId = :userId AND videoId = :videoId " +
            "AND startedAt = :startedAt LIMIT 1",
    )
    suspend fun find(userId: Long, videoId: String, startedAt: Long): PlayEventEntity?

    @Query(
        "SELECT * FROM play_events WHERE userId = :userId " +
            "ORDER BY startedAt DESC LIMIT :limit",
    )
    suspend fun recent(userId: Long, limit: Int): List<PlayEventEntity>

    /** Chaves `videoId|startedAt` para mesclar backups/sincronizações sem duplicar. */
    @Query("SELECT videoId || '|' || startedAt FROM play_events WHERE userId = :userId")
    suspend fun keys(userId: Long): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        """,
    )
    suspend fun eventCount(userId: Long, from: Long, to: Long, minPlayedMs: Long): Int

    @Query(
        """
        SELECT COALESCE(SUM(playedMs), 0) FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        """,
    )
    suspend fun totalPlayedMs(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
    ): Long

    @Query(
        """
        SELECT COUNT(*) FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        """,
    )
    suspend fun playCount(userId: Long, from: Long, to: Long, minPlayedMs: Long): Int

    @Query(
        """
        SELECT videoId,
               MIN(title) AS title,
               MIN(artist) AS artist,
               MAX(album) AS album,
               MAX(thumbnailUrl) AS thumbnailUrl,
               COUNT(*) AS plays,
               SUM(playedMs) AS playedMs
        FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        GROUP BY videoId
        ORDER BY plays DESC, playedMs DESC, title COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun topTracks(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
        limit: Int,
    ): List<PlayEventTrackStat>

    @Query(
        """
        SELECT MIN(artist) AS name,
               MAX(thumbnailUrl) AS thumbnailUrl,
               COUNT(*) AS plays,
               SUM(playedMs) AS playedMs
        FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND TRIM(artist) != ''
          AND playedMs >= :minPlayedMs
        GROUP BY LOWER(TRIM(artist))
        ORDER BY plays DESC, playedMs DESC, name COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun topArtists(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
        limit: Int,
    ): List<PlayEventNameStat>

    @Query(
        """
        SELECT MIN(album) AS name,
               MIN(artist) AS artist,
               MAX(thumbnailUrl) AS thumbnailUrl,
               COUNT(*) AS plays,
               SUM(playedMs) AS playedMs
        FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND TRIM(album) != ''
          AND playedMs >= :minPlayedMs
        GROUP BY LOWER(TRIM(album))
        ORDER BY plays DESC, playedMs DESC, name COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun topAlbums(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
        limit: Int,
    ): List<PlayEventAlbumStat>

    @Query(
        """
        SELECT date(startedAt / 1000, 'unixepoch', 'localtime') AS day,
               SUM(playedMs) AS playedMs
        FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    suspend fun dailyPlayedMs(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
    ): List<PlayEventDayStat>

    @Query(
        """
        SELECT CAST(strftime('%H', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               SUM(playedMs) AS playedMs
        FROM play_events
        WHERE userId = :userId AND startedAt >= :from AND startedAt < :to
          AND playedMs >= :minPlayedMs
        GROUP BY hour
        ORDER BY hour ASC
        """,
    )
    suspend fun hourlyPlayedMs(
        userId: Long,
        from: Long,
        to: Long,
        minPlayedMs: Long,
    ): List<PlayEventHourStat>

    @Query(
        """
        SELECT DISTINCT CAST(julianday(date(startedAt / 1000, 'unixepoch', 'localtime')) + 0.5 AS INTEGER) AS day
        FROM play_events
        WHERE userId = :userId
          AND playedMs >= :minPlayedMs
        ORDER BY day DESC
        """,
    )
    suspend fun listenedDays(userId: Long, minPlayedMs: Long): List<Long>
}

data class PlayEventTrackStat(
    @ColumnInfo(name = "videoId") val videoId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "thumbnailUrl") val thumbnailUrl: String?,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "playedMs") val playedMs: Long,
)

data class PlayEventNameStat(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "thumbnailUrl") val thumbnailUrl: String?,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "playedMs") val playedMs: Long,
)

data class PlayEventAlbumStat(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "thumbnailUrl") val thumbnailUrl: String?,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "playedMs") val playedMs: Long,
)

data class PlayEventDayStat(
    @ColumnInfo(name = "day") val day: String,
    @ColumnInfo(name = "playedMs") val playedMs: Long,
)

data class PlayEventHourStat(
    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "playedMs") val playedMs: Long,
)

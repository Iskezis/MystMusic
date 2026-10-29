package com.iskezis.myst.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

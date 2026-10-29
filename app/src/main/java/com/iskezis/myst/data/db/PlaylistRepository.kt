package com.iskezis.myst.data.db

import androidx.room.withTransaction
import com.iskezis.myst.data.youtube.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class PlaylistExport(
    val name: String,
    val tracks: List<Track>,
    val coverUri: String? = null,
)

class PlaylistRepository(private val database: AppDatabase) {

    private val dao get() = database.playlistDao()

    fun observePlaylists(userId: Long): Flow<List<PlaylistEntity>> = dao.observePlaylists(userId)

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun observeTracks(playlistId: Long): Flow<List<Track>> =
        dao.observeTracks(playlistId).map { tracks -> tracks.map(::toTrack) }

    fun observeTrackCount(playlistId: Long): Flow<Int> = dao.observeTrackCount(playlistId)

    suspend fun getPlaylist(id: Long): PlaylistEntity? = withContext(Dispatchers.IO) {
        dao.findById(id)
    }

    suspend fun tracks(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        dao.tracks(playlistId).map(::toTrack)
    }

    suspend fun create(userId: Long, name: String, coverUri: String? = null): Long =
        withContext(Dispatchers.IO) {
            dao.insert(
                PlaylistEntity(
                    userId = userId,
                    name = name.trim().take(40),
                    coverUri = coverUri,
                ),
            )
        }

    suspend fun rename(playlistId: Long, name: String) = withContext(Dispatchers.IO) {
        val newName = name.trim().take(40)
        val playlist = dao.findById(playlistId)
        if (playlist != null && !playlist.name.equals(newName, ignoreCase = true)) {
            recordTombstone(playlist.userId, playlist.name)
        }
        dao.rename(playlistId, newName)
    }

    suspend fun setCover(playlistId: Long, coverUri: String?) = withContext(Dispatchers.IO) {
        dao.updateCover(playlistId, coverUri)
    }

    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        val playlist = dao.findById(playlistId)
        if (playlist != null) {
            recordTombstone(playlist.userId, playlist.name)
        }
        dao.delete(playlistId)
    }

    suspend fun tombstones(userId: Long): List<PairTombstoneEntity> =
        withContext(Dispatchers.IO) { database.pairTombstoneDao().all(userId) }

    suspend fun recordTombstone(userId: Long, name: String, at: Long = System.currentTimeMillis()) {
        val key = normalizePlaylistName(name)
        if (userId <= 0L || key.isBlank()) return
        val previous = database.pairTombstoneDao().get(userId, key)
        database.pairTombstoneDao().upsert(
            PairTombstoneEntity(
                userId = userId,
                key = key,
                deletedAt = maxOf(previous?.deletedAt ?: 0L, at),
            ),
        )
    }

    suspend fun addTrack(playlistId: Long, track: Track): Boolean = withContext(Dispatchers.IO) {
        val position = dao.maxPosition(playlistId) + 1
        val result = dao.insertTrack(track.toEntity(playlistId, position))
        result != -1L
    }

    suspend fun addTracks(playlistId: Long, tracks: List<Track>): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            var position = dao.maxPosition(playlistId) + 1
            var inserted = 0
            tracks.forEach { track ->
                val result = dao.insertTrack(track.toEntity(playlistId, position))
                if (result != -1L) {
                    position++
                    inserted++
                }
            }
            inserted
        }
    }

    suspend fun removeTrack(playlistId: Long, videoId: String) = withContext(Dispatchers.IO) {
        dao.deleteTrack(playlistId, videoId)
    }

    suspend fun restoreTrack(playlistId: Long, track: Track, position: Int) = withContext(Dispatchers.IO) {
        dao.insertTrack(track.toEntity(playlistId, position))
    }

    suspend fun reorder(playlistId: Long, ordered: List<Track>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            ordered.forEachIndexed { index, track ->
                dao.updatePosition(playlistId, track.videoId, index)
            }
        }
    }

    suspend fun export(userId: Long): List<PlaylistExport> = withContext(Dispatchers.IO) {
        dao.playlists(userId).map { playlist ->
            PlaylistExport(
                name = playlist.name,
                tracks = dao.tracks(playlist.id).map(::toTrack),
                coverUri = playlist.coverUri,
            )
        }
    }

    suspend fun replaceAll(userId: Long, exports: List<PlaylistExport>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.deleteAll(userId)
                exports.forEach { export ->
                    val playlistId = dao.insert(
                        PlaylistEntity(
                            userId = userId,
                            name = export.name.take(40),
                            coverUri = export.coverUri,
                        ),
                    )
                    export.tracks.forEachIndexed { index, track ->
                        dao.insertTrack(track.toEntity(playlistId, index))
                    }
                }
            }
        }

    suspend fun import(userId: Long, exports: List<PlaylistExport>) = withContext(Dispatchers.IO) {
        exports.forEach { export ->
            val playlistId = dao.insert(
                PlaylistEntity(
                    userId = userId,
                    name = export.name.take(40),
                    coverUri = export.coverUri,
                ),
            )
            export.tracks.forEachIndexed { index, track ->
                dao.insertTrack(track.toEntity(playlistId, index))
            }
        }
    }

    private fun toTrack(entity: PlaylistTrackEntity): Track = Track(
        videoId = entity.videoId,
        title = entity.title,
        artist = entity.artist,
        thumbnailUrl = entity.thumbnailUrl,
        durationMs = entity.durationMs,
    )

    companion object {
        fun normalizePlaylistName(name: String): String = name.trim().lowercase()
    }

    private fun Track.toEntity(playlistId: Long, position: Int): PlaylistTrackEntity =
        PlaylistTrackEntity(
            playlistId = playlistId,
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            position = position,
        )
}

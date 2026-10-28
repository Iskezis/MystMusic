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
        dao.rename(playlistId, name.trim().take(40))
    }

    suspend fun setCover(playlistId: Long, coverUri: String?) = withContext(Dispatchers.IO) {
        dao.updateCover(playlistId, coverUri)
    }

    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        dao.delete(playlistId)
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
            )
        }
    }

    suspend fun import(userId: Long, exports: List<PlaylistExport>) = withContext(Dispatchers.IO) {
        exports.forEach { export ->
            val playlistId = dao.insert(
                PlaylistEntity(userId = userId, name = export.name.take(40)),
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

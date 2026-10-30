package com.iskezis.myst.data.youtube

import androidx.room.withTransaction
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.db.YtHistoryEntity
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.data.db.YtPlaylistTrackEntity
import com.iskezis.myst.data.db.YtSyncEntity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Cache local somente leitura da biblioteca do YouTube Music. A tela mostra
 * imediatamente o que está no Room e o repositório atualiza em segundo plano
 * (stale-while-revalidate) quando o cache passa de 15 minutos. O app nunca
 * envia escrita para a conta do YouTube.
 */
class YouTubeLibraryRepository(
    private val database: AppDatabase,
    private val youtube: YouTubeRepository,
    private val playlists: PlaylistRepository,
    private val session: SessionManager,
) {

    private val dao get() = database.youTubeLibraryDao()

    fun observePlaylists(userId: Long): Flow<List<YtPlaylistEntity>> =
        dao.observePlaylists(userId)

    fun observePlaylist(userId: Long, ytId: String): Flow<YtPlaylistEntity?> =
        dao.observePlaylist(userId, ytId)

    fun observeHistory(userId: Long, limit: Int = HISTORY_LIMIT): Flow<List<YtHistoryEntity>> =
        dao.observeHistory(userId, limit)

    fun observePlaylistTracks(userId: Long, ytId: String): Flow<List<Track>> =
        dao.observePlaylistTracks(userId, ytId).map { entities ->
            entities.map { entity ->
                Track(
                    videoId = entity.videoId,
                    title = entity.title,
                    artist = entity.artist,
                    thumbnailUrl = entity.thumbnailUrl,
                    durationMs = entity.durationMs,
                    album = entity.album,
                )
            }
        }

    suspend fun refreshPlaylists(userId: Long, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) throw MusicException("Conta Myst inválida")
            if (!force && isFresh(userId, SECTION_PLAYLISTS)) {
                AppLog.d(AppLog.TAG_TUBE, "Playlists do YouTube em cache (< 15 min)")
                return@withContext
            }
            ensureSession()
            val startedAt = System.currentTimeMillis()
            val items = youtube.libraryPlaylists(MAX_PLAYLISTS)
            val now = System.currentTimeMillis()
            val entities = items.map { item ->
                YtPlaylistEntity(
                    userId = userId,
                    ytId = item.id,
                    title = item.title,
                    thumbnailUrl = item.thumbnailUrl,
                    trackCount = item.trackCount,
                    liked = item.liked,
                    syncedAt = now,
                )
            }
            database.withTransaction {
                if (entities.isEmpty()) {
                    dao.deletePlaylists(userId)
                } else {
                    dao.upsertPlaylists(entities)
                    dao.deletePlaylistsNotIn(userId, entities.map { it.ytId })
                }
                dao.upsertSync(YtSyncEntity(userId, SECTION_PLAYLISTS, now))
            }
            AppLog.i(
                AppLog.TAG_TUBE,
                "Biblioteca do YouTube: ${entities.size} playlists em " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        }

    suspend fun refreshHistory(userId: Long, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) throw MusicException("Conta Myst inválida")
            if (!force && isFresh(userId, SECTION_HISTORY)) {
                AppLog.d(AppLog.TAG_TUBE, "Histórico do YouTube em cache (< 15 min)")
                return@withContext
            }
            ensureSession()
            val startedAt = System.currentTimeMillis()
            val tracks = youtube.accountHistory(HISTORY_LIMIT)
            val now = System.currentTimeMillis()
            val entities = tracks.mapIndexed { index, track ->
                YtHistoryEntity(
                    userId = userId,
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    thumbnailUrl = track.thumbnailUrl,
                    durationMs = track.durationMs,
                    // A API informa apenas o dia/seções. Espaçar a ordem
                    // relativa permite intercalar com o playedAt local.
                    playedAt = now - index * HISTORY_STEP_MS,
                )
            }
            database.withTransaction {
                dao.deleteHistory(userId)
                if (entities.isNotEmpty()) dao.upsertHistory(entities)
                dao.upsertSync(YtSyncEntity(userId, SECTION_HISTORY, now))
            }
            AppLog.i(
                AppLog.TAG_TUBE,
                "Histórico do YouTube: ${entities.size} faixas em " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        }

    suspend fun refreshPlaylistTracks(
        userId: Long,
        ytId: String,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (userId <= 0L) throw MusicException("Conta Myst inválida")
        if (ytId.isBlank()) throw MusicException("Playlist inválida")
        val section = playlistSection(ytId)
        if (!force && isFresh(userId, section)) {
            AppLog.d(AppLog.TAG_TUBE, "Faixas de $ytId em cache (< 15 min)")
            return@withContext
        }
        ensureSession()
        val startedAt = System.currentTimeMillis()
        val tracks = youtube.playlistTracks(ytId, MAX_PLAYLIST_TRACKS)
        val now = System.currentTimeMillis()
        val entities = tracks.mapIndexed { index, track ->
            YtPlaylistTrackEntity(
                userId = userId,
                ytId = ytId,
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationMs = track.durationMs,
                position = index,
                syncedAt = now,
            )
        }
        database.withTransaction {
            dao.deletePlaylistTracks(userId, ytId)
            if (entities.isNotEmpty()) dao.upsertPlaylistTracks(entities)
            dao.updatePlaylistCount(userId, ytId, entities.size, now)
            dao.upsertSync(YtSyncEntity(userId, section, now))
        }
        AppLog.i(
            AppLog.TAG_TUBE,
            "Playlist $ytId: ${entities.size} faixas em " +
                "${System.currentTimeMillis() - startedAt}ms",
        )
    }

    /**
     * Cria uma playlist Myst editável com as mesmas faixas. Se o cache da
     * playlist ainda estiver vazio, busca antes. Nenhuma escrita vai para o
     * YouTube.
     */
    suspend fun copyToMyst(userId: Long, ytId: String, fallbackTitle: String): Long =
        withContext(Dispatchers.IO) {
            var tracks = cachedTracks(userId, ytId)
            if (tracks.isEmpty()) {
                refreshPlaylistTracks(userId, ytId, force = true)
                tracks = cachedTracks(userId, ytId)
            }
            if (tracks.isEmpty()) throw MusicException("A playlist não tem músicas")
            val summary = dao.playlist(userId, ytId)
            val name = summary?.title?.takeIf { it.isNotBlank() }
                ?: fallbackTitle.takeIf { it.isNotBlank() }
                ?: "Playlist do YouTube Music"
            AppLog.i(
                AppLog.TAG_TUBE,
                "Copiando playlist $ytId (${tracks.size} faixas) para Myst como \"$name\"",
            )
            val playlistId = playlists.create(userId, name)
            playlists.addTracks(playlistId, tracks)
            summary?.thumbnailUrl?.takeIf { it.startsWith("http") }?.let { cover ->
                playlists.setCover(playlistId, cover)
            }
            playlistId
        }

    private suspend fun cachedTracks(userId: Long, ytId: String): List<Track> =
        dao.playlistTracks(userId, ytId).map { entity ->
            Track(
                videoId = entity.videoId,
                title = entity.title,
                artist = entity.artist,
                thumbnailUrl = entity.thumbnailUrl,
                durationMs = entity.durationMs,
                album = entity.album,
            )
        }

    private suspend fun isFresh(userId: Long, section: String): Boolean {
        val syncedAt = dao.sync(userId, section)?.syncedAt ?: return false
        return System.currentTimeMillis() - syncedAt < CACHE_TTL_MS
    }

    private fun ensureSession() {
        if (session.youtubeCookies.isNullOrBlank()) {
            throw SessionExpiredException("Sessão do YouTube Music não está disponível")
        }
    }

    private fun playlistSection(ytId: String): String = "playlist:$ytId"

    companion object {
        private val CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(15)
        private const val HISTORY_STEP_MS = 60_000L
        private const val HISTORY_LIMIT = 50
        private const val MAX_PLAYLISTS = 300
        private const val MAX_PLAYLIST_TRACKS = 500
        private const val SECTION_PLAYLISTS = "playlists"
        private const val SECTION_HISTORY = "history"
    }
}

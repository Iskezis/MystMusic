package com.iskezis.myst.ui.browse

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.MusicException
import com.iskezis.myst.data.youtube.PlaylistPage
import com.iskezis.myst.data.youtube.SessionExpiredException
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PublicPlaylistViewModel(
    private val app: MystApp,
    private val playlistId: String,
    private val fallbackTitle: String,
    private val fallbackThumbnail: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BrowseDetailState(
            title = fallbackTitle,
            subtitle = app.getString(R.string.browse_playlist),
            thumbnailUrl = fallbackThumbnail.takeIf { it.isNotBlank() },
            showCopy = true,
            emptyMessageRes = R.string.browse_empty_playlist,
        ),
    )
    val state: StateFlow<BrowseDetailState> = _state.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    private val _copied = MutableStateFlow<Long?>(null)
    val copied: StateFlow<Long?> = _copied.asStateFlow()

    private var page: PlaylistPage? = null
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var copyJob: Job? = null

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        _state.update {
            it.copy(
                title = fallbackTitle,
                tracks = LoadState.Loading,
                loadingMore = false,
                loadMoreError = false,
                copying = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = app.youtube.publicPlaylistPage(playlistId)
                page = loaded
                val tracks = loaded.tracks
                _state.value = BrowseDetailState(
                    title = loaded.title.ifBlank { fallbackTitle },
                    subtitle = loaded.author.ifBlank {
                        app.getString(R.string.browse_playlist)
                    },
                    meta = meta(loaded),
                    description = loaded.description,
                    thumbnailUrl = loaded.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: fallbackThumbnail.takeIf { it.isNotBlank() },
                    tracks = if (tracks.isEmpty()) {
                        LoadState.Empty
                    } else {
                        LoadState.Data(tracks)
                    },
                    hasMore = loaded.continuation != null,
                    showCopy = true,
                    emptyMessageRes = R.string.browse_empty_playlist,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha carregando playlist $playlistId", t)
                _state.update {
                    it.copy(
                        tracks = LoadState.Error(t.browseErrorRes()),
                        loadingMore = false,
                        loadMoreError = false,
                    )
                }
            }
        }
    }

    fun loadMore() {
        val loaded = page ?: return
        val token = loaded.continuation ?: return
        if (_state.value.loadingMore) return
        moreJob?.cancel()
        _state.update { it.copy(loadingMore = true, loadMoreError = false) }
        moreJob = viewModelScope.launch {
            try {
                val next = app.youtube.browseContinuation(token)
                val merged = (loaded.tracks + next.tracks).distinctBy { it.videoId }
                val nextToken = next.continuation?.takeIf { it != token }
                page = loaded.copy(tracks = merged, continuation = nextToken)
                _state.update {
                    it.copy(
                        tracks = if (merged.isEmpty()) {
                            LoadState.Empty
                        } else {
                            LoadState.Data(merged)
                        },
                        hasMore = nextToken != null,
                        loadingMore = false,
                        loadMoreError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(
                    AppLog.TAG_TUBE,
                    "Falha carregando mais da playlist $playlistId",
                    t,
                )
                _state.update { it.copy(loadingMore = false, loadMoreError = true) }
            }
        }
    }

    /**
     * Carrega todas as faixas da playlist pública para o "Baixar tudo".
     * Respeita os mesmos limites da cópia para não baixar uma playlist
     * infinita por engano.
     */
    suspend fun loadAllTracks(): List<Track> {
        val loaded = page ?: app.youtube.publicPlaylistPage(playlistId).also { page = it }
        var all = loaded.tracks
        var token = loaded.continuation
        var pages = 0
        while (token != null && all.size < MAX_COPY_TRACKS && pages < MAX_COPY_PAGES) {
            val next = app.youtube.browseContinuation(token)
            all = (all + next.tracks).distinctBy { it.videoId }
            token = next.continuation?.takeIf { it != token }
            pages++
        }
        return all.distinctBy { it.videoId }
    }

    fun copyToMyst() {
        val loaded = page ?: return
        if (_state.value.copying) return
        val userId = app.session.session?.userId ?: run {
            _message.value = R.string.error_login_required
            return
        }
        _state.update { it.copy(copying = true) }
        copyJob?.cancel()
        copyJob = viewModelScope.launch {
            try {
                val all = loaded.tracks.toMutableList()
                var token = loaded.continuation
                var pages = 0
                while (token != null && all.size < MAX_COPY_TRACKS && pages < MAX_COPY_PAGES) {
                    val next = app.youtube.browseContinuation(token)
                    all += next.tracks
                    token = next.continuation?.takeIf { it != token }
                    pages++
                }
                val distinct = all.distinctBy { it.videoId }
                if (distinct.isEmpty()) {
                    throw MusicException("A playlist não tem músicas")
                }
                val name = loaded.title.ifBlank { fallbackTitle }
                    .ifBlank { app.getString(R.string.browse_playlist) }
                AppLog.i(
                    AppLog.TAG_TUBE,
                    "Copiando playlist pública $playlistId (${distinct.size} faixas) como \"$name\"",
                )
                val localId = app.playlists.create(userId, name)
                app.playlists.addTracks(localId, distinct)
                loaded.thumbnailUrl?.takeIf { it.startsWith("http") }?.let { cover ->
                    app.playlists.setCover(localId, cover)
                }
                _state.update { it.copy(copying = false) }
                _copied.value = localId
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(copying = false) }
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(
                    AppLog.TAG_TUBE,
                    "Falha copiando playlist pública $playlistId",
                    t,
                )
                _state.update { it.copy(copying = false) }
                _message.value = when {
                    t is SessionExpiredException -> R.string.yt_session_expired
                    t is MusicException && t.message?.contains("não tem músicas") == true ->
                        R.string.browse_empty_playlist
                    else -> R.string.browse_copy_error
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeCopied() {
        _copied.value = null
    }

    private fun meta(loaded: PlaylistPage): String {
        val count = loaded.trackCount.takeIf { it > 0 } ?: loaded.tracks.size
        return if (count > 0) {
            app.getString(R.string.browse_playlist_meta, count)
        } else {
            ""
        }
    }

    companion object {
        private const val MAX_COPY_TRACKS = 500
        private const val MAX_COPY_PAGES = 12

        fun factory(
            app: Application,
            playlistId: String,
            fallbackTitle: String,
            fallbackThumbnail: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PublicPlaylistViewModel(
                    app = app as MystApp,
                    playlistId = playlistId,
                    fallbackTitle = fallbackTitle,
                    fallbackThumbnail = fallbackThumbnail,
                )
            }
        }
    }
}

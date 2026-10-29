package com.iskezis.myst.ui.playlists

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.data.youtube.MusicException
import com.iskezis.myst.data.youtube.SessionExpiredException
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class YtPlaylistDetailUiState(
    val playlist: YtPlaylistEntity? = null,
    val tracks: LoadState<List<Track>> = LoadState.Loading,
    val sessionExpired: Boolean = false,
    val refreshErrorRes: Int? = null,
    val copying: Boolean = false,
)

/**
 * Detalhe somente leitura de uma playlist do YouTube Music. As faixas são
 * cacheadas no Room; a cópia para a conta Myst é a única escrita e acontece
 * numa playlist local separada.
 */
class YtPlaylistDetailViewModel(
    private val app: MystApp,
    private val ytId: String,
    private val fallbackTitle: String,
) : ViewModel() {

    private val _state = MutableStateFlow(YtPlaylistDetailUiState())
    val state: StateFlow<YtPlaylistDetailUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    private val _copied = MutableStateFlow<Long?>(null)
    val copied: StateFlow<Long?> = _copied.asStateFlow()

    private var playlistJob: Job? = null
    private var tracksJob: Job? = null
    private var refreshJob: Job? = null
    private var syncDone = false

    init {
        AppLog.d(AppLog.TAG_TUBE, "YtPlaylistDetailViewModel init ytId=$ytId")
        observePlaylist()
        observeTracks()
        refreshTracks(force = false)
    }

    fun retry() {
        AppLog.d(AppLog.TAG_TUBE, "YtPlaylistDetail retry ytId=$ytId")
        observePlaylist()
        observeTracks()
        refreshTracks(force = true)
    }

    fun retryTracks() {
        refreshTracks(force = true)
    }

    fun onYouTubeRelinked() {
        AppLog.i(AppLog.TAG_TUBE, "YouTube reconectado; recarregando playlist $ytId")
        app.youtube.invalidate()
        _state.update { it.copy(sessionExpired = false, refreshErrorRes = null) }
        refreshTracks(force = true)
    }

    fun copyToMyst() {
        if (_state.value.copying) return
        val userId = app.session.session?.userId ?: run {
            _message.value = R.string.error_login_required
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(copying = true) }
            try {
                val playlistId = app.ytLibrary.copyToMyst(
                    userId = userId,
                    ytId = ytId,
                    fallbackTitle = _state.value.playlist?.title?.takeIf { it.isNotBlank() }
                        ?: fallbackTitle,
                )
                AppLog.i(AppLog.TAG_TUBE, "Playlist $ytId copiada para Myst: id=$playlistId")
                _state.update { it.copy(copying = false) }
                _copied.value = playlistId
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(copying = false) }
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha copiando playlist $ytId para Myst", t)
                val expired = t is SessionExpiredException
                _state.update {
                    it.copy(
                        copying = false,
                        sessionExpired = it.sessionExpired || expired,
                    )
                }
                _message.value = when {
                    expired -> R.string.yt_session_expired
                    t is MusicException && t.message?.contains("não tem músicas") == true ->
                        R.string.yt_playlist_empty
                    else -> R.string.yt_copy_error
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

    fun consumeRefreshError() {
        _state.update { it.copy(refreshErrorRes = null) }
    }

    private fun observePlaylist() {
        playlistJob?.cancel()
        val userId = app.session.session?.userId ?: return
        playlistJob = viewModelScope.launch {
            app.ytLibrary.observePlaylist(userId, ytId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando playlist $ytId", t)
                }
                .collect { playlist ->
                    _state.update { current ->
                        current.copy(playlist = playlist ?: current.playlist)
                    }
                }
        }
    }

    private fun observeTracks() {
        tracksJob?.cancel()
        val userId = app.session.session?.userId ?: run {
            _state.update {
                it.copy(tracks = LoadState.Error(R.string.error_login_required))
            }
            return
        }
        tracksJob = viewModelScope.launch {
            app.ytLibrary.observePlaylistTracks(userId, ytId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando faixas de $ytId", t)
                    _state.update { current ->
                        if (current.tracks is LoadState.Data) {
                            current.copy(refreshErrorRes = R.string.error_generic)
                        } else {
                            current.copy(tracks = LoadState.Error(R.string.error_generic))
                        }
                    }
                }
                .collect { tracks ->
                    _state.update { current ->
                        when {
                            tracks.isNotEmpty() -> current.copy(tracks = LoadState.Data(tracks))
                            syncDone -> current.copy(tracks = LoadState.Empty)
                            else -> current.copy(tracks = LoadState.Loading)
                        }
                    }
                }
        }
    }

    private fun refreshTracks(force: Boolean) {
        val userId = app.session.session?.userId ?: run {
            _state.update {
                it.copy(tracks = LoadState.Error(R.string.error_login_required))
            }
            return
        }
        if (refreshJob?.isActive == true) {
            AppLog.d(AppLog.TAG_TUBE, "Playlist $ytId já está sincronizando")
            return
        }
        syncDone = false
        _state.update { current ->
            current.copy(
                tracks = if (current.tracks is LoadState.Data) {
                    current.tracks
                } else {
                    LoadState.Loading
                },
                refreshErrorRes = null,
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                app.ytLibrary.refreshPlaylistTracks(userId, ytId, force)
                syncDone = true
                _state.update { current ->
                    current.copy(
                        tracks = if ((current.tracks as? LoadState.Data)?.value?.isNotEmpty() == true) {
                            current.tracks
                        } else {
                            LoadState.Empty
                        },
                        sessionExpired = false,
                        refreshErrorRes = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha sincronizando playlist $ytId", t)
                syncDone = true
                val expired = t is SessionExpiredException
                val res = if (expired) R.string.yt_session_expired else errorRes(t)
                _state.update { current ->
                    val hasData =
                        (current.tracks as? LoadState.Data)?.value?.isNotEmpty() == true
                    current.copy(
                        tracks = if (hasData) current.tracks else LoadState.Error(res),
                        sessionExpired = current.sessionExpired || expired,
                        refreshErrorRes = if (hasData && !expired) res else null,
                    )
                }
            }
        }
    }

    private fun errorRes(t: Throwable): Int = when {
        t is SessionExpiredException -> R.string.yt_session_expired
        t is MusicException -> R.string.error_youtube
        t is IOException -> R.string.error_network
        else -> R.string.error_generic
    }

    companion object {
        fun factory(
            app: Application,
            ytId: String,
            fallbackTitle: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                YtPlaylistDetailViewModel(app as MystApp, ytId, fallbackTitle)
            }
        }
    }
}

package com.iskezis.myst.ui.playlists

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val playlist: PlaylistEntity? = null,
    val tracks: LoadState<List<Track>> = LoadState.Loading,
    val errorRes: Int? = null,
)

class PlaylistDetailViewModel(
    private val app: MystApp,
    private val playlistId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    private var playlistJob: Job? = null
    private var tracksJob: Job? = null

    init {
        AppLog.d(AppLog.TAG_DB, "PlaylistDetailViewModel init id=$playlistId")
        observePlaylist()
        observeTracks()
    }

    fun retry() {
        AppLog.d(AppLog.TAG_DB, "PlaylistDetail retry id=$playlistId")
        observePlaylist()
        observeTracks()
    }

    fun rename(name: String) {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Renomeando playlist $playlistId para ${name.trim()}")
                app.playlists.rename(playlistId, name)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha renomeando playlist $playlistId", t)
                _message.value = R.string.error_generic
            }
        }
    }

    fun setCover(uri: String?) {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Alterando capa da playlist $playlistId: ${uri != null}")
                app.playlists.setCover(playlistId, uri)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha alterando capa da playlist $playlistId", t)
                _message.value = R.string.error_generic
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Removendo playlist $playlistId")
                app.playlists.delete(playlistId)
                _deleted.value = true
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha removendo playlist $playlistId", t)
                _message.value = R.string.error_generic
            }
        }
    }

    fun removeTrack(track: Track) {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Removendo ${track.videoId} da playlist $playlistId")
                app.playlists.removeTrack(playlistId, track.videoId)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha removendo faixa da playlist", t)
                _message.value = R.string.error_generic
                observeTracks()
            }
        }
    }

    fun restoreTrack(track: Track, position: Int) {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Restaurando ${track.videoId} na posição $position")
                app.playlists.restoreTrack(playlistId, track, position)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha restaurando faixa da playlist", t)
                _message.value = R.string.error_generic
                observeTracks()
            }
        }
    }

    fun reorder(tracks: List<Track>) {
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Reordenando ${tracks.size} faixas da playlist $playlistId")
                app.playlists.reorder(playlistId, tracks)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha reordenando playlist $playlistId", t)
                _message.value = R.string.error_generic
                observeTracks()
            }
        }
    }

    fun retryTracks() {
        observeTracks()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun observePlaylist() {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            app.playlists.observePlaylist(playlistId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando playlist $playlistId", t)
                    _state.update {
                        it.copy(loading = false, errorRes = R.string.error_generic)
                    }
                }
                .collect { playlist ->
                    AppLog.d(
                        AppLog.TAG_DB,
                        "Playlist $playlistId observada: ${playlist?.name ?: "<removida>"}",
                    )
                    _state.update {
                        it.copy(loading = false, playlist = playlist)
                    }
                }
        }
    }

    private fun observeTracks() {
        tracksJob?.cancel()
        _state.update { it.copy(tracks = LoadState.Loading) }
        tracksJob = viewModelScope.launch {
            app.playlists.observeTracks(playlistId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando faixas da playlist $playlistId", t)
                    _state.update { it.copy(tracks = LoadState.Error(R.string.error_generic)) }
                }
                .collect { tracks ->
                    AppLog.d(
                        AppLog.TAG_DB,
                        "Faixas da playlist $playlistId: ${tracks.size}",
                    )
                    _state.update {
                        it.copy(tracks = if (tracks.isEmpty()) LoadState.Empty else LoadState.Data(tracks))
                    }
                }
        }
    }

    companion object {
        fun factory(app: Application, playlistId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PlaylistDetailViewModel(app as MystApp, playlistId) }
            }
    }
}

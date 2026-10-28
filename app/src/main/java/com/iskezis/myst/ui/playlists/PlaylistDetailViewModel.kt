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
        observePlaylist()
        observeTracks()
    }

    fun retry() {
        observePlaylist()
        observeTracks()
    }

    fun rename(name: String) {
        viewModelScope.launch {
            try {
                app.playlists.rename(playlistId, name)
            } catch (t: Throwable) {
                _message.value = R.string.error_generic
            }
        }
    }

    fun setCover(uri: String?) {
        viewModelScope.launch {
            try {
                app.playlists.setCover(playlistId, uri)
            } catch (t: Throwable) {
                _message.value = R.string.error_generic
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                app.playlists.delete(playlistId)
                _deleted.value = true
            } catch (t: Throwable) {
                _message.value = R.string.error_generic
            }
        }
    }

    fun removeTrack(track: Track) {
        viewModelScope.launch {
            try {
                app.playlists.removeTrack(playlistId, track.videoId)
            } catch (t: Throwable) {
                _message.value = R.string.error_generic
                observeTracks()
            }
        }
    }

    fun restoreTrack(track: Track, position: Int) {
        viewModelScope.launch {
            try {
                app.playlists.restoreTrack(playlistId, track, position)
            } catch (t: Throwable) {
                _message.value = R.string.error_generic
                observeTracks()
            }
        }
    }

    fun reorder(tracks: List<Track>) {
        viewModelScope.launch {
            try {
                app.playlists.reorder(playlistId, tracks)
            } catch (t: Throwable) {
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
                .catch {
                    _state.update {
                        it.copy(loading = false, errorRes = R.string.error_generic)
                    }
                }
                .collect { playlist ->
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
                .catch {
                    _state.update { it.copy(tracks = LoadState.Error(R.string.error_generic)) }
                }
                .collect { tracks ->
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

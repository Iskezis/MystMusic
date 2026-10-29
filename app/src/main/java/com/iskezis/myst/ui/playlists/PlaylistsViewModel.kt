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
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.data.youtube.MusicException
import com.iskezis.myst.data.youtube.SessionExpiredException
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

data class PlaylistsUiState(
    val loading: Boolean = true,
    val playlists: List<PlaylistEntity> = emptyList(),
    val errorRes: Int? = null,
    val ytPlaylists: List<YtPlaylistEntity> = emptyList(),
    val ytLoading: Boolean = false,
    val ytRefreshing: Boolean = false,
    val ytErrorRes: Int? = null,
    val ytSessionExpired: Boolean = false,
    val ytCached: Boolean = false,
)

class PlaylistsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    private var observeJob: Job? = null
    private var ytObserveJob: Job? = null
    private var refreshJob: Job? = null

    init {
        AppLog.d(AppLog.TAG_DB, "PlaylistsViewModel init")
        observe()
        observeYouTube()
        refreshYouTube(force = false)
    }

    fun retry() = observe()

    fun retryYouTube() {
        AppLog.d(AppLog.TAG_TUBE, "Playlists: tentar novamente o YouTube Music")
        refreshYouTube(force = true)
    }

    fun refresh() {
        refreshYouTube(force = true)
    }

    /** Chamado depois que o usuário entrou de novo com o YouTube. */
    fun onYouTubeRelinked() {
        AppLog.i(AppLog.TAG_TUBE, "Playlists: YouTube reconectado; sincronizando de novo")
        app.youtube.invalidate()
        _state.update { it.copy(ytSessionExpired = false, ytErrorRes = null) }
        refreshYouTube(force = true)
    }

    fun create(name: String, onCreated: (Long) -> Unit) {
        val userId = app.session.session?.userId ?: run {
            AppLog.w(AppLog.TAG_DB, "Criar playlist sem sessão")
            _message.value = R.string.error_login_required
            return
        }
        viewModelScope.launch {
            try {
                AppLog.i(AppLog.TAG_DB, "Criando playlist ${name.trim()} para usuário $userId")
                val id = app.playlists.create(userId, name)
                AppLog.i(AppLog.TAG_DB, "Playlist criada: id=$id")
                onCreated(id)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha criando playlist", t)
                _message.value = R.string.error_generic
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun observe() {
        observeJob?.cancel()
        val userId = app.session.session?.userId
        if (userId == null) {
            _state.update {
                it.copy(loading = false, errorRes = R.string.error_login_required)
            }
            return
        }
        _state.update { it.copy(loading = true, errorRes = null) }
        observeJob = viewModelScope.launch {
            app.playlists.observePlaylists(userId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando playlists", t)
                    _state.update {
                        it.copy(loading = false, errorRes = R.string.error_generic)
                    }
                }
                .collect { list ->
                    AppLog.d(AppLog.TAG_DB, "Playlists observadas: ${list.size}")
                    _state.update {
                        it.copy(loading = false, playlists = list, errorRes = null)
                    }
                }
        }
    }

    private fun observeYouTube() {
        ytObserveJob?.cancel()
        val userId = app.session.session?.userId
        if (userId == null) {
            _state.update { it.copy(ytLoading = false, ytPlaylists = emptyList()) }
            return
        }
        ytObserveJob = viewModelScope.launch {
            app.ytLibrary.observePlaylists(userId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando playlists do YouTube", t)
                    _state.update {
                        it.copy(ytLoading = false, ytErrorRes = R.string.error_generic)
                    }
                }
                .collect { list ->
                    AppLog.d(AppLog.TAG_DB, "Playlists do YouTube observadas: ${list.size}")
                    _state.update { current ->
                        // A carga em andamento controla ytLoading; a emissão do
                        // Room pode chegar vazia antes da sincronização acabar.
                        current.copy(ytPlaylists = list)
                    }
                }
        }
    }

    private fun refreshYouTube(force: Boolean) {
        val userId = app.session.session?.userId ?: run {
            _state.update { it.copy(ytLoading = false, ytErrorRes = R.string.error_login_required) }
            return
        }
        if (refreshJob?.isActive == true) {
            if (force) _state.update { it.copy(ytRefreshing = true) }
            return
        }
        _state.update {
            it.copy(
                ytLoading = it.ytPlaylists.isEmpty(),
                ytRefreshing = force && it.ytPlaylists.isNotEmpty(),
                ytErrorRes = null,
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                app.ytLibrary.refreshPlaylists(userId, force)
                _state.update {
                    it.copy(
                        ytLoading = false,
                        ytRefreshing = false,
                        ytErrorRes = null,
                        ytSessionExpired = false,
                        ytCached = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(ytLoading = false, ytRefreshing = false) }
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha sincronizando playlists do YouTube", t)
                val expired = t is SessionExpiredException
                if (!expired) {
                    AppLog.w(AppLog.TAG_TUBE, "Motivo da falha: ${AppLog.describe(t)}")
                }
                _state.update {
                    it.copy(
                        ytLoading = false,
                        ytRefreshing = false,
                        ytSessionExpired = expired,
                        ytErrorRes = if (expired) null else errorRes(t),
                        ytCached = it.ytPlaylists.isNotEmpty(),
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
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlaylistsViewModel(app as MystApp) }
        }
    }
}

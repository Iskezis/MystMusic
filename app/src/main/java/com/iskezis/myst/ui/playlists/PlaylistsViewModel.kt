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
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val loading: Boolean = true,
    val playlists: List<PlaylistEntity> = emptyList(),
    val errorRes: Int? = null,
)

class PlaylistsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    private var observeJob: Job? = null

    init {
        AppLog.d(AppLog.TAG_DB, "PlaylistsViewModel init")
        observe()
    }

    fun retry() = observe()

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
            _state.value = PlaylistsUiState(
                loading = false,
                errorRes = R.string.error_login_required,
            )
            return
        }
        _state.update { it.copy(loading = true, errorRes = null) }
        observeJob = viewModelScope.launch {
            app.playlists.observePlaylists(userId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando playlists", t)
                    _state.value = PlaylistsUiState(
                        loading = false,
                        errorRes = R.string.error_generic,
                    )
                }
                .collect { list ->
                    AppLog.d(AppLog.TAG_DB, "Playlists observadas: ${list.size}")
                    _state.value = PlaylistsUiState(
                        loading = false,
                        playlists = list,
                        errorRes = null,
                    )
                }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlaylistsViewModel(app as MystApp) }
        }
    }
}

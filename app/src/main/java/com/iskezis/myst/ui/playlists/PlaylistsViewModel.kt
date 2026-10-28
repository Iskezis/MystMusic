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
        observe()
    }

    fun retry() = observe()

    fun create(name: String, onCreated: (Long) -> Unit) {
        val userId = app.session.session?.userId ?: run {
            _message.value = R.string.error_login_required
            return
        }
        viewModelScope.launch {
            try {
                val id = app.playlists.create(userId, name)
                onCreated(id)
            } catch (t: Throwable) {
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
                .catch {
                    _state.value = PlaylistsUiState(
                        loading = false,
                        errorRes = R.string.error_generic,
                    )
                }
                .collect { list ->
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

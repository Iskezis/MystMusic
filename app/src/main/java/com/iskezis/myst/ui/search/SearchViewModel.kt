package com.iskezis.myst.ui.search

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.MusicException
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val tracks: List<Track>) : SearchState
    data object Empty : SearchState
    data class Error(@StringRes val messageRes: Int) : SearchState
}

data class SearchUiState(
    val query: String = "",
    val state: SearchState = SearchState.Idle,
)

class SearchViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun onQueryChanged(raw: String) {
        val query = raw.trim()
        job?.cancel()
        if (query.length < 2) {
            _state.value = SearchUiState(raw, SearchState.Idle)
            return
        }
        _state.value = SearchUiState(raw, SearchState.Loading)
        job = viewModelScope.launch {
            delay(350)
            execute(query, raw)
        }
    }

    fun searchNow() {
        val raw = _state.value.query
        val query = raw.trim()
        if (query.length < 2) return
        job?.cancel()
        _state.value = SearchUiState(raw, SearchState.Loading)
        job = viewModelScope.launch { execute(query, raw) }
    }

    fun retry() {
        searchNow()
    }

    private suspend fun execute(query: String, raw: String) {
        try {
            AppLog.d(AppLog.TAG_TUBE, "Busca: $query")
            val startedAt = System.currentTimeMillis()
            val tracks = app.youtube.search(query, 40)
            AppLog.i(
                AppLog.TAG_TUBE,
                "Busca $query: ${tracks.size} resultados em " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
            _state.value = SearchUiState(
                raw,
                if (tracks.isEmpty()) SearchState.Empty else SearchState.Results(tracks),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Busca falhou: $query", t)
            _state.value = SearchUiState(raw, SearchState.Error(errorRes(t)))
        }
    }

    private fun errorRes(t: Throwable): Int = when {
        t is MusicException -> R.string.error_youtube
        t is IOException -> R.string.error_network
        else -> R.string.error_generic
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app as MystApp) }
        }
    }
}

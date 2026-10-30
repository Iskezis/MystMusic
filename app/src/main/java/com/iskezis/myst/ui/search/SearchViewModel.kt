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
import com.iskezis.myst.data.youtube.SearchFilter
import com.iskezis.myst.data.youtube.SearchPage
import com.iskezis.myst.data.youtube.SessionExpiredException
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
    data class Results(
        val page: SearchPage,
        val loadingMore: Boolean = false,
        val loadMoreError: Boolean = false,
    ) : SearchState

    data object Empty : SearchState
    data class Error(@StringRes val messageRes: Int) : SearchState
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val state: SearchState = SearchState.Idle,
    /** Parâmetros reais devolvidos pelos chips do YouTube, por filtro. */
    val params: Map<SearchFilter, String> = emptyMap(),
)

class SearchViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null

    fun onQueryChanged(raw: String) {
        val query = raw.trim()
        val current = _state.value
        if (query == current.query.trim() && current.query.isNotEmpty()) {
            if (raw != current.query) _state.value = current.copy(query = raw)
            return
        }
        searchJob?.cancel()
        moreJob?.cancel()
        if (query.length < 2) {
            _state.value = current.copy(
                query = raw,
                state = SearchState.Idle,
                params = emptyMap(),
            )
            return
        }
        _state.value = current.copy(
            query = raw,
            state = SearchState.Loading,
            params = emptyMap(),
        )
        searchJob = viewModelScope.launch {
            delay(350)
            execute(query, current.filter, emptyMap())
        }
    }

    fun searchNow() {
        val current = _state.value
        val query = current.query.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        moreJob?.cancel()
        _state.value = current.copy(state = SearchState.Loading)
        searchJob = viewModelScope.launch {
            execute(query, current.filter, current.params)
        }
    }

    fun onFilterChanged(filter: SearchFilter) {
        val current = _state.value
        if (current.filter == filter) return
        searchJob?.cancel()
        moreJob?.cancel()
        val query = current.query.trim()
        if (query.length < 2) {
            _state.value = current.copy(filter = filter, state = SearchState.Idle)
            return
        }
        _state.value = current.copy(filter = filter, state = SearchState.Loading)
        searchJob = viewModelScope.launch {
            execute(query, filter, current.params)
        }
    }

    fun retry() {
        searchNow()
    }

    fun loadMore() {
        val current = _state.value
        val results = current.state as? SearchState.Results ?: return
        val continuation = results.page.continuation ?: return
        if (results.loadingMore) return
        moreJob?.cancel()
        _state.value = current.copy(
            state = results.copy(loadingMore = true, loadMoreError = false),
        )
        moreJob = viewModelScope.launch {
            try {
                val next = app.youtube.searchPage(
                    query = current.query.trim(),
                    filter = current.filter,
                    params = current.params[current.filter],
                    continuation = continuation,
                )
                val latest = _state.value
                val visible = latest.state as? SearchState.Results ?: return@launch
                _state.value = latest.copy(
                    state = SearchState.Results(
                        page = visible.page.append(next),
                        loadingMore = false,
                        loadMoreError = false,
                    ),
                    params = latest.params + next.chips.toParams(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha carregando mais resultados", t)
                val latest = _state.value
                val visible = latest.state as? SearchState.Results ?: return@launch
                _state.value = latest.copy(
                    state = visible.copy(loadingMore = false, loadMoreError = true),
                )
            }
        }
    }

    fun retryLoadMore() {
        loadMore()
    }

    private suspend fun execute(
        query: String,
        filter: SearchFilter,
        params: Map<SearchFilter, String>,
    ) {
        try {
            AppLog.d(AppLog.TAG_TUBE, "Busca completa: $query filtro=${filter.name}")
            val startedAt = System.currentTimeMillis()
            val page = app.youtube.searchPage(query, filter, params[filter])
            val mergedParams = params + page.chips.toParams()
            AppLog.i(
                AppLog.TAG_TUBE,
                "Busca $query/${filter.name}: ${page.tracks.size} músicas, " +
                    "${page.artists.size} artistas, ${page.albums.size} álbuns e " +
                    "${page.playlists.size} playlists em " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
            _state.value = SearchUiState(
                query = _state.value.query,
                filter = filter,
                state = if (page.isEmpty(filter)) {
                    SearchState.Empty
                } else {
                    SearchState.Results(page)
                },
                params = mergedParams,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Busca falhou: $query/${filter.name}", t)
            _state.value = _state.value.copy(
                filter = filter,
                state = SearchState.Error(errorRes(t)),
            )
        }
    }

    private fun errorRes(t: Throwable): Int = when (t) {
        is SessionExpiredException -> R.string.yt_session_expired
        is MusicException -> R.string.error_youtube
        is IOException -> R.string.error_network
        else -> R.string.error_generic
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app as MystApp) }
        }
    }
}

private fun SearchPage.isEmpty(filter: SearchFilter): Boolean = when (filter) {
    SearchFilter.ALL ->
        tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
    SearchFilter.SONGS -> tracks.isEmpty()
    SearchFilter.ARTISTS -> artists.isEmpty()
    SearchFilter.ALBUMS -> albums.isEmpty()
    SearchFilter.PLAYLISTS -> playlists.isEmpty()
}

private fun SearchPage.append(next: SearchPage): SearchPage {
    val token = next.continuation?.takeIf { it != continuation }
    return SearchPage(
        tracks = (tracks + next.tracks).distinctBy { it.videoId },
        artists = (artists + next.artists).distinctBy { it.browseId },
        albums = (albums + next.albums).distinctBy { it.browseId },
        playlists = (playlists + next.playlists).distinctBy { it.browseId },
        chips = if (next.chips.isEmpty()) chips else next.chips,
        continuation = token,
    )
}

private fun List<com.iskezis.myst.data.youtube.SearchChip>.toParams(): Map<SearchFilter, String> =
    mapNotNull { chip -> chip.params?.takeIf { it.isNotBlank() }?.let { chip.filter to it } }
        .toMap()

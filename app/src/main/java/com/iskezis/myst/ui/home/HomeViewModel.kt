package com.iskezis.myst.ui.home

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
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class HomeUiState(
    val accountName: String = "",
    val recent: LoadState<List<Track>> = LoadState.Loading,
    val trending: LoadState<List<Track>> = LoadState.Loading,
    val recommended: LoadState<List<Track>> = LoadState.Loading,
    val refreshing: Boolean = false,
)

class HomeViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var recentJob: Job? = null
    private var refreshJob: Job? = null

    init {
        AppLog.d(AppLog.TAG_TUBE, "HomeViewModel init")
        viewModelScope.launch {
            val user = runCatching { app.accounts.currentUser() }.getOrNull()
            val session = app.session.session
            val name = user?.displayName?.ifBlank { user.username }
                ?: session?.displayName.orEmpty()
            AppLog.d(AppLog.TAG_UI, "Home: usuário atual=$name")
            _state.update { it.copy(accountName = name) }
        }
        observeRecent()
        viewModelScope.launch {
            coroutineScope {
                launch { fetchTrending() }
                launch { fetchRecommended() }
            }
        }
    }

    private fun observeRecent() {
        val userId = app.session.session?.userId ?: run {
            _state.update { it.copy(recent = LoadState.Empty) }
            return
        }
        recentJob?.cancel()
        recentJob = viewModelScope.launch {
            app.recommendations.observeRecent(userId, 20)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando recentes", t)
                    _state.update { current -> current.copy(recent = LoadState.Error(R.string.error_generic)) }
                }
                .collect { tracks ->
                    AppLog.d(AppLog.TAG_DB, "Recentes: ${tracks.size} faixas")
                    _state.update {
                        it.copy(recent = if (tracks.isEmpty()) LoadState.Empty else LoadState.Data(tracks))
                    }
                }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            coroutineScope {
                launch { fetchTrending() }
                launch { fetchRecommended() }
            }
            _state.update { it.copy(refreshing = false) }
        }
        observeRecent()
    }

    fun retryTrending() {
        viewModelScope.launch {
            _state.update { it.copy(trending = LoadState.Loading) }
            fetchTrending()
        }
    }

    fun retryRecommended() {
        viewModelScope.launch {
            _state.update { it.copy(recommended = LoadState.Loading) }
            fetchRecommended()
        }
    }

    fun retryRecent() {
        observeRecent()
    }

    private suspend fun fetchTrending() {
        val result = fetch { app.recommendations.trendingForUser(requireUserId()) }
        _state.update { it.copy(trending = result) }
    }

    private suspend fun fetchRecommended() {
        val result = fetch { app.recommendations.recommendedForUser(requireUserId()) }
        _state.update { it.copy(recommended = result) }
    }

    private fun requireUserId(): Long = app.session.session?.userId ?: 0L

    private suspend fun fetch(block: suspend () -> List<Track>): LoadState<List<Track>> {
        return try {
            val startedAt = System.currentTimeMillis()
            val tracks = block()
            AppLog.i(
                AppLog.TAG_TUBE,
                "Home carregou ${tracks.size} faixas em ${System.currentTimeMillis() - startedAt}ms",
            )
            if (tracks.isEmpty()) LoadState.Empty else LoadState.Data(tracks)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Falha carregando seção da home", t)
            LoadState.Error(errorRes(t))
        }
    }

    private fun errorRes(t: Throwable): Int = when {
        t is MusicException -> R.string.error_youtube
        t is IOException -> R.string.error_network
        else -> R.string.error_generic
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(app as MystApp) }
        }
    }
}

@StringRes
internal fun sectionTitleRes(section: HomeSection): Int = when (section) {
    HomeSection.RECENT -> R.string.section_recent
    HomeSection.TRENDING -> R.string.section_trending
    HomeSection.RECOMMENDED -> R.string.section_recommended
}

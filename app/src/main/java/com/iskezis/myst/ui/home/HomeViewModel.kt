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
import com.iskezis.myst.data.youtube.SessionExpiredException
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class HomeUiState(
    val accountName: String = "",
    val avatarPath: String? = null,
    val recent: LoadState<List<Track>> = LoadState.Loading,
    val trending: LoadState<List<Track>> = LoadState.Loading,
    val recommended: LoadState<List<Track>> = LoadState.Loading,
    val downloads: LoadState<List<Track>> = LoadState.Loading,
    val refreshing: Boolean = false,
    val sessionExpired: Boolean = false,
    val offline: Boolean = false,
)

class HomeViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var recentJob: Job? = null
    private var refreshJob: Job? = null
    private var historyJob: Job? = null
    private var historyLoaded = false
    private var historyErrorRes: Int? = null

    init {
        AppLog.d(AppLog.TAG_TUBE, "HomeViewModel init")
        refreshAccount()
        observeRecent()
        observeConnectivity()
        observeDownloads()
        // Em Alta e Recomendados usam o histórico e a afinidade da conta do
        // YouTube. O histórico é atualizado antes para a primeira carga já
        // refletir os dados reais, não apenas o cache vazio.
        historyJob = viewModelScope.launch {
            loadYouTubeHistory(force = false)
            coroutineScope {
                launch { fetchTrending() }
                launch { fetchRecommended() }
            }
        }
    }

    /** Relê nome/foto da conta Myst ao voltar das Configurações. */
    fun refreshAccount() {
        viewModelScope.launch {
            val user = runCatching { app.accounts.currentUser() }.getOrNull()
            val session = app.session.session
            val name = user?.displayName?.ifBlank { user.username }
                ?: session?.displayName.orEmpty()
            AppLog.d(AppLog.TAG_UI, "Home: usuário atual=$name avatar=${user?.avatarPath != null}")
            _state.update { it.copy(accountName = name, avatarPath = user?.avatarPath) }
        }
    }

    /**
     * Sem internet a Navegação não mostra erros de rede: ela passa a exibir
     * um aviso amigável e as músicas baixadas. Ao voltar a conexão, as seções
     * normais são recarregadas automaticamente.
     */
    private fun observeConnectivity() {
        viewModelScope.launch {
            app.networkMonitor.online.collect { online ->
                val wasOffline = _state.value.offline
                _state.update { it.copy(offline = !online) }
                if (online && wasOffline) {
                    AppLog.i(AppLog.TAG_UI, "Conexão voltou; atualizando a Navegação")
                    refresh()
                }
            }
        }
    }

    private fun observeDownloads() {
        val userId = app.session.session?.userId
        if (userId == null || userId <= 0L) {
            _state.update { it.copy(downloads = LoadState.Empty) }
            return
        }
        viewModelScope.launch {
            app.downloads.observeDone(userId)
                .map { list -> list.map { item -> app.downloads.trackFor(item) } }
                .flowOn(Dispatchers.IO)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando baixadas na home", t)
                    _state.update {
                        it.copy(downloads = LoadState.Error(R.string.downloads_error))
                    }
                }
                .collect { tracks ->
                    _state.update {
                        it.copy(
                            downloads = if (tracks.isEmpty()) {
                                LoadState.Empty
                            } else {
                                LoadState.Data(tracks)
                            },
                        )
                    }
                }
        }
    }

    /** "Verificar conexão" do estado offline. */
    fun retryOffline() {
        if (!app.networkMonitor.isOnline()) return
        _state.update { it.copy(offline = false) }
        refresh()
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
                    val error = historyErrorRes
                    val recent = when {
                        tracks.isNotEmpty() -> LoadState.Data(tracks)
                        !historyLoaded -> LoadState.Loading
                        error != null -> LoadState.Error(error)
                        else -> LoadState.Empty
                    }
                    _state.update { it.copy(recent = recent) }
                }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshAccount()
        historyJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                historyLoaded = false
                loadYouTubeHistory(force = true)
                coroutineScope {
                    launch { fetchTrending() }
                    launch { fetchRecommended() }
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
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
        requestYouTubeHistory(force = true)
    }

    /** Chamado após o usuário entrar de novo com o YouTube. */
    fun onYouTubeRelinked() {
        AppLog.i(AppLog.TAG_TUBE, "YouTube reconectado; atualizando a home")
        app.youtube.invalidate()
        _state.update { it.copy(sessionExpired = false) }
        refresh()
    }

    private fun requestYouTubeHistory(force: Boolean) {
        if (historyJob?.isActive == true || refreshJob?.isActive == true) return
        if (force) {
            historyLoaded = false
            historyErrorRes = null
            _state.update { current ->
                if (current.recent is LoadState.Data) current else current.copy(recent = LoadState.Loading)
            }
        }
        historyJob = viewModelScope.launch { loadYouTubeHistory(force) }
    }

    private suspend fun loadYouTubeHistory(force: Boolean) {
        val userId = app.session.session?.userId ?: run {
            historyLoaded = true
            _state.update { it.copy(recent = LoadState.Empty) }
            return
        }
        try {
            app.ytLibrary.refreshHistory(userId, force)
            historyLoaded = true
            historyErrorRes = null
            _state.update { current ->
                current.copy(
                    sessionExpired = false,
                    recent = if (current.recent is LoadState.Data) {
                        current.recent
                    } else {
                        LoadState.Empty
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Falha sincronizando histórico do YouTube", t)
            historyLoaded = true
            val expired = t is SessionExpiredException
            val error = if (expired) R.string.yt_session_expired else errorRes(t)
            historyErrorRes = error
            _state.update { current ->
                current.copy(
                    sessionExpired = current.sessionExpired || expired,
                    recent = if (current.recent is LoadState.Data) {
                        current.recent
                    } else {
                        LoadState.Error(error)
                    },
                )
            }
        }
    }

    private suspend fun fetchTrending() {
        val result = fetch { app.recommendations.trendingForUser(requireUserId()) }
        _state.update { it.copy(trending = result) }
    }

    private suspend fun fetchRecommended() {
        val userId = requireUserId()
        val personalized = try {
            app.recommendations.personalizedForUser(userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (t is SessionExpiredException) {
                _state.update { it.copy(sessionExpired = true) }
            } else {
                AppLog.w(
                    AppLog.TAG_TUBE,
                    "Home personalizada indisponível; usando algoritmo local: ${AppLog.describe(t)}",
                )
            }
            emptyList()
        }
        if (personalized.isNotEmpty()) {
            _state.update { it.copy(recommended = LoadState.Data(personalized)) }
            return
        }
        val result = fetch { app.recommendations.recommendedForUser(userId) }
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
            if (t is SessionExpiredException) {
                _state.update { it.copy(sessionExpired = true) }
            }
            LoadState.Error(errorRes(t))
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
            initializer { HomeViewModel(app as MystApp) }
        }
    }
}

@StringRes
internal fun sectionTitleRes(section: HomeSection): Int = when (section) {
    HomeSection.RECENT -> R.string.section_recent
    HomeSection.TRENDING -> R.string.section_trending
    HomeSection.RECOMMENDED -> R.string.section_recommended
    HomeSection.OFFLINE -> R.string.section_downloads
}

package com.iskezis.myst.ui.stats

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.stats.StatsAlbum
import com.iskezis.myst.data.stats.StatsArtist
import com.iskezis.myst.data.stats.StatsData
import com.iskezis.myst.data.stats.StatsPeriod
import com.iskezis.myst.data.youtube.AlbumSummary
import com.iskezis.myst.data.youtube.ArtistSummary
import com.iskezis.myst.data.youtube.SearchFilter
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

data class StatsBrowseVisual(
    val browseId: String,
    val thumbnailUrl: String? = null,
)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val state: LoadState<StatsData> = LoadState.Loading,
)

class StatsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private val _artistVisuals = MutableStateFlow<Map<String, StatsBrowseVisual>>(emptyMap())
    val artistVisuals: StateFlow<Map<String, StatsBrowseVisual>> = _artistVisuals.asStateFlow()

    private val _albumVisuals = MutableStateFlow<Map<String, StatsBrowseVisual>>(emptyMap())
    val albumVisuals: StateFlow<Map<String, StatsBrowseVisual>> = _albumVisuals.asStateFlow()

    private val artistJobs = mutableSetOf<String>()
    private val albumJobs = mutableSetOf<String>()
    private val albumAttempts = mutableSetOf<String>()

    private var job: Job? = null
    private var albumJob: Job? = null

    init {
        load(StatsPeriod.WEEK)
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period == _state.value.period && _state.value.state !is LoadState.Error) return
        load(period)
    }

    fun retry() {
        albumAttempts.clear()
        load(_state.value.period)
    }

    fun refresh() {
        albumAttempts.clear()
        load(_state.value.period)
    }

    fun ensureArtistVisuals(artists: List<StatsArtist>) {
        artists.forEach { artist ->
            val name = artist.name.trim()
            if (name.isBlank()) return@forEach
            val key = normalizeName(name)
            if (_artistVisuals.value.containsKey(key) || !artistJobs.add(key)) return@forEach
            viewModelScope.launch {
                try {
                    val visual = searchArtist(name)
                    if (visual != null) {
                        _artistVisuals.update { it + (key to visual) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(
                        AppLog.TAG_TUBE,
                        "Falha buscando a foto do artista \"$name\"",
                        t,
                    )
                } finally {
                    artistJobs.remove(key)
                }
            }
        }
    }

    fun artistVisual(name: String): StatsBrowseVisual? {
        val key = normalizeName(name)
        return if (key.isBlank()) null else _artistVisuals.value[key]
    }

    fun albumVisual(name: String, artist: String): StatsBrowseVisual? {
        val key = albumKey(name, artist)
        return if (key.isBlank()) null else _albumVisuals.value[key]
    }

    /**
     * Usado no toque da linha de artista. A busca também preenche a foto que
     * falta na tela de estatísticas; a chamada pode ser repetida sem problema
     * porque o repositório do YouTube mantém cache de páginas por 5 minutos.
     */
    suspend fun resolveArtist(name: String): StatsBrowseVisual? {
        val lookup = name.trim()
        if (lookup.isBlank()) return null
        artistVisual(lookup)?.let { return it }
        val key = normalizeName(lookup)
        return try {
            val visual = searchArtist(lookup)
            if (visual != null) {
                _artistVisuals.update { it + (key to visual) }
            }
            visual
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Falha resolvendo o artista \"$lookup\"", t)
            null
        }
    }

    suspend fun resolveAlbum(name: String, artist: String): StatsBrowseVisual? {
        val lookup = name.trim()
        if (lookup.isBlank()) return null
        albumVisual(lookup, artist)?.let { return it }
        val key = albumKey(lookup, artist)
        if (!albumJobs.add(key)) return null
        return try {
            val visual = searchAlbum(lookup, artist)
            if (visual != null) {
                _albumVisuals.update { it + (key to visual) }
            }
            visual
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_TUBE, "Falha resolvendo o álbum \"$lookup\"", t)
            null
        } finally {
            albumJobs.remove(key)
        }
    }

    private suspend fun searchArtist(name: String): StatsBrowseVisual? {
        val page = app.youtube.searchPage(
            query = name,
            filter = SearchFilter.ARTISTS,
        )
        val artist = pickArtist(name, page.artists) ?: return null
        return StatsBrowseVisual(
            browseId = artist.browseId,
            thumbnailUrl = artist.thumbnailUrl,
        )
    }

    private suspend fun searchAlbum(name: String, artist: String): StatsBrowseVisual? {
        val query = listOf(name, artist)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (query.isBlank()) return null
        val page = app.youtube.searchPage(
            query = query,
            filter = SearchFilter.ALBUMS,
        )
        val album = pickAlbum(name, artist, page.albums) ?: return null
        return StatsBrowseVisual(
            browseId = album.browseId,
            thumbnailUrl = album.thumbnailUrl,
        )
    }

    private fun pickArtist(name: String, artists: List<ArtistSummary>): ArtistSummary? {
        val target = normalizeName(name)
        if (target.isBlank()) return null
        artists.firstOrNull { normalizeName(it.name) == target }?.let { return it }
        return artists.firstOrNull { candidate ->
            val normalized = normalizeName(candidate.name)
            normalized.length >= 3 &&
                target.length >= 3 &&
                (normalized.startsWith(target) || target.startsWith(normalized))
        }
    }

    private fun pickAlbum(
        name: String,
        artist: String,
        albums: List<AlbumSummary>,
    ): AlbumSummary? {
        val targetTitle = normalizeName(name)
        if (targetTitle.isBlank()) return null
        val targetArtist = normalizeName(artist)
        val candidates = albums.filter { normalizeName(it.title) == targetTitle }
        return candidates.firstOrNull { artistMatches(it.artist, targetArtist) }
            ?: candidates.firstOrNull()
    }

    private fun artistMatches(candidate: String, target: String): Boolean {
        if (target.isBlank()) return true
        val normalized = normalizeName(candidate)
        if (normalized.isBlank()) return false
        if (normalized == target) return true
        if (normalized.length < 3 || target.length < 3) return false
        return normalized.contains(target) || target.contains(normalized)
    }

    private fun albumKey(name: String, artist: String): String {
        val title = normalizeName(name)
        if (title.isBlank()) return ""
        return "$title|${normalizeName(artist)}"
    }

    private fun normalizeName(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()

    private fun load(period: StatsPeriod) {
        job?.cancel()
        _state.update { it.copy(period = period, state = LoadState.Loading) }
        val userId = app.session.session?.userId ?: 0L
        if (userId <= 0L) {
            _state.update { it.copy(state = LoadState.Empty) }
            return
        }
        job = viewModelScope.launch {
            val result = try {
                val data = app.stats.load(userId, period)
                backfillAlbums(userId, data)
                if (data.isEmpty) LoadState.Empty else LoadState.Data(data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha carregando estatísticas", t)
                LoadState.Error(R.string.stats_error_subtitle)
            }
            _state.update { current ->
                if (current.period == period) current.copy(state = result) else current
            }
        }
    }

    /**
     * O histórico antigo pode ter sido gravado antes de o app guardar o álbum.
     * Identificamos até 12 faixas por vez no YouTube Music, atualizamos as
     * tabelas e recarregamos a tela quando algo muda. A tela abre na hora com
     * os dados disponíveis; os álbuns aparecem assim que a busca termina.
     */
    private fun backfillAlbums(userId: Long, data: StatsData) {
        if (data.isEmpty || albumJob?.isActive == true) return
        albumJob = viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                runCatching { app.stats.missingAlbums(userId, 12) }
                    .getOrElse { emptyList() }
            }.filter { albumAttempts.add(it.videoId) }
            if (missing.isEmpty()) return@launch

            var changed = false
            coroutineScope {
                missing.chunked(3).forEach { chunk ->
                    val results = chunk.map { item ->
                        async(Dispatchers.IO) {
                            val track = Track(
                                videoId = item.videoId,
                                title = item.title,
                                artist = item.artist,
                            )
                            val album = runCatching { app.youtube.albumFor(track) }
                                .getOrNull()
                            if (album != null) {
                                runCatching {
                                    app.stats.updateAlbum(userId, item.videoId, album.name)
                                }.isSuccess
                            } else {
                                false
                            }
                        }
                    }.awaitAll()
                    if (results.any { it }) changed = true
                }
            }
            if (changed) {
                AppLog.i(AppLog.TAG_DB, "Álbuns identificados; recarregando estatísticas")
                load(_state.value.period)
            }
        }
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")

        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(app as MystApp) }
        }
    }
}

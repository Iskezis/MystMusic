package com.iskezis.myst.ui.browse

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.youtube.ArtistPage
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistUiState(
    val page: ArtistPage? = null,
    val loading: Boolean = true,
    @StringRes val errorRes: Int? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
)

class ArtistViewModel(
    private val app: MystApp,
    private val artistId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        _state.value = ArtistUiState(loading = true)
        loadJob = viewModelScope.launch {
            try {
                val page = app.youtube.artistPage(artistId)
                _state.value = ArtistUiState(page = page, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha carregando artista $artistId", t)
                _state.value = ArtistUiState(loading = false, errorRes = t.browseErrorRes())
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val page = current.page ?: return
        val token = page.songsContinuation ?: return
        if (current.loadingMore) return
        moreJob?.cancel()
        _state.update { it.copy(loadingMore = true, loadMoreError = false) }
        moreJob = viewModelScope.launch {
            try {
                val next = app.youtube.browseContinuation(token)
                val merged = (page.topTracks + next.tracks).distinctBy { it.videoId }
                val nextToken = next.continuation?.takeIf { it != token }
                _state.update {
                    it.copy(
                        page = page.copy(
                            topTracks = merged,
                            songsContinuation = nextToken,
                        ),
                        loadingMore = false,
                        loadMoreError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(
                    AppLog.TAG_TUBE,
                    "Falha carregando mais músicas do artista $artistId",
                    t,
                )
                _state.update { it.copy(loadingMore = false, loadMoreError = true) }
            }
        }
    }

    /**
     * Lista completa de músicas para "Baixar todas as músicas". Combina a
     * prateleira do artista com a playlist "Músicas em alta" (quando existe) e
     * segue as continuações, sem alterar a lista visível.
     */
    suspend fun loadAllSongs(): List<Track> {
        val page = _state.value.page ?: app.youtube.artistPage(artistId)
        var tracks = page.topTracks
        var token = page.songsContinuation
        var pages = 0
        while (token != null && tracks.size < MAX_DOWNLOAD_TRACKS && pages < MAX_DOWNLOAD_PAGES) {
            val next = app.youtube.browseContinuation(token)
            tracks = (tracks + next.tracks).distinctBy { it.videoId }
            token = next.continuation?.takeIf { it != token }
            pages++
        }
        val topSongsId = page.topSongsBrowseId
        if (!topSongsId.isNullOrBlank() && tracks.size < MAX_DOWNLOAD_TRACKS) {
            runCatching {
                val playlist = app.youtube.publicPlaylistPage(topSongsId)
                tracks = (tracks + playlist.tracks).distinctBy { it.videoId }
                var playlistToken = playlist.continuation
                var playlistPages = 0
                while (
                    playlistToken != null &&
                    tracks.size < MAX_DOWNLOAD_TRACKS &&
                    playlistPages < MAX_DOWNLOAD_PAGES
                ) {
                    val next = app.youtube.browseContinuation(playlistToken)
                    tracks = (tracks + next.tracks).distinctBy { it.videoId }
                    playlistToken = next.continuation?.takeIf { it != playlistToken }
                    playlistPages++
                }
            }.onFailure {
                AppLog.w(
                    AppLog.TAG_TUBE,
                    "Playlist de músicas do artista $artistId indisponível: " +
                        AppLog.describe(it),
                )
            }
        }
        return tracks
    }

    companion object {
        private const val MAX_DOWNLOAD_TRACKS = 500
        private const val MAX_DOWNLOAD_PAGES = 12

        fun factory(
            app: Application,
            artistId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtistViewModel(app as MystApp, artistId) }
        }
    }
}

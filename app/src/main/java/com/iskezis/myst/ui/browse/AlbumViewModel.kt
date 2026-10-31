package com.iskezis.myst.ui.browse

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.AlbumPage
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AlbumViewModel(
    private val app: MystApp,
    private val albumId: String,
    private val fallbackTitle: String,
    private val fallbackArtist: String,
    private val fallbackThumbnail: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BrowseDetailState(
            title = fallbackTitle,
            subtitle = app.getString(R.string.browse_album),
            meta = fallbackArtist,
            thumbnailUrl = fallbackThumbnail.takeIf { it.isNotBlank() },
            emptyMessageRes = R.string.browse_empty_album,
        ),
    )
    val state: StateFlow<BrowseDetailState> = _state.asStateFlow()

    private var page: AlbumPage? = null
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
        _state.update {
            it.copy(
                title = fallbackTitle,
                tracks = LoadState.Loading,
                loadingMore = false,
                loadMoreError = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = app.youtube.albumPage(albumId)
                page = loaded
                val cover = loaded.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: fallbackThumbnail.takeIf { it.isNotBlank() }
                val tracks = loaded.tracks.withCover(cover)
                _state.value = BrowseDetailState(
                    title = loaded.title.ifBlank { fallbackTitle },
                    subtitle = app.getString(R.string.browse_album),
                    meta = meta(loaded),
                    description = loaded.description,
                    thumbnailUrl = cover,
                    tracks = if (tracks.isEmpty()) {
                        LoadState.Empty
                    } else {
                        LoadState.Data(tracks)
                    },
                    hasMore = loaded.continuation != null,
                    showCopy = false,
                    emptyMessageRes = R.string.browse_empty_album,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha carregando álbum $albumId", t)
                _state.update {
                    it.copy(
                        tracks = LoadState.Error(t.browseErrorRes()),
                        loadingMore = false,
                        loadMoreError = false,
                    )
                }
            }
        }
    }

    fun loadMore() {
        val loaded = page ?: return
        val token = loaded.continuation ?: return
        val current = _state.value
        if (current.loadingMore) return
        moreJob?.cancel()
        _state.update { it.copy(loadingMore = true, loadMoreError = false) }
        moreJob = viewModelScope.launch {
            try {
                val next = app.youtube.browseContinuation(token)
                val cover = loaded.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: fallbackThumbnail.takeIf { it.isNotBlank() }
                val merged = (loaded.tracks + next.tracks.withCover(cover))
                    .distinctBy { it.videoId }
                val nextToken = next.continuation?.takeIf { it != token }
                page = loaded.copy(tracks = merged, continuation = nextToken)
                _state.update {
                    it.copy(
                        tracks = if (merged.isEmpty()) {
                            LoadState.Empty
                        } else {
                            LoadState.Data(merged)
                        },
                        hasMore = nextToken != null,
                        loadingMore = false,
                        loadMoreError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_TUBE, "Falha carregando mais do álbum $albumId", t)
                _state.update { it.copy(loadingMore = false, loadMoreError = true) }
            }
        }
    }

    /**
     * Carrega todas as faixas do álbum para o "Baixar tudo". Não altera a
     * lista visível: o download acontece em segundo plano e a tela continua
     * exatamente onde o usuário estava.
     */
    suspend fun loadAllTracks(): List<Track> {
        val loaded = page ?: app.youtube.albumPage(albumId).also { page = it }
        val cover = loaded.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: fallbackThumbnail.takeIf { it.isNotBlank() }
        var tracks = loaded.tracks.withCover(cover)
        var token = loaded.continuation
        var pages = 0
        while (token != null && tracks.size < MAX_DOWNLOAD_TRACKS && pages < MAX_DOWNLOAD_PAGES) {
            val next = app.youtube.browseContinuation(token)
            tracks = (tracks + next.tracks.withCover(cover))
                .distinctBy { it.videoId }
            token = next.continuation?.takeIf { it != token }
            pages++
        }
        return tracks
    }

    private fun meta(loaded: AlbumPage): String {
        val artist = loaded.artist.ifBlank { fallbackArtist }
            .ifBlank { app.getString(R.string.browse_artist_unknown) }
        val count = loaded.tracks.size
        return if (loaded.year.isNotBlank()) {
            app.getString(R.string.browse_album_meta, loaded.year, count, artist)
        } else {
            app.getString(R.string.browse_album_meta_no_year, count, artist)
        }
    }

    /** No YouTube Music todas as faixas do álbum usam a capa do álbum. */
    private fun List<Track>.withCover(cover: String?): List<Track> =
        if (cover.isNullOrBlank()) this else map { it.copy(thumbnailUrl = cover) }

    companion object {
        private const val MAX_DOWNLOAD_TRACKS = 500
        private const val MAX_DOWNLOAD_PAGES = 12

        fun factory(
            app: Application,
            albumId: String,
            fallbackTitle: String,
            fallbackArtist: String,
            fallbackThumbnail: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AlbumViewModel(
                    app = app as MystApp,
                    albumId = albumId,
                    fallbackTitle = fallbackTitle,
                    fallbackArtist = fallbackArtist,
                    fallbackThumbnail = fallbackThumbnail,
                )
            }
        }
    }
}

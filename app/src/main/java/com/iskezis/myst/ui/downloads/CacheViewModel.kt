package com.iskezis.myst.ui.downloads

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.downloads.StoragePreferences
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CachedTrackItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val bytes: Long,
) {
    fun toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs,
        album = album,
    )
}

data class CacheUiState(
    val load: LoadState<List<CachedTrackItem>> = LoadState.Loading,
    val usedBytes: Long = 0L,
    val maxBytes: Long = StoragePreferences.PHONE_DEFAULT_CACHE_BYTES,
    val ageDays: Int = StoragePreferences.DEFAULT_CACHE_AGE_DAYS,
    val enabled: Boolean = true,
    val freeBytes: Long = 0L,
    val count: Int = 0,
    @StringRes val messageRes: Int? = null,
    val clearing: Boolean = false,
)

/**
 * Lista o cache real do Media3 e aplica as preferências de tamanho/retenção.
 * O catálogo de metadados é apenas uma ajuda: a fonte da verdade são as
 * chaves com trechos gravados no cache.
 */
class CacheViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(CacheUiState())
    val state: StateFlow<CacheUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _state.update { it.copy(load = LoadState.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { loadCache() }
                _state.value = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PLAYER, "Falha lendo o cache de áudio", t)
                _state.update {
                    it.copy(load = LoadState.Error(R.string.storage_error))
                }
            }
        }
    }

    fun setMaxBytes(bytes: Long) {
        app.storagePreferences.cacheMaxBytes = bytes
        applyAndRefresh()
    }

    fun setAgeDays(days: Int) {
        app.storagePreferences.cacheMaxAgeDays = days
        applyAndRefresh()
    }

    fun setEnabled(enabled: Boolean) {
        app.storagePreferences.cacheEnabled = enabled
        if (!enabled) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    app.audioCache.clear()
                    app.cacheCatalog.clear()
                }
                refresh()
            }
        } else {
            applyAndRefresh()
        }
    }

    fun clear() {
        if (_state.value.clearing) return
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.audioCache.clear()
                    app.cacheCatalog.clear()
                }
                _state.update { it.copy(messageRes = R.string.storage_cache_cleared) }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PLAYER, "Falha limpando o cache de áudio", t)
                _state.update {
                    it.copy(
                        clearing = false,
                        load = LoadState.Error(R.string.storage_error),
                    )
                }
            }
        }
    }

    fun remove(item: CachedTrackItem) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.audioCache.removeStream(item.videoId)
                    app.cacheCatalog.remove(item.videoId)
                }
                _state.update { it.copy(messageRes = R.string.cache_item_removed) }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(
                    AppLog.TAG_PLAYER,
                    "Falha removendo ${item.videoId} do cache",
                    t,
                )
                _state.update { it.copy(messageRes = R.string.storage_error) }
            }
        }
    }

    fun play(item: CachedTrackItem) {
        app.playerController.play(listOf(item.toTrack()), 0)
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    private fun applyAndRefresh() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.audioCache.cache()
                    app.audioCache.trim()
                    app.cacheCatalog.retainOnly(app.audioCache.cachedVideoIds())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PLAYER, "Falha aplicando limite do cache", t)
            }
            refresh()
        }
    }

    private suspend fun loadCache(): CacheUiState {
        val preferences = app.storagePreferences
        app.audioCache.cache()
        app.audioCache.trim()
        val ids = app.audioCache.cachedVideoIds()
        app.cacheCatalog.retainOnly(ids)
        val catalog = app.cacheCatalog.entries().associateBy { it.videoId }
        val userId = app.session.mystUserId
        val history = if (userId > 0L) {
            app.database.historyDao().recent(userId, HISTORY_LOOKUP_LIMIT)
                .associateBy { it.videoId }
        } else {
            emptyMap()
        }
        val items = ids.mapNotNull { videoId ->
            val bytes = app.audioCache.entryBytes(videoId)
            if (bytes <= 0L) return@mapNotNull null
            val cached = catalog[videoId]
            val played = history[videoId]
            CachedTrackItem(
                videoId = videoId,
                title = cached?.title?.takeIf { it.isNotBlank() }
                    ?: played?.title?.takeIf { it.isNotBlank() }
                    ?: app.getString(R.string.player_unknown_title),
                artist = cached?.artist?.takeIf { it.isNotBlank() }
                    ?: played?.artist?.takeIf { it.isNotBlank() }
                    ?: app.getString(R.string.player_unknown_artist),
                album = cached?.album?.takeIf { it.isNotBlank() }
                    ?: played?.album.orEmpty(),
                thumbnailUrl = cached?.thumbnailUrl
                    ?: played?.thumbnailUrl,
                durationMs = cached?.durationMs?.takeIf { it > 0L }
                    ?: played?.durationMs
                    ?: 0L,
                bytes = bytes,
            )
        }.sortedBy { it.title.lowercase() }

        return CacheUiState(
            load = if (items.isEmpty()) LoadState.Empty else LoadState.Data(items),
            usedBytes = app.audioCache.usedBytes(),
            maxBytes = preferences.cacheMaxBytes,
            ageDays = preferences.cacheMaxAgeDays,
            enabled = preferences.cacheEnabled,
            freeBytes = app.downloadStorage.availableBytes(),
            count = items.size,
        )
    }

    companion object {
        private const val HISTORY_LOOKUP_LIMIT = 500

        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { CacheViewModel(app as MystApp) }
        }
    }
}

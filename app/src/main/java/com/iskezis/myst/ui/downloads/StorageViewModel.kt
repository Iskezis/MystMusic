package com.iskezis.myst.ui.downloads

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.downloads.StoragePreferences
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageUiState(
    val load: LoadState<StorageSummary> = LoadState.Loading,
)

data class StorageSummary(
    val downloadsBytes: Long = 0L,
    val downloadsCount: Int = 0,
    val cacheBytes: Long = 0L,
    val cacheMaxBytes: Long = StoragePreferences.PHONE_DEFAULT_CACHE_BYTES,
    val cacheAgeDays: Int = StoragePreferences.DEFAULT_CACHE_AGE_DAYS,
    val cacheEnabled: Boolean = true,
    val freeBytes: Long = 0L,
    val path: String = "",
    val publicAccess: Boolean = false,
)

class StorageViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _state.value = StorageUiState(load = LoadState.Loading)
        loadJob = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { readSummary() }
                _state.value = StorageUiState(load = LoadState.Data(summary))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha lendo o resumo de armazenamento", t)
                _state.value = StorageUiState(load = LoadState.Error(R.string.storage_error))
            }
        }
    }

    private suspend fun readSummary(): StorageSummary {
        val userId = app.session.mystUserId
        val downloadsBytes = if (userId > 0L) {
            app.database.downloadDao().usedBytes(userId)
        } else {
            0L
        }
        val downloadsCount = if (userId > 0L) {
            app.database.downloadDao().doneCount(userId)
        } else {
            0
        }
        val preferences = app.storagePreferences
        return StorageSummary(
            downloadsBytes = downloadsBytes,
            downloadsCount = downloadsCount,
            cacheBytes = app.audioCache.usedBytes(),
            cacheMaxBytes = preferences.cacheMaxBytes,
            cacheAgeDays = preferences.cacheMaxAgeDays,
            cacheEnabled = preferences.cacheEnabled,
            freeBytes = app.downloadStorage.availableBytes(),
            path = app.downloadStorage.displayPath(),
            publicAccess = app.downloadStorage.hasPublicAccess(),
        )
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { StorageViewModel(app as MystApp) }
        }
    }
}

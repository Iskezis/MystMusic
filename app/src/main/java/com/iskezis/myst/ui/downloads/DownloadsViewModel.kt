package com.iskezis.myst.ui.downloads

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.downloads.DownloadService
import com.iskezis.myst.data.downloads.DownloadStatus
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadsUiState(
    val items: LoadState<List<DownloadEntity>> = LoadState.Loading,
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val path: String = "",
    val publicAccess: Boolean = false,
    val removingAll: Boolean = false,
    val messageRes: Int? = null,
) {
    val count: Int
        get() = (items as? LoadState.Data)?.value?.count {
            it.status == DownloadStatus.DONE
        } ?: 0

    val totalCount: Int
        get() = (items as? LoadState.Data)?.value?.size ?: 0
}

/**
 * Estado da tela Baixadas. A lista vem direto do Room, então o progresso, as
 * falhas e as remoções aparecem sem a tela precisar consultar o serviço.
 */
class DownloadsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe()
        refreshSpace()
    }

    private fun observe() {
        val userId = app.session.session?.userId
        if (userId == null || userId <= 0L) {
            _state.update { it.copy(items = LoadState.Empty) }
            return
        }
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            app.downloads.observe(userId)
                .catch { t ->
                    AppLog.failure(AppLog.TAG_DB, "Falha observando downloads", t)
                    _state.update {
                        it.copy(items = LoadState.Error(R.string.downloads_error))
                    }
                }
                .collect { list ->
                    val ordered = list.sortedWith(
                        compareBy<DownloadEntity> { statusOrder(it.status) }
                            .thenByDescending { it.addedAt },
                    )
                    _state.update {
                        it.copy(
                            items = if (ordered.isEmpty()) {
                                LoadState.Empty
                            } else {
                                LoadState.Data(ordered)
                            },
                            usedBytes = list
                                .filter { item -> item.status == DownloadStatus.DONE }
                                .sumOf { item -> item.bytes },
                        )
                    }
                }
        }
    }

    /** Recalcula espaço livre e pasta; chamado ao voltar da permissão. */
    fun refreshSpace() {
        viewModelScope.launch {
            try {
                val free = withContext(Dispatchers.IO) { app.downloadStorage.availableBytes() }
                val path = withContext(Dispatchers.IO) { app.downloadStorage.displayPath() }
                val publicAccess = withContext(Dispatchers.IO) {
                    app.downloadStorage.hasPublicAccess()
                }
                _state.update {
                    it.copy(freeBytes = free, path = path, publicAccess = publicAccess)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha lendo o espaço de downloads", t)
            }
        }
    }

    fun retry() {
        _state.update { it.copy(items = LoadState.Loading) }
        observe()
        refreshSpace()
    }

    /**
     * Remove uma faixa. Se ela ainda estiver na fila, pede o cancelamento ao
     * serviço antes de apagar a linha; o serviço confere a linha antes de
     * renomear o arquivo, então nada fica órfão.
     */
    fun remove(item: DownloadEntity) {
        val userId = app.session.session?.userId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (
                        item.status == DownloadStatus.QUEUED ||
                        item.status == DownloadStatus.DOWNLOADING
                    ) {
                        DownloadService.cancel(app, item.videoId)
                    }
                    app.downloads.remove(userId, item.videoId)
                }
                refreshSpace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha removendo download", t)
                _state.update { it.copy(messageRes = R.string.downloads_error) }
            }
        }
    }

    fun removeAll() {
        val userId = app.session.session?.userId ?: return
        if (_state.value.removingAll) return
        _state.update { it.copy(removingAll = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DownloadService.cancelAll(app)
                    app.downloads.cancelAll(userId, "Removido pelo usuário")
                    app.downloads.removeAll(userId)
                }
                refreshSpace()
                _state.update {
                    it.copy(
                        removingAll = false,
                        messageRes = R.string.downloads_removed_all,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha removendo todos os downloads", t)
                _state.update {
                    it.copy(
                        removingAll = false,
                        messageRes = R.string.downloads_error,
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    private fun statusOrder(status: String): Int = when (status) {
        DownloadStatus.DOWNLOADING -> 0
        DownloadStatus.QUEUED -> 1
        DownloadStatus.FAILED -> 2
        DownloadStatus.DONE -> 3
        else -> 4
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { DownloadsViewModel(app as MystApp) }
        }
    }
}

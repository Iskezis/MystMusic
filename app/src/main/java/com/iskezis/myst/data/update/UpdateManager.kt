package com.iskezis.myst.data.update

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import com.iskezis.myst.R
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Available(val release: AppRelease) : UpdateCheckState
    data class Failed(@StringRes val messageRes: Int) : UpdateCheckState
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val release: AppRelease,
        val downloaded: Long,
        val total: Long,
    ) : UpdateDownloadState
    data class Ready(val release: AppRelease, val file: File) : UpdateDownloadState
    data class Failed(
        val release: AppRelease,
        @StringRes val messageRes: Int,
    ) : UpdateDownloadState
}

/**
 * Estado de atualização compartilhado pelo processo. A verificação automática
 * acontece no máximo a cada 6 horas; abrir o app de novo (novo processo) volta
 * a mostrar o pop-up se ainda houver uma versão nova, mesmo que o usuário
 * tenha escolhido "Agora não" na execução anterior.
 */
class UpdateManager(context: Context) {

    private val repository = GitHubUpdateRepository(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _checkState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    @Volatile
    var dismissedVersionCode: Int = -1
        private set

    /** Diz à UI se o resultado que chegou veio do botão do GitHub. */
    @Volatile
    var lastCheckWasManual: Boolean = false
        private set

    /**
     * Fica ligado quando o usuário foi levado às configurações para liberar a
     * instalação. Ao voltar para o app, a Home tenta abrir o instalador de novo.
     */
    @Volatile
    var installPermissionPending: Boolean = false
        private set

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastAutomaticCheck = 0L

    fun check(manual: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!manual) {
            if (checkJob?.isActive == true) return
            if (lastAutomaticCheck != 0L &&
                now - lastAutomaticCheck < AUTOMATIC_CHECK_INTERVAL_MS
            ) {
                return
            }
        }
        lastAutomaticCheck = now
        lastCheckWasManual = manual
        checkJob?.cancel()
        _checkState.value = UpdateCheckState.Checking
        checkJob = scope.launch {
            val state = try {
                when (val result = repository.check()) {
                    is UpdateCheckResult.UpToDate -> UpdateCheckState.UpToDate
                    is UpdateCheckResult.Available -> UpdateCheckState.Available(result.release)
                    is UpdateCheckResult.Failure -> UpdateCheckState.Failed(result.messageRes)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UPDATE, "Falha inesperada verificando atualizações", t)
                UpdateCheckState.Failed(R.string.update_error_network)
            }
            _checkState.value = state
        }
    }

    fun dismiss(release: AppRelease) {
        dismissedVersionCode = release.versionCode
    }

    fun markInstallPermissionPending() {
        installPermissionPending = true
    }

    fun clearInstallPermissionPending() {
        installPermissionPending = false
    }

    fun download(release: AppRelease) {
        downloadJob?.cancel()
        repository.cancelDownload()
        _downloadState.value = UpdateDownloadState.Downloading(
            release = release,
            downloaded = 0L,
            total = release.assetSize,
        )
        downloadJob = scope.launch {
            try {
                val file = repository.download(release) { downloaded, total ->
                    _downloadState.value = UpdateDownloadState.Downloading(
                        release = release,
                        downloaded = downloaded,
                        total = total,
                    )
                }
                _downloadState.value = UpdateDownloadState.Ready(release, file)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UPDATE, "Atualização não foi baixada", t)
                _downloadState.value = UpdateDownloadState.Failed(
                    release = release,
                    messageRes = (t as? UpdateException)?.messageRes
                        ?: R.string.update_download_error,
                )
            }
        }
    }

    fun cancelDownload() {
        repository.cancelDownload()
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = UpdateDownloadState.Idle
    }

    fun markInstallLaunched() {
        _downloadState.value = UpdateDownloadState.Idle
    }

    companion object {
        private const val AUTOMATIC_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

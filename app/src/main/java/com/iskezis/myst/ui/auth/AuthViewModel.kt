package com.iskezis.myst.ui.auth

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AccountFileException
import com.iskezis.myst.data.account.AccountRepository
import com.iskezis.myst.data.account.InvalidCredentialsException
import com.iskezis.myst.data.account.UsernameTakenException
import com.iskezis.myst.data.account.YouTubeLinkResult
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class YouTubeConflictUi(
    val linkedName: String,
    val newName: String,
)

/** Conta Myst encontrada no banco local, usada para preencher o login. */
data class LocalAccountUi(
    val username: String,
    val displayName: String,
)

data class AuthState(
    val loading: Boolean = false,
    val errorRes: Int? = null,
    val mystReady: Boolean = false,
    val mystName: String = "",
    val mystAvatarPath: String? = null,
    val youtubeReady: Boolean = false,
    val youtubeName: String = "",
    val youtubeConflict: YouTubeConflictUi? = null,
    val needsLegacyTransfer: Boolean = false,
    val migrationNotice: Boolean = false,
    val navigate: Boolean = false,
    val localAccounts: List<LocalAccountUi> = emptyList(),
    val pendingAccountFileUserId: Long? = null,
    val pendingAccountFileUsername: String = "",
    val messageRes: Int? = null,
)

class AuthViewModel(private val accounts: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                refreshState()
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha lendo o estado das contas", t)
                _state.value = _state.value.copy(
                    loading = false,
                    errorRes = R.string.auth_error_generic,
                )
            }
        }
    }

    fun login(username: String, password: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Login Myst solicitado para usuário $username")
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                accounts.login(username, password)
                AppLog.i(AppLog.TAG_AUTH, "Login Myst concluído")
                refreshState()
            } catch (t: Throwable) {
                AppLog.w(AppLog.TAG_AUTH, "Login Myst falhou: ${AppLog.describe(t)}")
                _state.value = _state.value.copy(loading = false, errorRes = mapError(t))
            }
        }
    }

    fun register(username: String, password: String, avatarPath: String?) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Cadastro Myst solicitado para usuário $username")
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                val result = accounts.register(username, password, avatarPath)
                AppLog.i(AppLog.TAG_AUTH, "Cadastro Myst concluído")
                refreshState(
                    pendingAccountFileUserId = result.userId,
                    pendingAccountFileUsername = username.trim().lowercase(),
                )
            } catch (t: Throwable) {
                AppLog.w(AppLog.TAG_AUTH, "Cadastro Myst falhou: ${AppLog.describe(t)}")
                _state.value = _state.value.copy(loading = false, errorRes = mapError(t))
            }
        }
    }

    /**
     * Login pelo Mystaccount.json. O arquivo é lido no repositório (IO), a
     * conta é restaurada/mesclada e a sessão Myst é aberta. O YouTube é pedido
     * em seguida, porque a sessão dele não viaja no arquivo.
     */
    fun importAccount(uri: Uri) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Restauração por Mystaccount.json solicitada")
            _state.value = _state.value.copy(loading = true, errorRes = null, messageRes = null)
            try {
                accounts.importAccount(uri)
                AppLog.i(AppLog.TAG_AUTH, "Restauração por Mystaccount.json concluída")
                refreshState(messageRes = R.string.account_file_restored)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha restaurando Mystaccount.json", t)
                _state.value = _state.value.copy(loading = false, errorRes = mapError(t))
            }
        }
    }

    /**
     * Chamado depois que a pessoa escolhe (ou cancela) onde salvar o arquivo
     * criado no cadastro. Libera a navegação em qualquer um dos casos; a cópia
     * interna continua sendo atualizada e pode ser salva depois nas
     * Configurações.
     */
    fun completeAccountFileExport(uri: Uri?) {
        val userId = _state.value.pendingAccountFileUserId ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            if (uri == null) {
                refreshState(messageRes = R.string.account_file_later_hint)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, errorRes = null, messageRes = null)
            try {
                val result = accounts.saveAccountFile(userId, uri)
                refreshState(
                    messageRes = if (result.autoSync) {
                        R.string.account_file_saved
                    } else {
                        R.string.account_file_saved_no_auto
                    },
                )
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha salvando Mystaccount.json", t)
                refreshState(messageRes = R.string.account_file_error)
            }
        }
    }

    fun loginYouTube(cookies: String, fallbackName: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Login YouTube solicitado (cookies=${cookies.length} bytes)")
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                when (val result = accounts.prepareYouTubeLink(cookies, fallbackName)) {
                    is YouTubeLinkResult.Linked -> {
                        AppLog.i(AppLog.TAG_AUTH, "Login YouTube concluído: ${result.name}")
                        refreshState()
                    }
                    is YouTubeLinkResult.Conflict -> {
                        AppLog.w(
                            AppLog.TAG_AUTH,
                            "Conflito de vínculo do YouTube: vinculada=${result.linkedName} " +
                                "nova=${result.newName}",
                        )
                        _state.value = _state.value.copy(
                            loading = false,
                            youtubeConflict = YouTubeConflictUi(
                                linkedName = result.linkedName,
                                newName = result.newName,
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Login YouTube falhou", t)
                _state.value = _state.value.copy(loading = false, errorRes = mapError(t))
            }
        }
    }

    fun confirmYouTubeConflict() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                if (accounts.hasPendingYouTube()) {
                    accounts.confirmYouTubeLink()
                } else {
                    accounts.confirmMystYouTubeLink()
                }
                refreshState()
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha confirmando vínculo do YouTube", t)
                _state.value = _state.value.copy(
                    loading = false,
                    errorRes = R.string.auth_error_generic,
                )
            }
        }
    }

    fun useLinkedYouTube() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                accounts.cancelYouTubeLink()
                val disconnected = accounts.useLinkedYouTube()
                refreshState(
                    errorRes = if (disconnected) {
                        R.string.auth_youtube_conflict_cancelled
                    } else {
                        null
                    },
                )
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha usando a conta do YouTube vinculada", t)
                _state.value = _state.value.copy(
                    loading = false,
                    errorRes = R.string.auth_error_generic,
                )
            }
        }
    }

    fun dismissYouTubeConflict() {
        _state.value = _state.value.copy(youtubeConflict = null)
    }

    fun confirmLegacyTransfer() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                if (accounts.transferLegacyData()) {
                    refreshState()
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorRes = R.string.auth_legacy_error,
                    )
                }
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha trazendo dados da versão anterior", t)
                _state.value = _state.value.copy(
                    loading = false,
                    errorRes = R.string.auth_legacy_error,
                )
            }
        }
    }

    fun skipLegacyTransfer() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorRes = null)
            try {
                accounts.skipLegacyTransfer()
                refreshState()
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha ignorando dados da versão anterior", t)
                _state.value = _state.value.copy(
                    loading = false,
                    errorRes = R.string.auth_error_generic,
                )
            }
        }
    }

    fun clearError() {
        if (_state.value.errorRes != null) {
            _state.value = _state.value.copy(errorRes = null)
        }
    }

    fun consumeMessage() {
        if (_state.value.messageRes != null) {
            _state.value = _state.value.copy(messageRes = null)
        }
    }

    private suspend fun refreshState(
        errorRes: Int? = null,
        pendingAccountFileUserId: Long? = null,
        pendingAccountFileUsername: String = "",
        messageRes: Int? = null,
    ) {
        val snapshot = try {
            accounts.snapshot()
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha lendo o estado das contas", t)
            _state.value = _state.value.copy(
                loading = false,
                errorRes = errorRes ?: R.string.auth_error_generic,
            )
            return
        }
        val localAccounts = runCatching { accounts.localAccounts() }
            .onFailure { AppLog.failure(AppLog.TAG_AUTH, "Falha listando contas locais", it) }
            .getOrDefault(emptyList())
            .map { user ->
                LocalAccountUi(
                    username = user.username,
                    displayName = user.displayName.ifBlank { user.username },
                )
            }
        val navigate = snapshot.mystReady &&
            snapshot.youtubeReady &&
            !snapshot.youtubeConflict &&
            !snapshot.needsLegacyTransfer &&
            pendingAccountFileUserId == null
        val conflict = if (snapshot.youtubeConflict) {
            YouTubeConflictUi(
                linkedName = snapshot.linkedYouTubeName.ifBlank {
                    snapshot.youtubeName.ifBlank { "YouTube" }
                },
                newName = snapshot.youtubeName.ifBlank { "YouTube" },
            )
        } else {
            null
        }
        _state.value = AuthState(
            loading = false,
            errorRes = errorRes,
            mystReady = snapshot.mystReady,
            mystName = snapshot.mystName,
            mystAvatarPath = snapshot.mystAvatarPath,
            youtubeReady = snapshot.youtubeReady,
            youtubeName = snapshot.youtubeName,
            youtubeConflict = conflict,
            needsLegacyTransfer = snapshot.needsLegacyTransfer,
            migrationNotice = snapshot.mystReady xor snapshot.youtubeReady,
            navigate = navigate,
            localAccounts = localAccounts,
            pendingAccountFileUserId = pendingAccountFileUserId,
            pendingAccountFileUsername = pendingAccountFileUsername,
            messageRes = messageRes,
        )
    }

    private fun mapError(t: Throwable): Int = when (t) {
        is UsernameTakenException -> R.string.auth_error_exists
        is InvalidCredentialsException -> R.string.auth_error_invalid
        is AccountFileException -> when (t.reason) {
            AccountFileException.Reason.INVALID -> R.string.account_file_invalid
            AccountFileException.Reason.CORRUPT -> R.string.account_file_corrupt
            AccountFileException.Reason.TOO_LARGE -> R.string.account_file_too_large
            AccountFileException.Reason.READ_FAILED -> R.string.account_file_read_error
        }
        else -> R.string.auth_error_generic
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel((app as MystApp).accounts)
            }
        }
    }
}

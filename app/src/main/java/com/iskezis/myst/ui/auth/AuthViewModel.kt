package com.iskezis.myst.ui.auth

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
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
                accounts.register(username, password, avatarPath)
                AppLog.i(AppLog.TAG_AUTH, "Cadastro Myst concluído")
                refreshState()
            } catch (t: Throwable) {
                AppLog.w(AppLog.TAG_AUTH, "Cadastro Myst falhou: ${AppLog.describe(t)}")
                _state.value = _state.value.copy(loading = false, errorRes = mapError(t))
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

    private suspend fun refreshState(errorRes: Int? = null) {
        val snapshot = accounts.snapshot()
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
            !snapshot.needsLegacyTransfer
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
        )
    }

    private fun mapError(t: Throwable): Int = when (t) {
        is UsernameTakenException -> R.string.auth_error_exists
        is InvalidCredentialsException -> R.string.auth_error_invalid
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

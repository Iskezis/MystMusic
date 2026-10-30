package com.iskezis.myst.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AccountFileStatus
import com.iskezis.myst.data.discord.DiscordState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SettingsState(
    val name: String = "",
    val initial: String = "",
    val avatarPath: String? = null,
    val youtubeConnected: Boolean = false,
    val youtubeName: String = "",
    val accountFileStatus: AccountFileStatus = AccountFileStatus(),
    val accountFileSaving: Boolean = false,
    val pairedTv: Boolean = false,
    val pairedTvName: String = "",
    val tvDisconnecting: Boolean = false,
)

class SettingsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** A TV não respondeu; a tela oferece remover o pareamento só no celular. */
    private val _forceDisconnectPrompt = MutableStateFlow(false)
    val forceDisconnectPrompt: StateFlow<Boolean> = _forceDisconnectPrompt.asStateFlow()

    val discord: StateFlow<DiscordState> = app.discord.state

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            // A TV pode desfazer o pareamento enquanto esta tela está aberta.
            // O fluxo da sessão esconde "Desconectar TV" imediatamente.
            app.session.tvPairingVersion.collect { updatePairingState() }
        }
        viewModelScope.launch {
            app.accountFiles.status.collect { status ->
                _state.value = _state.value.copy(
                    accountFileStatus = statusForCurrentUser(status),
                )
            }
        }
    }

    fun connectDiscord(token: String) {
        app.discord.connect(token)
        // O token fica na sessão imediatamente; a TV pareada recebe a conexão
        // sem esperar a próxima sincronização periódica.
        app.pairedSync.requestSync()
    }

    /** Atualiza o status da TV ao voltar do pareamento ou de outra tela. */
    fun refreshPairing() {
        updatePairingState()
        if (!app.session.hasTvPairing) return
        // Se o aviso de despareamento da TV não chegou (app do celular estava
        // fechado), confere com a TV. A chamada é assíncrona e tem throttle.
        viewModelScope.launch {
            app.pairedSync.verifyTvPairing()
            updatePairingState()
        }
    }

    private fun updatePairingState() {
        _state.value = _state.value.copy(
            pairedTv = app.session.hasTvPairing,
            pairedTvName = app.session.tvName,
        )
    }

    /**
     * Pede para a TV encerrar o pareamento. O botão fica desabilitado durante
     * a descoberta na rede, mas a remoção local nunca fica refém da TV: se ela
     * não responder, o pareamento deste celular é removido e o usuário pode
     * parear de novo na hora.
     */
    fun disconnectTv(forceLocal: Boolean = false) {
        if (_state.value.tvDisconnecting) return
        if (!app.session.hasTvPairing) {
            refreshPairing()
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(tvDisconnecting = true)
            try {
                val notified = app.pairedSync.disconnect(forceLocal = forceLocal)
                _message.value = if (!notified) {
                    R.string.settings_tv_force_disconnect_success
                } else {
                    R.string.settings_tv_disconnect_success
                }
                AppLog.i(
                    AppLog.TAG_PAIRING,
                    "TV desconectada pelo botão das Configurações (remota=$notified)",
                )
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha desconectando a TV", t)
                _error.value = R.string.settings_tv_disconnect_error
            } finally {
                _state.value = _state.value.copy(tvDisconnecting = false)
                refreshPairing()
            }
        }
    }

    fun consumeForceDisconnectPrompt() {
        _forceDisconnectPrompt.value = false
    }

    /**
     * Botão exclusivo da TV. Tenta avisar o celular e sempre limpa os dados
     * temporários locais, porque a TV é o terminal que precisa voltar ao
     * código de 5 dígitos mesmo se o celular estiver desligado.
     */
    fun disconnectFromPhone() {
        if (_state.value.tvDisconnecting) return
        if (!app.session.hasTvPairing) {
            refreshPairing()
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(tvDisconnecting = true)
            try {
                app.pairedSync.disconnectOnTv()
                _message.value = R.string.settings_tv_phone_disconnect_success
                AppLog.i(AppLog.TAG_PAIRING, "TV desconectada pelo próprio controle remoto")
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha desconectando a TV pelo controle", t)
                _error.value = R.string.settings_tv_disconnect_error
            } finally {
                _state.value = _state.value.copy(tvDisconnecting = false)
                refreshPairing()
            }
        }
    }

    fun retryDiscord() {
        app.discord.retry()
        app.pairedSync.requestSync()
    }

    fun disconnectDiscord() {
        app.discord.disconnect()
        app.pairedSync.requestSync()
    }

    fun hasDiscordToken(): Boolean = !app.session.discordToken.isNullOrBlank()

    fun changeAvatar(sourcePath: String) {
        viewModelScope.launch {
            val userId = app.session.mystUserId
            if (userId <= 0L) {
                _error.value = R.string.settings_avatar_error
                return@launch
            }
            val saved = try {
                app.accounts.updateAvatar(userId, sourcePath)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UI, "Falha atualizando avatar nas Configurações", t)
                null
            }
            if (saved == null) {
                _error.value = R.string.settings_avatar_error
                return@launch
            }
            // A chave do Coil inclui tamanho e data do arquivo: o avatar novo
            // usa outra chave, então a foto anterior não reaparece na tela.
            refresh()
        }
    }

    /**
     * Salva uma nova cópia do Mystaccount.json no local escolhido. Se o
     * provedor conceder permissão persistente, a cópia passa a ser atualizada
     * junto com a cópia interna a cada mudança da conta.
     */
    fun saveAccountFile(uri: Uri) {
        if (_state.value.accountFileSaving) return
        val userId = app.session.mystUserId
        if (userId <= 0L) {
            _error.value = R.string.account_file_error
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(accountFileSaving = true)
            try {
                val result = app.accounts.saveAccountFile(userId, uri)
                _message.value = if (result.autoSync) {
                    R.string.account_file_saved
                } else {
                    R.string.account_file_saved_no_auto
                }
                refresh()
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha salvando Mystaccount.json nas Configurações", t)
                _error.value = R.string.account_file_error
            } finally {
                _state.value = _state.value.copy(accountFileSaving = false)
            }
        }
    }

    /** Reexecuta a cópia automática, por exemplo ao voltar da tela de permissão. */
    fun refreshAccountFile() {
        app.accountFiles.requestSync()
    }

    fun logoutMyst() = logout { app.accounts.logoutMyst() }

    fun logoutYouTube() = logout { app.accounts.logoutYouTube() }

    fun logoutAll() = logout { app.accounts.logoutAll() }

    fun consumeError() {
        _error.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun logout(action: suspend () -> Unit) {
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Logout solicitado nas Configurações")
            runCatching { app.discord.disconnect() }
                .onFailure {
                    AppLog.failure(AppLog.TAG_UI, "Falha desconectando Discord no logout", it)
                }
            // Antes de apagar o segredo local, avisa a TV para ela limpar os
            // dados temporários. Se a TV estiver desligada/fora da rede, o
            // logout continua; nesse caso o pareamento local é removido e a
            // TV apaga tudo quando o processo dela terminar ou no próximo
            // logout/desconexão.
            if (BuildConfig.FLAVOR != "tv" && app.session.hasTvPairing) {
                val disconnected = withTimeoutOrNull(3_000) {
                    runCatching { app.pairedSync.disconnect() }.isSuccess
                } ?: false
                if (!disconnected) {
                    AppLog.w(
                        AppLog.TAG_PAIRING,
                        "TV não respondeu antes do logout; pareamento local removido",
                    )
                }
            }
            try {
                action()
                _loggedOut.value = true
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha no logout", t)
                _error.value = R.string.error_generic
            }
        }
    }

    private suspend fun refresh() {
        val snapshot = app.accounts.snapshot()
        val name = snapshot.mystName.ifBlank { app.session.mystDisplayName }
        AppLog.d(
            AppLog.TAG_UI,
            "Settings: myst=${snapshot.mystReady} youtube=${snapshot.youtubeReady} nome=$name",
        )
        _state.value = SettingsState(
            name = name,
            initial = name.take(1).uppercase(),
            avatarPath = snapshot.mystAvatarPath,
            youtubeConnected = snapshot.youtubeReady,
            youtubeName = snapshot.youtubeName,
            accountFileStatus = statusForCurrentUser(app.accountFiles.status.value),
            accountFileSaving = _state.value.accountFileSaving,
            pairedTv = app.session.hasTvPairing,
            pairedTvName = app.session.tvName,
            tvDisconnecting = _state.value.tvDisconnecting,
        )
    }

    private fun statusForCurrentUser(status: AccountFileStatus): AccountFileStatus =
        if (status.userId == app.session.mystUserId) {
            status
        } else {
            AccountFileStatus(userId = app.session.mystUserId)
        }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(app as MystApp) }
        }
    }
}

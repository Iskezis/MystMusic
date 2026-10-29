package com.iskezis.myst.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.imageLoader
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.discord.DiscordState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val name: String = "",
    val initial: String = "",
    val avatarPath: String? = null,
    val youtubeConnected: Boolean = false,
    val youtubeName: String = "",
)

class SettingsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    val discord: StateFlow<DiscordState> = app.discord.state

    init {
        viewModelScope.launch { refresh() }
    }

    fun connectDiscord(token: String) {
        app.discord.connect(token)
    }

    fun retryDiscord() {
        app.discord.retry()
    }

    fun disconnectDiscord() {
        app.discord.disconnect()
    }

    fun hasDiscordToken(): Boolean = !app.session.discordToken.isNullOrBlank()

    fun changeAvatar(sourcePath: String) {
        viewModelScope.launch {
            val userId = app.session.mystUserId
            if (userId <= 0L) {
                _error.value = R.string.settings_avatar_error
                return@launch
            }
            val oldPath = _state.value.avatarPath
            val saved = app.accounts.updateAvatar(userId, sourcePath)
            if (saved == null) {
                _error.value = R.string.settings_avatar_error
                return@launch
            }
            // A chave do Coil inclui tamanho e data do arquivo; limpar a antiga
            // evita que a foto anterior reapareça ao voltar para a tela.
            AvatarStore.cacheKey(oldPath)?.let { key ->
                runCatching { app.imageLoader.memoryCache?.remove(key) }
            }
            refresh()
        }
    }

    fun logoutMyst() = logout { app.accounts.logoutMyst() }

    fun logoutYouTube() = logout { app.accounts.logoutYouTube() }

    fun logoutAll() = logout { app.accounts.logoutAll() }

    fun consumeError() {
        _error.value = null
    }

    private fun logout(action: suspend () -> Unit) {
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Logout solicitado nas Configurações")
            runCatching { app.discord.disconnect() }
                .onFailure {
                    AppLog.failure(AppLog.TAG_UI, "Falha desconectando Discord no logout", it)
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
        )
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(app as MystApp) }
        }
    }
}

package com.iskezis.myst.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.discord.DiscordState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val name: String = "",
    val accountType: String = "",
    val initial: String = "",
)

class SettingsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    val discord: StateFlow<DiscordState> = app.discord.state

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

    init {
        viewModelScope.launch {
            val user = app.accounts.currentUser()
            val session = app.session.session
            val name = user?.displayName?.ifBlank { user.username } ?: session?.displayName.orEmpty()
            val type = when (user?.accountType ?: session?.type) {
                SessionManager.TYPE_YOUTUBE -> SessionManager.TYPE_YOUTUBE
                else -> SessionManager.TYPE_MYST
            }
            _state.value = SettingsState(
                name = name,
                accountType = type,
                initial = name.take(1).uppercase(),
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            app.discord.disconnect()
            app.accounts.logout()
            _loggedOut.value = true
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(app as MystApp) }
        }
    }
}

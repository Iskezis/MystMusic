package com.iskezis.myst.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.SessionManager
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

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val loading: Boolean = false,
    val errorRes: Int? = null,
    val success: Boolean = false,
)

class AuthViewModel(private val accounts: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun login(username: String, password: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.login(username, password)
                AuthState(success = true)
            } catch (t: Throwable) {
                AuthState(errorRes = mapError(t))
            }
        }
    }

    fun register(username: String, password: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.register(username, password)
                AuthState(success = true)
            } catch (t: Throwable) {
                AuthState(errorRes = mapError(t))
            }
        }
    }

    fun loginYouTube(cookies: String, fallbackName: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.loginYouTube(cookies, fallbackName)
                AuthState(success = true)
            } catch (t: Throwable) {
                AuthState(errorRes = mapError(t))
            }
        }
    }

    fun clearError() {
        if (_state.value.errorRes != null) {
            _state.value = _state.value.copy(errorRes = null)
        }
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

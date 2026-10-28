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
import com.iskezis.myst.util.AppLog
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
            AppLog.i(AppLog.TAG_AUTH, "Login Myst solicitado para usuário $username")
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.login(username, password)
                AppLog.i(AppLog.TAG_AUTH, "Login Myst concluído")
                AuthState(success = true)
            } catch (t: Throwable) {
                AppLog.w(AppLog.TAG_AUTH, "Login Myst falhou: ${AppLog.describe(t)}")
                AuthState(errorRes = mapError(t))
            }
        }
    }

    fun register(username: String, password: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Cadastro Myst solicitado para usuário $username")
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.register(username, password)
                AppLog.i(AppLog.TAG_AUTH, "Cadastro Myst concluído")
                AuthState(success = true)
            } catch (t: Throwable) {
                AppLog.w(AppLog.TAG_AUTH, "Cadastro Myst falhou: ${AppLog.describe(t)}")
                AuthState(errorRes = mapError(t))
            }
        }
    }

    fun loginYouTube(cookies: String, fallbackName: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            AppLog.i(AppLog.TAG_AUTH, "Login YouTube solicitado (cookies=${cookies.length} bytes)")
            _state.value = AuthState(loading = true)
            _state.value = try {
                accounts.loginYouTube(cookies, fallbackName)
                AppLog.i(AppLog.TAG_AUTH, "Login YouTube concluído")
                AuthState(success = true)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Login YouTube falhou", t)
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

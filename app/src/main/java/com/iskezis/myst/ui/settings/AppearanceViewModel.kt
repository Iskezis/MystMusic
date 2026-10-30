package com.iskezis.myst.ui.settings

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.settings.ThemeMode
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val darkEffective: Boolean = true,
)

/**
 * Estado da tela Aparência. O tema é uma preferência do aparelho, não da conta
 * Myst; por isso vive em SharedPreferences e não no banco da conta.
 */
class AppearanceViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    /**
     * O ViewModel sobrevive a mudanças de configuração. Quando o sistema troca
     * entre claro/escuro com o modo "Sistema", o estado precisa ser relido para
     * que a linha AMOLED apareça/desapareça corretamente.
     */
    fun refresh() {
        _state.value = readState()
    }

    fun setThemeMode(mode: ThemeMode): Boolean {
        if (app.appearance.themeMode == mode) return false
        app.appearance.themeMode = mode
        _state.value = readState()
        AppLog.i(AppLog.TAG_UI, "Aparência: modo=${mode.storageValue}")
        // A preferência não está no Room; avisa o arquivo da conta para a
        // cópia interna/externa também levar a nova aparência.
        app.accountFiles.requestSync()
        // A aparência é aplicada na TV pareada imediatamente, sem esperar o
        // ciclo de segurança de 30 s.
        app.pairedSync.requestSync()
        // O AppCompat recria as Activities abertas quando o modo noturno muda.
        // O overlay AMOLED é reaplicado no início de cada Activity.
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        return true
    }

    fun setAmoled(enabled: Boolean): Boolean {
        if (app.appearance.amoled == enabled) return false
        app.appearance.amoled = enabled
        _state.value = readState()
        AppLog.i(AppLog.TAG_UI, "Aparência: AMOLED=$enabled")
        app.accountFiles.requestSync()
        app.pairedSync.requestSync()
        return true
    }

    private fun readState(): AppearanceState = AppearanceState(
        mode = app.appearance.themeMode,
        amoled = app.appearance.amoled,
        darkEffective = app.appearance.isDarkEffective(app),
    )

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppearanceViewModel(app as MystApp) }
        }
    }
}

package com.iskezis.myst.data.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    val nightMode: Int
        get() = when (this) {
            SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/**
 * Preferências de aparência no nível do aparelho (não da conta Myst): o tema
 * precisa ser lido antes de qualquer Activity e não deve sumir no logout.
 */
class AppearancePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    var themeMode: ThemeMode
        get() = ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.storageValue).apply()
        }

    var amoled: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AMOLED, value).apply()
        }

    val nightMode: Int
        get() = themeMode.nightMode

    fun isDarkEffective(context: Context): Boolean = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun isAmoledActive(context: Context): Boolean = amoled && isDarkEffective(context)

    companion object {
        private const val PREFS_NAME = "myst_appearance"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED = "amoled"
    }
}

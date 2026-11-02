package com.iskezis.myst.data.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.iskezis.myst.util.AppLocale
import com.iskezis.myst.util.AppLog

/**
 * Preferência de idioma do aparelho.
 *
 * - `language` é a escolha local (Sistema/Português/English/Español).
 * - `pairedLanguage` é uma sobreposição temporária usada só na TV enquanto ela
 *   está pareada; o celular envia o idioma efetivo em cada sincronização.
 *
 * A preferência fica no aparelho, não na conta Myst: o idioma é uma escolha de
 * acessibilidade de quem está usando aquele aparelho.
 */
class LanguagePreferences(
    context: Context,
    private val onChanged: () -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val language: AppLanguage
        get() = AppLanguage.fromStorage(prefs.getString(KEY_LANGUAGE, null))

    val pairedLanguage: AppLanguage?
        get() = prefs.getString(KEY_PAIRED_LANGUAGE, null)
            ?.let(AppLanguage::fromStorage)
            ?.takeIf { it != AppLanguage.SYSTEM }

    /** Idioma que está sendo exibido agora (a sobreposição da TV vence). */
    fun effectiveLanguage(): AppLanguage {
        pairedLanguage?.let { return it }
        val selected = language
        return if (selected == AppLanguage.SYSTEM) systemLanguage() else selected
    }

    /**
     * Tag usada no InnerTube e enviada à TV. Nunca devolve "system": o idioma
     * do sistema é resolvido para pt-BR, en ou es; fora desses três, English.
     */
    fun effectiveTag(): String = effectiveLanguage().languageTag ?: "en"

    fun systemLanguage(): AppLanguage = when (systemLanguageCode()) {
        "pt" -> AppLanguage.PORTUGUESE
        "es" -> AppLanguage.SPANISH
        else -> AppLanguage.ENGLISH
    }

    fun setLanguage(value: AppLanguage) {
        if (language == value) {
            // Mesmo com a escolha repetida, reaplica: o sistema pode ter
            // mudado de idioma desde a última vez.
            apply()
            return
        }
        prefs.edit().putString(KEY_LANGUAGE, value.storageValue).commit()
        AppLog.i(AppLog.TAG_UI, "Idioma local alterado para ${value.storageValue}")
        onChanged()
        apply()
    }

    fun setPairedLanguage(value: AppLanguage?) {
        val normalized = value?.takeIf { it != AppLanguage.SYSTEM }
        if (pairedLanguage == normalized) {
            apply()
            return
        }
        prefs.edit().putString(KEY_PAIRED_LANGUAGE, normalized?.storageValue).commit()
        AppLog.i(AppLog.TAG_UI, "Idioma recebido do celular: ${normalized?.storageValue ?: "nenhum"}")
        onChanged()
        apply()
    }

    fun clearPairedLanguage() {
        if (prefs.getString(KEY_PAIRED_LANGUAGE, null) == null) return
        prefs.edit().remove(KEY_PAIRED_LANGUAGE).commit()
        AppLog.i(AppLog.TAG_UI, "TV despareada: voltando ao idioma local")
        onChanged()
        apply()
    }

    /**
     * Aplica a locale efetiva. É seguro chamar várias vezes: só troca quando o
     * valor atual do AppCompat difere do desejado.
     */
    fun apply() {
        syncFromSystemIfNeeded()
        val desired = desiredLocales()
        val desiredTags = desired.toLanguageTags()
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        AppLocale.update(effectiveLanguage())
        prefs.edit().putString(KEY_LAST_APPLIED, desiredTags).commit()
        if (currentTags == desiredTags) return
        AppLog.i(
            AppLog.TAG_UI,
            "Aplicando idioma: atual=\"$currentTags\" desejado=\"$desiredTags\"",
        )
        setLocalesOnMain(desired)
    }

    /**
     * Android 13+ permite trocar o idioma do app nas configurações do sistema.
     * Quando o valor do AppCompat muda sem passar por esta classe, refletimos a
     * escolha na tela Idioma em vez de sobrescrevê-la no próximo início.
     */
    private fun syncFromSystemIfNeeded() {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val last = prefs.getString(KEY_LAST_APPLIED, null)
        if (last == null) {
            // Primeira execução da versão com idioma: se o sistema já tinha uma
            // preferência por app, ela vira a escolha local.
            if (current.isNotBlank()) {
                val mapped = AppLanguage.fromTag(current)
                if (mapped != AppLanguage.SYSTEM) {
                    prefs.edit().putString(KEY_LANGUAGE, mapped.storageValue).commit()
                }
            }
            prefs.edit().putString(KEY_LAST_APPLIED, current).commit()
            return
        }
        if (current == last) return
        val mapped = if (current.isBlank()) {
            AppLanguage.SYSTEM
        } else {
            AppLanguage.fromTag(current)
        }
        if (language != mapped) {
            prefs.edit().putString(KEY_LANGUAGE, mapped.storageValue).commit()
            AppLog.i(
                AppLog.TAG_UI,
                "Idioma alterado pelo sistema Android: ${mapped.storageValue}",
            )
        }
        prefs.edit().putString(KEY_LAST_APPLIED, current).commit()
    }

    private fun desiredLocales(): LocaleListCompat {
        pairedLanguage?.let { paired ->
            return LocaleListCompat.forLanguageTags(paired.languageTag ?: "en")
        }
        val selected = language
        if (selected != AppLanguage.SYSTEM) {
            return LocaleListCompat.forLanguageTags(selected.languageTag ?: "en")
        }
        // Sistema com um dos três idiomas: deixa o Android escolher os
        // recursos (inclusive variantes regionais). Fora deles, English.
        return if (isSystemLanguageSupported()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags("en")
        }
    }

    private fun isSystemLanguageSupported(): Boolean =
        systemLanguageCode() in SUPPORTED_LANGUAGE_CODES

    private fun systemLanguageCode(): String {
        val configuration = Resources.getSystem().configuration
        @Suppress("DEPRECATION")
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)
        } else {
            configuration.locale
        }
        return locale?.language.orEmpty().lowercase()
    }

    private fun setLocalesOnMain(locales: LocaleListCompat) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppCompatDelegate.setApplicationLocales(locales)
        } else {
            Handler(Looper.getMainLooper()).post {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "myst_language"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PAIRED_LANGUAGE = "paired_language"
        private const val KEY_LAST_APPLIED = "last_applied_locale"
        private val SUPPORTED_LANGUAGE_CODES = setOf("pt", "en", "es")
    }
}

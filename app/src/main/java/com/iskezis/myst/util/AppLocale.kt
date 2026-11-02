package com.iskezis.myst.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.iskezis.myst.data.settings.AppLanguage
import java.util.Locale

/**
 * Locale efetiva do app para formatação de datas, números e ordenação.
 *
 * O Android troca os recursos das Activities via AppCompat, mas serviços e
 * notificações usam o contexto da Application. Manter a locale efetiva aqui
 * permite criar um contexto localizado sem depender do idioma do aparelho.
 */
object AppLocale {

    @Volatile
    var currentLocale: Locale = Locale.getDefault()
        private set

    fun update(language: AppLanguage) {
        currentLocale = when (language) {
            AppLanguage.PORTUGUESE -> Locale.forLanguageTag("pt-BR")
            AppLanguage.SPANISH -> Locale.forLanguageTag("es-MX")
            AppLanguage.ENGLISH -> Locale.US
            AppLanguage.SYSTEM -> Locale.US
        }
    }

    fun update(locale: Locale) {
        currentLocale = locale
    }

    /** Contexto com os recursos no idioma efetivo, para serviços e receivers. */
    fun localizedContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(currentLocale))
        } else {
            @Suppress("DEPRECATION")
            configuration.setLocale(currentLocale)
        }
        configuration.setLayoutDirection(currentLocale)
        return context.createConfigurationContext(configuration)
    }
}

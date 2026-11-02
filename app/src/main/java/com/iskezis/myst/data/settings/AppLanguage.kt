package com.iskezis.myst.data.settings

import androidx.annotation.StringRes
import com.iskezis.myst.R

/**
 * Idiomas oferecidos pelo app. O valor persistido é estável e não depende do
 * texto exibido; o rótulo de cada idioma é sempre escrito no próprio idioma.
 */
enum class AppLanguage(
    val storageValue: String,
    val languageTag: String?,
    @StringRes val labelRes: Int,
) {
    SYSTEM("system", null, R.string.language_system),
    PORTUGUESE("pt", "pt-BR", R.string.language_portuguese),
    ENGLISH("en", "en", R.string.language_english),
    SPANISH("es", "es", R.string.language_spanish),
    ;

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: SYSTEM

        /**
         * Converte uma tag BCP-47 (inclusive as que vêm do pareamento) em um
         * idioma do app. Tags desconhecidas caem em [SYSTEM].
         */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            return when {
                tag.startsWith("pt", ignoreCase = true) -> PORTUGUESE
                tag.startsWith("en", ignoreCase = true) -> ENGLISH
                tag.startsWith("es", ignoreCase = true) -> SPANISH
                else -> SYSTEM
            }
        }
    }
}

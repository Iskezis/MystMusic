package com.iskezis.myst.data.downloads

import android.content.Context
import com.iskezis.myst.BuildConfig

/**
 * Preferências de armazenamento que não pertencem a uma conta: espaço máximo
 * do cache de streaming, retenção, aviso de uso pessoal e a pasta escolhida.
 */
class StoragePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("myst_storage", Context.MODE_PRIVATE)

    var firstDownloadWarningSeen: Boolean
        get() = prefs.getBoolean(KEY_WARNING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_WARNING_SEEN, value).apply()

    var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CACHE_ENABLED, value).apply()

    var cacheMaxBytes: Long
        get() = prefs.getLong(KEY_CACHE_MAX, defaultCacheBytes())
        set(value) = prefs.edit().putLong(KEY_CACHE_MAX, value.coerceAtLeast(MIN_CACHE_BYTES)).apply()

    /** 0 = guardar para sempre (respeitando apenas o limite de tamanho). */
    var cacheMaxAgeDays: Int
        get() = prefs.getInt(KEY_CACHE_AGE, DEFAULT_CACHE_AGE_DAYS)
        set(value) = prefs.edit().putInt(KEY_CACHE_AGE, value.coerceAtLeast(0)).apply()

    val cacheMaxAgeMs: Long
        get() = cacheMaxAgeDays.toLong() * DAY_MS

    private fun defaultCacheBytes(): Long =
        if (BuildConfig.FLAVOR == "tv") TV_DEFAULT_CACHE_BYTES else PHONE_DEFAULT_CACHE_BYTES

    companion object {
        private const val KEY_WARNING_SEEN = "download_warning_seen"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CACHE_MAX = "cache_max_bytes"
        private const val KEY_CACHE_AGE = "cache_max_age_days"

        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_CACHE_BYTES = 64L * 1024L * 1024L
        const val PHONE_DEFAULT_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
        const val TV_DEFAULT_CACHE_BYTES = 1L * 1024L * 1024L * 1024L
        const val DEFAULT_CACHE_AGE_DAYS = 30

        val MAX_CACHE_OPTIONS = longArrayOf(
            256L * 1024L * 1024L,
            512L * 1024L * 1024L,
            1L * 1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L,
            4L * 1024L * 1024L * 1024L,
            8L * 1024L * 1024L * 1024L,
        )

        val AGE_OPTIONS_DAYS = intArrayOf(1, 7, 30, 90, 180, 0)
    }
}

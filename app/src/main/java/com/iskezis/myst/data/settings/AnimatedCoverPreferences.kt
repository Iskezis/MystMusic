package com.iskezis.myst.data.settings

import android.content.Context

/** Preferência do aparelho; na TV o recurso nem é exibido. */
class AnimatedCoverPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "myst_animated_covers"
        private const val KEY_ENABLED = "enabled"
    }
}

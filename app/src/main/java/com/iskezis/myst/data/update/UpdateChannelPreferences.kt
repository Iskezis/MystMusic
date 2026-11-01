package com.iskezis.myst.data.update

import android.content.Context
import com.iskezis.myst.BuildConfig

/**
 * Preferência do aparelho (não da conta Myst) para o canal de atualização.
 *
 * Instalação nova começa em `stable`. Quem já está rodando uma build
 * beta/alpha/rc/dev começa em `beta` na primeira leitura, para não parar de
 * receber atualizações sem querer. Um valor inválido sempre volta para
 * `stable`.
 */
class UpdateChannelPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    var channel: UpdateChannel
        get() {
            val stored = prefs.getString(KEY_CHANNEL, null)
            if (stored != null) return UpdateChannel.fromStorage(stored)
            val initial = if (BETA_VERSION.matches(BuildConfig.VERSION_NAME)) {
                UpdateChannel.BETA
            } else {
                UpdateChannel.STABLE
            }
            prefs.edit().putString(KEY_CHANNEL, initial.storageValue).apply()
            return initial
        }
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.storageValue).apply()
        }

    companion object {
        private const val PREFS_NAME = "myst_update_channel"
        private const val KEY_CHANNEL = "channel"
        private val BETA_VERSION = Regex("(?i).*(alpha|beta|rc|dev).*")
    }
}

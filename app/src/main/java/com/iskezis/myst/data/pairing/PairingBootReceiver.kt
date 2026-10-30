package com.iskezis.myst.data.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.util.AppLog

/**
 * Retoma o canal de pareamento depois de reiniciar o aparelho ou de uma
 * atualização do APK. Sem isso o pareamento continuaria salvo, mas o celular
 * ficaria inalcançável pela TV até o usuário abrir o app manualmente.
 */
class PairingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (BuildConfig.FLAVOR == "tv") return
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        val app = context.applicationContext as? MystApp ?: return
        if (!app.session.hasTvPairing) return
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Retomando canal de pareamento após $action",
        )
        PairingSyncService.start(context)
    }
}

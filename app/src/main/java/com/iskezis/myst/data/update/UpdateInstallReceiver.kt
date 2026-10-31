package com.iskezis.myst.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.iskezis.myst.util.AppLog

/**
 * Recebe o resultado da sessão do PackageInstaller. O sistema usa o
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] para pedir a confirmação do
 * usuário; os demais estados ou são sucesso/cancelamento ou viram uma
 * notificação com o motivo da falha.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirmation == null) {
                    AppLog.w(
                        AppLog.TAG_UPDATE,
                        "Instalador não enviou a confirmação do usuário",
                    )
                    UpdateNotifications.showInstallFailure(context, status)
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                } catch (t: Throwable) {
                    AppLog.failure(
                        AppLog.TAG_UPDATE,
                        "Falha abrindo a confirmação do instalador",
                        t,
                    )
                    UpdateNotifications.showInstallFailure(context, status)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.w(AppLog.TAG_UPDATE, "Atualização instalada com sucesso")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.w(AppLog.TAG_UPDATE, "Instalação da atualização cancelada")
            }

            else -> UpdateNotifications.showInstallFailure(context, status)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.iskezis.myst.action.INSTALL_RESULT"
    }
}

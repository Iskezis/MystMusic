package com.iskezis.myst.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.iskezis.myst.MainActivity
import com.iskezis.myst.R
import com.iskezis.myst.util.AppLog

/**
 * A instalação acontece fora do app, então uma falha do PackageInstaller não
 * pode ser mostrada em um diálogo da Home (o instalador está na frente). A
 * notificação mantém o erro visível e abre o app para o usuário tentar de novo.
 */
object UpdateNotifications {

    fun showInstallFailure(context: Context, status: Int) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(
                AppLog.TAG_UPDATE,
                "Falha de instalação ($status) sem notificação: permissão negada",
            )
            return
        }
        if (!manager.areNotificationsEnabled()) {
            AppLog.w(
                AppLog.TAG_UPDATE,
                "Falha de instalação ($status) sem notificação: notificações desativadas",
            )
            return
        }

        createChannel(app)
        val message = messageRes(status)
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                },
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(app.getString(R.string.update_install_failed_title))
            .setContentText(app.getString(message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(message)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: SecurityException) {
            AppLog.failure(AppLog.TAG_UPDATE, "Falha publicando notificação de instalação", t)
        }
    }

    private fun messageRes(status: Int): Int = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.update_install_failed_blocked
        PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.update_install_failed_conflict
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            R.string.update_install_failed_incompatible
        PackageInstaller.STATUS_FAILURE_INVALID -> R.string.update_install_failed_invalid
        PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_install_failed_storage
        else -> R.string.update_install_failed_message
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private const val CHANNEL_ID = "myst_updates"
    private const val NOTIFICATION_ID = 73
}

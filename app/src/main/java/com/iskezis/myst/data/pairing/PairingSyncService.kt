package com.iskezis.myst.data.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantém o canal de pareamento do celular vivo em segundo plano.
 *
 * O servidor HTTP e o ciclo de sincronização precisam continuar ativos quando
 * o usuário fecha a tela de pareamento, volta para a TV ou remove o app dos
 * recentes. Sem um serviço em primeiro plano o Android pode encerrar o
 * processo, e a TV não consegue entregar o histórico/estatísticas temporários
 * ao celular. O serviço também cobre o curto período de confirmação do código
 * antes de o pareamento existir.
 */
class PairingSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: MystApp

    override fun onCreate() {
        super.onCreate()
        app = application as MystApp
        createChannel()
        startForegroundSafely()
        app.pairedSync.onBackgroundServiceStarted()
        scope.launch {
            app.phonePairing.state.collect {
                if (shouldRun()) {
                    startForegroundSafely()
                    if (app.session.hasTvPairing) {
                        app.pairedSync.onBackgroundServiceStarted()
                    }
                } else {
                    stopSelfSafely()
                }
            }
        }
        scope.launch {
            app.session.tvPairingVersion.collect {
                if (shouldRun()) {
                    startForegroundSafely()
                    if (app.session.hasTvPairing) {
                        app.pairedSync.onBackgroundServiceStarted()
                    }
                } else {
                    stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        if (app.session.hasTvPairing) {
            app.pairedSync.onBackgroundServiceStarted()
        }
        return if (app.session.hasTvPairing) START_STICKY else START_NOT_STICKY
    }

    /**
     * Remover a tarefa dos recentes não pode derrubar o canal: a TV pode
     * continuar enviando dados e o usuário espera encontrá-los ao reabrir o
     * app. O sistema ainda pode encerrar o serviço em situação de força maior;
     * nesse caso o START_STICKY o recria quando o pareamento continua ativo.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Tarefa do celular removida; canal de pareamento continua em segundo plano",
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_PAIRING, "PairingSyncService.onDestroy")
        app.pairedSync.onBackgroundServiceStopped()
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shouldRun(): Boolean =
        app.session.hasTvPairing || app.phonePairing.isPairingInProgress

    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                type,
            )
        } catch (t: Throwable) {
            AppLog.failure(
                AppLog.TAG_PAIRING,
                "Não foi possível manter o canal de pareamento em primeiro plano",
                t,
            )
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val pairing = app.phonePairing.state.value
        val title = if (pairing is PhonePairingSession.State.Waiting) {
            getString(R.string.pairing_sync_active_title)
        } else {
            getString(R.string.pairing_sync_connected_title)
        }
        val text = if (pairing is PhonePairingSession.State.Waiting) {
            getString(R.string.pairing_sync_active_text)
        } else {
            val tvName = app.session.tvName.ifBlank {
                getString(R.string.settings_tv_default_name)
            }
            getString(R.string.pairing_sync_connected_text, tvName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.pairing_sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.pairing_sync_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "myst_pairing_sync"
        private const val NOTIFICATION_ID = 42

        /** Inicia ou reaproveita o serviço; sempre chamado a partir da tela. */
        fun start(context: Context) {
            if (BuildConfig.FLAVOR == "tv") return
            val intent = Intent(context, PairingSyncService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                AppLog.failure(
                    AppLog.TAG_PAIRING,
                    "Serviço de pareamento não pôde ser iniciado",
                    t,
                )
            }
        }

        /** Só sobe o serviço quando existe um pareamento salvo. */
        fun startIfPaired(context: Context) {
            val app = context.applicationContext as? MystApp ?: return
            if (app.session.hasTvPairing) start(context)
        }

        /** Encerra o serviço quando não há mais pareamento nem código ativo. */
        fun stop(context: Context) {
            if (BuildConfig.FLAVOR == "tv") return
            runCatching { context.stopService(Intent(context, PairingSyncService::class.java)) }
                .onFailure {
                    AppLog.w(AppLog.TAG_PAIRING, "Falha encerrando serviço de pareamento")
                }
        }
    }
}

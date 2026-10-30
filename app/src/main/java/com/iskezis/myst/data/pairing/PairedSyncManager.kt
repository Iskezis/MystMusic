package com.iskezis.myst.data.pairing

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.TvEphemeralData
import com.iskezis.myst.player.PlaybackService
import com.iskezis.myst.ui.pairing.TvPairingActivity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Orquestra os dois lados do canal persistente:
 *
 * - TV: mantém o servidor/NSD ativo enquanto o processo está vivo, para que o
 *   celular consiga sincronizar e desconectar mesmo fora da tela de ajustes.
 * - Celular: sincroniza enquanto a MainActivity está visível, de forma
 *   periódica e ao abrir a tela, usando um Mutex para não sobrepor pedidos.
 */
class PairedSyncManager(private val app: MystApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = PairedSyncClient()
    private val syncMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var tvServer: PairedTvServer? = null

    private var phoneLoop: Job? = null

    private val isTv: Boolean
        get() = BuildConfig.FLAVOR == "tv"

    fun onAppCreated() {
        if (isTv) startTvServerIfNeeded()
    }

    /** Chamado depois que a TV recebeu a conta e o segredo pelo pareamento. */
    fun onTvPaired() {
        if (!isTv) return
        synchronized(this) {
            tvServer?.close()
            tvServer = null
        }
        startTvServerIfNeeded()
    }

    fun onTvMainReady() {
        if (isTv) startTvServerIfNeeded()
    }

    /** A MainActivity do celular está visível: sincroniza agora e a cada 20 s. */
    fun onMainStart() {
        if (isTv) {
            onTvMainReady()
            return
        }
        startPhoneLoop()
    }

    fun onMainStop() {
        if (isTv) return
        phoneLoop?.cancel()
        phoneLoop = null
    }

    /** Dispara uma sincronização imediata sem bloquear a UI. */
    fun requestSync() {
        if (isTv) return
        scope.launch {
            runCatching { syncNow() }
                .onFailure { AppLog.d(AppLog.TAG_PAIRING, "Sincronização imediata não concluída: ${it.message}") }
        }
    }

    suspend fun syncNow(): Boolean = syncMutex.withLock {
        if (app.session.mystUserId <= 0L || !app.session.hasTvPairing) return@withLock false
        try {
            client.sync(app)
            true
        } catch (t: Throwable) {
            AppLog.d(
                AppLog.TAG_PAIRING,
                "Sincronização com a TV adiada: ${t.javaClass.simpleName}: ${t.message}",
            )
            false
        }
    }

    /**
     * Desfaz o pareamento no celular e manda a TV parar a reprodução e voltar
     * para a tela do código. Se a TV não responder, o erro sobe para a tela e
     * o pareamento local é mantido, permitindo tentar de novo.
     */
    suspend fun disconnect() {
        if (isTv) throw IOException("A TV não desconecta a si mesma")
        syncMutex.withLock {
            client.disconnect(app)
        }
        phoneLoop?.cancel()
        phoneLoop = null
    }

    /**
     * Executado no servidor da TV quando o celular pede a desconexão. O
     * servidor fecha, a reprodução para e a tarefa é limpa com a tela de
     * código no topo; a flag de pareamento fica falsa para o próximo boot.
     */
    fun onTvUnpaired() {
        if (!isTv) return
        AppLog.i(AppLog.TAG_PAIRING, "Desfazendo pareamento da TV")
        synchronized(this) {
            runCatching { tvServer?.close() }
            tvServer = null
        }
        app.session.clearTvPairing()
        runCatching { app.playerController.stop() }
            .onFailure { AppLog.failure(AppLog.TAG_PLAYER, "Falha parando o player na desconexão", it) }
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
            .onFailure { AppLog.failure(AppLog.TAG_PLAYER, "Falha parando o serviço de reprodução", it) }
        // A TV não mantém a biblioteca depois da desconexão. O banco é em
        // memória, mas limpamos também os arquivos temporários e tabelas para
        // a tela de código não herdar nenhum dado do celular.
        scope.launch {
            runCatching { app.database.clearAllTables() }.onFailure {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha limpando tabelas temporárias da TV", it)
            }
            TvEphemeralData.purge(app)
        }
        handler.post {
            // Limpa o tema temporário antes de abrir a tela de código, para
            // ela não herdar a aparência do celular que acabou de desconectar.
            TvEphemeralData.resetAppearance(app)
            val intent = Intent(app, TvPairingActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
            runCatching { app.startActivity(intent) }
                .onFailure {
                    AppLog.failure(
                        AppLog.TAG_PAIRING,
                        "Não foi possível abrir a tela de código; ela abrirá no próximo start",
                        it,
                    )
                }
        }
    }

    @Synchronized
    private fun startTvServerIfNeeded() {
        if (!isTv) return
        if (!app.session.hasTvPairing) return
        if (tvServer != null) return
        val server = PairedTvServer(app)
        if (server.start()) {
            tvServer = server
        }
    }

    private fun startPhoneLoop() {
        if (isTv) return
        if (phoneLoop?.isActive == true) return
        phoneLoop = scope.launch {
            while (isActive) {
                val paired = app.session.hasTvPairing && app.session.mystUserId > 0L
                val success = if (paired) syncNow() else false
                delay(
                    when {
                        !paired -> 60_000L
                        success -> 20_000L
                        else -> 45_000L
                    },
                )
            }
        }
    }
}

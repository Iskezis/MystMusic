package com.iskezis.myst.data.pairing

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.room.InvalidationTracker
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.TvEphemeralData
import com.iskezis.myst.player.PlaybackService
import com.iskezis.myst.ui.pairing.TvPairingActivity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Orquestra os dois lados do canal persistente:
 *
 * - TV: mantém o servidor/NSD ativo enquanto o processo está vivo e avisa o
 *   celular quando o histórico/estatísticas mudam nela.
 * - Celular: mantém um servidor para receber esse aviso, sincroniza ao abrir a
 *   tela e quando os dados locais mudam, além de um ciclo de segurança.
 *
 * O ciclo de segurança não é o caminho principal: as notificações autenticadas
 * entre os dois aparelhos deixam a sincronização quase imediata. Ele existe
 * para o caso de uma notificação UDP/HTTP se perder na rede local.
 */
class PairedSyncManager(private val app: MystApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = PairedSyncClient()
    private val syncMutex = Mutex()
    private val phoneNotifyMutex = Mutex()
    private val phonePushMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var tvServer: PairedTvServer? = null

    @Volatile
    private var phoneServer: PairedPhoneServer? = null

    private var phoneLoop: Job? = null
    private var changeJob: Job? = null
    private var serverRetryJob: Job? = null
    private var unpairCleanupJob: Job? = null
    private var observer: InvalidationTracker.Observer? = null

    @Volatile
    private var suppressChangesUntil = 0L

    /**
     * Impede um sync novo de começar enquanto o usuário desconecta. A flag é
     * diferente do `syncMutex`: o pedido de desconexão precisa liberar o mutex
     * durante a chamada remota, senão o `/push` final da TV (que o celular
     * precisa gravar antes de responder) ficaria bloqueado para sempre.
     */
    @Volatile
    private var disconnecting = false

    /**
     * Cada pareamento/despareamento recebe uma geração. A limpeza assíncrona
     * da TV só pode apagar dados enquanto a geração que a iniciou ainda for a
     * atual; se o usuário pareou de novo no meio, a limpeza antiga aborta.
     */
    @Volatile
    private var pairingEpoch = 0L

    private val isTv: Boolean
        get() = BuildConfig.FLAVOR == "tv"

    fun onAppCreated() {
        registerLocalObserver()
        if (isTv) startTvServerIfNeeded() else startPhoneServerIfNeeded()
    }

    /**
     * Chamado no início do pareamento da TV. Cancela uma limpeza antiga e
     * fecha o canal anterior antes de o novo segredo ser gravado.
     */
    fun prepareTvPairing() {
        if (!isTv) return
        pairingEpoch++
        unpairCleanupJob?.cancel()
        unpairCleanupJob = null
        serverRetryJob?.cancel()
        serverRetryJob = null
        changeJob?.cancel()
        changeJob = null
        synchronized(this) {
            runCatching { tvServer?.close() }
            tvServer = null
        }
    }

    /** Chamado depois que a TV recebeu a conta e o segredo pelo pareamento. */
    fun onTvPaired() {
        if (!isTv) return
        pairingEpoch++
        unpairCleanupJob?.cancel()
        unpairCleanupJob = null
        synchronized(this) {
            runCatching { tvServer?.close() }
            tvServer = null
        }
        startTvServerIfNeeded()
    }

    fun onTvMainReady() {
        if (isTv) startTvServerIfNeeded()
    }

    /** Chamado no celular depois que o payload foi entregue à TV. */
    fun onPhonePaired() {
        if (isTv) return
        disconnecting = false
        // Um novo pareamento pode usar outro pairId/segredo. O servidor antigo
        // não serve mais: fecha e sobe outro com as credenciais recém-gravadas.
        synchronized(this) { stopPhoneChannel() }
        startPhoneServerIfNeeded()
        requestSync()
    }

    /** A MainActivity do celular está visível: garante o canal e o ciclo. */
    fun onMainStart() {
        if (isTv) {
            onTvMainReady()
            return
        }
        startPhoneServerIfNeeded()
        startPhoneLoop()
    }

    fun onMainStop() {
        if (isTv) return
        phoneLoop?.cancel()
        phoneLoop = null
    }

    /** Dispara uma sincronização imediata sem bloquear a UI. */
    fun requestSync() {
        if (isTv || !app.session.hasTvPairing) return
        scope.launch {
            runCatching { syncNow() }
                .onFailure {
                    AppLog.d(
                        AppLog.TAG_PAIRING,
                        "Sincronização imediata não concluída: ${it.message}",
                    )
                }
        }
    }

    suspend fun syncNow(): Boolean = syncMutex.withLock {
        if (disconnecting) return@withLock false
        if (app.session.mystUserId <= 0L || !app.session.hasTvPairing) return@withLock false
        // O merge altera o Room; essas escritas são consequência do snapshot
        // recebido, não uma novidade local que precise voltar para a TV.
        suppressLocalChanges()
        return@withLock try {
            client.sync(app)
            suppressLocalChanges()
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
     * Desfaz o pareamento no celular e pede para a TV limpar os dados.
     *
     * A remoção local nunca depende da TV responder. Se ela estiver desligada,
     * fora da rede ou com o servidor reiniciando, o celular remove o segredo,
     * fecha o canal e fecha o ciclo; o usuário pode parear de novo na hora. A
     * TV é limpa pelo próprio botão dela ou no próximo pareamento.
     */
    suspend fun disconnect(forceLocal: Boolean = false): Boolean {
        if (isTv) return false
        disconnecting = true
        val notified = try {
            // Dá um instante para um sync já em andamento terminar, mas não
            // deixa a desconexão presa a uma rede lenta. O POST /disconnect
            // devolve o retrato final embutido; não há /push concorrente.
            withTimeoutOrNull(DISCONNECT_WAIT_MS) { syncMutex.withLock { } }
            client.disconnect(app)
            true
        } catch (t: Throwable) {
            AppLog.w(
                AppLog.TAG_PAIRING,
                "TV não confirmou a desconexão (forceLocal=$forceLocal); " +
                    "removendo o pareamento deste celular: ${AppLog.describe(t)}",
            )
            false
        } finally {
            disconnecting = false
        }
        stopPhoneChannel()
        phoneLoop?.cancel()
        phoneLoop = null
        if (!notified) clearPairingLocally()
        return notified
    }

    /** Remove o canal local sem tentar falar com a TV. */
    fun clearPairingLocally() {
        if (isTv) {
            onTvUnpaired()
            return
        }
        app.session.clearTvPairing()
        stopPhoneChannel()
        phoneLoop?.cancel()
        phoneLoop = null
        changeJob?.cancel()
        changeJob = null
        AppLog.i(AppLog.TAG_PAIRING, "Pareamento removido localmente no celular")
    }

    /**
     * Botão da própria TV: envia o último retrato (histórico/estatísticas) ao
     * celular, avisa que o pareamento terminou e só então apaga os dados
     * temporários da TV.
     */
    suspend fun disconnectOnTv() {
        if (!isTv) return
        if (app.session.hasTvPairing) {
            withTimeoutOrNull(FINAL_PUSH_TIMEOUT_MS) {
                runCatching { pushTvSnapshotToPhone() }
                    .onFailure {
                        AppLog.w(
                            AppLog.TAG_PAIRING,
                            "Retrato final da TV não chegou ao celular: ${AppLog.describe(it)}",
                        )
                    }
            }
            withTimeoutOrNull(NOTIFY_TIMEOUT_MS) {
                runCatching { notifyPhone("unpair") }
                    .onFailure {
                        AppLog.d(
                            AppLog.TAG_PAIRING,
                            "Aviso de despareamento não entregue: ${it.message}",
                        )
                    }
            }
        }
        onTvUnpaired()
    }

    /**
     * Chamado pelo servidor do celular quando a TV envia um retrato. O merge é
     * feito antes de responder, para a TV só apagar os dados depois de o
     * celular confirmar que gravou. As escritas são suprimidas para não gerar
     * um novo sync de volta à TV.
     */
    suspend fun acceptTvSnapshot(snapshot: PairSnapshot): Boolean = syncMutex.withLock {
        if (isTv || !app.session.hasTvPairing) return@withLock false
        val userId = app.session.mystUserId
        if (userId <= 0L) return@withLock false
        suppressLocalChanges()
        return@withLock try {
            PairingSync.mergeSnapshot(app, userId, snapshot)
            suppressLocalChanges()
            app.accountFiles.requestSync()
            AppLog.i(
                AppLog.TAG_PAIRING,
                "Retrato da TV recebido: histórico=${snapshot.history.size} " +
                    "eventos=${snapshot.playEvents.size} playlists=${snapshot.playlists.size}",
            )
            true
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PAIRING, "Falha aplicando o retrato da TV", t)
            false
        }
    }

    /**
     * Executado no servidor da TV quando o celular pede a desconexão, ou pelo
     * botão local da TV. O servidor fecha, a reprodução para e a tarefa é
     * limpa com a tela de código no topo.
     */
    fun onTvUnpaired() {
        if (!isTv) return
        val epoch = ++pairingEpoch
        AppLog.i(AppLog.TAG_PAIRING, "Desfazendo pareamento da TV (geração $epoch)")
        unpairCleanupJob?.cancel()
        synchronized(this) {
            runCatching { tvServer?.close() }
            tvServer = null
        }
        serverRetryJob?.cancel()
        serverRetryJob = null
        changeJob?.cancel()
        changeJob = null
        app.session.clearTvPairing()
        runCatching { app.discord.disconnect() }
            .onFailure { AppLog.failure(AppLog.TAG_DISCORD, "Falha desconectando Discord na TV", it) }
        runCatching { app.playerController.stop() }
            .onFailure { AppLog.failure(AppLog.TAG_PLAYER, "Falha parando o player na desconexão", it) }
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
            .onFailure { AppLog.failure(AppLog.TAG_PLAYER, "Falha parando o serviço de reprodução", it) }
        // A limpeza não pode usar o scope do PairedTvServer: ele acabou de ser
        // fechado e cancelaria a própria rotina de limpeza. O escopo da
        // Application sobrevive ao servidor e termina a transação.
        unpairCleanupJob = app.backgroundScope.launch {
            if (!isCurrentUnpair(epoch)) return@launch
            runCatching { app.database.clearAllTables() }.onFailure {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha limpando tabelas temporárias da TV", it)
            }
            if (!isCurrentUnpair(epoch)) return@launch
            TvEphemeralData.purge(app)
            handler.post {
                // Se o usuário já iniciou outro pareamento, esta limpeza ficou
                // obsoleta: não pode apagar o tema novo nem sequestrar a tela.
                if (!isCurrentUnpair(epoch)) return@post
                // Limpa o tema temporário antes de abrir a tela de código, para
                // ela não herdar a aparência do celular que acabou de
                // desconectar. A tela só abre depois da limpeza do Room para
                // não recriar o banco em paralelo com o clearAllTables.
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
    }

    private fun isCurrentUnpair(epoch: Long): Boolean =
        pairingEpoch == epoch && !app.session.hasTvPairing

    /**
     * TV → celular: envia o retrato reduzido (histórico, estatísticas,
     * playlists) diretamente ao endpoint `/push` do celular. O celular grava e
     * responde antes de a TV apagar qualquer dado. Se o push falhar, o aviso
     * `/notify` e o ciclo de segurança continuam funcionando como reserva.
     */
    suspend fun pushTvSnapshotToPhone(): Boolean = phonePushMutex.withLock {
        if (!isTv || !app.session.hasTvPairing) return@withLock false
        val session = app.session
        val pairId = session.tvPairId ?: return@withLock false
        val secret = session.tvPairSecret ?: return@withLock false
        val userId = session.mystUserId
        if (userId <= 0L) return@withLock false
        val target = findPhoneTarget(pairId) ?: return@withLock false
        val snapshot = PairingSync.exportTvChanges(app, userId)
        val envelope = PairSecureEnvelope(
            pairId = pairId,
            payload = PairingCrypto.encryptSecret(
                secret,
                PairingSync.json.encodeToString(PairSnapshot.serializer(), snapshot),
            ),
        )
        val responseText = PairingHttp.postJson(
            url = PairingHttp.url(target.host, target.port, "/push"),
            body = PairingSync.json.encodeToString(PairSecureEnvelope.serializer(), envelope),
            connectTimeoutMs = 4_000,
            readTimeoutMs = 60_000,
        )
        val ack = runCatching {
            PairingSync.json.decodeFromString(PairAck.serializer(), responseText)
        }.getOrNull()
        if (ack?.ok != true) throw IOException("Celular recusou o retrato da TV")
        session.phoneHost = target.host
        session.phonePort = target.port
        AppLog.d(
            AppLog.TAG_PAIRING,
            "Retrato da TV entregue: histórico=${snapshot.history.size} " +
                "eventos=${snapshot.playEvents.size} em ${target.host}:${target.port}",
        )
        true
    }

    /** Chamado pelo servidor da TV logo após o merge de um snapshot. */
    fun suppressLocalChanges(durationMs: Long = SUPPRESS_CHANGES_MS) {
        suppressChangesUntil = System.currentTimeMillis() + durationMs
    }

    @Synchronized
    private fun startTvServerIfNeeded() {
        if (!isTv) return
        if (!app.session.hasTvPairing) return
        if (tvServer != null) return
        val server = PairedTvServer(app)
        if (server.start()) {
            tvServer = server
        } else if (serverRetryJob?.isActive != true) {
            serverRetryJob = scope.launch {
                delay(SERVER_RETRY_MS)
                startTvServerIfNeeded()
            }
        }
    }

    @Synchronized
    private fun startPhoneServerIfNeeded() {
        if (isTv) return
        if (!app.session.hasTvPairing) return
        if (phoneServer != null) return
        val server = PairedPhoneServer(app) { requestSync() }
        if (server.start()) {
            phoneServer = server
        } else {
            AppLog.w(AppLog.TAG_PAIRING, "Canal do celular não iniciou; tentando de novo")
            if (serverRetryJob?.isActive != true) {
                serverRetryJob = scope.launch {
                    delay(SERVER_RETRY_MS)
                    startPhoneServerIfNeeded()
                }
            }
        }
    }

    @Synchronized
    private fun stopPhoneChannel() {
        if (!isTv) {
            serverRetryJob?.cancel()
            serverRetryJob = null
        }
        runCatching { phoneServer?.close() }
        phoneServer = null
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
                        success -> FALLBACK_SYNC_MS
                        // Logo após o pareamento a TV pode ainda estar gravando
                        // o payload grande. Tenta de novo em poucos segundos em
                        // vez de deixar a primeira sincronização para 45 s.
                        app.session.tvLastSyncAt <= 0L -> INITIAL_SYNC_RETRY_MS
                        else -> FAILED_SYNC_MS
                    },
                )
            }
        }
    }

    /**
     * Room avisa quando qualquer tabela sincronizada muda. No celular isso
     * empurra a alteração para a TV; na TV isso avisa o celular para puxar o
     * novo histórico/estatísticas quase imediatamente.
     */
    private fun registerLocalObserver() {
        if (observer != null) return
        scope.launch {
            runCatching {
                val created = object : InvalidationTracker.Observer(PAIRING_TABLES) {
                    override fun onInvalidated(tables: Set<String>) {
                        onLocalDataChanged(tables)
                    }
                }
                observer = created
                app.database.invalidationTracker.addObserver(created)
                AppLog.d(AppLog.TAG_PAIRING, "Observador de dados do pareamento ativo")
            }.onFailure {
                observer = null
                AppLog.failure(AppLog.TAG_PAIRING, "Falha observando dados do pareamento", it)
            }
        }
    }

    private fun onLocalDataChanged(tables: Set<String>) {
        if (!app.session.hasTvPairing) return
        AppLog.d(
            AppLog.TAG_PAIRING,
            "Dados mudaram (${tables.joinToString(limit = 4)}); agendando " +
                (if (isTv) "retrato ao celular" else "sync"),
        )
        changeJob?.cancel()
        changeJob = scope.launch {
            // Uma mudança que acontece enquanto um merge está em andamento não
            // pode ser simplesmente descartada. Espera o fim da supressão e
            // reavalia; sem isso, um play na TV durante um sync sumia.
            var waits = 0
            while (isActive && waits < 4) {
                val remaining = suppressChangesUntil - System.currentTimeMillis()
                if (remaining > 0L) {
                    delay(remaining + 60L)
                    waits++
                    continue
                }
                delay(CHANGE_DEBOUNCE_MS)
                if (System.currentTimeMillis() < suppressChangesUntil) continue
                if (!app.session.hasTvPairing) return@launch
                if (isTv) {
                    val pushed = runCatching { pushTvSnapshotToPhone() }
                        .onFailure {
                            AppLog.d(
                                AppLog.TAG_PAIRING,
                                "Retrato direto não entregue; usando aviso: ${it.message}",
                            )
                        }
                        .getOrDefault(false)
                    if (!pushed) {
                        runCatching { notifyPhone("sync") }.onFailure {
                            AppLog.d(
                                AppLog.TAG_PAIRING,
                                "Aviso em tempo real para o celular não entregue: ${it.message}",
                            )
                        }
                    }
                } else {
                    runCatching { syncNow() }
                }
                return@launch
            }
        }
    }

    /**
     * TV → celular: descobre o servidor persistente do celular (serviço NSD
     * com papel "phone") e envia um aviso cifrado. O celular então puxa o
     * snapshot completo, mantendo a mesma validação do canal normal.
     */
    private suspend fun notifyPhone(action: String): Boolean = phoneNotifyMutex.withLock {
        if (!isTv) return@withLock false
        val session = app.session
        val pairId = session.tvPairId ?: return@withLock false
        val secret = session.tvPairSecret ?: return@withLock false
        val target = findPhoneTarget(pairId) ?: return@withLock false
        val envelope = PairSecureEnvelope(
            pairId = pairId,
            payload = PairingCrypto.encryptSecret(secret, action),
        )
        val responseText = PairingHttp.postJson(
            url = PairingHttp.url(target.host, target.port, "/notify"),
            body = PairingSync.json.encodeToString(PairSecureEnvelope.serializer(), envelope),
            connectTimeoutMs = 3_000,
            readTimeoutMs = 5_000,
        )
        val ack = runCatching {
            PairingSync.json.decodeFromString(PairAck.serializer(), responseText)
        }.getOrNull()
        if (ack?.ok != true) throw IOException("Celular recusou o aviso")
        session.phoneHost = target.host
        session.phonePort = target.port
        AppLog.d(AppLog.TAG_PAIRING, "Aviso '$action' entregue ao celular em ${target.host}:${target.port}")
        true
    }

    private suspend fun findPhoneTarget(pairId: String): PairingNsd.Target? {
        val host = app.session.phoneHost
        val cachedPort = app.session.phonePort
        if (!host.isNullOrBlank()) {
            val ports = linkedSetOf<Int>()
            if (cachedPort in 1..65535) ports += cachedPort
            ports += PHONE_PORT
            ports.forEach { port ->
                val cached = PairingNsd.Target(
                    name = app.session.tvName.ifBlank { "Celular" },
                    host = host,
                    port = port,
                )
                if (matchesPhone(cached, pairId)) return cached
            }
        }
        return discoverPhone(pairId)
    }

    private suspend fun discoverPhone(pairId: String): PairingNsd.Target? = coroutineScope {
        val nsd = PairingNsd(app)
        val result = CompletableDeferred<PairingNsd.Target?>()
        val probed = mutableSetOf<String>()
        nsd.discover(
            onFound = { target ->
                val key = "${target.host}:${target.port}"
                val added = synchronized(probed) { probed.add(key) }
                if (added) {
                    launch {
                        if (matchesPhone(target, pairId)) result.complete(target)
                    }
                }
            },
            onLost = { },
            onError = { error ->
                AppLog.w(AppLog.TAG_PAIRING, "Descoberta do celular falhou: $error")
                result.complete(null)
            },
        )
        try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { result.await() }
        } finally {
            runCatching { nsd.close() }
        }
    }

    private suspend fun matchesPhone(target: PairingNsd.Target, pairId: String): Boolean =
        runCatching {
            val text = PairingHttp.getJson(
                PairingHttp.url(target.host, target.port, "/hello"),
                connectTimeoutMs = 1_800,
                readTimeoutMs = 2_500,
            )
            val hello = PairingSync.json.decodeFromString(PairHello.serializer(), text)
            hello.paired && hello.pairId == pairId && hello.role == "phone"
        }.getOrDefault(false)

    companion object {
        /**
         * Portas preferidas dos canais persistentes. Se estiverem ocupadas, o
         * servidor cai para uma porta livre e o NSD continua encontrando o
         * endereço; o IP/porta em cache é só o atalho.
         */
        const val TV_PORT = 8765
        const val PHONE_PORT = 8766

        private const val FALLBACK_SYNC_MS = 30_000L
        private const val FAILED_SYNC_MS = 45_000L
        private const val INITIAL_SYNC_RETRY_MS = 5_000L
        private const val CHANGE_DEBOUNCE_MS = 700L
        private const val SUPPRESS_CHANGES_MS = 2_500L
        private const val SERVER_RETRY_MS = 5_000L
        private const val DISCOVERY_TIMEOUT_MS = 5_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000L
        private const val FINAL_PUSH_TIMEOUT_MS = 8_000L
        private const val DISCONNECT_WAIT_MS = 2_000L

        private val PAIRING_TABLES = arrayOf(
            "playlists",
            "playlist_tracks",
            "history",
            "artist_affinity",
            "play_events",
            "pair_tombstones",
            "yt_playlists",
            "yt_playlist_tracks",
            "yt_history",
            "yt_sync_state",
        )
    }
}

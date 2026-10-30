package com.iskezis.myst.data.pairing

import android.os.Build
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * Servidor que fica ativo na TV enquanto o app está aberto, depois do
 * pareamento. Ele recebe o instantâneo do celular, devolve o da TV e atende ao
 * pedido de desconexão. Tudo é cifrado/autenticado com o segredo criado no
 * pareamento; o identificador sozinho não permite ler nem alterar nada.
 */
class PairedTvServer(private val app: MystApp) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    @Volatile
    private var closed = false

    private var server: LocalPairingServer? = null
    private var nsd: PairingNsd? = null
    private var port = 0

    @Synchronized
    fun start(): Boolean {
        if (closed) return false
        if (server != null) return true
        val pairId = app.session.tvPairId
        if (pairId.isNullOrBlank() || app.session.tvPairSecret.isNullOrBlank()) {
            AppLog.w(AppLog.TAG_PAIRING, "TV sem canal persistente; servidor não iniciado")
            return false
        }
        val pairingServer = LocalPairingServer(maxBodyBytes = MAX_BODY_BYTES) { path, body, _ ->
            handle(path, body)
        }
        return runCatching {
            // Endereço estável: se o processo da TV reiniciar e o NSD demorar,
            // o celular ainda reconecta pelo último IP/porta conhecidos.
            port = pairingServer.start(PairedSyncManager.TV_PORT)
            server = pairingServer
            val helper = PairingNsd(app)
            nsd = helper
            helper.register(
                serviceName = "MystTV-${pairId.filter { it.isLetterOrDigit() }.take(12)}",
                port = port,
                onRegistered = { name ->
                    AppLog.i(
                        AppLog.TAG_PAIRING,
                        "Canal da TV ativo em $port ($name)",
                    )
                },
                onError = { error ->
                    AppLog.w(AppLog.TAG_PAIRING, "NSD do canal da TV falhou: $error")
                },
            )
            true
        }.getOrElse { t ->
            AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo o canal persistente da TV", t)
            runCatching { nsd?.close() }
            nsd = null
            runCatching { pairingServer.close() }
            server = null
            port = 0
            false
        }
    }

    private suspend fun handle(path: String, body: String): LocalPairingServer.HttpResult =
        when (path) {
            "/hello" -> hello()
            "/sync" -> sync(body)
            "/disconnect" -> disconnect(body)
            else -> LocalPairingServer.HttpResult(
                404,
                json(PairAck.serializer(), PairAck(false, "not_found")),
            )
        }

    private fun hello(): LocalPairingServer.HttpResult {
        val pairId = app.session.tvPairId ?: return unauthorized()
        val hello = PairHello(
            pairId = pairId,
            deviceName = Build.MODEL.orEmpty().ifBlank { "MystMusic TV" },
            appVersion = BuildConfig.VERSION_NAME,
            paired = app.session.hasTvPairing,
            role = "tv",
        )
        return LocalPairingServer.HttpResult(200, json(PairHello.serializer(), hello))
    }

    private suspend fun sync(body: String): LocalPairingServer.HttpResult = syncMutex.withLock {
        val session = app.session
        val pairId = session.tvPairId ?: return unauthorized()
        val secret = session.tvPairSecret ?: return unauthorized()
        val envelope = decodeEnvelope(body) ?: return badRequest()
        if (envelope.pairId != pairId) return unauthorized()
        val plaintext = runCatching {
            PairingCrypto.decryptSecret(secret, envelope.payload)
        }.getOrElse {
            AppLog.w(AppLog.TAG_PAIRING, "Sincronização recusada: segredo inválido")
            return unauthorized()
        }
        val request = runCatching {
            PairingSync.json.decodeFromString(PairSyncRequest.serializer(), plaintext)
        }.getOrNull() ?: return badRequest()

        val userId = session.mystUserId
        if (userId <= 0L) return unauthorized()
        // O banco da TV é em memória e pode ter sido recriado depois de o
        // processo reiniciar. Recria a identidade mínima antes do merge.
        val effectiveUserId = runCatching { app.accounts.ensureTvSessionUser() }
            .getOrElse { error ->
                AppLog.failure(
                    AppLog.TAG_PAIRING,
                    "Falha preparando usuário temporário da TV para o sync",
                    error,
                )
                userId
            }
        if (effectiveUserId <= 0L) return unauthorized()
        // Aparência/AMOLED e Discord são aplicados a cada sync para que uma
        // mudança feita no celular depois do pareamento apareça na TV.
        PairingSync.applyExtras(app, request.extras)
        PairingSync.mergeSnapshot(app, effectiveUserId, request.snapshot)
        // O merge veio de fora; não trate as escritas resultantes como uma
        // mudança local, senão a TV avisaria o celular em loop.
        app.pairedSync.suppressLocalChanges()
        val response = PairSyncResponse(
            snapshot = PairingSync.exportSnapshot(app, effectiveUserId),
        )
        val encrypted = PairingCrypto.encryptSecret(
            secret,
            PairingSync.json.encodeToString(PairSyncResponse.serializer(), response),
        )
        return LocalPairingServer.HttpResult(
            200,
            PairingSync.json.encodeToString(
                PairSecureEnvelope.serializer(),
                PairSecureEnvelope(pairId, encrypted),
            ),
        )
    }

    private suspend fun disconnect(body: String): LocalPairingServer.HttpResult =
        syncMutex.withLock {
            val session = app.session
            val pairId = session.tvPairId ?: return unauthorized()
            val secret = session.tvPairSecret ?: return unauthorized()
            val envelope = decodeEnvelope(body) ?: return badRequest()
            if (envelope.pairId != pairId) return unauthorized()
            val action = runCatching {
                PairingCrypto.decryptSecret(secret, envelope.payload)
            }.getOrNull() ?: return unauthorized()
            if (action != DISCONNECT_ACTION) return badRequest()
            AppLog.i(AppLog.TAG_PAIRING, "Celular pediu para desfazer o pareamento da TV")
            // O último retrato vai na própria resposta. Assim o celular grava
            // antes de limpar a sessão e não há um `/push` concorrente que
            // pudesse ficar esperando o mutex do celular enquanto ele espera
            // esta resposta.
            val userId = session.mystUserId
            val snapshot = if (userId > 0L) {
                runCatching { PairingSync.exportTvChanges(app, userId) }
                    .getOrElse {
                        AppLog.failure(
                            AppLog.TAG_PAIRING,
                            "Falha montando o retrato final da TV",
                            it,
                        )
                        PairSnapshot()
                    }
            } else {
                PairSnapshot()
            }
            val response = PairDisconnectResponse(ok = true, snapshot = snapshot)
            val encrypted = PairingCrypto.encryptSecret(
                secret,
                PairingSync.json.encodeToString(PairDisconnectResponse.serializer(), response),
            )
            scope.launch {
                // Deixa a resposta HTTP sair antes de encerrar o servidor e a
                // reprodução; a Activity volta para a tela do código.
                delay(350)
                app.pairedSync.onTvUnpaired()
            }
            return LocalPairingServer.HttpResult(
                200,
                json(PairSecureEnvelope.serializer(), PairSecureEnvelope(pairId, encrypted)),
            )
        }

    private fun decodeEnvelope(body: String): PairSecureEnvelope? = runCatching {
        PairingSync.json.decodeFromString(PairSecureEnvelope.serializer(), body)
    }.getOrNull()

    private fun unauthorized(): LocalPairingServer.HttpResult = LocalPairingServer.HttpResult(
        403,
        json(PairAck.serializer(), PairAck(false, "unauthorized")),
    )

    private fun badRequest(): LocalPairingServer.HttpResult = LocalPairingServer.HttpResult(
        400,
        json(PairAck.serializer(), PairAck(false, "invalid")),
    )

    private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        PairingSync.json.encodeToString(serializer, value)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { nsd?.close() }
        nsd = null
        runCatching { server?.close() }
        server = null
        port = 0
        scope.cancel()
        AppLog.i(AppLog.TAG_PAIRING, "Canal persistente da TV encerrado")
    }

    companion object {
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        private const val DISCONNECT_ACTION = "disconnect"
    }
}

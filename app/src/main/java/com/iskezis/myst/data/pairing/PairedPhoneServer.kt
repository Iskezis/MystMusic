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
import java.io.Closeable

/**
 * Servidor persistente do celular no pareamento.
 *
 * A TV já tinha um servidor para receber os dados do celular; faltava o
 * caminho inverso. Com este canal a TV consegue avisar o celular assim que o
 * histórico, as estatísticas ou uma playlist mudam nela, em vez de esperar o
 * próximo ciclo de sincronização. O aviso é autenticado com o mesmo segredo
 * AES-GCM do pareamento e não carrega dados da conta.
 */
class PairedPhoneServer(
    private val app: MystApp,
    private val onSyncRequested: () -> Unit,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    private var server: LocalPairingServer? = null
    private var nsd: PairingNsd? = null

    @Synchronized
    fun start(): Boolean {
        if (closed) return false
        if (server != null) return true
        val pairId = app.session.tvPairId
        if (pairId.isNullOrBlank() || app.session.tvPairSecret.isNullOrBlank()) {
            AppLog.d(AppLog.TAG_PAIRING, "Celular sem canal persistente; servidor não iniciado")
            return false
        }
        val pairingServer = LocalPairingServer(maxBodyBytes = MAX_BODY_BYTES) { path, body, _ ->
            handle(path, body)
        }
        return runCatching {
            val port = pairingServer.start(PairedSyncManager.PHONE_PORT)
            server = pairingServer
            val helper = PairingNsd(app)
            nsd = helper
            helper.register(
                serviceName = "MystPhone-${pairId.filter { it.isLetterOrDigit() }.take(12)}",
                port = port,
                onRegistered = { name ->
                    AppLog.i(
                        AppLog.TAG_PAIRING,
                        "Canal do celular ativo na porta $port ($name)",
                    )
                },
                onError = { error ->
                    AppLog.w(AppLog.TAG_PAIRING, "NSD do canal do celular falhou: $error")
                },
            )
            true
        }.getOrElse { t ->
            AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo o canal persistente do celular", t)
            runCatching { nsd?.close() }
            nsd = null
            runCatching { pairingServer.close() }
            server = null
            false
        }
    }

    private suspend fun handle(path: String, body: String): LocalPairingServer.HttpResult =
        when (path) {
            "/hello" -> hello()
            "/notify" -> notify(body)
            "/push" -> push(body)
            else -> LocalPairingServer.HttpResult(
                404,
                json(PairAck.serializer(), PairAck(false, "not_found")),
            )
        }

    private fun hello(): LocalPairingServer.HttpResult {
        val pairId = app.session.tvPairId ?: return unauthorized()
        val hello = PairHello(
            pairId = pairId,
            deviceName = Build.MODEL.orEmpty().ifBlank { "Celular" },
            appVersion = BuildConfig.VERSION_NAME,
            paired = app.session.hasTvPairing,
            role = "phone",
        )
        return LocalPairingServer.HttpResult(200, json(PairHello.serializer(), hello))
    }

    private fun notify(body: String): LocalPairingServer.HttpResult {
        val session = app.session
        val pairId = session.tvPairId ?: return unauthorized()
        val secret = session.tvPairSecret ?: return unauthorized()
        val envelope = runCatching {
            PairingSync.json.decodeFromString(PairSecureEnvelope.serializer(), body)
        }.getOrNull() ?: return badRequest()
        if (envelope.pairId != pairId) return unauthorized()
        val action = runCatching {
            PairingCrypto.decryptSecret(secret, envelope.payload)
        }.getOrNull() ?: return unauthorized()
        if (action != NOTIFY_ACTION && action != UNPAIR_ACTION) return badRequest()
        // Responde primeiro e só depois executa a ação. A pequena espera evita
        // que a própria requisição de sincronização dispute a conexão.
        scope.launch {
            delay(180)
            if (action == UNPAIR_ACTION) {
                AppLog.i(AppLog.TAG_PAIRING, "A TV pediu para desfazer o pareamento")
                app.pairedSync.clearPairingLocally()
            } else {
                runCatching { onSyncRequested() }.onFailure {
                    AppLog.failure(AppLog.TAG_PAIRING, "Falha agendando sync após aviso da TV", it)
                }
            }
        }
        return LocalPairingServer.HttpResult(200, json(PairAck.serializer(), PairAck(true)))
    }

    /**
     * Recebe o retrato completo que a TV envia após uma mudança local. O merge
     * acontece antes da resposta: a TV só limpa o histórico/estatísticas dela
     * quando o celular confirma que gravou.
     */
    private suspend fun push(body: String): LocalPairingServer.HttpResult {
        val session = app.session
        val pairId = session.tvPairId ?: return unauthorized()
        val secret = session.tvPairSecret ?: return unauthorized()
        val envelope = runCatching {
            PairingSync.json.decodeFromString(PairSecureEnvelope.serializer(), body)
        }.getOrNull() ?: return badRequest()
        if (envelope.pairId != pairId) return unauthorized()
        val plaintext = runCatching {
            PairingCrypto.decryptSecret(secret, envelope.payload)
        }.getOrNull() ?: return unauthorized()
        val snapshot = runCatching {
            PairingSync.json.decodeFromString(PairSnapshot.serializer(), plaintext)
        }.getOrNull() ?: return badRequest()
        val applied = app.pairedSync.acceptTvSnapshot(snapshot)
        return if (applied) {
            LocalPairingServer.HttpResult(200, json(PairAck.serializer(), PairAck(true)))
        } else {
            LocalPairingServer.HttpResult(500, json(PairAck.serializer(), PairAck(false, "merge")))
        }
    }

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
        scope.cancel()
        AppLog.i(AppLog.TAG_PAIRING, "Canal persistente do celular encerrado")
    }

    private companion object {
        const val MAX_BODY_BYTES = 32 * 1024 * 1024
        const val NOTIFY_ACTION = "sync"
        const val UNPAIR_ACTION = "unpair"
    }
}

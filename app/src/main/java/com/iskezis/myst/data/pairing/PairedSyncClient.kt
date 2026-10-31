package com.iskezis.myst.data.pairing

import com.iskezis.myst.MystApp
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Lado do celular no canal persistente. Tenta o último endereço conhecido da
 * TV e, se ele mudou (DHCP/reinício), descobre de novo via NSD. Antes de
 * qualquer pedido confirma o `pairId` em `/hello`, então uma TV de outra conta
 * na mesma rede nunca recebe dados por engano.
 */
class PairedSyncClient {

    data class DisconnectCredentials(
        val pairId: String,
        val secret: String,
        val name: String = "",
        val host: String? = null,
        val port: Int = 0,
    )

    suspend fun sync(app: MystApp): SyncResult = withContext(Dispatchers.IO) {
        val session = app.session
        val pairId = session.tvPairId
            ?: throw IOException("TV não pareada")
        val secret = session.tvPairSecret
            ?: throw IOException("Segredo do pareamento ausente")
        val userId = session.mystUserId
        if (userId <= 0L) throw IOException("Conta Myst ausente")

        val target = findTarget(
            app = app,
            pairId = pairId,
            cachedHost = session.tvHost,
            cachedPort = session.tvPort,
            tvName = session.tvName,
        ) ?: throw IOException("TV não encontrada na rede")
        val outgoing = PairingSync.exportSnapshot(app, userId)
        val request = PairSyncRequest(
            snapshot = outgoing,
            extras = PairingSync.exportExtras(app),
        )
        val envelope = PairSecureEnvelope(
            pairId = pairId,
            payload = PairingCrypto.encryptSecret(
                secret,
                PairingSync.json.encodeToString(PairSyncRequest.serializer(), request),
            ),
        )
        val responseText = PairingHttp.postJson(
            url = PairingHttp.url(target.host, target.port, "/sync"),
            body = PairingSync.json.encodeToString(
                PairSecureEnvelope.serializer(),
                envelope,
            ),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 45_000,
        )
        val responseEnvelope = runCatching {
            PairingSync.json.decodeFromString(PairSecureEnvelope.serializer(), responseText)
        }.getOrElse {
            throw IOException("Resposta inválida da TV", it)
        }
        if (responseEnvelope.pairId != pairId) throw IOException("TV respondeu outro pareamento")
        val plaintext = runCatching {
            PairingCrypto.decryptSecret(secret, responseEnvelope.payload)
        }.getOrElse {
            throw IOException("Não foi possível validar a resposta da TV", it)
        }
        val response = runCatching {
            PairingSync.json.decodeFromString(PairSyncResponse.serializer(), plaintext)
        }.getOrElse {
            throw IOException("Resposta de sincronização inválida", it)
        }
        PairingSync.mergeSnapshot(app, userId, response.snapshot)
        session.updateTvEndpoint(target.host, target.port)
        session.tvLastSyncAt = System.currentTimeMillis()
        // O Room acabou de mudar; a cópia interna/externa do Mystaccount.json
        // precisa refletir as playlists e o histórico vindos da TV.
        app.accountFiles.requestSync()
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Sincronização com a TV concluída: playlists=${response.snapshot.playlists.size} " +
                "histórico=${response.snapshot.history.size} " +
                "yt_playlists=${response.snapshot.youtube.playlists.size} " +
                "yt_histórico=${response.snapshot.youtube.history.size}",
        )
        SyncResult(
            tvName = session.tvName.ifBlank { target.name },
            playlists = response.snapshot.playlists.size,
            tracks = response.snapshot.playlists.sumOf { it.tracks.size },
        )
    }

    /**
     * Pede a desconexão usando credenciais explícitas. O gerenciador pode
     * manter um despareamento pendente depois de já ter removido a sessão
     * local, por isso este método não lê nem apaga o `SessionManager`.
     *
     * A TV só apaga o retrato final depois de receber `/disconnect_ack`; se o
     * merge no celular falhar, o ack não é enviado e o outbox da TV é mantido
     * para a próxima tentativa/pareamento.
     */
    suspend fun disconnect(
        app: MystApp,
        credentials: DisconnectCredentials,
        acceptWithoutPairing: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val pairId = credentials.pairId
        val secret = credentials.secret
        if (pairId.isBlank() || secret.isBlank()) throw IOException("Credenciais da TV ausentes")
        val target = findTarget(
            app = app,
            pairId = pairId,
            cachedHost = credentials.host ?: app.session.tvHost,
            cachedPort = credentials.port.takeIf { it > 0 } ?: app.session.tvPort,
            tvName = credentials.name.ifBlank { app.session.tvName },
        ) ?: throw IOException("TV não encontrada na rede")
        val envelope = PairSecureEnvelope(
            pairId = pairId,
            payload = PairingCrypto.encryptSecret(secret, DISCONNECT_ACTION),
        )
        val responseText = PairingHttp.postJson(
            url = PairingHttp.url(target.host, target.port, "/disconnect"),
            body = PairingSync.json.encodeToString(PairSecureEnvelope.serializer(), envelope),
            connectTimeoutMs = 5_000,
            // A resposta pode carregar o retrato final da TV (histórico,
            // estatísticas, playlists e buscas), então merece mais folga que
            // um ack. Se o celular desistir cedo demais, a TV preserva o
            // outbox, mas o retrato só chega no próximo pareamento.
            readTimeoutMs = 60_000,
        )
        // A TV nova devolve um envelope cifrado com o retrato final. A TV
        // antiga devolvia um PairAck em texto puro; aceitar os dois formatos
        // evita que atualizar só um dos aparelhos quebre a desconexão.
        val responseEnvelope = runCatching {
            PairingSync.json.decodeFromString(PairSecureEnvelope.serializer(), responseText)
        }.getOrNull()
        if (responseEnvelope != null) {
            if (responseEnvelope.pairId != pairId) {
                throw IOException("TV respondeu outro pareamento")
            }
            val plaintext = runCatching {
                PairingCrypto.decryptSecret(secret, responseEnvelope.payload)
            }.getOrElse {
                throw IOException("Não foi possível validar a desconexão", it)
            }
            val response = runCatching {
                PairingSync.json.decodeFromString(PairDisconnectResponse.serializer(), plaintext)
            }.getOrElse {
                throw IOException("Resposta de desconexão inválida", it)
            }
            if (!response.ok) throw IOException("A TV recusou a desconexão")
            val accepted = runCatching {
                if (app.session.hasTvPairing) {
                    app.pairedSync.acceptTvSnapshot(response.snapshot)
                } else if (acceptWithoutPairing) {
                    app.pairedSync.acceptUnpairedTvSnapshot(response.snapshot)
                } else {
                    false
                }
            }.getOrElse {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha gravando o retrato final da TV", it)
                false
            }
            if (accepted) {
                // Confirma que o retrato foi gravado. Sem isso a TV mantém o
                // outbox completo para a próxima tentativa e não apaga dados
                // que o celular nunca recebeu.
                runCatching {
                    sendDisconnectAck(target, pairId, secret)
                }.onFailure {
                    AppLog.w(
                        AppLog.TAG_PAIRING,
                        "TV não recebeu a confirmação do retrato final: ${it.message}",
                    )
                }
            } else {
                // A TV já parou o som, mas o retrato não entrou no banco do
                // celular. Mantém o despareamento como pendente para tentar de
                // novo em vez de apagar a sessão e deixar os dados só na TV.
                throw IOException("Não foi possível gravar o retrato final da TV")
            }
            AppLog.i(
                AppLog.TAG_PAIRING,
                "Último retrato da TV gravado antes de desconectar: $accepted " +
                    "(histórico=${response.snapshot.history.size} " +
                    "eventos=${response.snapshot.playEvents.size})",
            )
        } else {
            val ack = runCatching {
                PairingSync.json.decodeFromString(PairAck.serializer(), responseText)
            }.getOrNull()
            if (ack?.ok != true) throw IOException("A TV recusou a desconexão")
        }
        AppLog.i(AppLog.TAG_PAIRING, "TV desconectada pelo celular")
    }

    private suspend fun sendDisconnectAck(
        target: PairingNsd.Target,
        pairId: String,
        secret: String,
    ) {
        val envelope = PairSecureEnvelope(
            pairId = pairId,
            payload = PairingCrypto.encryptSecret(secret, DISCONNECT_ACK_ACTION),
        )
        PairingHttp.postJson(
            url = PairingHttp.url(target.host, target.port, "/disconnect_ack"),
            body = PairingSync.json.encodeToString(PairSecureEnvelope.serializer(), envelope),
            connectTimeoutMs = 3_000,
            readTimeoutMs = 5_000,
        )
    }

    private suspend fun findTarget(
        app: MystApp,
        pairId: String,
        cachedHost: String?,
        cachedPort: Int,
        tvName: String,
    ): PairingNsd.Target? {
        if (!cachedHost.isNullOrBlank()) {
            val candidates = linkedSetOf<Int>()
            if (cachedPort in 1..65535) candidates += cachedPort
            // O servidor persistente usa esta porta fixa; tentar também cobre
            // o caso de o NSD não estar disponível logo após reiniciar a TV.
            candidates += PairedSyncManager.TV_PORT
            candidates.forEach { port ->
                val cached = PairingNsd.Target(
                    name = tvName.ifBlank { "MystMusic TV" },
                    host = cachedHost,
                    port = port,
                )
                if (matches(cached, pairId)) return cached
            }
        }
        return discover(app, pairId)
    }

    private suspend fun discover(app: MystApp, pairId: String): PairingNsd.Target? =
        coroutineScope {
            val nsd = PairingNsd(app)
            val result = CompletableDeferred<PairingNsd.Target?>()
            val probed = mutableSetOf<String>()
            nsd.discover(
                onFound = { target ->
                    val key = "${target.host}:${target.port}"
                    val added = synchronized(probed) { probed.add(key) }
                    if (added) {
                        launch {
                            if (matches(target, pairId)) {
                                result.complete(target)
                            }
                        }
                    }
                },
                onLost = { },
                onError = { error ->
                    AppLog.w(AppLog.TAG_PAIRING, "Descoberta da TV falhou: $error")
                    result.complete(null)
                },
            )
            try {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { result.await() }
            } finally {
                runCatching { nsd.close() }
            }
        }

    private suspend fun matches(target: PairingNsd.Target, pairId: String): Boolean =
        runCatching {
            val text = PairingHttp.getJson(
                PairingHttp.url(target.host, target.port, "/hello"),
                connectTimeoutMs = 2_500,
                readTimeoutMs = 4_000,
            )
            val hello = PairingSync.json.decodeFromString(PairHello.serializer(), text)
            hello.paired && hello.pairId == pairId && hello.role != "phone"
        }.getOrDefault(false)

    data class SyncResult(
        val tvName: String,
        val playlists: Int,
        val tracks: Int,
    )

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 7_000L
        const val DISCONNECT_ACTION = "disconnect"
        const val DISCONNECT_ACK_ACTION = "disconnect_ack"
    }
}

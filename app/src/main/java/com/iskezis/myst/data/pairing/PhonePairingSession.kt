package com.iskezis.myst.data.pairing

import android.os.Build
import com.iskezis.myst.MystApp
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sessão de pareamento do celular, independente da Activity.
 *
 * O servidor que responde ao `/confirm` da TV precisa continuar vivo quando o
 * usuário volta para a TV, coloca o app em segundo plano ou remove a tarefa
 * recente. Antes ele pertencia à PhonePairingActivity e era fechado no
 * onDestroy; agora pertence à Application e só termina no sucesso, na
 * expiração do código ou quando o usuário cancela de propósito.
 */
class PhonePairingSession(private val app: MystApp) {

    sealed interface State {
        data object Idle : State
        data class Waiting(val challenge: Challenge) : State
        data class Paired(val tvName: String) : State
        data object Expired : State
    }

    data class Challenge(
        val code: String,
        val nonce: String,
        val tvName: String,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = PairingSync.json
    @Volatile
    private var server: LocalPairingServer? = null
    @Volatile
    private var busy = false
    private var expiryJob: Job? = null
    private var closeJob: Job? = null

    val isPairingInProgress: Boolean
        get() = _state.value is State.Waiting

    val port: Int
        get() = server?.port ?: 0

    val currentChallenge: Challenge?
        get() = (_state.value as? State.Waiting)?.challenge

    /**
     * Abre o servidor de confirmação se ainda não estiver aberto. É chamado
     * antes de o celular pedir à TV para iniciar a sessão, para o endereço já
     * estar pronto quando a TV digitar o código.
     */
    @Synchronized
    fun ensureServer(): Boolean {
        if (server != null) return true
        val pairingServer = LocalPairingServer { path, body, remoteHost ->
            if (path != "/confirm") {
                LocalPairingServer.HttpResult(
                    404,
                    json.encodeToString(PairAck.serializer(), PairAck(false, "not_found")),
                )
            } else {
                handleConfirm(body, remoteHost)
            }
        }
        return runCatching {
            pairingServer.start()
            server = pairingServer
            AppLog.i(
                AppLog.TAG_PAIRING,
                "Celular ouvindo confirmação na porta ${pairingServer.port}",
            )
            true
        }.getOrElse { t ->
            AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo servidor do celular", t)
            runCatching { pairingServer.close() }
            server = null
            false
        }
    }

    /**
     * Gera um novo código e mantém o servidor ativo mesmo se a tela for
     * fechada. O serviço em primeiro plano impede o Android de encerrar o
     * processo enquanto a TV ainda pode confirmar.
     */
    @Synchronized
    fun begin(tvName: String): Challenge? {
        if (!ensureServer()) return null
        closeJob?.cancel()
        closeJob = null
        expiryJob?.cancel()
        val challenge = Challenge(
            code = PairingCrypto.newCode(),
            nonce = PairingCrypto.newNonce(),
            tvName = tvName.trim().ifBlank { "MystMusic TV" },
        )
        busy = false
        _state.value = State.Waiting(challenge)
        scheduleExpiry()
        PairingSyncService.start(app)
        return challenge
    }

    /** Cancela uma sessão em andamento; nunca desfaz um pareamento concluído. */
    @Synchronized
    fun cancel() {
        if (_state.value is State.Paired) {
            closeServer()
            return
        }
        expiryJob?.cancel()
        expiryJob = null
        closeJob?.cancel()
        closeJob = null
        _state.value = State.Idle
        closeServer()
        if (!app.session.hasTvPairing) {
            PairingSyncService.stop(app)
        }
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = app.backgroundScope.launch {
            delay(CODE_TTL_MS)
            if (_state.value !is State.Waiting) return@launch
            AppLog.i(AppLog.TAG_PAIRING, "Código de pareamento do celular expirou")
            _state.value = State.Expired
            closeServer()
            if (!app.session.hasTvPairing) {
                PairingSyncService.stop(app)
            }
        }
    }

    private suspend fun handleConfirm(
        body: String,
        remoteHost: String,
    ): LocalPairingServer.HttpResult {
        val waiting = _state.value as? State.Waiting
            ?: return expiredResult()
        if (busy) return busyResult()
        val confirm = runCatching {
            json.decodeFromString(PairConfirm.serializer(), body)
        }.getOrNull() ?: return LocalPairingServer.HttpResult(
            400,
            json.encodeToString(PairAck.serializer(), PairAck(false, "invalid")),
        )
        if (confirm.code != waiting.challenge.code) {
            AppLog.w(AppLog.TAG_PAIRING, "TV enviou código incorreto")
            return LocalPairingServer.HttpResult(
                403,
                json.encodeToString(PairAck.serializer(), PairAck(false, "invalid_code")),
            )
        }
        busy = true
        return try {
            // A senha de 5 dígitos só autoriza o primeiro contato. O canal que
            // fica ativo depois usa este identificador/segredo, guardado nos
            // dois lados.
            val pairId = java.util.UUID.randomUUID().toString()
            val pairSecret = PairingCrypto.newSecret()
            val bundle = runCatching {
                PairingSync.export(
                    app = app,
                    pairId = pairId,
                    pairSecret = pairSecret,
                    phoneName = Build.MODEL.orEmpty().ifBlank { "Celular" },
                )
            }.getOrElse { throwable ->
                AppLog.failure(AppLog.TAG_PAIRING, "Falha preparando payload", throwable)
                return LocalPairingServer.HttpResult(
                    500,
                    json.encodeToString(PairAck.serializer(), PairAck(false, "export_failed")),
                )
            }
            val plaintext = json.encodeToString(
                com.iskezis.myst.data.pairing.PairBundle.serializer(),
                bundle,
            )
            val encrypted = PairingCrypto.encrypt(waiting.challenge.code, plaintext)
            // Guarda o canal persistente antes de liberar a resposta; se a
            // conexão cair depois, a TV ainda consegue parear e a próxima
            // sincronização encontra o servidor dela na rede.
            app.session.saveTvPairing(
                pairId = pairId,
                secret = pairSecret,
                name = waiting.challenge.tvName,
                host = remoteHost,
                port = 0,
            )
            expiryJob?.cancel()
            expiryJob = null
            _state.value = State.Paired(waiting.challenge.tvName)
            app.pairedSync.onPhonePaired()
            PairingSyncService.start(app)
            // Deixa a resposta HTTP sair antes de fechar o servidor temporário.
            closeJob?.cancel()
            closeJob = app.backgroundScope.launch {
                delay(SERVER_CLOSE_DELAY_MS)
                closeServer()
            }
            LocalPairingServer.HttpResult(
                200,
                json.encodeToString(PairPayload.serializer(), PairPayload(encrypted)),
            )
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PAIRING, "Falha concluindo pareamento", t)
            LocalPairingServer.HttpResult(
                500,
                json.encodeToString(PairAck.serializer(), PairAck(false, "internal")),
            )
        } finally {
            busy = false
        }
    }

    private fun expiredResult(): LocalPairingServer.HttpResult = LocalPairingServer.HttpResult(
        403,
        json.encodeToString(PairAck.serializer(), PairAck(false, "expired")),
    )

    private fun busyResult(): LocalPairingServer.HttpResult = LocalPairingServer.HttpResult(
        403,
        json.encodeToString(PairAck.serializer(), PairAck(false, "busy")),
    )

    @Synchronized
    private fun closeServer() {
        runCatching { server?.close() }
        server = null
    }

    private companion object {
        const val CODE_TTL_MS = 2 * 60 * 1000L
        const val SERVER_CLOSE_DELAY_MS = 3_000L
    }
}

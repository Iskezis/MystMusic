package com.iskezis.myst.data.discord

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

enum class DiscordConnection { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class DiscordState(
    val connection: DiscordConnection = DiscordConnection.DISCONNECTED,
    val userName: String? = null,
)

/**
 * Rich Presence via gateway do Discord, usando o token de usuário capturado
 * pelo login WebView. É uma integração não oficial e opt-in.
 *
 * A implementação segue o mesmo fluxo do KizzyRPC usado pelo BitChord:
 * valida o token na REST, identifica com o fingerprint do cliente Android,
 * mantém heartbeat com ACK e reconecta com backoff. Ao voltar, a presença é
 * reconstruída a partir do estado atual do player. Nenhum token é gravado no
 * Logcat.
 */
class DiscordPresenceManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val session: SessionManager,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * O cliente do gateway usa `readTimeout=0` porque o WebSocket fica aberto.
     * As chamadas REST (validar token, criar aplicativo, registrar capa) precisam
     * de timeout para nunca prender uma coroutine do player.
     */
    private val restClient = client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        DiscordState(
            connection = DiscordConnection.DISCONNECTED,
            userName = session.discordName,
        ),
    )
    val state: StateFlow<DiscordState> = _state.asStateFlow()

    @Volatile
    private var token: String? = cleanToken(session.discordToken)
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var manualStop = token == null

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var helloTimeoutJob: Job? = null
    private var readyTimeoutJob: Job? = null

    @Volatile
    private var ready = false
    private var sequence: Int? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var heartbeatInterval = 0L
    private var awaitingAck = false
    private var lastAckAt = 0L
    private var reconnectAttempt = 0

    private var latestPlayerState: PlayerState? = null
    private var presenceActive = false
    private var presenceTrackId: String? = null
    private var presenceStart = 0L
    private var presenceDurationMs = 0L
    private var lastPlaying = false

    /** Evita reenviar a mesma presença e descartar uma publicação antiga. */
    private var presenceJob: Job? = null

    /**
     * O gateway só aceita `large_image` como asset do aplicativo ou como
     * caminho `mp:` emitido por `/applications/{id}/external-assets`. A URL
     * crua faz o card ficar sem imagem. O id pode vir de `local.properties`/
     * Secrets; sem ele, usamos o aplicativo público do BitChord apenas como
     * namespace do media proxy (o card continua “Ouvindo MystMusic”).
     */
    @Volatile
    private var appId: String? = null

    private val rejectedAppIds =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val artworkCache = object : LruCache<String, String>(64) {}

    init {
        // Versões antigas salvavam o token com as aspas do localStorage do
        // Discord (74 caracteres em vez de 72). O Discord responde 401 a esse
        // valor; limpar na leitura conserta a sessão já salva sem exigir um
        // novo login.
        val stored = token
        if (stored != null) {
            if (stored != session.discordToken) session.discordToken = stored
            startConnection(validateToken = true)
        }
    }

    fun connect(newToken: String) {
        val clean = cleanToken(newToken)
        AppLog.i(
            AppLog.TAG_DISCORD,
            "Discord connect solicitado (token=${clean?.length ?: 0} caracteres)",
        )
        if (clean == null) {
            AppLog.w(AppLog.TAG_DISCORD, "Discord connect com token vazio")
            _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
            return
        }
        token = clean
        manualStop = false
        reconnectAttempt = 0
        session.discordToken = clean
        startConnection(validateToken = true)
    }

    fun disconnect() {
        AppLog.i(AppLog.TAG_DISCORD, "Discord disconnect")
        manualStop = true
        token = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        ready = false
        sequence = null
        sessionId = null
        resumeUrl = null
        awaitingAck = false
        presenceActive = false
        presenceTrackId = null
        lastPlaying = false
        presenceJob?.cancel()
        presenceJob = null
        appId = null
        rejectedAppIds.clear()
        artworkCache.evictAll()
        session.clearDiscord()
        runCatching {
            socket?.close(1000, "Desconectado")
            socket?.cancel()
        }
        socket = null
        _state.value = DiscordState(DiscordConnection.DISCONNECTED, null)
    }

    fun retry() {
        if (token == null) return
        AppLog.i(AppLog.TAG_DISCORD, "Discord retry")
        manualStop = false
        reconnectAttempt = 0
        startConnection(validateToken = true)
    }

    /**
     * Chamado pelo observador global de `MystApp` a cada atualização do player.
     * Qualquer falha aqui fica isolada: o Discord nunca pode derrubar a
     * reprodução nem a navegação do app.
     */
    fun onPlayerState(state: PlayerState) {
        try {
            handlePlayerState(state)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_DISCORD, "Falha atualizando presença do Discord", t)
        }
    }

    private fun handlePlayerState(state: PlayerState) {
        latestPlayerState = state
        val track = state.track
        if (track == null) {
            lastPlaying = false
            if (presenceActive) {
                presenceActive = false
                presenceTrackId = null
                presenceDurationMs = 0L
                clearPresence()
            }
            return
        }

        if (!state.isPlaying) {
            // O card do Discord não deve continuar contando tempo enquanto a
            // música está pausada. Antes havia uma carência de 2 minutos; o
            // usuário pediu que sumisse imediatamente, inclusive ao trocar de
            // faixa (o estado intermediário é "pausado").
            lastPlaying = false
            if (presenceActive) {
                presenceActive = false
                presenceTrackId = null
                presenceDurationMs = 0L
                clearPresence()
            }
            return
        }

        val resumed = !lastPlaying
        lastPlaying = true

        val expectedStart = System.currentTimeMillis() - state.positionMs
        val trackChanged = presenceTrackId != track.videoId
        val wasInactive = !presenceActive
        val jumped = !trackChanged && !wasInactive &&
            abs(expectedStart - presenceStart) > 3_000L
        // A duração chega alguns milissegundos depois do primeiro estado
        // "playing". Sem reenviar quando ela aparece, o card fica sem `end` e
        // o Discord não desenha a barra de progresso.
        val durationBecameKnown = presenceDurationMs <= 0L && state.durationMs > 0L
        if (!trackChanged && !wasInactive && !resumed && !jumped && !durationBecameKnown) {
            return
        }

        presenceTrackId = track.videoId
        presenceStart = expectedStart
        presenceDurationMs = state.durationMs
        presenceActive = true
        if (ready) publishPresence(track, expectedStart, state.durationMs)
    }

    private fun startConnection(validateToken: Boolean) {
        val currentToken = token ?: return
        manualStop = false
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        ready = false
        sequence = null
        sessionId = null
        resumeUrl = null
        awaitingAck = false
        presenceJob?.cancel()
        presenceJob = null
        appId = null
        rejectedAppIds.clear()
        connectJob?.cancel()
        socket?.cancel()
        socket = null
        _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
        AppLog.i(
            AppLog.TAG_DISCORD,
            "Validando/iniciando Discord (token=${currentToken.length} caracteres, valida=$validateToken)",
        )
        connectJob = scope.launch {
            try {
                if (validateToken) {
                    val name = withContext(Dispatchers.IO) { fetchUserName(currentToken) }
                    if (currentToken != token || manualStop) return@launch
                    if (!name.isNullOrBlank()) {
                        session.discordName = name
                        _state.value = DiscordState(DiscordConnection.CONNECTING, name)
                    }
                }
                if (currentToken != token || manualStop) return@launch
                openSocket(currentToken)
            } catch (t: InvalidTokenException) {
                if (currentToken != token) return@launch
                failAuthentication()
            } catch (t: Throwable) {
                if (currentToken != token || manualStop) return@launch
                AppLog.failure(AppLog.TAG_DISCORD, "Falha validando token do Discord", t)
                _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
                scheduleReconnect()
            }
        }
    }

    private fun fetchUserName(currentToken: String): String? {
        // Mesmo caminho do KizzyRPC/BitChord: REST v9 com o fingerprint do
        // cliente Android. O token já chega sem as aspas do localStorage;
        // enviar as aspas no header Authorization é o que causava HTTP 401.
        val request = Request.Builder()
            .url("https://discord.com/api/v9/users/@me")
            .header("Authorization", currentToken)
            .header("User-Agent", DiscordSuperProperties.userAgent)
            .header("X-Super-Properties", DiscordSuperProperties.base64)
            .header("Accept", "application/json")
            .build()
        restClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                AppLog.w(
                    AppLog.TAG_DISCORD,
                    "Discord recusou o token: HTTP ${response.code} ${text.take(120)}",
                )
                throw InvalidTokenException()
            }
            if (!response.isSuccessful) {
                throw IOException("Discord HTTP ${response.code}")
            }
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            val globalName = (root["global_name"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val username = (root["username"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            return globalName ?: username
        }
    }

    private fun failAuthentication() {
        AppLog.w(AppLog.TAG_DISCORD, "Token do Discord inválido ou expirado; limpando sessão")
        manualStop = true
        token = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        ready = false
        session.clearDiscord()
        presenceJob?.cancel()
        presenceJob = null
        appId = null
        rejectedAppIds.clear()
        runCatching { socket?.cancel() }
        socket = null
        _state.value = DiscordState(DiscordConnection.ERROR, null)
    }

    private fun openSocket(currentToken: String) {
        if (manualStop || currentToken != token) return
        reconnectJob?.cancel()
        reconnectJob = null
        helloTimeoutJob?.cancel()
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        val url = resumeUrl ?: GATEWAY_URL
        AppLog.i(AppLog.TAG_DISCORD, "Abrindo WebSocket do Discord em $url")
        try {
            val previous = socket
            socket = null
            previous?.cancel()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DiscordSuperProperties.userAgent)
                .header("X-Super-Properties", DiscordSuperProperties.base64)
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build()
            socket = client.newWebSocket(request, Listener(currentToken))
            _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
            // Um socket que abre mas nunca manda HELLO deixaria a tela
            // "Conectando..." para sempre. Depois de 15 s viramos erro e
            // tentamos de novo em segundo plano.
            helloTimeoutJob = scope.launch {
                delay(HELLO_TIMEOUT_MS)
                if (!ready && socket != null) {
                    AppLog.w(AppLog.TAG_DISCORD, "Discord não enviou HELLO em 15s; reabrindo")
                    _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
                    socket?.cancel()
                    socket = null
                    scheduleReconnect()
                }
            }
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_DISCORD, "Falha abrindo WebSocket do Discord", t)
            _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
            scheduleReconnect()
        }
    }

    private inner class Listener(private val currentToken: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            AppLog.i(AppLog.TAG_DISCORD, "WebSocket do Discord conectado")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Todos os eventos do socket são serializados na main para que
            // ready/sessionId/sequence nunca sejam lidos de outra thread.
            scope.launch {
                if (webSocket !== socket) return@launch
                runCatching { handleMessage(text, currentToken) }
                    .onFailure {
                        AppLog.failure(AppLog.TAG_DISCORD, "Payload inválido do Discord", it)
                    }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            AppLog.i(AppLog.TAG_DISCORD, "Discord fechando: $code ${reason.take(160)}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                if (webSocket !== socket) return@launch
                socket = null
                onSocketDown(currentToken, code, reason, null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code ?: 0
            scope.launch {
                if (webSocket !== socket) return@launch
                socket = null
                onSocketDown(currentToken, code, t.message.orEmpty(), t)
            }
        }
    }

    private fun onSocketDown(
        currentToken: String,
        code: Int,
        reason: String,
        failure: Throwable?,
    ) {
        ready = false
        presenceJob?.cancel()
        presenceJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        awaitingAck = false
        if (failure != null) {
            // Se o resume URL falhou, o próximo HELLO usa o gateway base e
            // faz um identify novo em vez de insistir numa sessão morta.
            resumeUrl = null
            sessionId = null
            sequence = null
            AppLog.w(
                AppLog.TAG_DISCORD,
                "Conexão Discord caiu HTTP=$code: ${AppLog.describe(failure)}",
            )
        } else {
            AppLog.w(AppLog.TAG_DISCORD, "Conexão Discord fechada: $code ${reason.take(160)}")
        }
        if (manualStop || currentToken != token) {
            _state.value = DiscordState(DiscordConnection.DISCONNECTED, session.discordName)
            return
        }
        if (code in AUTH_CLOSE_CODES) {
            failAuthentication()
            return
        }
        _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
        scheduleReconnect()
    }

    private fun handleMessage(text: String, currentToken: String) {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return
        (root["s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { sequence = it }
        when ((root["op"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) {
            OP_HELLO -> {
                helloTimeoutJob?.cancel()
                helloTimeoutJob = null
                val interval = ((root["d"] as? JsonObject)
                    ?.get("heartbeat_interval") as? JsonPrimitive)
                    ?.contentOrNull?.toLongOrNull() ?: 41_250L
                heartbeatInterval = interval
                if (sessionId != null && sequence != null && resumeUrl != null) {
                    sendResume(currentToken)
                } else {
                    sendIdentify(currentToken)
                }
                startHeartbeat(interval)
                armReadyTimeout(currentToken)
            }
            OP_HEARTBEAT_ACK -> {
                awaitingAck = false
                lastAckAt = SystemClock.elapsedRealtime()
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_RECONNECT -> {
                AppLog.i(AppLog.TAG_DISCORD, "Discord pediu reconexão")
                dropSocket("reconnect solicitado")
            }
            OP_INVALID_SESSION -> {
                AppLog.w(AppLog.TAG_DISCORD, "Sessão do Discord inválida; identificando de novo")
                ready = false
                sequence = null
                sessionId = null
                resumeUrl = null
                scope.launch {
                    delay(150)
                    if (!manualStop && currentToken == token) {
                        sendIdentify(currentToken)
                        armReadyTimeout(currentToken)
                    }
                }
            }
            OP_DISPATCH -> when ((root["t"] as? JsonPrimitive)?.contentOrNull) {
                "READY" -> handleReady(root, currentToken)
                "RESUMED" -> handleResumed()
            }
        }
    }

    /**
     * O gateway pode aceitar o HELLO e manter o socket aberto sem nunca emitir
     * READY (identify rejeitado por política, shard inválido etc.). Sem este
     * prazo a tela ficaria em "Conectando" indefinidamente.
     */
    private fun armReadyTimeout(currentToken: String) {
        readyTimeoutJob?.cancel()
        readyTimeoutJob = scope.launch {
            delay(READY_TIMEOUT_MS)
            if (!ready && currentToken == token && socket != null && !manualStop) {
                AppLog.w(
                    AppLog.TAG_DISCORD,
                    "Discord não ficou pronto em ${READY_TIMEOUT_MS / 1000}s; descartando socket",
                )
                _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
                socket?.cancel()
                socket = null
                scheduleReconnect()
            }
        }
    }

    private fun handleReady(root: JsonObject, currentToken: String) {
        if (currentToken != token) return
        val data = root["d"] as? JsonObject ?: return
        sessionId = (data["session_id"] as? JsonPrimitive)?.contentOrNull
        resumeUrl = (data["resume_gateway_url"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                if (raw.contains("?")) raw else raw.trimEnd('/') + "/?v=9&encoding=json"
            }
        val user = data["user"] as? JsonObject
        val name = (user?.get("global_name") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: (user?.get("username") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        if (!name.isNullOrBlank()) session.discordName = name
        ready = true
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        reconnectAttempt = 0
        awaitAckReset()
        _state.value = DiscordState(DiscordConnection.CONNECTED, name ?: session.discordName)
        AppLog.i(
            AppLog.TAG_DISCORD,
            "Discord pronto como ${name ?: session.discordName ?: "usuário"}",
        )
        // Sempre reconstrói a partir do estado atual do player. Reenviar um
        // `lastPresence` antigo poderia ressuscitar uma música que já foi
        // pausada ou trocada enquanto o socket estava caído.
        replayLatest()
    }

    private fun handleResumed() {
        ready = true
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        reconnectAttempt = 0
        awaitAckReset()
        _state.value = DiscordState(DiscordConnection.CONNECTED, session.discordName)
        AppLog.i(AppLog.TAG_DISCORD, "Sessão do Discord retomada")
        replayLatest()
    }

    private fun replayLatest() {
        val current = latestPlayerState
        val track = current?.track
        if (current?.isPlaying == true && track != null) {
            val start = System.currentTimeMillis() - current.positionMs
            presenceActive = true
            presenceTrackId = track.videoId
            presenceStart = start
            presenceDurationMs = current.durationMs
            lastPlaying = true
            publishPresence(track, start, current.durationMs)
        } else {
            // Pausado, parado ou sem faixa: o card não deve voltar no reconnect.
            presenceActive = false
            presenceTrackId = null
            presenceDurationMs = 0L
            lastPlaying = false
            clearPresence()
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatInterval = intervalMs.coerceAtLeast(1_000L)
        awaitAckReset()
        heartbeatJob = scope.launch {
            delay(heartbeatInterval)
            while (isActive) {
                if (awaitingAck &&
                    SystemClock.elapsedRealtime() - lastAckAt >
                    heartbeatInterval + HEARTBEAT_GRACE_MS
                ) {
                    AppLog.w(AppLog.TAG_DISCORD, "Heartbeat do Discord sem ACK; reconectando")
                    dropSocket("heartbeat sem ACK")
                    return@launch
                }
                sendHeartbeat()
                awaitingAck = true
                delay(heartbeatInterval)
            }
        }
    }

    private fun awaitAckReset() {
        awaitingAck = false
        lastAckAt = SystemClock.elapsedRealtime()
    }

    private fun sendHeartbeat() {
        val payload = buildJsonObject {
            put("op", OP_HEARTBEAT)
            put("d", sequence?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        send(payload)
    }

    private fun sendIdentify(currentToken: String) {
        if (manualStop || currentToken != token) return
        val payload = buildJsonObject {
            put("op", OP_IDENTIFY)
            put(
                "d",
                buildJsonObject {
                    put("token", currentToken)
                    put("capabilities", 16381)
                    put("compress", false)
                    put("largeThreshold", 100)
                    put(
                        "properties",
                        buildJsonObject {
                            put("os", "Android")
                            put("browser", "Discord Android")
                            put("device", Build.DEVICE.ifBlank { "Android" })
                        },
                    )
                    put(
                        "client_state",
                        buildJsonObject {
                            put("guild_versions", buildJsonObject { })
                            put("highest_last_message_id", "0")
                            put("read_state_version", 0)
                            put("user_guild_settings_version", -1)
                            put("user_settings_version", -1)
                            put("private_channels_version", "0")
                            put("api_code_version", 0)
                        },
                    )
                    put(
                        "shard",
                        buildJsonArray {
                            add(JsonPrimitive(0))
                            add(JsonPrimitive(1))
                        },
                    )
                    put("presence", presencePayload(null))
                },
            )
        }
        send(payload)
    }

    private fun sendResume(currentToken: String) {
        if (manualStop || currentToken != token) return
        val payload = buildJsonObject {
            put("op", OP_RESUME)
            put(
                "d",
                buildJsonObject {
                    put("token", currentToken)
                    put("session_id", sessionId)
                    put("seq", sequence ?: 0)
                },
            )
        }
        send(payload)
    }

    private fun dropSocket(reason: String) {
        AppLog.w(AppLog.TAG_DISCORD, "Descartando socket do Discord: $reason")
        ready = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        readyTimeoutJob?.cancel()
        readyTimeoutJob = null
        socket?.cancel()
        socket = null
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (manualStop || token == null) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            AppLog.w(
                AppLog.TAG_DISCORD,
                "Limite de reconexões do Discord atingido; aguardando ação do usuário",
            )
            _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
            return
        }
        val wait = min(60_000L, 1_000L * (1L shl reconnectAttempt.coerceAtMost(5)))
        reconnectAttempt++
        AppLog.i(AppLog.TAG_DISCORD, "Reconectando Discord em ${wait}ms")
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            val current = token ?: return@launch
            if (!manualStop) openSocket(current)
        }
    }

    private fun publishPresence(track: Track, startMs: Long, durationMs: Long) {
        if (!ready) return
        val currentToken = token ?: return
        presenceJob?.cancel()
        presenceJob = scope.launch {
            val artwork = withContext(Dispatchers.IO) {
                try {
                    resolveArtwork(track, currentToken)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_DISCORD, "Falha registrando capa no Discord", t)
                    null
                }
            }
            if (!isActive || !ready || manualStop || currentToken != token) return@launch
            val latest = latestPlayerState
            if (latest?.isPlaying != true || latest.track?.videoId != track.videoId) return@launch
            AppLog.d(
                AppLog.TAG_DISCORD,
                "Presença do Discord atualizada: ${track.title} | capa=${artwork != null}",
            )
            sendPresence(presencePayload(buildActivity(track, artwork, startMs, durationMs)))
        }
    }

    /**
     * Registra a capa no media proxy do Discord. O gateway não resolve uma
     * URL HTTPS crua em `large_image`: ele deixa o espaço em branco. É preciso
     * trocar a URL por um caminho `mp:` emitido por este endpoint.
     */
    private fun resolveArtwork(track: Track, currentToken: String): String? {
        val url = discordArtwork(track) ?: return null
        artworkCache.get(url)?.let { return it }
        val applicationId = ensureApplicationId() ?: return null
        val body = buildJsonObject {
            put("urls", buildJsonArray { add(JsonPrimitive(url)) })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("https://discord.com/api/v9/applications/$applicationId/external-assets")
            .header("Authorization", currentToken)
            .header("User-Agent", DiscordSuperProperties.userAgent)
            .header("X-Super-Properties", DiscordSuperProperties.base64)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()
        restClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                AppLog.w(
                    AppLog.TAG_DISCORD,
                    "Discord recusou o aplicativo de capas: HTTP ${response.code} ${text.take(120)}",
                )
                rejectedAppIds += applicationId
                appId = null
                artworkCache.evictAll()
                return null
            }
            if (!response.isSuccessful) {
                AppLog.w(
                    AppLog.TAG_DISCORD,
                    "Falha registrando capa no Discord: HTTP ${response.code} ${text.take(120)}",
                )
                return null
            }
            val list = json.parseToJsonElement(text) as? JsonArray
            val path = ((list?.firstOrNull() as? JsonObject)
                ?.get("external_asset_path") as? JsonPrimitive)
                ?.contentOrNull
            if (path.isNullOrBlank()) {
                AppLog.w(AppLog.TAG_DISCORD, "Discord não devolveu caminho para a capa")
                return null
            }
            val asset = "mp:$path"
            artworkCache.put(url, asset)
            AppLog.d(AppLog.TAG_DISCORD, "Capa registrada no media proxy do Discord")
            return asset
        }
    }

    /**
     * App ID usado para registrar as capas. Se não vier de
     * `local.properties`/Secrets, usa o aplicativo público do BitChord apenas
     * como namespace do media proxy. O nome do card continua sendo o `name` da
     * atividade, então o Discord mostra “Ouvindo MystMusic”.
     */
    private fun ensureApplicationId(): String? {
        appId?.let { return it }
        val configured = cleanAppId(BuildConfig.DISCORD_APP_ID)
        val candidate = listOfNotNull(configured, FALLBACK_APP_ID)
            .firstOrNull { it !in rejectedAppIds }
            ?: return null
        appId = candidate
        if (candidate == FALLBACK_APP_ID) {
            AppLog.i(
                AppLog.TAG_DISCORD,
                "Usando aplicativo público de capas (BitChord) no media proxy do Discord",
            )
        }
        return candidate
    }

    private fun buildActivity(
        track: Track,
        artwork: String?,
        startMs: Long,
        durationMs: Long,
    ): JsonObject = buildJsonObject {
        // O card mostra “Ouvindo MystMusic”, música, artista, capa e a barra de
        // progresso. Sem status_display_type nem large_text (linhas extras).
        put("name", "MystMusic")
        put("type", 2)
        put("details", track.title.ifBlank { "MystMusic" }.take(128))
        put("state", track.artist.ifBlank { "MystMusic" }.take(128))
        if (artwork != null) {
            put(
                "assets",
                buildJsonObject {
                    put("large_image", artwork)
                },
            )
        }
        put(
            "timestamps",
            buildJsonObject {
                put("start", startMs)
                if (durationMs > 0) put("end", startMs + durationMs)
            },
        )
    }

    /**
     * O cartão do Discord é pequeno e o media proxy não tenta outra resolução
     * quando a URL responde 404. `maxresdefault`/`hq720` não existem para todos
     * os vídeos do YouTube; `mqdefault` existe sempre, é 16:9 (sem as tarjas
     * do `hqdefault`) e cobre com folga os ~96dp desenhados pelo Discord.
     * Capas quadradas do YouTube Music (`googleusercontent`) são mantidas.
     */
    private fun discordArtwork(track: Track): String? {
        val url = track.artworkUrl()?.takeIf { it.startsWith("https://") } ?: return null
        if (!url.contains("i.ytimg.com/vi/")) return url
        return when {
            url.contains("maxresdefault") || url.contains("hq720") ||
                url.contains("hqdefault") || url.contains("sddefault") ->
                url.replace("maxresdefault", "mqdefault")
                    .replace("hq720", "mqdefault")
                    .replace("hqdefault", "mqdefault")
                    .replace("sddefault", "mqdefault")

            else -> url
        }
    }

    private fun presencePayload(activity: JsonObject?): JsonObject = buildJsonObject {
        put("since", 0)
        put("status", "online")
        put("afk", false)
        put(
            "activities",
            if (activity == null) JsonArray(emptyList()) else JsonArray(listOf(activity)),
        )
    }

    private fun sendPresence(presence: JsonObject) {
        if (!ready) return
        send(
            buildJsonObject {
                put("op", OP_PRESENCE_UPDATE)
                put("d", presence)
            },
        )
    }

    private fun clearPresence() {
        presenceJob?.cancel()
        presenceJob = null
        val presence = presencePayload(null)
        if (ready) {
            AppLog.i(AppLog.TAG_DISCORD, "Presença do Discord removida (pausa/parada)")
            sendPresence(presence)
        }
    }

    private fun send(payload: JsonObject): Boolean {
        val current = socket ?: return false
        return runCatching { current.send(payload.toString()) }
            .onFailure { AppLog.failure(AppLog.TAG_DISCORD, "Falha enviando ao Discord", it) }
            .getOrDefault(false)
    }

    private class InvalidTokenException : Exception()

    companion object {
        /**
         * Remove aspas/JSON que o WebView pode ter incluído no token. O
         * `localStorage.token` do Discord é uma string JSON (com aspas); se
         * elas forem enviadas no header Authorization, o Discord responde 401.
         * Aceita mais de uma camada para tokens antigos duplamente codificados.
         */
        private fun cleanToken(raw: String?): String? {
            var value = raw?.trim().orEmpty()
            while (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1).trim()
            }
            return value.takeIf { it.isNotBlank() }
        }

        private fun cleanAppId(raw: String?): String? =
            raw?.trim()?.takeIf { value -> value.isNotEmpty() && value.all { it.isDigit() } }

        /**
         * Aplicativo público do BitChord, usado somente como namespace do media
         * proxy do Discord. Como o `application_id` não vai na atividade, o card
         * continua “Ouvindo MystMusic”; um `DISCORD_APP_ID` próprio tem
         * prioridade quando configurado.
         */
        private const val FALLBACK_APP_ID = "1543679166967193620"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE_UPDATE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private const val HEARTBEAT_GRACE_MS = 15_000L
        private const val HELLO_TIMEOUT_MS = 15_000L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private val AUTH_CLOSE_CODES = setOf(4003, 4004, 4010, 4011, 4012, 4013, 4014, 401, 403)
    }
}

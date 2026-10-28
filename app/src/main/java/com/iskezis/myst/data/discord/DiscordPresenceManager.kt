package com.iskezis.myst.data.discord

import android.content.Context
import android.os.SystemClock
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.player.PlayerState
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

enum class DiscordConnection { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class DiscordState(
    val connection: DiscordConnection = DiscordConnection.DISCONNECTED,
    val userName: String? = null,
)

/**
 * Rich Presence via Gateway do Discord. Usa token de usuário capturado pelo
 * login WebView: é uma integração não oficial e opt-in, com aviso explícito.
 * Qualquer falha aqui é isolada e nunca interfere no player.
 */
class DiscordPresenceManager(
    context: Context,
    private val session: SessionManager,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        DiscordState(
            connection = DiscordConnection.DISCONNECTED,
            userName = session.discordName,
        ),
    )
    val state: StateFlow<DiscordState> = _state.asStateFlow()

    private var token: String? = session.discordToken?.takeIf { it.isNotBlank() }
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var pauseClearJob: Job? = null
    private var manualStop = token == null
    private var ready = false
    private var sequence: Int? = null
    private var reconnectAttempt = 0

    private var latestPlayerState: PlayerState? = null
    private var presenceActive = false
    private var presenceTrackId: String? = null
    private var presenceStart = 0L
    private var lastPlaying = false
    private var lastTrackId: String? = null

    init {
        if (token != null) {
            openSocket()
        }
    }

    fun connect(newToken: String) {
        val clean = newToken.trim()
        if (clean.isBlank()) {
            _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
            return
        }
        token = clean
        manualStop = false
        reconnectAttempt = 0
        session.discordToken = clean
        openSocket()
    }

    fun disconnect() {
        manualStop = true
        token = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        pauseClearJob?.cancel()
        pauseClearJob = null
        ready = false
        sequence = null
        presenceActive = false
        presenceTrackId = null
        lastTrackId = null
        lastPlaying = false
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
        manualStop = false
        reconnectAttempt = 0
        openSocket()
    }

    fun onPlayerState(state: PlayerState) {
        latestPlayerState = state
        val track = state.track
        if (track == null) {
            pauseClearJob?.cancel()
            pauseClearJob = null
            lastTrackId = null
            lastPlaying = false
            if (presenceActive) {
                presenceActive = false
                presenceTrackId = null
                sendPresence(null)
            }
            return
        }

        if (!state.isPlaying) {
            lastPlaying = false
            lastTrackId = track.videoId
            if (pauseClearJob == null) {
                pauseClearJob = scope.launch {
                    delay(PAUSE_CLEAR_MS)
                    pauseClearJob = null
                    if (latestPlayerState?.isPlaying == false && presenceActive) {
                        presenceActive = false
                        presenceTrackId = null
                        sendPresence(null)
                    }
                }
            }
            return
        }

        pauseClearJob?.cancel()
        pauseClearJob = null
        val resumed = !lastPlaying
        lastPlaying = true

        val expectedStart = System.currentTimeMillis() - state.positionMs
        val trackChanged = presenceTrackId != track.videoId
        val wasInactive = !presenceActive
        val jumped = !trackChanged && !wasInactive && abs(expectedStart - presenceStart) > 3_000L
        if (!trackChanged && !wasInactive && !jumped && !resumed) {
            return
        }

        lastTrackId = track.videoId
        presenceTrackId = track.videoId
        presenceStart = expectedStart
        presenceActive = true
        if (ready) {
            sendPresence(buildActivity(track, expectedStart))
        }
    }

    private fun openSocket() {
        val currentToken = token ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        ready = false
        sequence = null
        _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
        try {
            val previous = socket
            socket = null
            previous?.cancel()
            val request = Request.Builder().url(GATEWAY_URL).build()
            val newSocket = client.newWebSocket(request, Listener(currentToken))
            socket = newSocket
        } catch (t: Throwable) {
            _state.value = DiscordState(DiscordConnection.ERROR, session.discordName)
            scheduleReconnect()
        }
    }

    private inner class Listener(private val currentToken: String) : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            socket = null
            ready = false
            heartbeatJob?.cancel()
            heartbeatJob = null
            if (manualStop) {
                _state.value = DiscordState(DiscordConnection.DISCONNECTED, session.discordName)
                return
            }
            if (code == 4004 || code == 4003 || code == 4010 || code == 4011) {
                token = null
                manualStop = true
                session.clearDiscord()
                _state.value = DiscordState(DiscordConnection.ERROR, null)
                return
            }
            _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            socket = null
            ready = false
            heartbeatJob?.cancel()
            heartbeatJob = null
            if (manualStop || currentToken != token) return
            _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
            scheduleReconnect()
        }

        private fun handleMessage(text: String) {
            val root = runCatching {
                json.parseToJsonElement(text) as? JsonObject
            }.getOrNull() ?: return
            (root["s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { sequence = it }
            val op = (root["op"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            when (op) {
                10 -> {
                    val interval = ((root["d"] as? JsonObject)
                        ?.get("heartbeat_interval") as? JsonPrimitive)
                        ?.contentOrNull?.toLongOrNull() ?: 41_250L
                    startHeartbeat(interval)
                    identify(currentToken)
                }
                0 -> {
                    val type = (root["t"] as? JsonPrimitive)?.contentOrNull
                    if (type == "READY") onReady(root)
                }
                7 -> {
                    _state.value = DiscordState(DiscordConnection.CONNECTING, session.discordName)
                    socket?.cancel()
                    scheduleReconnect()
                }
                9 -> {
                    token = null
                    manualStop = true
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    socket?.cancel()
                    socket = null
                    session.clearDiscord()
                    _state.value = DiscordState(DiscordConnection.ERROR, null)
                }
            }
        }
    }

    private fun identify(currentToken: String) {
        val payload = buildJsonObject {
            put("op", 2)
            put(
                "d",
                buildJsonObject {
                    put("token", currentToken)
                    put("capabilities", 0)
                    put("compress", false)
                    put(
                        "properties",
                        buildJsonObject {
                            put("\$os", "Android")
                            put("\$browser", "MystMusic")
                            put("\$device", "MystMusic")
                        },
                    )
                    put(
                        "presence",
                        buildJsonObject {
                            put("status", "online")
                            put("since", 0)
                            put("afk", false)
                            put("activities", JsonArray(emptyList()))
                        },
                    )
                },
            )
        }
        send(payload)
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(intervalMs.coerceAtLeast(1_000L))
            while (isActive) {
                val payload = buildJsonObject {
                    put("op", 1)
                    put("d", sequence)
                }
                send(payload)
                delay(intervalMs.coerceAtLeast(1_000L))
            }
        }
    }

    private fun onReady(root: JsonObject) {
        ready = true
        reconnectAttempt = 0
        val user = (root["d"] as? JsonObject)?.get("user") as? JsonObject
        val name = (user?.get("global_name") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: (user?.get("username") as? JsonPrimitive)?.contentOrNull
        if (!name.isNullOrBlank()) {
            session.discordName = name
        }
        _state.value = DiscordState(DiscordConnection.CONNECTED, name ?: session.discordName)
        val latest = latestPlayerState
        if (latest?.isPlaying == true) {
            val track = latest.track
            if (track != null) {
                presenceActive = true
                presenceTrackId = track.videoId
                presenceStart = System.currentTimeMillis() - latest.positionMs
                lastTrackId = track.videoId
                lastPlaying = true
                sendPresence(buildActivity(track, presenceStart))
            }
        }
    }

    private fun buildActivity(track: Track, startMs: Long): JsonObject = buildJsonObject {
        put("type", 2)
        put("name", "MystMusic")
        put("details", track.title.ifBlank { "MystMusic" }.take(128))
        put("state", track.artist.ifBlank { "MystMusic" }.take(128))
        put(
            "timestamps",
            buildJsonObject {
                put("start", startMs)
            },
        )
        val thumb = track.artworkUrl()
        if (!thumb.isNullOrBlank()) {
            put(
                "assets",
                buildJsonObject {
                    put("large_image", thumb)
                    put("large_text", "MystMusic")
                },
            )
        }
    }

    private fun sendPresence(activity: JsonObject?) {
        if (!ready) return
        val payload = buildJsonObject {
            put("op", 3)
            put(
                "d",
                buildJsonObject {
                    put("since", 0)
                    put("status", "online")
                    put("afk", false)
                    put(
                        "activities",
                        if (activity == null) JsonArray(emptyList()) else JsonArray(listOf(activity)),
                    )
                },
            )
        }
        send(payload)
    }

    private fun send(payload: JsonObject): Boolean {
        return runCatching { socket?.send(payload.toString()) == true }.getOrDefault(false)
    }

    private fun scheduleReconnect() {
        if (manualStop || token == null) return
        if (reconnectJob?.isActive == true) return
        val wait = min(60_000L, 5_000L * (1L shl reconnectAttempt.coerceAtMost(4)))
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            if (!manualStop && token != null) openSocket()
        }
    }

    companion object {
        private const val GATEWAY_URL =
            "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val PAUSE_CLEAR_MS = 2 * 60 * 1000L
    }
}

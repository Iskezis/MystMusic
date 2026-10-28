package com.iskezis.myst.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
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

data class PlayerState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val track: Track? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: Boolean = false,
    val errorMessage: String? = null,
    val loginRequired: Boolean = false,
)

class PlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingPlay: PendingPlay? = null
    private var ticker: Job? = null
    private var lastLoggedVideoId: String? = null
    private var lastError: PlaybackException? = null
    private var loginRequired = false

    private data class PendingPlay(
        val tracks: List<Track>,
        val startIndex: Int,
        val shuffle: Boolean,
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastError = null
            loginRequired = false
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            lastError = error
            if (error != null) {
                AppLog.failure(
                    AppLog.TAG_PLAYER,
                    "Erro do ExoPlayer (${error.errorCodeName})",
                    error,
                )
            }
            loginRequired = error != null && detectsLoginRequired()
            _state.value = _state.value.copy(
                error = error != null || loginRequired,
                errorMessage = when {
                    loginRequired -> appContext.getString(R.string.player_error_login_required)
                    error != null -> messageFor(error)
                    else -> null
                },
                loginRequired = loginRequired,
            )
        }
    }

    init {
        AppLog.i(AppLog.TAG_PLAYER, "PlayerController criado; conectando ao PlaybackService")
        connect()
    }

    /**
     * Conecta (ou tenta reconectar) ao [PlaybackService]. O MediaController
     * precisa ser recriado quando a primeira conexão falha, caso contrário o
     * botão "Tentar novamente" nunca voltaria a funcionar.
     */
    @Synchronized
    private fun connect() {
        if (controller != null) return
        if (controllerFuture != null) {
            AppLog.d(AppLog.TAG_PLAYER, "Conexão com o serviço já está em andamento")
            return
        }
        AppLog.i(AppLog.TAG_PLAYER, "Conectando MediaController ao PlaybackService")
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                AppLog.w(AppLog.TAG_PLAYER, "MediaController desconectado do serviço")
                if (this@PlayerController.controller === controller) {
                    this@PlayerController.controller = null
                }
                _state.value = _state.value.copy(connected = false)
            }
        }).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controllerFuture = null
                runCatching {
                    val mediaController = future.get()
                    AppLog.i(AppLog.TAG_PLAYER, "MediaController conectado")
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                    pendingPlay?.let { pending ->
                        pendingPlay = null
                        AppLog.i(
                            AppLog.TAG_PLAYER,
                            "Executando reprodução pendente: ${pending.tracks.size} faixas, " +
                                "índice ${pending.startIndex}",
                        )
                        playInternal(
                            mediaController,
                            pending.tracks,
                            pending.startIndex,
                            pending.shuffle,
                        )
                    }
                    startTicker()
                }.onFailure { t ->
                    AppLog.failure(AppLog.TAG_PLAYER, "Falha ao conectar MediaController", t)
                    controller = null
                    _state.value = _state.value.copy(
                        error = true,
                        errorMessage = appContext.getString(R.string.player_connection_error),
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) {
            AppLog.w(AppLog.TAG_PLAYER, "play() chamado com lista vazia")
            return
        }
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        AppLog.i(
            AppLog.TAG_PLAYER,
            "play(): ${tracks.size} faixas, índice=$safeIndex, shuffle=$shuffle, " +
                "primeira=${tracks.getOrNull(safeIndex)?.title}",
        )
        val current = controller
        if (current == null) {
            pendingPlay = PendingPlay(tracks, safeIndex, shuffle)
            AppLog.w(
                AppLog.TAG_PLAYER,
                "Controller ainda não conectado; reprodução ficará pendente",
            )
            connect()
        } else {
            playInternal(current, tracks, safeIndex, shuffle)
        }
    }

    fun toggle() {
        val current = controller ?: run {
            AppLog.w(AppLog.TAG_PLAYER, "toggle() sem controller")
            connect()
            return
        }
        val playing = current.isPlaying
        AppLog.d(AppLog.TAG_PLAYER, "toggle(): ${if (playing) "pausar" else "tocar"}")
        if (playing) current.pause() else current.play()
    }

    fun next() {
        AppLog.d(AppLog.TAG_PLAYER, "next()")
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val current = controller ?: return
        if (current.currentPosition > 4_000L) {
            AppLog.d(AppLog.TAG_PLAYER, "previous(): reiniciando faixa")
            current.seekTo(0)
        } else {
            AppLog.d(AppLog.TAG_PLAYER, "previous(): faixa anterior")
            current.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        AppLog.d(AppLog.TAG_PLAYER, "seekTo(${AppLog.durationForLog(positionMs)})")
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekToIndex(index: Int) {
        val current = controller ?: return
        if (index in 0 until current.mediaItemCount) {
            AppLog.d(AppLog.TAG_PLAYER, "seekToIndex($index)")
            current.seekTo(index, 0L)
            current.play()
        }
    }

    fun toggleShuffle() {
        val current = controller ?: return
        current.shuffleModeEnabled = !current.shuffleModeEnabled
        AppLog.d(AppLog.TAG_PLAYER, "shuffle=${current.shuffleModeEnabled}")
    }

    fun cycleRepeat() {
        val current = controller ?: return
        current.repeatMode = when (current.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        AppLog.d(AppLog.TAG_PLAYER, "repeatMode=${current.repeatMode}")
    }

    fun playNext(track: Track) {
        val current = controller ?: run {
            play(listOf(track), 0)
            return
        }
        if (current.mediaItemCount == 0) {
            current.setMediaItem(track.toMediaItem())
            current.prepare()
            current.play()
            return
        }
        val index = (current.currentMediaItemIndex + 1).coerceIn(0, current.mediaItemCount)
        current.addMediaItem(index, track.toMediaItem())
        AppLog.d(AppLog.TAG_PLAYER, "Tocar em seguida: ${track.title} na posição $index")
    }

    fun queue(): List<Track> {
        val current = controller ?: return emptyList()
        return (0 until current.mediaItemCount).mapNotNull { index ->
            current.getMediaItemAt(index).toTrack()
        }
    }

    fun retry() {
        val current = controller
        lastError = null
        loginRequired = false
        (appContext as? MystApp)?.lastStreamFailure = null
        if (current == null) {
            AppLog.w(AppLog.TAG_PLAYER, "retry() sem controller; tentando reconectar")
            _state.value = _state.value.copy(error = false, errorMessage = null, loginRequired = false)
            connect()
            return
        }
        AppLog.i(AppLog.TAG_PLAYER, "retry(): prepare + play")
        _state.value = _state.value.copy(error = false, errorMessage = null, loginRequired = false)
        current.prepare()
        current.play()
    }

    fun stop() {
        val current = controller ?: return
        AppLog.i(AppLog.TAG_PLAYER, "stop()")
        current.stop()
        current.clearMediaItems()
        _state.value = PlayerState(connected = true)
    }

    private fun playInternal(
        mediaController: MediaController,
        tracks: List<Track>,
        startIndex: Int,
        shuffle: Boolean,
    ) {
        try {
            lastError = null
            loginRequired = false
            (appContext as? MystApp)?.lastStreamFailure = null
            _state.value = _state.value.copy(error = false, errorMessage = null, loginRequired = false)
            val items = tracks.map { it.toMediaItem() }
            AppLog.i(
                AppLog.TAG_PLAYER,
                "Enviando ${items.size} itens ao MediaController; prepare + play",
            )
            mediaController.setMediaItems(items, startIndex, 0L)
            mediaController.shuffleModeEnabled = shuffle
            mediaController.prepare()
            mediaController.play()
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha enviando mídia ao MediaController", t)
            if (controller === mediaController) controller = null
            pendingPlay = PendingPlay(tracks, startIndex, shuffle)
            _state.value = _state.value.copy(
                error = true,
                errorMessage = appContext.getString(R.string.player_connection_error),
            )
            connect()
        }
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { publish(it) }
                delay(500)
            }
        }
    }

    private fun publish(player: Player) {
        try {
            val item = player.currentMediaItem
            val track = item?.toTrack()
            val duration = player.duration.takeIf { it > 0 && it != Long.MIN_VALUE } ?: track?.durationMs ?: 0L
            val playbackError = player.playerError ?: lastError
            _state.value = PlayerState(
                connected = true,
                isPlaying = player.isPlaying,
                track = track,
                positionMs = if (track == null) 0L else player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                error = (playbackError != null || loginRequired) && track != null,
                errorMessage = when {
                    loginRequired -> appContext.getString(R.string.player_error_login_required)
                    playbackError != null -> messageFor(playbackError)
                    else -> null
                },
                loginRequired = loginRequired,
            )
            if (track?.videoId != lastLoggedVideoId) {
                lastLoggedVideoId = track?.videoId
                AppLog.i(
                    AppLog.TAG_PLAYER,
                    "Publicando faixa: ${track?.title ?: "<nenhuma>"} " +
                        "| estado=${player.playbackState} | erro=${playbackError?.errorCodeName ?: "nenhum"}",
                )
            }
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha lendo estado do MediaController", t)
            if (controller === player) controller = null
            _state.value = _state.value.copy(connected = false)
        }
    }

    private fun detectsLoginRequired(): Boolean {
        val failure = (appContext as? MystApp)?.lastStreamFailure ?: return false
        val videoId = controller?.currentMediaItem?.mediaId ?: return false
        val recent = System.currentTimeMillis() - failure.at < 30_000L
        return recent && failure.videoId == videoId && failure.loginRequired
    }

    private fun messageFor(error: PlaybackException): String = appContext.getString(
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> R.string.player_error_network

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> R.string.player_error_http

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> R.string.player_error_invalid_audio

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> R.string.player_error_decode

            PlaybackException.ERROR_CODE_TIMEOUT -> R.string.player_error_timeout
            else -> R.string.player_error_stream
        },
    )

    private fun Track.toMediaItem(): MediaItem {
        val extras = Bundle().apply { putLong(PlaybackService.EXTRA_DURATION, durationMs) }
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifBlank { "MystMusic" })
            .setArtist(artist)
            .setArtworkUri(artworkUrl()?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(Uri.parse("${StreamResolver.SCHEME}://track/$videoId"))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun MediaItem.toTrack(): Track {
        val extras = mediaMetadata.extras
        return Track(
            videoId = mediaId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = mediaMetadata.artworkUri?.toString(),
            durationMs = extras?.getLong(PlaybackService.EXTRA_DURATION, 0L) ?: 0L,
        )
    }
}

package com.iskezis.myst.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var resolver: StreamResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var historyJob: Job? = null

    private var currentTrack: Track? = null
    private var currentPosition = 0L
    private var recordedCurrent = false
    private var retriedCurrent = false

    override fun onCreate() {
        super.onCreate()
        AppLog.i(AppLog.TAG_PLAYER, "PlaybackService.onCreate")
        val app = application as MystApp
        resolver = StreamResolver(app)
        // O mesmo stack HTTP (OkHttp) usado para validar a URL no InnerTube também
        // baixa o áudio. Isso evita diferenças de TLS/proxy/IPv6 do
        // HttpURLConnection e garante que os headers por cliente cheguem ao
        // googlevideo exatamente como na validação. Não fixar User-Agent: ele
        // vem no DataSpec de cada faixa.
        val playbackHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient)
            .setContentTypePredicate { contentType ->
                contentType == null || !contentType.startsWith("text/", ignoreCase = true)
            }
        // O padrão do Media3 exige 2,5 s de áudio em buffer antes de começar a
        // tocar. Com uma URL já validada e uma faixa progressiva, 500 ms bastam
        // para evitar underrun e o som começa muito mais rápido.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                60_000,
                500,
                1_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    ResolvingDataSource.Factory(
                        DefaultDataSource.Factory(this, httpDataSourceFactory),
                        resolver,
                    ),
                ),
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                AppLog.i(
                    AppLog.TAG_PLAYER,
                    "Transição de mídia: ${mediaItem?.mediaId ?: "<fim>"} reason=$reason",
                )
                handleTransition(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                AppLog.d(
                    AppLog.TAG_PLAYER,
                    "Estado de reprodução: ${stateName(playbackState)} " +
                        "posição=${AppLog.durationForLog(player.currentPosition)}",
                )
                if (playbackState == Player.STATE_ENDED) {
                    finishCurrent()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AppLog.i(AppLog.TAG_PLAYER, "isPlaying=$isPlaying")
            }

            override fun onPlayerError(error: PlaybackException) {
                val videoId = currentTrack?.videoId
                AppLog.failure(
                    AppLog.TAG_PLAYER,
                    "Erro do player em ${videoId ?: "<sem faixa>"} " +
                        "(${error.errorCodeName}); tentativa automática=${!retriedCurrent}",
                    error,
                )
                if (!retriedCurrent && videoId != null && isRetryable(error)) {
                    retriedCurrent = true
                    resolver.invalidate(videoId)
                    val position = player.currentPosition
                    AppLog.w(
                        AppLog.TAG_PLAYER,
                        "Tentando novamente $videoId a partir de " +
                            AppLog.durationForLog(position),
                    )
                    player.prepare()
                    player.seekTo(position)
                    player.play()
                }
            }
        })


        val sessionIntent = Intent(this, MainActivity::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, sessionIntent, flags)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        historyJob = scope.launch { trackHistory(app) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.d(
            AppLog.TAG_PLAYER,
            "onTaskRemoved playWhenReady=${player.playWhenReady} itens=${player.mediaItemCount}",
        )
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_PLAYER, "PlaybackService.onDestroy")
        historyJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private fun handleTransition(mediaItem: MediaItem?) {
        val app = application as MystApp
        val previous = currentTrack
        if (previous != null && !recordedCurrent) {
            if (currentPosition >= 10_000L) {
                record(app, previous, currentPosition, completed = false)
            } else if (currentPosition > 0 && previous.durationMs > 10_000L) {
                scope.launch(Dispatchers.IO) {
                    app.session.session?.let { session ->
                        app.recommendations.recordSkip(session.userId, previous)
                    }
                }
            }
        }
        currentTrack = mediaItem?.let { item ->
            Track(
                videoId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString(),
                durationMs = item.mediaMetadata.extras?.getLong(EXTRA_DURATION, 0L) ?: 0L,
            )
        }
        currentPosition = 0L
        recordedCurrent = false
        retriedCurrent = false
    }

    private fun finishCurrent() {
        val app = application as MystApp
        val track = currentTrack
        if (track != null && !recordedCurrent) {
            record(app, track, player.currentPosition, completed = true)
        }
    }

    private suspend fun trackHistory(app: MystApp) {
        while (scope.isActive) {
            delay(2_000)
            val position = player.currentPosition
            if (position > 0) currentPosition = position
            val track = currentTrack ?: continue
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: track.durationMs
            if (!recordedCurrent) {
                val threshold = minOf(20_000L, (duration * 0.3f).toLong().coerceAtLeast(5_000L))
                if (position >= threshold) {
                    record(app, track, position, completed = duration > 0 && position >= duration - 2_000)
                }
            }
        }
    }

    private fun record(app: MystApp, track: Track, position: Long, completed: Boolean) {
        recordedCurrent = true
        val userId = app.session.session?.userId ?: return
        AppLog.d(
            AppLog.TAG_PLAYER,
            "Registrando histórico: ${track.videoId} posição=${AppLog.durationForLog(position)} " +
                "completa=$completed",
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                app.recommendations.recordPlay(userId, track, position, completed)
            }.onFailure {
                AppLog.failure(AppLog.TAG_DB, "Falha ao registrar histórico", it)
            }
        }
    }

    private fun isRetryable(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> true
            else -> false
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "DESCONHECIDO($state)"
    }

    companion object {
        const val EXTRA_DURATION = "extra_duration"
    }
}

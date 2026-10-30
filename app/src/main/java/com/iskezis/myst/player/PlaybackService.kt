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
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    private var watchedReported = false
    private var retriedCurrent = false

    // Sessão de estatística: começa quando a faixa realmente entra em
    // reprodução e é gravada ao trocar, terminar, pausar por mais de 30 s ou
    // fechar o serviço. O tempo é medido por delta de posição, então pausas,
    // buffering e saltos (seek) não inflam o tempo ouvido.
    private var eventTrack: Track? = null
    private var eventUserId: Long? = null
    private var eventStartedAt = 0L
    private var eventPlayedMs = 0L
    private var eventLastPosition = 0L
    private var eventLastTickAt = 0L
    private var eventPausedAt = 0L
    private var eventActive = false
    /** Inserção disparada aos 10 s; o flush aguarda e atualiza a mesma linha. */
    private var eventRecordTask: Deferred<Long>? = null

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
        // Grava o último trecho antes de soltar o player. A escrita roda no
        // escopo do Application para terminar mesmo depois que este serviço
        // for destruído; o ticker local é encerrado em seguida.
        flushEvent(application as MystApp, completed = false)
        historyJob?.cancel()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private fun handleTransition(mediaItem: MediaItem?) {
        val app = application as MystApp
        val previous = currentTrack
        flushEvent(app, completed = false)
        if (previous != null && !recordedCurrent) {
            if (currentPosition >= MIN_PLAY_MS) {
                record(app, previous, currentPosition, completed = false)
            } else if (currentPosition > 0 && previous.durationMs > MIN_PLAY_MS) {
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
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            )
        }
        currentPosition = 0L
        recordedCurrent = false
        watchedReported = false
        retriedCurrent = false
        if (currentTrack == null) {
            eventTrack = null
            eventUserId = null
        } else {
            currentTrack?.let { track -> startEvent(app, track) }
        }
    }

    private fun finishCurrent() {
        val app = application as MystApp
        val track = currentTrack
        flushEvent(app, completed = true)
        if (track != null && !recordedCurrent && player.currentPosition >= MIN_PLAY_MS) {
            record(app, track, player.currentPosition, completed = true)
        }
    }

    private suspend fun trackHistory(app: MystApp) {
        while (scope.isActive) {
            delay(1_000)
            val position = player.currentPosition
            if (position > 0) currentPosition = position
            val track = currentTrack ?: continue
            updateEvent(app, position)
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: track.durationMs
            if (!recordedCurrent && position >= MIN_PLAY_MS) {
                record(
                    app,
                    track,
                    position,
                    completed = duration > 0 && position >= duration - 2_000,
                )
            }
        }
    }

    private fun startEvent(app: MystApp, track: Track) {
        eventTrack = track
        eventUserId = app.session.session?.userId?.takeIf { it > 0L }
        eventStartedAt = System.currentTimeMillis()
        eventPlayedMs = 0L
        eventLastPosition = player.currentPosition.coerceAtLeast(0L)
        eventLastTickAt = System.currentTimeMillis()
        eventPausedAt = 0L
        eventActive = eventUserId != null
        eventRecordTask = null
    }

    private fun updateEvent(app: MystApp, position: Long) {
        val track = eventTrack ?: return
        val userId = eventUserId ?: return
        val now = System.currentTimeMillis()
        val playing = player.isPlaying && player.playbackState == Player.STATE_READY
        if (playing) {
            if (!eventActive) {
                startEvent(app, track)
                return
            }
            val wallDelta = (now - eventLastTickAt).coerceIn(0L, MAX_TICK_MS)
            val positionDelta = position - eventLastPosition
            if (positionDelta in 1..(wallDelta + SEEK_TOLERANCE_MS)) {
                eventPlayedMs = (eventPlayedMs + positionDelta).coerceAtMost(MAX_EVENT_MS)
            }
            eventLastPosition = position
            eventLastTickAt = now
            eventPausedAt = 0L
            // Conta a reprodução assim que ela passa dos 10 s. A linha é
            // atualizada no flush com o tempo total, então pular a faixa
            // depois do limiar mantém exatamente o trecho escutado.
            if (eventPlayedMs >= MIN_EVENT_MS && eventRecordTask == null) {
                eventRecordTask = recordPartialEvent(app, track, userId)
            }
        } else {
            eventLastPosition = position
            eventLastTickAt = now
            if (eventActive) {
                if (eventPausedAt == 0L) {
                    eventPausedAt = now
                } else if (now - eventPausedAt >= PAUSE_FLUSH_MS) {
                    flushEvent(app, completed = false)
                }
            }
        }
    }

    private fun recordPartialEvent(app: MystApp, track: Track, userId: Long): Deferred<Long> {
        val startedAt = eventStartedAt
        val playedMs = eventPlayedMs
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: track.durationMs
        return app.backgroundScope.async {
            app.stats.record(
                userId = userId,
                track = track,
                startedAt = startedAt,
                playedMs = playedMs,
                durationMs = duration,
                completed = false,
                source = if (BuildConfig.FLAVOR == "tv") "tv" else "app",
            )
        }
    }

    private fun flushEvent(app: MystApp, completed: Boolean) {
        val track = eventTrack ?: return
        val userId = eventUserId ?: return
        val playedMs = eventPlayedMs
        val startedAt = eventStartedAt
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: track.durationMs
        val position = player.currentPosition.coerceAtLeast(0L)
        val completedNow = completed || (duration > 0L && position >= duration - 2_000L)
        val partial = eventRecordTask
        eventRecordTask = null
        eventPlayedMs = 0L
        eventStartedAt = 0L
        eventActive = false
        if (playedMs < MIN_EVENT_MS) return
        AppLog.d(
            AppLog.TAG_PLAYER,
            "Registrando estatística: ${track.videoId} ouvido=${AppLog.durationForLog(playedMs)} " +
                "completa=$completedNow",
        )
        app.backgroundScope.launch {
            // Se a linha dos 10 s ainda está sendo inserida, espera para
            // atualizar a mesma reprodução em vez de criar uma duplicata.
            val existingId = runCatching { partial?.await() }.getOrNull() ?: 0L
            runCatching {
                if (existingId > 0L) {
                    app.stats.updateProgress(
                        id = existingId,
                        playedMs = playedMs,
                        durationMs = duration,
                        completed = completedNow,
                    )
                } else {
                    app.stats.record(
                        userId = userId,
                        track = track,
                        startedAt = startedAt,
                        playedMs = playedMs,
                        durationMs = duration,
                        completed = completedNow,
                        source = if (BuildConfig.FLAVOR == "tv") "tv" else "app",
                    )
                }
            }.onFailure {
                AppLog.failure(AppLog.TAG_DB, "Falha ao registrar evento de reprodução", it)
            }
        }
    }

    private fun record(app: MystApp, track: Track, position: Long, completed: Boolean) {
        recordedCurrent = true
        // O mesmo limiar do histórico local marca a faixa no YouTube. Assim
        // os Recentes da conta do YouTube Music também recebem o que foi
        // ouvido no MystMusic, inclusive depois de reinstalar o app.
        reportWatched(app, track, position)
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

    private fun reportWatched(app: MystApp, track: Track, position: Long) {
        if (watchedReported || position < MIN_PLAY_MS) return
        watchedReported = true
        app.backgroundScope.launch {
            runCatching {
                app.youtube.markWatched(track.videoId, position)
            }.onFailure {
                AppLog.w(
                    AppLog.TAG_TUBE,
                    "Não foi possível marcar ${track.videoId} no YouTube: ${AppLog.describe(it)}",
                )
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

        /** Só entra nas estatísticas depois dos primeiros 10 s, mesmo parcial. */
        private const val MIN_PLAY_MS = 10_000L
        private const val MIN_EVENT_MS = MIN_PLAY_MS
        private const val PAUSE_FLUSH_MS = 30_000L
        private const val MAX_TICK_MS = 5_000L
        private const val SEEK_TOLERANCE_MS = 1_500L
        private const val MAX_EVENT_MS = 24L * 60L * 60L * 1000L
    }
}

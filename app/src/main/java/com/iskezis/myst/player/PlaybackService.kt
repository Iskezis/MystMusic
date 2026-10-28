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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.youtube.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        val app = application as MystApp
        resolver = StreamResolver(app)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    ResolvingDataSource.Factory(DefaultDataSource.Factory(this), resolver),
                ),
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                handleTransition(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    finishCurrent()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val videoId = currentTrack?.videoId
                if (!retriedCurrent && videoId != null && isRetryable(error)) {
                    retriedCurrent = true
                    resolver.invalidate(videoId)
                    val position = player.currentPosition
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
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
        scope.launch(Dispatchers.IO) {
            runCatching {
                app.recommendations.recordPlay(userId, track, position, completed)
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

    companion object {
        const val EXTRA_DURATION = "extra_duration"
    }
}

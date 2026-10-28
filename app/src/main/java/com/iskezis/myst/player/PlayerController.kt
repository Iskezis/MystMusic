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
import com.iskezis.myst.data.youtube.Track
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
)

class PlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val future = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()

    private var pendingPlay: PendingPlay? = null
    private var ticker: Job? = null

    private data class PendingPlay(
        val tracks: List<Track>,
        val startIndex: Int,
        val shuffle: Boolean,
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = true)
        }
    }

    init {
        future.addListener(
            {
                runCatching {
                    val mediaController = future.get()
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                    pendingPlay?.let { pending ->
                        pendingPlay = null
                        playInternal(mediaController, pending.tracks, pending.startIndex, pending.shuffle)
                    }
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val current = controller
        if (current == null) {
            pendingPlay = PendingPlay(tracks, safeIndex, shuffle)
        } else {
            playInternal(current, tracks, safeIndex, shuffle)
        }
    }

    fun toggle() {
        val current = controller ?: return
        if (current.isPlaying) current.pause() else current.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val current = controller ?: return
        if (current.currentPosition > 4_000L) {
            current.seekTo(0)
        } else {
            current.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekToIndex(index: Int) {
        val current = controller ?: return
        if (index in 0 until current.mediaItemCount) {
            current.seekTo(index, 0L)
            current.play()
        }
    }

    fun toggleShuffle() {
        val current = controller ?: return
        current.shuffleModeEnabled = !current.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val current = controller ?: return
        current.repeatMode = when (current.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
    }

    fun queue(): List<Track> {
        val current = controller ?: return emptyList()
        return (0 until current.mediaItemCount).mapNotNull { index ->
            current.getMediaItemAt(index).toTrack()
        }
    }

    fun retry() {
        val current = controller ?: return
        _state.value = _state.value.copy(error = false)
        current.prepare()
        current.play()
    }

    fun stop() {
        val current = controller ?: return
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
        _state.value = _state.value.copy(error = false)
        val items = tracks.map { it.toMediaItem() }
        mediaController.setMediaItems(items, startIndex, 0L)
        mediaController.shuffleModeEnabled = shuffle
        mediaController.prepare()
        mediaController.play()
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (scope.isActive) {
                controller?.let { publish(it) }
                delay(500)
            }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val track = item?.toTrack()
        val duration = player.duration.takeIf { it > 0 && it != Long.MIN_VALUE } ?: track?.durationMs ?: 0L
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
            error = _state.value.error && track != null,
        )
    }

    private fun Track.toMediaItem(): MediaItem {
        val extras = Bundle().apply { putLong(PlaybackService.EXTRA_DURATION, durationMs) }
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifBlank { "MystMusic" })
            .setArtist(artist)
            .setArtworkUri(thumbnailUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
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

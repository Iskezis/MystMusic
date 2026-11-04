package com.iskezis.myst.player

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.OptIn
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.iskezis.myst.R
import com.iskezis.myst.data.animatedcover.AnimatedCover
import com.iskezis.myst.data.animatedcover.AnimatedCoverMediaCache
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations

/**
 * Segundo player dedicado à arte em movimento. É sempre mudo, repete em loop
 * e usa no máximo 720p. A capa estática permanece embaixo: o vídeo só aparece
 * depois do primeiro frame, com crossfade; qualquer erro esconde o vídeo e
 * mantém a capa.
 */
@OptIn(UnstableApi::class)
class AnimatedCoverPlayer(
    context: Context,
    private val view: PlayerView,
    private val onError: (String?) -> Unit,
) {

    private val appContext = context.applicationContext
    private val trackSelector = DefaultTrackSelector(appContext).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSize(720, 720)
                .setMaxVideoBitrate(3_000_000)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build(),
        )
    }

    private val player = ExoPlayer.Builder(appContext)
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 6_000, 500, 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()

    private var albumKey: String? = null
    private var currentUrl: String? = null
    private var playRequested = false
    private var screenVisible = true
    private var firstFrameShown = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (released || firstFrameShown) return
            firstFrameShown = true
            player.playWhenReady = playRequested && screenVisible
            show()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            val failedKey = albumKey
            AppLog.failure(
                AppLog.TAG_PLAYER,
                "Capa animada falhou; mantendo a capa estática",
                error,
            )
            clear(animate = false)
            onError(failedKey)
        }
    }

    init {
        view.player = player
        view.useController = false
        view.alpha = 0f
        view.visibility = View.INVISIBLE
        view.isClickable = false
        view.isFocusable = false
        view.clipToOutline = true
        val radius = appContext.resources.getDimension(R.dimen.corner_xlarge)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(outlineView: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    outlineView.width.coerceAtLeast(1),
                    outlineView.height.coerceAtLeast(1),
                    radius,
                )
            }
        }
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(listener)
    }

    fun load(cover: AnimatedCover, albumKey: String, playWhenReady: Boolean) {
        if (released || cover.url.isBlank()) return
        this.albumKey = albumKey
        playRequested = playWhenReady
        if (currentUrl == cover.url && player.mediaItemCount > 0) {
            applyPlayState()
            return
        }

        clear(animate = false)
        currentUrl = cover.url
        this.albumKey = albumKey
        playRequested = playWhenReady
        firstFrameShown = false
        view.alpha = 0f
        view.visibility = View.VISIBLE
        player.setMediaSource(buildSource(cover))
        player.prepare()
        player.playWhenReady = screenVisible
    }

    fun setPlaying(playing: Boolean) {
        playRequested = playing
        if (currentUrl == null) return
        applyPlayState()
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (currentUrl == null) return
        if (!visible) {
            player.playWhenReady = false
        } else {
            applyPlayState()
        }
    }

    fun clear(animate: Boolean = true) {
        if (released) return
        val hadMedia = currentUrl != null
        currentUrl = null
        albumKey = null
        firstFrameShown = false
        playRequested = false
        player.stop()
        player.clearMediaItems()
        view.animate().cancel()
        if (!hadMedia) {
            view.visibility = View.INVISIBLE
            view.alpha = 0f
            return
        }
        if (animate && UiAnimations.enabled(view.context) && view.alpha > 0f) {
            view.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    if (!released) view.visibility = View.INVISIBLE
                }
                .start()
        } else {
            view.visibility = View.INVISIBLE
            view.alpha = 0f
        }
    }

    fun release() {
        if (released) return
        released = true
        view.animate().cancel()
        runCatching { player.removeListener(listener) }
        runCatching { player.release() }
        view.player = null
        view.visibility = View.INVISIBLE
    }

    private fun applyPlayState() {
        player.playWhenReady = if (firstFrameShown) {
            playRequested && screenVisible
        } else {
            screenVisible
        }
    }

    private fun show() {
        view.visibility = View.VISIBLE
        view.animate().cancel()
        if (!UiAnimations.enabled(view.context)) {
            view.alpha = 1f
            return
        }
        view.animate()
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun buildSource(cover: AnimatedCover): androidx.media3.exoplayer.source.MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val directFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val cache = AnimatedCoverMediaCache.get(appContext)
        val sourceFactory: DataSource.Factory = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(directFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            directFactory
        }
        val item = MediaItem.fromUri(cover.url)
        return if (cover.mime == "hls" || cover.url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(sourceFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(item)
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

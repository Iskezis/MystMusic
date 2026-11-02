package com.iskezis.myst.ui.player

import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.navigation.fragment.findNavController
import coil.load
import com.google.android.material.button.MaterialButton
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentPlayerBinding
import com.iskezis.myst.player.PlayerController
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.ui.auth.YouTubeLoginActivity
import com.iskezis.myst.ui.browse.BrowseNavigation
import com.iskezis.myst.ui.common.AdaptiveContentColors
import com.iskezis.myst.ui.common.ContentPalette
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.BlurUtils
import com.iskezis.myst.util.MystTheme
import com.iskezis.myst.util.UiAnimations.animatePlayIcon
import com.iskezis.myst.util.UiAnimations.focusScale
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PALETTE_TRANSITION_MS = 250L
private const val PALETTE_SCROLL_DEBOUNCE_MS = 90L
private const val DISABLED_ALPHA = 0.38f
private const val PLACEHOLDER_BACKDROP = "placeholder"

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val controller: PlayerController?
        get() = (activity?.application as? MystApp)?.playerController
    private var latest: PlayerState = PlayerState()
    private var seeking = false
    private var loadedArtworkFor: String? = null
    private var backdropJob: Job? = null
    private var lastLoggedTrack: String? = null
    private var lastLoggedPlaying: Boolean? = null
    private var initialFocusApplied = false
    private var topPalette: ContentPalette? = null
    private var titlePalette: ContentPalette? = null
    private var controlsPalette: ContentPalette? = null
    private val argbEvaluator = ArgbEvaluator()
    private var paletteAnimator: ValueAnimator? = null
    private var scrollRemeasureJob: Job? = null
    private var scrollView: ScrollView? = null
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        schedulePaletteRemeasure()
    }
    private var backdropSample: Bitmap? = null
    private var backdropKey: String? = null
    private var playCircle: PlayerPlayCircleDrawable? = null
    private var progressDrawable: PlayerProgressDrawable? = null
    private var seekThumb: GradientDrawable? = null
    private var headerSubtitle: TextView? = null
    private var lastPlayEnabled: Boolean? = null

    private val youtubeLogin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            AppLog.i(AppLog.TAG_AUTH, "Login YouTube cancelado no player")
            return@registerForActivityResult
        }
        val cookies = result.data?.getStringExtra(YouTubeLoginActivity.EXTRA_COOKIES).orEmpty()
        val host = activity ?: return@registerForActivityResult
        val app = host.application as? MystApp
        if (cookies.isBlank() || app == null) {
            AppLog.w(AppLog.TAG_AUTH, "Login YouTube no player sem cookies válidos")
            Toast.makeText(host, R.string.player_login_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        AppLog.i(AppLog.TAG_AUTH, "Cookies do YouTube recebidos no player: ${cookies.length} bytes")
        host.lifecycleScope.launch {
            try {
                val name = app.accounts.linkYouTube(
                    cookies,
                    host.getString(R.string.youtube_account_fallback),
                )
                AppLog.i(AppLog.TAG_AUTH, "YouTube vinculado no player: $name")
                Toast.makeText(
                    host,
                    R.string.player_login_success,
                    Toast.LENGTH_SHORT,
                ).show()
                app.playerController.retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha vinculando YouTube no player", t)
                Toast.makeText(
                    host,
                    R.string.player_login_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment.onCreateView")
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.i(AppLog.TAG_UI, "PlayerFragment.onViewCreated")
        binding.playerBack.setOnClickListener { closePlayer() }
        binding.playerPlay.setOnClickListener { controller?.toggle() }
        binding.playerNext.setOnClickListener { controller?.next() }
        binding.playerPrevious.setOnClickListener { controller?.previous() }
        binding.playerRepeat.setOnClickListener { controller?.cycleRepeat() }
        binding.playerQueue.setOnClickListener {
            QueueSheet.newInstance().show(childFragmentManager, QueueSheet.TAG)
        }
        binding.playerRetry.setOnClickListener { retry() }
        binding.playerYouTubeLogin.setOnClickListener { openYouTubeLogin() }
        binding.playerCopyLogs.setOnClickListener { copyDiagnostics() }
        binding.playerMore.setOnClickListener {
            latest.track?.let { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            }
        }
        listOf(
            binding.playerBack,
            binding.playerMore,
            binding.playerPlay,
            binding.playerNext,
            binding.playerPrevious,
            binding.playerRepeat,
            binding.playerQueue,
            binding.playerRetry,
            binding.playerYouTubeLogin,
            binding.playerCopyLogs,
        ).forEach { it.pressScale() }
        setupTvFocus()
        setupAdaptiveColors()
        animateCoverEntrance()
        binding.playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && latest.durationMs > 0) {
                    binding.playerPosition.text = formatDuration(latest.durationMs * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (latest.durationMs > 0) {
                    controller?.seekTo(latest.durationMs * progress / 1000)
                }
                seeking = false
            }
        })

        val playerController = controller
        if (playerController == null) {
            AppLog.e(AppLog.TAG_UI, "PlayerFragment sem PlayerController")
            showConnectionError()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    playerController.state.collect { state ->
                        latest = state
                        render(state)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha ao observar o player", t)
                    showConnectionError()
                }
            }
        }
    }

    private fun render(state: PlayerState) {
        val binding = _binding ?: return
        if (!isAdded) return
        val track = state.track
        val title = track?.title?.ifBlank { getString(R.string.player_unknown_title) }
        val artist = track?.artist?.ifBlank { getString(R.string.player_unknown_artist) }
        binding.playerTitle.text = title ?: getString(R.string.player_unknown_title)
        binding.playerArtist.text = artist ?: getString(R.string.player_unknown_artist)
        val album = track?.album.orEmpty()
        binding.playerAlbum.text = album
        binding.playerAlbum.isVisible = album.isNotBlank()
        val currentTrack = track
        val artistClickable = currentTrack != null && currentTrack.artist.isNotBlank()
        binding.playerArtist.isClickable = artistClickable
        binding.playerArtist.isFocusable = artistClickable
        binding.playerArtist.setOnClickListener(
            if (artistClickable && currentTrack != null) {
                View.OnClickListener { openArtist(currentTrack) }
            } else {
                null
            },
        )
        val albumClickable = currentTrack != null && album.isNotBlank()
        binding.playerAlbum.isClickable = albumClickable
        binding.playerAlbum.isFocusable = albumClickable
        binding.playerAlbum.setOnClickListener(
            if (albumClickable && currentTrack != null) {
                View.OnClickListener { openAlbum(currentTrack) }
            } else {
                null
            },
        )
        binding.playerCover.contentDescription = getString(
            R.string.content_cover,
            title ?: getString(R.string.player_unknown_title),
        )

        val artwork = track?.artworkUrl()
        if (artwork.isNullOrBlank()) {
            loadedArtworkFor = null
            showPlaceholderBackdrop()
        } else if (loadedArtworkFor != artwork) {
            loadedArtworkFor = artwork
            binding.playerCover.load(artwork) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
                listener(
                    onSuccess = { _, result ->
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        val current = _binding
                        if (bitmap != null && current != null && view != null) {
                            applyBackdrop(bitmap, artwork)
                        } else if (current != null) {
                            showPlaceholderBackdrop()
                        }
                    },
                    onError = { _, result ->
                        AppLog.w(
                            AppLog.TAG_UI,
                            "Falha ao carregar capa do player: ${AppLog.describe(result.throwable)}",
                        )
                        loadedArtworkFor = null
                        showPlaceholderBackdrop()
                    },
                )
            }
        }

        binding.playerPlay.isEnabled = track != null
        if (lastPlayEnabled != binding.playerPlay.isEnabled) {
            lastPlayEnabled = binding.playerPlay.isEnabled
            applyPlayColors(binding, controlsPalette ?: initialPalette())
        }
        animatePlayIcon(binding.playerPlay, state.isPlaying, R.drawable.ic_play, R.drawable.ic_pause)
        binding.playerPlay.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )
        binding.playerMore.isEnabled = track != null
        binding.playerQueue.isEnabled = track != null
        binding.playerNext.isEnabled = state.hasNext || track != null
        binding.playerPrevious.isEnabled = track != null
        binding.playerRepeat.isSelected = state.repeatMode != Player.REPEAT_MODE_OFF
        binding.playerRepeatOne.isVisible = state.repeatMode == Player.REPEAT_MODE_ONE
        binding.playerRepeat.contentDescription = getString(
            when (state.repeatMode) {
                Player.REPEAT_MODE_OFF -> R.string.player_repeat_off
                Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                else -> R.string.player_repeat_all
            },
        )

        val duration = state.durationMs
        binding.playerDuration.text = formatDuration(duration)
        binding.playerPosition.text = formatDuration(state.positionMs)
        binding.playerSeek.isEnabled = duration > 0
        binding.playerSeek.alpha = if (duration > 0) 1f else DISABLED_ALPHA
        if (!seeking) {
            val progress = if (duration > 0) {
                (state.positionMs * 1000 / duration).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            binding.playerSeek.progress = progress
        }

        binding.playerError.isVisible = state.error
        binding.playerError.text = state.errorMessage ?: getString(R.string.player_error_stream)
        binding.playerRetry.isVisible = state.error && !state.loginRequired
        binding.playerYouTubeLogin.isVisible = state.loginRequired
        binding.playerCopyLogs.isVisible = state.error

        if (state.track?.videoId != lastLoggedTrack) {
            lastLoggedTrack = state.track?.videoId
            AppLog.i(
                AppLog.TAG_PLAYER,
                "Player exibindo: ${state.track?.title ?: "<nenhuma>"} " +
                    "(${state.track?.videoId ?: "-"})",
            )
        }
        if (state.isPlaying != lastLoggedPlaying) {
            lastLoggedPlaying = state.isPlaying
            AppLog.i(AppLog.TAG_PLAYER, "Player isPlaying=${state.isPlaying}")
        }
        updateTvFocusOrder()
        applyInitialFocus()
    }

    private fun isTv(): Boolean = BuildConfig.FLAVOR == "tv"

    private fun setupTvFocus() {
        if (!isTv()) return
        // O play/pause troca de ícone com uma animação de alpha; escalá-lo no
        // foco cancelaria essa animação. Ele usa o anel de foco do
        // PlayerPlayCircleDrawable, desenhado dentro do círculo adaptativo.
        listOf(
            binding.playerBack,
            binding.playerMore,
            binding.playerPrevious,
            binding.playerNext,
            binding.playerRepeat,
            binding.playerQueue,
        ).forEach { it.focusScale() }
        binding.playerArtist.focusScale(1.03f)
        binding.playerAlbum.focusScale(1.03f)
        listOf(
            binding.playerRetry,
            binding.playerYouTubeLogin,
            binding.playerCopyLogs,
        ).forEach { it.focusScale(1.04f) }
    }

    /**
     * O foco espacial padrão do Android se perde quando o álbum some ou quando
     * o seek está desabilitado. Estas ligações mantêm o D-pad previsível.
     */
    private fun updateTvFocusOrder() {
        if (!isTv()) return
        val afterTop = when {
            binding.playerArtist.isFocusable -> R.id.playerArtist
            binding.playerAlbum.isFocusable -> R.id.playerAlbum
            binding.playerSeek.isEnabled -> R.id.playerSeek
            binding.playerPlay.isEnabled -> R.id.playerPlay
            binding.playerRetry.isVisible -> R.id.playerRetry
            binding.playerYouTubeLogin.isVisible -> R.id.playerYouTubeLogin
            binding.playerCopyLogs.isVisible -> R.id.playerCopyLogs
            else -> R.id.playerBack
        }
        binding.playerBack.nextFocusDownId = afterTop
        binding.playerMore.nextFocusDownId = afterTop
        binding.playerArtist.nextFocusUpId =
            if (binding.playerMore.isEnabled) R.id.playerMore else R.id.playerBack
        binding.playerAlbum.nextFocusUpId =
            if (binding.playerArtist.isFocusable) R.id.playerArtist else R.id.playerBack
        binding.playerArtist.nextFocusDownId = when {
            binding.playerAlbum.isFocusable -> R.id.playerAlbum
            binding.playerSeek.isEnabled -> R.id.playerSeek
            else -> R.id.playerPlay
        }
        binding.playerAlbum.nextFocusDownId =
            if (binding.playerSeek.isEnabled) R.id.playerSeek else R.id.playerPlay
        binding.playerSeek.nextFocusUpId = when {
            binding.playerAlbum.isFocusable -> R.id.playerAlbum
            binding.playerArtist.isFocusable -> R.id.playerArtist
            else -> R.id.playerBack
        }
        val errorUp = when {
            binding.playerSeek.isEnabled -> R.id.playerSeek
            binding.playerPlay.isEnabled -> R.id.playerPlay
            else -> R.id.playerBack
        }
        binding.playerRetry.nextFocusUpId = errorUp
        binding.playerYouTubeLogin.nextFocusUpId = errorUp
        binding.playerCopyLogs.nextFocusUpId = errorUp
        binding.playerPrevious.nextFocusDownId = R.id.playerRepeat
        binding.playerNext.nextFocusDownId = R.id.playerQueue
        binding.playerRepeat.nextFocusUpId = R.id.playerPlay
        binding.playerRepeat.nextFocusRightId = R.id.playerQueue
        binding.playerQueue.nextFocusUpId = R.id.playerPlay
        binding.playerQueue.nextFocusLeftId = R.id.playerRepeat
        binding.playerPlay.nextFocusDownId = when {
            binding.playerRetry.isVisible -> R.id.playerRetry
            binding.playerYouTubeLogin.isVisible -> R.id.playerYouTubeLogin
            binding.playerCopyLogs.isVisible -> R.id.playerCopyLogs
            binding.playerRepeat.isEnabled -> R.id.playerRepeat
            else -> View.NO_ID
        }
    }

    private fun setupAdaptiveColors() {
        val initial = initialPalette()
        topPalette = initial
        titlePalette = initial
        controlsPalette = initial
        headerSubtitle = binding.playerRoot.findViewById(R.id.playerHeaderSubtitle)
        playCircle = PlayerPlayCircleDrawable(resources.displayMetrics.density).also {
            it.setColors(initial.primary, initial.opposite)
            binding.playerPlay.background = it
        }
        progressDrawable = PlayerProgressDrawable(resources.displayMetrics.density).also {
            it.setColors(initial.track, initial.primary)
            binding.playerSeek.progressDrawable = it
        }
        val thumbSize = (12 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        seekThumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(thumbSize, thumbSize)
            setColor(initial.primary)
        }
        binding.playerSeek.thumb = seekThumb
        applyPaletteFrame(initial, initial, initial)

        scrollView = binding.playerRoot.findViewById<View>(R.id.playerScroll) as? ScrollView
        scrollView?.viewTreeObserver?.addOnScrollChangedListener(scrollListener)
        binding.playerRoot.post {
            if (view != null && backdropSample == null) {
                measurePlaceholderPalette()
            }
        }
    }

    private fun initialPalette(): ContentPalette {
        val dark = try {
            MystTheme.isDark(requireContext())
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha detectando tema para o player", t)
            true
        }
        return AdaptiveContentColors.paletteForDarkBackground(dark)
    }

    private fun showPlaceholderBackdrop() {
        val current = _binding ?: return
        if (backdropKey == PLACEHOLDER_BACKDROP) return
        backdropKey = PLACEHOLDER_BACKDROP
        backdropJob?.cancel()
        current.playerCover.setImageResource(R.drawable.bg_cover_placeholder)
        current.playerBackdrop.setImageResource(R.drawable.bg_cover_placeholder)
        measurePlaceholderPalette()
    }

    /**
     * O placeholder não tem capa para desfocar, mas também é um fundo real.
     * Medimos o gradiente do tema para que os controles continuem legíveis
     * quando ainda não há música ou quando a capa falha.
     */
    private fun measurePlaceholderPalette() {
        val current = _binding ?: return
        if (!isAdded || view == null) return
        val root = current.playerRoot
        if (root.width <= 0 || root.height <= 0) {
            root.post { if (view != null) measurePlaceholderPalette() }
            return
        }
        val width = root.width
        val height = root.height
        val zones = computeZones()
        val previous = previousPalettes()
        val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cover_placeholder)
        if (drawable == null) {
            applyPalettes(previous.top, previous.title, previous.controls, animate = false)
            return
        }
        backdropJob?.cancel()
        backdropJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val sample = AdaptiveContentColors.renderSample(drawable, width, height)
                    PlayerSample(
                        blurred = null,
                        sample = sample,
                        palettes = samplePalettes(sample, zones, previous),
                    )
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha medindo placeholder do player", t)
                    null
                }
            } ?: return@launch
            if (backdropKey != PLACEHOLDER_BACKDROP && backdropKey != null) {
                result.sample.recycle()
                return@launch
            }
            if (_binding == null) {
                result.sample.recycle()
                return@launch
            }
            scrollRemeasureJob?.cancel()
            backdropSample?.recycle()
            backdropSample = result.sample
            applyPalettes(
                result.palettes.top,
                result.palettes.title,
                result.palettes.controls,
                animate = true,
            )
        }
    }

    private fun applyBackdrop(source: Bitmap, key: String) {
        if (view == null || !isAdded) return
        val current = _binding ?: return
        backdropKey = key
        val root = current.playerRoot
        if (root.width <= 0 || root.height <= 0) {
            root.post { if (view != null && backdropKey == key) applyBackdrop(source, key) }
            return
        }
        val width = root.width
        val height = root.height
        val zones = computeZones()
        val previous = previousPalettes()
        backdropJob?.cancel()
        backdropJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val blurred = BlurUtils.blur(source)
                    val sample = AdaptiveContentColors.screenSample(blurred, width, height)
                    PlayerSample(
                        blurred = blurred,
                        sample = sample,
                        palettes = samplePalettes(sample, zones, previous),
                    )
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha medindo fundo do player", t)
                    null
                }
            } ?: return@launch
            if (backdropKey != key) {
                result.sample.recycle()
                return@launch
            }
            val active = _binding ?: return@launch
            scrollRemeasureJob?.cancel()
            backdropSample?.recycle()
            backdropSample = result.sample
            val blurred = result.blurred ?: return@launch
            val hostContext = context ?: return@launch
            val drawable = BitmapDrawable(resources, blurred)
            drawable.setFilterBitmap(true)
            val previousDrawable = active.playerBackdrop.drawable
            if (previousDrawable != null &&
                com.iskezis.myst.util.UiAnimations.enabled(hostContext)
            ) {
                val transition = TransitionDrawable(arrayOf(previousDrawable, drawable))
                transition.isCrossFadeEnabled = true
                active.playerBackdrop.setImageDrawable(transition)
                transition.startTransition(PALETTE_TRANSITION_MS.toInt())
                active.playerBackdrop.postDelayed({
                    if (isAdded && active.playerBackdrop.drawable === transition) {
                        active.playerBackdrop.setImageDrawable(drawable)
                    }
                }, PALETTE_TRANSITION_MS + 40L)
            } else {
                active.playerBackdrop.setImageDrawable(drawable)
            }
            applyPalettes(
                result.palettes.top,
                result.palettes.title,
                result.palettes.controls,
                animate = true,
            )
        }
    }

    private fun previousPalettes(): PlayerPalettes {
        val fallback = initialPalette()
        return PlayerPalettes(
            top = topPalette ?: fallback,
            title = titlePalette ?: fallback,
            controls = controlsPalette ?: fallback,
        )
    }

    private fun samplePalettes(
        sample: Bitmap,
        zones: PlayerZones,
        previous: PlayerPalettes,
    ): PlayerPalettes {
        val topLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.top)
        val titleLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.title)
        val controlsLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.controls)
        return PlayerPalettes(
            top = AdaptiveContentColors.paletteFor(topLuminance, previous.top),
            title = AdaptiveContentColors.paletteFor(titleLuminance, previous.title),
            controls = AdaptiveContentColors.paletteFor(controlsLuminance, previous.controls),
        )
    }

    /**
     * Mede a zona de cada grupo de controles na posição real da tela. O blur
     * pode ser claro no topo e escuro embaixo, então cada bloco tem sua paleta.
     */
    private fun computeZones(): PlayerZones {
        val binding = _binding ?: return fallbackZones()
        val root = binding.playerRoot
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return fallbackZones()
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)

        fun union(vararg views: View?): RectF? {
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            views.forEach { view ->
                if (view == null || view.visibility != View.VISIBLE) return@forEach
                if (view.width <= 0 || view.height <= 0) return@forEach
                val visible = Rect()
                if (!view.getGlobalVisibleRect(visible)) return@forEach
                val viewLeft = visible.left - rootLocation[0]
                val viewTop = visible.top - rootLocation[1]
                val viewRight = visible.right - rootLocation[0]
                val viewBottom = visible.bottom - rootLocation[1]
                left = minOf(left, viewLeft)
                top = minOf(top, viewTop)
                right = maxOf(right, viewRight)
                bottom = maxOf(bottom, viewBottom)
            }
            if (left == Int.MAX_VALUE) return null
            val safeLeft = left.coerceIn(0, width).toFloat()
            val safeTop = top.coerceIn(0, height).toFloat()
            val safeRight = right.coerceIn(0, width).toFloat()
            val safeBottom = bottom.coerceIn(0, height).toFloat()
            if (safeRight - safeLeft < 2f || safeBottom - safeTop < 2f) return null
            return RectF(safeLeft / width, safeTop / height, safeRight / width, safeBottom / height)
        }

        val top = union(binding.playerBack, binding.playerHeaderTitle, headerSubtitle)
        val title = union(binding.playerTitle, binding.playerArtist, binding.playerAlbum, binding.playerMore)
        val controls = union(
            binding.playerSeek,
            binding.playerPosition,
            binding.playerDuration,
            binding.playerPrevious,
            binding.playerPlay,
            binding.playerNext,
            binding.playerRepeat,
            binding.playerQueue,
        )
        return PlayerZones(
            top = padded(top ?: RectF(0f, 0.02f, 1f, 0.14f)),
            title = padded(title ?: RectF(0f, 0.42f, 1f, 0.64f)),
            controls = padded(controls ?: RectF(0f, 0.66f, 1f, 0.94f)),
        )
    }

    private fun fallbackZones(): PlayerZones = PlayerZones(
        top = padded(RectF(0f, 0.02f, 1f, 0.14f)),
        title = padded(RectF(0f, 0.42f, 1f, 0.64f)),
        controls = padded(RectF(0f, 0.66f, 1f, 0.94f)),
    )

    private fun padded(rect: RectF): RectF = RectF(
        (rect.left - 0.015f).coerceAtLeast(0f),
        (rect.top - 0.01f).coerceAtLeast(0f),
        (rect.right + 0.015f).coerceAtMost(1f),
        (rect.bottom + 0.01f).coerceAtMost(1f),
    )

    private fun schedulePaletteRemeasure() {
        val sample = backdropSample ?: return
        if (!isAdded) return
        scrollRemeasureJob?.cancel()
        scrollRemeasureJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PALETTE_SCROLL_DEBOUNCE_MS)
            if (!isAdded) return@launch
            val zones = computeZones()
            val previous = previousPalettes()
            val palettes = withContext(Dispatchers.Default) {
                if (sample.isRecycled) null else samplePalettes(sample, zones, previous)
            } ?: return@launch
            applyPalettes(palettes.top, palettes.title, palettes.controls, animate = true)
        }
    }

    private fun applyPalettes(
        top: ContentPalette,
        title: ContentPalette,
        controls: ContentPalette,
        animate: Boolean,
    ) {
        if (!isAdded) return
        paletteAnimator?.cancel()
        paletteAnimator = null
        val fromTop = topPalette ?: top
        val fromTitle = titlePalette ?: title
        val fromControls = controlsPalette ?: controls
        val unchanged = fromTop == top && fromTitle == title && fromControls == controls
        if (unchanged) {
            topPalette = top
            titlePalette = title
            controlsPalette = controls
            applyPaletteFrame(top, title, controls)
            return
        }
        if (!animate || !com.iskezis.myst.util.UiAnimations.enabled(requireContext())) {
            topPalette = top
            titlePalette = title
            controlsPalette = controls
            applyPaletteFrame(top, title, controls)
            return
        }
        val startTop = fromTop
        val startTitle = fromTitle
        val startControls = fromControls
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PALETTE_TRANSITION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val nextTop = lerpPalette(startTop, top, fraction)
                val nextTitle = lerpPalette(startTitle, title, fraction)
                val nextControls = lerpPalette(startControls, controls, fraction)
                topPalette = nextTop
                titlePalette = nextTitle
                controlsPalette = nextControls
                applyPaletteFrame(nextTop, nextTitle, nextControls)
            }
        }
        var cancelled = false
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || !isAdded) return
                paletteAnimator = null
                topPalette = top
                titlePalette = title
                controlsPalette = controls
                applyPaletteFrame(top, title, controls)
            }
        })
        paletteAnimator = animator
        animator.start()
    }

    private fun lerpPalette(from: ContentPalette, to: ContentPalette, fraction: Float): ContentPalette {
        if (fraction <= 0f) return from
        if (fraction >= 1f) return to
        fun color(start: Int, end: Int): Int =
            argbEvaluator.evaluate(fraction, start, end) as Int

        return ContentPalette(
            primary = color(from.primary, to.primary),
            secondary = color(from.secondary, to.secondary),
            isDarkContent = if (fraction < 0.5f) from.isDarkContent else to.isDarkContent,
            track = color(from.track, to.track),
            disabled = color(from.disabled, to.disabled),
            error = color(from.error, to.error),
            opposite = color(from.opposite, to.opposite),
            ripple = color(from.ripple, to.ripple),
        )
    }

    private fun applyPaletteFrame(
        top: ContentPalette,
        title: ContentPalette,
        controls: ContentPalette,
    ) {
        val binding = _binding ?: return
        tintIcon(binding.playerBack, top.primary, top.disabled)
        applyText(binding.playerHeaderTitle, top.primary, top)
        headerSubtitle?.let { applyText(it, top.secondary, top) }

        applyText(binding.playerTitle, title.primary, title)
        applyText(binding.playerArtist, title.secondary, title)
        applyText(
            binding.playerAlbum,
            AdaptiveContentColors.withAlpha(title.primary, 0x99),
            title,
        )
        tintIcon(binding.playerMore, title.primary, title.disabled)

        applyText(binding.playerPosition, controls.secondary, controls)
        applyText(binding.playerDuration, controls.secondary, controls)
        tintIcon(binding.playerPrevious, controls.primary, controls.disabled)
        tintIcon(binding.playerNext, controls.primary, controls.disabled)
        tintIcon(binding.playerQueue, controls.primary, controls.disabled)
        tintRepeat(binding.playerRepeatIcon, controls)
        applyText(binding.playerRepeatOne, controls.primary, controls)
        applyPlayColors(binding, controls)
        progressDrawable?.setColors(controls.track, controls.primary)
        seekThumb?.setColor(controls.primary)
        applyText(binding.playerError, controls.error, controls)
        styleButtons(binding, controls)
    }

    private fun applyText(view: TextView, color: Int, palette: ContentPalette) {
        view.setTextColor(color)
        if (palette.isDarkContent) {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            view.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }
    }

    private fun tintIcon(view: android.widget.ImageView, color: Int, disabled: Int) {
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(disabled, color),
            ),
        )
    }

    private fun tintRepeat(view: android.widget.ImageView, palette: ContentPalette) {
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(),
                ),
                intArrayOf(
                    palette.disabled,
                    palette.primary,
                    palette.primary,
                    palette.secondary,
                ),
            ),
        )
    }

    private fun applyPlayColors(binding: FragmentPlayerBinding, palette: ContentPalette) {
        val enabled = binding.playerPlay.isEnabled
        playCircle?.setColors(
            if (enabled) palette.primary else palette.disabled,
            if (enabled) palette.opposite else palette.disabled,
        )
        ImageViewCompat.setImageTintList(
            binding.playerPlay,
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    AdaptiveContentColors.withAlpha(palette.opposite, 0x61),
                    palette.opposite,
                ),
            ),
        )
    }

    private fun styleButtons(binding: FragmentPlayerBinding, palette: ContentPalette) {
        styleButton(binding.playerRetry, palette, primary = false)
        styleButton(binding.playerYouTubeLogin, palette, primary = true)
        styleButton(binding.playerCopyLogs, palette, primary = false)
    }

    private fun styleButton(button: MaterialButton, palette: ContentPalette, primary: Boolean) {
        val text = if (primary) palette.opposite else palette.primary
        val textStates = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(AdaptiveContentColors.withAlpha(text, 0x61), text),
        )
        button.backgroundTintList = ColorStateList.valueOf(
            if (primary) {
                palette.primary
            } else {
                AdaptiveContentColors.withAlpha(palette.primary, 0x1F)
            },
        )
        button.setTextColor(textStates)
        button.setIconTint(textStates)
        val strokeDefault = if (primary) {
            palette.primary
        } else {
            AdaptiveContentColors.withAlpha(palette.primary, 0x66)
        }
        val strokeFocused = if (primary) palette.opposite else palette.primary
        button.setStrokeColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(strokeFocused, strokeDefault),
            ),
        )
        button.setRippleColor(
            ColorStateList.valueOf(
                if (primary) {
                    AdaptiveContentColors.withAlpha(palette.opposite, 0x33)
                } else {
                    AdaptiveContentColors.withAlpha(palette.primary, 0x33)
                },
            ),
        )
    }

    private data class PlayerZones(
        val top: RectF,
        val title: RectF,
        val controls: RectF,
    )

    private data class PlayerPalettes(
        val top: ContentPalette,
        val title: ContentPalette,
        val controls: ContentPalette,
    )

    private data class PlayerSample(
        val blurred: Bitmap?,
        val sample: Bitmap,
        val palettes: PlayerPalettes,
    )

    private fun applyInitialFocus() {
        if (!isTv() || initialFocusApplied) return
        initialFocusApplied = true
        val target = when {
            binding.playerPlay.isEnabled -> binding.playerPlay
            binding.playerRetry.isVisible -> binding.playerRetry
            binding.playerYouTubeLogin.isVisible -> binding.playerYouTubeLogin
            binding.playerCopyLogs.isVisible -> binding.playerCopyLogs
            else -> binding.playerBack
        }
        binding.root.post {
            if (isAdded) target.requestFocus()
        }
    }

    private fun openArtist(track: Track) {
        val context = context ?: return
        val opened = BrowseNavigation.openArtistOrSearch(
            context,
            track.artistId,
            track.artist,
        )
        if (!opened) {
            Toast.makeText(context, R.string.track_menu_artist_unavailable, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openAlbum(track: Track) {
        val context = context ?: return
        val opened = BrowseNavigation.openAlbumOrSearch(
            context,
            track.albumId,
            track.album,
        )
        if (!opened) {
            Toast.makeText(context, R.string.track_menu_album_unavailable, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showConnectionError() {
        val binding = _binding ?: return
        val playerController = controller
        binding.playerError.isVisible = true
        binding.playerError.text = getString(R.string.player_connection_error)
        binding.playerRetry.isVisible = true
        binding.playerYouTubeLogin.isVisible = false
        binding.playerCopyLogs.isVisible = true
        binding.playerPlay.isEnabled = playerController != null && latest.track != null
        if (lastPlayEnabled != binding.playerPlay.isEnabled) {
            lastPlayEnabled = binding.playerPlay.isEnabled
            applyPlayColors(binding, controlsPalette ?: initialPalette())
        }
        binding.playerNext.isEnabled =
            playerController != null && (latest.hasNext || latest.track != null)
        binding.playerPrevious.isEnabled = playerController != null && latest.track != null
        binding.playerMore.isEnabled = playerController != null && latest.track != null
        binding.playerQueue.isEnabled = playerController != null && latest.track != null
        binding.playerRepeat.isEnabled = playerController != null
        binding.playerRepeat.isSelected = false
        binding.playerRepeatOne.isVisible = false
        binding.playerRepeat.contentDescription = getString(R.string.player_repeat_off)
        binding.playerSeek.isEnabled = playerController != null && latest.durationMs > 0
        binding.playerSeek.alpha = if (binding.playerSeek.isEnabled) 1f else DISABLED_ALPHA
        updateTvFocusOrder()
        applyInitialFocus()
    }

    private fun retry() {
        val playerController = controller
        if (playerController == null) {
            AppLog.e(AppLog.TAG_PLAYER, "Tentar novamente sem PlayerController")
            Toast.makeText(requireContext(), R.string.player_connection_error, Toast.LENGTH_LONG)
                .show()
            return
        }
        AppLog.i(AppLog.TAG_PLAYER, "Tentar novamente solicitado pelo player")
        playerController.retry()
    }

    private fun openYouTubeLogin() {
        AppLog.i(AppLog.TAG_AUTH, "Abrindo login do YouTube a partir do player")
        try {
            youtubeLogin.launch(Intent(requireContext(), YouTubeLoginActivity::class.java))
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha abrindo login do YouTube no player", t)
            Toast.makeText(requireContext(), R.string.player_login_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyDiagnostics() {
        try {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("MystMusic", AppLog.diagnostics()),
            )
            Toast.makeText(
                requireContext(),
                R.string.player_logs_copied,
                Toast.LENGTH_LONG,
            ).show()
            AppLog.i(AppLog.TAG_PLAYER, "Diagnóstico copiado para a área de transferência")
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha copiando diagnóstico", t)
            Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_LONG).show()
        }
    }

    private fun closePlayer() {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment: fechar")
        val host = activity ?: return
        if (host is PlayerActivity) {
            host.finish()
            return
        }
        val nav = runCatching { findNavController() }.getOrNull()
        if (nav == null) {
            AppLog.w(AppLog.TAG_UI, "Player sem NavController e sem PlayerActivity; não fecha a tela principal")
            return
        }
        val moved = runCatching { nav.navigateUp() }
            .onFailure {
                AppLog.failure(AppLog.TAG_UI, "navigateUp falhou no player", it)
            }
            .getOrDefault(false)
        if (!moved) {
            AppLog.w(AppLog.TAG_UI, "navigateUp não moveu a navegação do player")
        }
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment.onDestroyView")
        backdropJob?.cancel()
        backdropJob = null
        paletteAnimator?.cancel()
        paletteAnimator = null
        scrollRemeasureJob?.cancel()
        scrollRemeasureJob = null
        scrollView?.let { scroll ->
            val observer = scroll.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnScrollChangedListener(scrollListener)
            }
        }
        scrollView = null
        backdropSample?.recycle()
        backdropSample = null
        backdropKey = null
        loadedArtworkFor = null
        topPalette = null
        titlePalette = null
        controlsPalette = null
        playCircle = null
        progressDrawable = null
        seekThumb = null
        headerSubtitle = null
        lastPlayEnabled = null
        super.onDestroyView()
        _binding = null
    }

    private fun animateCoverEntrance() {
        val reduced = !com.iskezis.myst.util.UiAnimations.enabled(requireContext())
        if (reduced) {
            binding.playerCover.alpha = 1f
            binding.playerCover.scaleX = 1f
            binding.playerCover.scaleY = 1f
            return
        }
        binding.playerCover.alpha = 0f
        binding.playerCover.scaleX = 0.92f
        binding.playerCover.scaleY = 0.92f
        binding.playerCover.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }
}

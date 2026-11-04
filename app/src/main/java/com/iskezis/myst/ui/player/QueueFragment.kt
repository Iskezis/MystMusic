package com.iskezis.myst.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.button.MaterialButton
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentQueueBinding
import com.iskezis.myst.player.PlayerController
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.ui.common.AdaptiveContentColors
import com.iskezis.myst.ui.common.BackdropCache
import com.iskezis.myst.ui.common.ContentPalette
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.BlurUtils
import com.iskezis.myst.util.MystTheme
import com.iskezis.myst.util.UiAnimations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val QUEUE_ENTER_MS = 320L
private const val QUEUE_EXIT_MS = 260L
private const val BACKDROP_CROSSFADE_MS = 300
private const val TV_BACKDROP_CROSSFADE_MS = 180
private const val PALETTE_TRANSITION_MS = 300L
private const val TV_PALETTE_TRANSITION_MS = 160L
private const val LOADING_TIMEOUT_MS = 8_000L
private const val PLACEHOLDER_BACKDROP = "placeholder:queue"

/**
 * Fila de reprodução em tela cheia.
 *
 * É um fragmento filho do PlayerFragment: o player continua vivo e com a
 * própria paleta por baixo, então fechar a fila revela exatamente o fundo e as
 * cores atuais, sem reconstruir a tela. A fila usa o mesmo [CoverBackdropView]
 * e o mesmo [BackdropCache] do player.
 */
class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: QueueAdapter
    private var controller: PlayerController? = null
    private var latest = PlayerState()
    private var stateJob: Job? = null
    private var backdropJob: Job? = null
    private var placeholderJob: Job? = null
    private var loadingTimeoutJob: Job? = null
    private var paletteAnimator: ValueAnimator? = null
    private var backdropSample: Bitmap? = null
    private var backdropKey: String? = null
    private var failedBackdropKey: String? = null
    private var lastQueueKey: String? = null
    private var loadedNowArtwork: String? = null
    private var failedNowArtwork: String? = null
    private var headerPalette: ContentPalette? = null
    private var listPalette: ContentPalette? = null
    private var backCallback: OnBackPressedCallback? = null
    private var closing = false
    private var dragging = false
    private var dragStartRawY = 0f
    private var initialFocusApplied = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val initial = initialPalette()
        headerPalette = initial
        listPalette = initial
        adapter = QueueAdapter(initial) { index ->
            controller?.seekToIndex(index)
            close()
        }
        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter
        binding.queueClose.setOnClickListener { close() }
        binding.queueRetry.setOnClickListener { retryQueue() }
        setupBack()
        setupDragToClose()
        applyPalettes(initial, initial, animate = false)
        setupEntrance(savedInstanceState)

        val playerController = (requireActivity().application as? MystApp)?.playerController
        if (playerController == null) {
            AppLog.w(AppLog.TAG_UI, "Fila sem PlayerController")
            showError(getString(R.string.player_connection_error))
            return
        }
        controller = playerController
        latest = playerController.state.value
        renderQueue(latest)
        startObserving(playerController)
    }

    private fun startObserving(playerController: PlayerController) {
        stateJob?.cancel()
        stateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                playerController.state.collect { state ->
                    latest = state
                    renderQueue(state)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                stateJob = null
                AppLog.failure(AppLog.TAG_UI, "Falha observando a fila", t)
                showError(getString(R.string.player_queue_error))
            }
        }
    }

    private fun renderQueue(state: PlayerState) {
        if (_binding == null || !isAdded) return
        latest = state
        val playerController = controller ?: return
        val queue: List<Track>
        val currentIndex: Int
        try {
            queue = playerController.queue()
            currentIndex = playerController.currentIndex()
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha lendo a fila", t)
            showError(getString(R.string.player_queue_error))
            return
        }

        if (queue.isEmpty()) {
            lastQueueKey = null
            adapter.submitList(emptyList())
            updateNowPlaying(null, state.isPlaying)
            requestBackdrop(null)
            binding.queueCount.text = resources.getQuantityString(
                R.plurals.player_queue_count,
                0,
                0,
            )
            if (!state.connected) {
                showLoading()
            } else {
                showEmpty()
            }
            return
        }
        if (currentIndex !in queue.indices) {
            AppLog.w(
                AppLog.TAG_UI,
                "Fila com índice atual inválido: $currentIndex de ${queue.size}",
            )
            showError(getString(R.string.player_queue_error))
            return
        }

        val track = queue[currentIndex]
        val key = queueKey(queue, currentIndex, state.repeatMode)
        val queueChanged = key != lastQueueKey
        lastQueueKey = key
        if (queueChanged) {
            val items = buildQueueItems(queue, currentIndex, state.repeatMode)
            adapter.submitList(items)
            scrollToCurrent(items)
            applyInitialFocus(items)
        }
        binding.queueCount.text = resources.getQuantityString(
            R.plurals.player_queue_count,
            queue.size,
            queue.size,
        )
        updateNowPlaying(track, state.isPlaying)
        requestBackdrop(track)
        showContent()
        applyOverlayPalette(listPalette ?: initialPalette())
    }

    private fun buildQueueItems(
        queue: List<Track>,
        current: Int,
        repeatMode: Int,
    ): List<QueueListItem> {
        if (queue.isEmpty() || current !in queue.indices) return emptyList()
        val items = mutableListOf<QueueListItem>()
        items += QueueListItem.Entry(queue[current], current, isCurrent = true)

        val upcoming = mutableListOf<Int>()
        for (index in current + 1 until queue.size) upcoming += index
        if (repeatMode == Player.REPEAT_MODE_ALL) {
            for (index in 0 until current) upcoming += index
        }
        if (upcoming.isNotEmpty()) {
            items += QueueListItem.Header(R.string.player_queue_next)
            upcoming.forEach { index ->
                items += QueueListItem.Entry(queue[index], index, isCurrent = false)
            }
        }
        return items
    }

    private fun queueKey(queue: List<Track>, current: Int, repeatMode: Int): String =
        buildString {
            append(current)
            append('|')
            append(repeatMode)
            queue.forEach { track ->
                append('|')
                append(track.videoId)
            }
        }

    private fun updateNowPlaying(track: Track?, isPlaying: Boolean) {
        val current = _binding ?: return
        if (track == null) {
            current.queueNowPlaying.isVisible = false
            current.queueEqualizer.setPlaying(false)
            return
        }
        current.queueNowPlaying.isVisible = true
        val title = track.title.ifBlank { getString(R.string.player_unknown_title) }
        val artist = track.artist.ifBlank { getString(R.string.player_unknown_artist) }
        current.queueNowTitle.text = title
        current.queueNowArtist.text = artist
        current.queueNowCover.contentDescription = getString(R.string.content_cover, title)
        val artwork = track.artworkUrl()
        if (artwork.isNullOrBlank()) {
            if (loadedNowArtwork != null || failedNowArtwork != null) {
                current.queueNowCover.setImageResource(R.drawable.bg_cover_placeholder)
            }
            loadedNowArtwork = null
            failedNowArtwork = null
        } else if (artwork != loadedNowArtwork && artwork != failedNowArtwork) {
            loadedNowArtwork = artwork
            failedNowArtwork = null
            current.queueNowCover.load(artwork) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
                listener(
                    onError = { _, _ ->
                        failedNowArtwork = artwork
                        loadedNowArtwork = null
                    },
                )
            }
        }
        current.queueEqualizer.setPlaying(isPlaying)
    }

    private fun scrollToCurrent(items: List<QueueListItem>) {
        val position = items.indexOfFirst { it is QueueListItem.Entry && it.isCurrent }
        if (position < 0) return
        binding.queueList.post {
            if (!isAdded) return@post
            val manager = binding.queueList.layoutManager as? LinearLayoutManager ?: return@post
            manager.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun applyInitialFocus(items: List<QueueListItem>) {
        if (!isTv() || initialFocusApplied) return
        initialFocusApplied = true
        val position = items.indexOfFirst { it is QueueListItem.Entry && it.isCurrent }
        binding.queueList.post { focusCurrentRow(position) }
    }

    private fun focusCurrentRow(position: Int, attempt: Int = 0) {
        if (!isTv() || !isAdded || closing || _binding == null) return
        val holder = binding.queueList.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }
        if (attempt < 12) {
            binding.queueList.postDelayed({ focusCurrentRow(position, attempt + 1) }, 50L)
        } else {
            binding.queueClose.requestFocus()
        }
    }

    private fun requestBackdrop(track: Track?) {
        val artwork = track?.artworkUrl()
        val videoId = track?.videoId
        if (track == null || artwork.isNullOrBlank() || videoId.isNullOrBlank()) {
            showPlaceholderBackdrop()
            return
        }
        val key = BackdropCache.key(videoId, artwork) ?: run {
            showPlaceholderBackdrop()
            return
        }
        if (key == backdropKey) return
        if (key == failedBackdropKey) {
            showPlaceholderBackdrop()
            return
        }
        failedBackdropKey = null
        backdropKey = key
        cancelBackdropJobs()
        val cached = BackdropCache.get(videoId, artwork)
        if (cached != null) {
            backdropJob = viewLifecycleOwner.lifecycleScope.launch {
                applyBlurredBackdrop(cached, key)
            }
            return
        }
        backdropJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = context ?: return@launch
            val result = try {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(artwork)
                        .allowHardware(false)
                        .size(512)
                        .build(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UI, "Falha carregando capa da fila", t)
                null
            }
            val drawable = (result as? SuccessResult)?.drawable as? BitmapDrawable
            val bitmap = drawable?.bitmap
            if (bitmap == null) {
                onBackdropFailure(key)
                return@launch
            }
            val blurred = withContext(Dispatchers.Default) {
                try {
                    if (isTv()) {
                        BlurUtils.blur(bitmap, maxSize = 160, radius = 8, passes = 2)
                    } else {
                        BlurUtils.blur(bitmap)
                    }
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha desfocando capa da fila", t)
                    null
                }
            } ?: run {
                onBackdropFailure(key)
                return@launch
            }
            if (backdropKey != key) return@launch
            BackdropCache.put(videoId, artwork, blurred)
            applyBlurredBackdrop(blurred, key)
        }
    }

    private suspend fun applyBlurredBackdrop(blurred: Bitmap, key: String) {
        var attempts = 0
        while (isAdded && _binding != null) {
            val current = _binding ?: return
            if (current.queueRoot.width > 0 && current.queueRoot.height > 0) break
            if (attempts++ >= 20) return
            delay(16)
        }
        val current = _binding ?: return
        if (!isAdded || backdropKey != key) return
        val width = current.queueRoot.width
        val height = current.queueRoot.height
        if (width <= 0 || height <= 0) return
        val zones = computeZones()
        val previous = previousPalettes()
        val sample = try {
            withContext(Dispatchers.Default) {
                AdaptiveContentColors.screenSample(blurred, width, height)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha medindo fundo da fila", t)
            onBackdropFailure(key)
            return
        }
        if (backdropKey != key) {
            sample.recycle()
            return
        }
        val palettes = try {
            withContext(Dispatchers.Default) {
                val headerLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.header)
                val listLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.list)
                QueuePalettes(
                    header = AdaptiveContentColors.paletteFor(headerLuminance, previous.header),
                    list = AdaptiveContentColors.paletteFor(listLuminance, previous.list),
                )
            }
        } catch (cancelled: CancellationException) {
            sample.recycle()
            throw cancelled
        } catch (t: Throwable) {
            sample.recycle()
            AppLog.failure(AppLog.TAG_UI, "Falha calculando cores da fila", t)
            onBackdropFailure(key)
            return
        }
        if (!isAdded || _binding == null || backdropKey != key) {
            sample.recycle()
            return
        }
        val active = _binding ?: return
        backdropSample?.recycle()
        backdropSample = sample
        placeholderJob?.cancel()
        placeholderJob = null
        active.queueBackdrop.showBitmap(
            blurred,
            if (isTv()) TV_BACKDROP_CROSSFADE_MS else BACKDROP_CROSSFADE_MS,
        )
        applyPalettes(palettes.header, palettes.list, animate = true)
        AppLog.d(
            AppLog.TAG_UI,
            "Fundo da fila aplicado: ${blurred.width}x${blurred.height} para $key",
        )
    }

    private fun showPlaceholderBackdrop() {
        if (backdropKey == PLACEHOLDER_BACKDROP) return
        backdropKey = PLACEHOLDER_BACKDROP
        cancelBackdropJobs()
        val current = _binding ?: return
        current.queueBackdrop.showPlaceholder()
        val placeholder = ContextCompat.getDrawable(
            requireContext(),
            R.drawable.bg_cover_placeholder,
        ) ?: run {
            applyPalettes(initialPalette(), initialPalette(), animate = false)
            return
        }
        placeholderJob = viewLifecycleOwner.lifecycleScope.launch {
            var attempts = 0
            while (isAdded && _binding != null) {
                val root = _binding?.queueRoot ?: return@launch
                if (root.width > 0 && root.height > 0) break
                if (attempts++ >= 20) return@launch
                delay(16)
            }
            val root = _binding?.queueRoot ?: return@launch
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) return@launch
            val zones = computeZones()
            val previous = previousPalettes()
            val result = withContext(Dispatchers.Default) {
                try {
                    val sample = AdaptiveContentColors.renderSample(placeholder, width, height)
                    val headerLuminance =
                        AdaptiveContentColors.sampleLuminance(sample, zones.header)
                    val listLuminance = AdaptiveContentColors.sampleLuminance(sample, zones.list)
                    QueuePalettes(
                        header = AdaptiveContentColors.paletteFor(
                            headerLuminance,
                            previous.header,
                        ),
                        list = AdaptiveContentColors.paletteFor(listLuminance, previous.list),
                    ) to sample
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha medindo placeholder da fila", t)
                    null
                }
            } ?: return@launch
            if (backdropKey != PLACEHOLDER_BACKDROP || !isAdded || _binding == null) {
                result.second.recycle()
                return@launch
            }
            backdropSample?.recycle()
            backdropSample = result.second
            applyPalettes(result.first.header, result.first.list, animate = true)
        }
    }

    private fun onBackdropFailure(key: String) {
        AppLog.w(AppLog.TAG_UI, "Capa da fila indisponível para o fundo")
        if (backdropKey != key) return
        failedBackdropKey = key
        showPlaceholderBackdrop()
    }

    private fun cancelBackdropJobs() {
        backdropJob?.cancel()
        backdropJob = null
        placeholderJob?.cancel()
        placeholderJob = null
    }

    private fun computeZones(): QueueZones {
        val current = _binding ?: return fallbackZones()
        val root = current.queueRoot
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
                val visible = android.graphics.Rect()
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

        val header = union(current.queueHeaderRow, current.queueNowPlaying)
        val list = union(current.queueList)
        return QueueZones(
            header = padded(header ?: RectF(0f, 0.02f, 1f, 0.24f)),
            list = padded(list ?: RectF(0f, 0.26f, 1f, 0.98f)),
        )
    }

    private fun fallbackZones(): QueueZones = QueueZones(
        header = padded(RectF(0f, 0.02f, 1f, 0.24f)),
        list = padded(RectF(0f, 0.26f, 1f, 0.98f)),
    )

    private fun padded(rect: RectF): RectF = RectF(
        (rect.left - 0.015f).coerceAtLeast(0f),
        (rect.top - 0.01f).coerceAtLeast(0f),
        (rect.right + 0.015f).coerceAtMost(1f),
        (rect.bottom + 0.01f).coerceAtMost(1f),
    )

    private fun previousPalettes(): QueuePalettes {
        val fallback = initialPalette()
        return QueuePalettes(
            header = headerPalette ?: fallback,
            list = listPalette ?: fallback,
        )
    }

    private fun initialPalette(): ContentPalette {
        val dark = try {
            MystTheme.isDark(requireContext())
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha detectando tema para a fila", t)
            true
        }
        return AdaptiveContentColors.paletteForDarkBackground(dark)
    }

    private fun showContent() {
        val current = _binding ?: return
        stopLoading()
        current.queueList.isVisible = true
        current.queueEmpty.isVisible = false
        current.queueError.isVisible = false
    }

    private fun showLoading() {
        val current = _binding ?: return
        current.queueList.isVisible = false
        current.queueEmpty.isVisible = false
        current.queueError.isVisible = false
        current.queueNowPlaying.isVisible = false
        current.queueShimmer.isVisible = true
        current.queueShimmer.startShimmer()
        current.queueCount.text = ""
        if (isTv()) {
            current.queueClose.post {
                if (isAdded) current.queueClose.requestFocus()
            }
        }
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(LOADING_TIMEOUT_MS)
            if (!isAdded || !current.queueShimmer.isVisible) return@launch
            showError(getString(R.string.player_queue_error))
        }
    }

    private fun showEmpty() {
        val current = _binding ?: return
        stopLoading()
        current.queueList.isVisible = false
        current.queueShimmer.isVisible = false
        current.queueError.isVisible = false
        current.queueNowPlaying.isVisible = false
        current.queueEmpty.isVisible = true
        current.queueEmptyTitle.text = getString(R.string.player_queue_empty)
        current.queueEmptyHint.text = getString(R.string.player_queue_empty_hint)
        current.queueEmptyHint.isVisible = true
        if (isTv()) {
            current.queueClose.requestFocus()
        }
    }

    private fun showError(message: String) {
        val current = _binding ?: return
        stopLoading()
        current.queueList.isVisible = false
        current.queueEmpty.isVisible = false
        current.queueShimmer.isVisible = false
        current.queueNowPlaying.isVisible = false
        current.queueError.isVisible = true
        current.queueErrorTitle.text = message
        current.queueCount.text = ""
        applyOverlayPalette(listPalette ?: initialPalette())
        if (isTv()) {
            current.queueRetry.post {
                if (isAdded) current.queueRetry.requestFocus()
            }
        }
    }

    private fun stopLoading() {
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = null
        if (_binding == null) return
        binding.queueShimmer.stopShimmer()
        binding.queueShimmer.isVisible = false
    }

    private fun retryQueue() {
        val playerController = (requireActivity().application as? MystApp)?.playerController
        if (playerController == null) {
            showError(getString(R.string.player_connection_error))
            return
        }
        controller = playerController
        if (!latest.connected) {
            playerController.retry()
        }
        showLoading()
        lastQueueKey = null
        if (stateJob?.isActive != true) {
            startObserving(playerController)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            delay(180)
            if (isAdded) {
                latest = playerController.state.value
                renderQueue(latest)
            }
        }
    }

    private fun applyPalettes(
        header: ContentPalette,
        list: ContentPalette,
        animate: Boolean,
    ) {
        if (!isAdded) return
        paletteAnimator?.cancel()
        paletteAnimator = null
        val fromHeader = headerPalette ?: header
        val fromList = listPalette ?: list
        if (fromHeader == header && fromList == list) {
            headerPalette = header
            listPalette = list
            applyPaletteFrame(header, list)
            return
        }
        if (!animate || !UiAnimations.enabled(requireContext())) {
            headerPalette = header
            listPalette = list
            applyPaletteFrame(header, list)
            return
        }
        val startHeader = fromHeader
        val startList = fromList
        val duration = if (isTv()) TV_PALETTE_TRANSITION_MS else PALETTE_TRANSITION_MS
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val nextHeader = AdaptiveContentColors.blend(startHeader, header, fraction)
                val nextList = AdaptiveContentColors.blend(startList, list, fraction)
                headerPalette = nextHeader
                listPalette = nextList
                applyPaletteFrame(nextHeader, nextList)
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
                headerPalette = header
                listPalette = list
                applyPaletteFrame(header, list)
            }
        })
        paletteAnimator = animator
        animator.start()
    }

    private fun applyPaletteFrame(header: ContentPalette, list: ContentPalette) {
        val current = _binding ?: return
        tintIcon(current.queueClose, header.primary, header.disabled)
        current.queueTitle.applyAdaptiveColor(header.primary, header)
        current.queueCount.applyAdaptiveColor(header.secondary, header)
        current.queueNowPlaying.background = roundedBackground(
            header.ripple,
            R.dimen.corner_large,
        )
        current.queueNowLabel.applyAdaptiveColor(header.secondary, header)
        current.queueNowTitle.applyAdaptiveColor(header.primary, header)
        current.queueNowArtist.applyAdaptiveColor(header.secondary, header)
        current.queueEqualizer.setBarColor(header.primary)

        adapter.updatePalette(list, current.queueList)
        applyOverlayPalette(list)
    }

    private fun applyOverlayPalette(palette: ContentPalette) {
        val current = _binding ?: return
        tintIcon(current.queueEmptyIcon, palette.secondary, palette.disabled)
        current.queueEmptyTitle.applyAdaptiveColor(palette.primary, palette)
        current.queueEmptyHint.applyAdaptiveColor(palette.secondary, palette)
        tintIcon(current.queueErrorIcon, palette.secondary, palette.disabled)
        current.queueErrorTitle.applyAdaptiveColor(palette.primary, palette)
        styleButton(current.queueRetry, palette)
    }

    private fun applyAdaptiveColor(view: TextView, color: Int, palette: ContentPalette) {
        view.setTextColor(color)
        if (palette.isDarkContent) {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            view.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }
    }

    private fun tintIcon(view: ImageView, color: Int, disabled: Int) {
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(disabled, color),
            ),
        )
    }

    private fun styleButton(button: MaterialButton, palette: ContentPalette) {
        val text = palette.opposite
        val textStates = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(AdaptiveContentColors.withAlpha(text, 0x61), text),
        )
        button.backgroundTintList = ColorStateList.valueOf(palette.primary)
        button.setTextColor(textStates)
        button.setIconTint(textStates)
        button.setStrokeColor(ColorStateList.valueOf(palette.primary))
        button.setRippleColor(
            ColorStateList.valueOf(AdaptiveContentColors.withAlpha(text, 0x33)),
        )
    }

    private fun roundedBackground(color: Int, radiusRes: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = resources.getDimension(radiusRes)
            setColor(color)
        }

    private fun setupBack() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                close()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        backCallback = callback
    }

    private fun setupDragToClose() {
        if (isTv()) return
        val dragThreshold = resources.displayMetrics.density * 96f
        val listener = View.OnTouchListener { _, event ->
            if (closing) return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = event.rawY
                    dragging = true
                    binding.queueRoot.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@OnTouchListener false
                    val dy = (event.rawY - dragStartRawY).coerceAtLeast(0f)
                    binding.queueRoot.translationY = dy
                    val height = binding.queueRoot.height.coerceAtLeast(1)
                    binding.queueBackdrop.alpha =
                        (1f - dy / height * 0.65f).coerceIn(0.2f, 1f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@OnTouchListener false
                    dragging = false
                    val distance = binding.queueRoot.translationY
                    if (distance > dragThreshold) {
                        close()
                    } else {
                        resetDrag()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    resetDrag()
                    true
                }
                else -> false
            }
        }
        binding.queueHeaderRow.setOnTouchListener(listener)
        binding.queueNowPlaying.setOnTouchListener(listener)
    }

    private fun resetDrag() {
        val current = _binding ?: return
        if (!UiAnimations.enabled(requireContext())) {
            current.queueRoot.translationY = 0f
            current.queueBackdrop.alpha = 1f
            return
        }
        current.queueRoot.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
        current.queueBackdrop.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()
    }

    private fun setupEntrance(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val current = _binding ?: return
        current.queueRoot.post {
            if (!isAdded || closing || _binding == null) return@post
            val root = binding.queueRoot
            if (!UiAnimations.enabled(requireContext())) {
                root.translationY = 0f
                root.alpha = 1f
                binding.queueBackdrop.alpha = 1f
                return@post
            }
            if (isTv()) {
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(170L).start()
                return@post
            }
            val height = root.height
            if (height <= 0) return@post
            root.translationY = height.toFloat()
            binding.queueBackdrop.alpha = 0f
            root.animate()
                .translationY(0f)
                .setDuration(QUEUE_ENTER_MS)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
            binding.queueBackdrop.animate().alpha(1f).setDuration(260L).start()
        }
    }

    private fun close() {
        if (closing || !isAdded) return
        closing = true
        backCallback?.isEnabled = false
        val current = _binding
        if (current == null) {
            finishClose()
            return
        }
        current.queueClose.isEnabled = false
        if (!UiAnimations.enabled(requireContext())) {
            finishClose()
            return
        }
        if (isTv()) {
            current.queueRoot.animate().cancel()
            current.queueRoot.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction { finishClose() }
                .start()
            return
        }
        val height = current.queueRoot.height.coerceAtLeast(1)
        current.queueRoot.animate().cancel()
        current.queueRoot.animate()
            .translationY(height.toFloat())
            .setDuration(QUEUE_EXIT_MS)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction { finishClose() }
            .start()
        current.queueBackdrop.animate()
            .alpha(0f)
            .setDuration(200L)
            .start()
    }

    private fun finishClose() {
        if (!isAdded) return
        runCatching {
            parentFragmentManager.setFragmentResult(RESULT_CLOSED, Bundle.EMPTY)
        }.onFailure {
            AppLog.failure(AppLog.TAG_UI, "Falha avisando o player que a fila fechou", it)
        }
        runCatching {
            parentFragmentManager.beginTransaction()
                .remove(this)
                .commitAllowingStateLoss()
        }.onFailure {
            AppLog.failure(AppLog.TAG_UI, "Falha removendo a fila", it)
        }
    }

    private fun isTv(): Boolean = BuildConfig.FLAVOR == "tv"

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "QueueFragment.onDestroyView")
        stateJob?.cancel()
        stateJob = null
        cancelBackdropJobs()
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = null
        paletteAnimator?.cancel()
        paletteAnimator = null
        backCallback?.isEnabled = false
        backCallback = null
        if (_binding != null) {
            binding.queueShimmer.stopShimmer()
        }
        backdropSample?.recycle()
        backdropSample = null
        backdropKey = null
        failedBackdropKey = null
        lastQueueKey = null
        loadedNowArtwork = null
        failedNowArtwork = null
        headerPalette = null
        listPalette = null
        super.onDestroyView()
        _binding = null
    }

    private data class QueueZones(val header: RectF, val list: RectF)

    private data class QueuePalettes(val header: ContentPalette, val list: ContentPalette)

    companion object {
        const val TAG = "QueueFragment"
        const val RESULT_CLOSED = "queue_closed"

        fun newInstance(): QueueFragment = QueueFragment()
    }
}

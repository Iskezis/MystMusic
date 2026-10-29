package com.iskezis.myst.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.databinding.FragmentPlaylistDetailBinding
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.ui.common.YouTubeLoginFragment
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.launch

/**
 * Playlist da biblioteca do YouTube Music em modo somente leitura. O único
 * botão de escrita é "Copiar para Myst", que cria uma playlist local.
 */
class YtPlaylistDetailFragment : YouTubeLoginFragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val ytId: String by lazy {
        requireArguments().getString(ARG_YT_ID).orEmpty()
    }
    private val fallbackTitle: String by lazy {
        requireArguments().getString(ARG_YT_TITLE).orEmpty()
    }
    private val fallbackThumbnail: String by lazy {
        requireArguments().getString(ARG_YT_THUMBNAIL).orEmpty()
    }
    private val viewModel: YtPlaylistDetailViewModel by viewModels {
        YtPlaylistDetailViewModel.factory(
            requireActivity().application,
            ytId,
            fallbackTitle,
        )
    }
    private lateinit var adapter: PlaylistTrackAdapter
    private var currentState = YtPlaylistDetailUiState()
    private var animatedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "YtPlaylistDetailFragment.onViewCreated ytId=$ytId")
        adapter = PlaylistTrackAdapter(
            onPlay = { tracks, index ->
                AppLog.i(
                    AppLog.TAG_UI,
                    "Playlist do YouTube $ytId: tocar ${tracks.size} faixas no índice $index",
                )
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onRemove = { _, _ -> },
            editable = false,
        )
        binding.detailTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.detailTracks.adapter = adapter

        binding.detailBack.setOnClickListener { findNavController().navigateUp() }
        binding.detailBack.pressScale()
        binding.detailMore.isVisible = false
        binding.detailCoverEdit.isVisible = false
        binding.detailCoverFrame.isClickable = false
        binding.detailCoverFrame.isFocusable = false
        binding.detailCopy.isVisible = true
        binding.detailCopy.setOnClickListener { viewModel.copyToMyst() }
        binding.detailPlayAll.setOnClickListener { playAll(shuffle = false) }
        binding.detailShuffle.setOnClickListener { playAll(shuffle = true) }
        binding.detailPlayAll.pressScale()
        binding.detailShuffle.pressScale()
        binding.detailState.stateRetry.setOnClickListener {
            if (currentState.tracks is LoadState.Error) viewModel.retry() else viewModel.retryTracks()
        }
        binding.detailYtBanner.ytBannerAction.setOnClickListener { requestYouTubeLogin() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        currentState = state
                        render(state)
                        state.refreshErrorRes?.let { errorRes ->
                            Snackbar.make(binding.root, errorRes, Snackbar.LENGTH_LONG)
                                .setAction(R.string.state_retry) { viewModel.retryTracks() }
                                .show()
                            viewModel.consumeRefreshError()
                        }
                    }
                }
                launch {
                    viewModel.copied.collect { playlistId ->
                        if (playlistId != null) {
                            viewModel.consumeCopied()
                            Snackbar.make(
                                binding.root,
                                R.string.yt_copy_success,
                                Snackbar.LENGTH_LONG,
                            )
                                .setAction(R.string.yt_copy_open) {
                                    openMystPlaylist(playlistId)
                                }
                                .show()
                        }
                    }
                }
                launch {
                    viewModel.message.collect { messageRes ->
                        if (messageRes != null) {
                            Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG)
                                .show()
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }

    override fun onYouTubeRelinked() {
        viewModel.onYouTubeRelinked()
    }

    private fun render(state: YtPlaylistDetailUiState) {
        binding.detailYtBanner.ytBannerRoot.isVisible = state.sessionExpired
        binding.detailCopy.apply {
            isEnabled = !state.copying && state.tracks is LoadState.Data
            setText(if (state.copying) R.string.yt_copying else R.string.yt_copy_to_myst)
        }
        renderHeader(state)
        when (val tracks = state.tracks) {
            is LoadState.Loading -> {
                binding.detailShimmer.isVisible = true
                binding.detailShimmer.startShimmer()
                binding.detailTracks.isVisible = false
                binding.detailState.stateRoot.isVisible = false
                setPlayButtons(false)
            }
            is LoadState.Data -> {
                stopShimmer()
                binding.detailState.stateRoot.isVisible = false
                binding.detailTracks.isVisible = true
                adapter.submit(tracks.value)
                setPlayButtons(tracks.value.isNotEmpty())
                if (!animatedOnce && tracks.value.isNotEmpty()) {
                    animatedOnce = true
                    val list = binding.detailTracks
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(120)
                        if (!isAdded || view == null) return@launch
                        if (list.isAttachedToWindow) list.staggerVisibleChildren()
                    }
                }
            }
            is LoadState.Empty -> {
                stopShimmer()
                binding.detailTracks.isVisible = false
                setPlayButtons(false)
                with(binding.detailState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_playlist)
                    stateTitle.setText(R.string.state_empty)
                    stateSubtitle.setText(R.string.yt_playlist_empty)
                    stateSubtitle.isVisible = true
                    stateRetry.isVisible = false
                }
            }
            is LoadState.Error -> {
                stopShimmer()
                binding.detailTracks.isVisible = false
                setPlayButtons(false)
                with(binding.detailState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_cloud_off)
                    stateTitle.setText(R.string.state_error)
                    stateSubtitle.text = getString(tracks.messageRes)
                    stateSubtitle.isVisible = true
                    stateRetry.setText(R.string.state_retry)
                    stateRetry.isVisible = true
                }
            }
        }
    }

    private fun renderHeader(state: YtPlaylistDetailUiState) {
        val name = state.playlist?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val thumbnail = state.playlist?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: fallbackThumbnail
        binding.detailBarTitle.text = name.ifBlank { getString(R.string.playlists_youtube_section) }
        binding.detailName.text = name.ifBlank { getString(R.string.playlists_youtube_section) }
        binding.detailInitial.text = name.trim().take(1).uppercase()
        if (thumbnail.isBlank()) {
            binding.detailCover.setImageDrawable(null)
            binding.detailInitial.isVisible = true
        } else {
            binding.detailInitial.isVisible = false
            binding.detailCover.load(thumbnail) {
                crossfade(true)
                placeholder(R.drawable.bg_playlist_placeholder)
                error(R.drawable.bg_playlist_placeholder)
            }
        }
        binding.detailSubtitle.isVisible = true
        binding.detailSubtitle.text = when (val tracks = state.tracks) {
            is LoadState.Data -> getString(R.string.yt_playlist_subtitle, tracks.value.size)
            is LoadState.Empty -> getString(R.string.yt_playlist_subtitle, 0)
            else -> getString(R.string.yt_playlist_readonly)
        }
    }

    private fun setPlayButtons(enabled: Boolean) {
        binding.detailPlayAll.isEnabled = enabled
        binding.detailShuffle.isEnabled = enabled
    }

    private fun stopShimmer() {
        binding.detailShimmer.stopShimmer()
        binding.detailShimmer.isVisible = false
    }

    private fun playAll(shuffle: Boolean) {
        val tracks = (currentState.tracks as? LoadState.Data)?.value ?: return
        if (tracks.isEmpty()) return
        AppLog.i(
            AppLog.TAG_UI,
            "Playlist do YouTube $ytId: tocar tudo (${tracks.size} faixas, shuffle=$shuffle)",
        )
        (requireActivity().application as MystApp).playerController.play(
            tracks,
            0,
            shuffle,
        )
    }

    private fun openMystPlaylist(playlistId: Long) {
        AppLog.d(AppLog.TAG_UI, "Abrindo playlist Myst copiada: id=$playlistId")
        findNavController().navigate(
            R.id.playlistDetailFragment,
            bundleOf(PlaylistsFragment.ARG_PLAYLIST_ID to playlistId),
        )
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "YtPlaylistDetailFragment.onDestroyView ytId=$ytId")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_YT_ID = "ytId"
        const val ARG_YT_TITLE = "ytTitle"
        const val ARG_YT_THUMBNAIL = "ytThumbnail"
    }
}

package com.iskezis.myst.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.R
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.databinding.FragmentPlaylistsBinding
import com.iskezis.myst.ui.common.YouTubeLoginFragment
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.launch

class PlaylistsFragment : YouTubeLoginFragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: PlaylistsViewModel by viewModels {
        PlaylistsViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: PlaylistGridAdapter
    private var currentState = PlaylistsUiState()
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
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "PlaylistsFragment.onViewCreated")
        adapter = PlaylistGridAdapter(
            onNew = { showCreateDialog() },
            onOpen = { playlist -> openPlaylist(playlist.id) },
            onOpenYt = { playlist -> openYouTubePlaylist(playlist) },
            onRetryYoutube = { viewModel.retryYouTube() },
        )
        val layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) GRID_COLUMNS else 1
        }
        binding.playlistsList.layoutManager = layoutManager
        binding.playlistsList.adapter = adapter
        binding.playlistsRefresh.setOnRefreshListener {
            AppLog.d(AppLog.TAG_UI, "Playlists: atualização por gesto")
            viewModel.refresh()
        }
        binding.spotifyButton.setOnClickListener {
            AppLog.i(AppLog.TAG_SPOTIFY, "Abrindo pop-up Integrar do Spotify")
            SpotifyImportSheet.newInstance()
                .show(childFragmentManager, SpotifyImportSheet.TAG)
        }
        binding.spotifyButton.pressScale()
        binding.playlistsYtBanner.ytBannerAction.setOnClickListener { requestYouTubeLogin() }
        binding.playlistsState.stateRetry.setOnClickListener {
            if (currentState.errorRes != null) viewModel.retry() else showCreateDialog()
        }

        childFragmentManager.setFragmentResultListener(
            SpotifyImportSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val playlistId = bundle.getLong(SpotifyImportSheet.EXTRA_PLAYLIST_ID, -1L)
            val message = bundle.getString(SpotifyImportSheet.EXTRA_MESSAGE)
            AppLog.d(
                AppLog.TAG_SPOTIFY,
                "Resultado da importação: playlist=$playlistId mensagem=${message ?: "-"}",
            )
            if (!message.isNullOrBlank()) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
            if (playlistId > 0) openPlaylist(playlistId)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        currentState = state
                        render(state)
                    }
                }
                launch {
                    viewModel.message.collect { messageRes ->
                        if (messageRes != null) {
                            Snackbar.make(
                                binding.root,
                                messageRes,
                                Snackbar.LENGTH_LONG,
                            ).show()
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

    private fun render(state: PlaylistsUiState) {
        AppLog.d(
            AppLog.TAG_UI,
            "Playlists render: loading=${state.loading} erro=${state.errorRes} " +
                "myst=${state.playlists.size} youtube=${state.ytPlaylists.size} " +
                "ytLoading=${state.ytLoading} ytErro=${state.ytErrorRes}",
        )
        binding.playlistsYtBanner.ytBannerRoot.isVisible = state.ytSessionExpired
        binding.playlistsRefresh.isRefreshing = state.ytRefreshing
        when {
            state.loading -> showLoading()
            state.errorRes != null -> showError(state.errorRes)
            else -> showContent(state)
        }
    }

    private fun showLoading() {
        binding.playlistsShimmer.isVisible = true
        binding.playlistsShimmer.startShimmer()
        binding.playlistsRefresh.isVisible = false
        binding.playlistsState.stateRoot.isVisible = false
    }

    private fun showError(@StringRes errorRes: Int) {
        stopShimmer()
        binding.playlistsRefresh.isVisible = false
        with(binding.playlistsState) {
            stateRoot.isVisible = true
            stateIcon.setImageResource(R.drawable.ic_cloud_off)
            stateTitle.setText(R.string.state_error)
            stateSubtitle.text = getString(errorRes)
            stateSubtitle.isVisible = true
            stateRetry.setText(R.string.state_retry)
            stateRetry.isVisible = true
        }
    }

    private fun showContent(state: PlaylistsUiState) {
        stopShimmer()
        binding.playlistsState.stateRoot.isVisible = false
        binding.playlistsRefresh.isVisible = true
        adapter.submitList(buildItems(state))
        if (!animatedOnce) {
            animatedOnce = true
            val list = binding.playlistsList
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(120)
                if (list.isAttachedToWindow) list.staggerVisibleChildren()
            }
        }
    }

    private fun buildItems(state: PlaylistsUiState): List<PlaylistGridItem> = buildList {
        add(PlaylistGridItem.Header(R.string.playlists_myst_section))
        if (state.playlists.isEmpty()) {
            add(
                PlaylistGridItem.Status(
                    titleRes = R.string.state_empty,
                    subtitleRes = R.string.playlists_myst_hint,
                    iconRes = R.drawable.ic_playlist,
                ),
            )
        }
        add(PlaylistGridItem.New)
        state.playlists.forEach { add(PlaylistGridItem.Item(it)) }

        add(
            PlaylistGridItem.Header(
                titleRes = R.string.playlists_youtube_section,
                subtitle = youtubeSubtitle(state),
                actionRes = if (!state.ytSessionExpired && state.ytErrorRes != null) {
                    R.string.state_retry
                } else {
                    null
                },
            ),
        )
        when {
            state.ytSessionExpired && state.ytPlaylists.isEmpty() -> {
                add(
                    PlaylistGridItem.Status(
                        titleRes = R.string.yt_session_expired,
                        subtitleRes = R.string.yt_session_expired_hint,
                        iconRes = R.drawable.ic_cloud_off,
                    ),
                )
            }
            state.ytLoading -> add(PlaylistGridItem.Loading)
            state.ytErrorRes != null && state.ytPlaylists.isEmpty() -> {
                add(
                    PlaylistGridItem.Status(
                        titleRes = R.string.state_error,
                        subtitleRes = R.string.yt_sync_error,
                        iconRes = R.drawable.ic_cloud_off,
                        retry = true,
                    ),
                )
            }
            state.ytPlaylists.isEmpty() && !state.ytSessionExpired -> {
                add(
                    PlaylistGridItem.Status(
                        titleRes = R.string.state_empty,
                        subtitleRes = R.string.playlists_youtube_empty,
                        iconRes = R.drawable.ic_playlist,
                    ),
                )
            }
            else -> state.ytPlaylists.forEach { add(PlaylistGridItem.YtItem(it)) }
        }
    }

    private fun youtubeSubtitle(state: PlaylistsUiState): String? = when {
        state.ytSessionExpired && state.ytPlaylists.isNotEmpty() ->
            getString(R.string.playlists_youtube_cached)
        state.ytSessionExpired -> null
        state.ytLoading -> getString(R.string.playlists_youtube_syncing)
        state.ytErrorRes != null -> getString(R.string.yt_sync_error)
        state.ytCached -> getString(R.string.playlists_youtube_cached)
        state.ytPlaylists.isNotEmpty() -> getString(
            R.string.playlists_youtube_count,
            state.ytPlaylists.size,
        )
        else -> null
    }

    private fun stopShimmer() {
        binding.playlistsShimmer.stopShimmer()
        binding.playlistsShimmer.isVisible = false
    }

    private fun showCreateDialog() {
        showPlaylistNameDialog(
            context = requireContext(),
            titleRes = R.string.playlists_new,
            positiveRes = R.string.playlist_create,
        ) { name ->
            viewModel.create(name) { id -> openPlaylist(id) }
        }
    }

    private fun openPlaylist(id: Long) {
        AppLog.d(AppLog.TAG_UI, "Abrindo playlist $id")
        findNavController().navigate(
            R.id.playlistDetailFragment,
            bundleOf(ARG_PLAYLIST_ID to id),
        )
    }

    private fun openYouTubePlaylist(playlist: YtPlaylistEntity) {
        AppLog.d(AppLog.TAG_UI, "Abrindo playlist do YouTube ${playlist.ytId}")
        findNavController().navigate(
            R.id.ytPlaylistDetailFragment,
            bundleOf(
                YtPlaylistDetailFragment.ARG_YT_ID to playlist.ytId,
                YtPlaylistDetailFragment.ARG_YT_TITLE to playlist.title,
                YtPlaylistDetailFragment.ARG_YT_THUMBNAIL to playlist.thumbnailUrl.orEmpty(),
            ),
        )
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "PlaylistsFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
        private const val GRID_COLUMNS = 2
    }
}

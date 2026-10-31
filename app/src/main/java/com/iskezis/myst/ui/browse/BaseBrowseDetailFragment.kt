package com.iskezis.myst.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentBrowseDetailBinding
import com.iskezis.myst.ui.common.LoadMoreAdapter
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.ui.common.TrackRowAdapter
import com.iskezis.myst.ui.player.TrackMenuSheet
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base de álbum e playlist pública. O cabeçalho e as faixas vivem no mesmo
 * RecyclerView; o rodapé de continuação fica em um ConcatAdapter separado.
 */
abstract class BaseBrowseDetailFragment : Fragment() {

    private var _binding: FragmentBrowseDetailBinding? = null
    protected val binding get() = checkNotNull(_binding)

    protected lateinit var headerAdapter: BrowseHeaderAdapter
    protected lateinit var trackAdapter: TrackRowAdapter
    protected lateinit var loadMoreAdapter: LoadMoreAdapter
    protected var currentState: BrowseDetailState = BrowseDetailState()

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
        _binding = FragmentBrowseDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    protected fun setupDetail(
        onPlayAll: () -> Unit,
        onShuffle: () -> Unit,
        onCopy: () -> Unit,
        onDownload: () -> Unit,
        onRetry: () -> Unit,
        onLoadMore: () -> Unit,
        onArtist: (Track) -> Unit,
    ) {
        headerAdapter = BrowseHeaderAdapter(onPlayAll, onShuffle, onCopy, onDownload)
        trackAdapter = TrackRowAdapter(
            onPlay = { tracks, index ->
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onMore = { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
            onArtistClick = onArtist,
        )
        loadMoreAdapter = LoadMoreAdapter(
            readyTextRes = R.string.browse_load_more,
            loadingTextRes = R.string.browse_loading_more,
            onLoadMore = onLoadMore,
        )
        binding.browseTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.browseTracks.adapter = ConcatAdapter(headerAdapter, trackAdapter, loadMoreAdapter)
        binding.browseBack.setOnClickListener { findNavController().navigateUp() }
        binding.browseBack.pressScale()
        binding.browseState.stateRetry.setOnClickListener { onRetry() }
        binding.browseState.stateRetry.pressScale()
    }

    protected fun renderDetail(state: BrowseDetailState) {
        currentState = state
        binding.browseBarTitle.text = state.title.ifBlank { getString(R.string.app_name) }
        headerAdapter.submit(
            BrowseHeader(
                title = state.title,
                subtitle = state.subtitle,
                meta = state.meta,
                description = state.description,
                thumbnailUrl = state.thumbnailUrl,
                showCopy = state.showCopy,
                copying = state.copying,
                canPlay = state.trackList.isNotEmpty(),
            ),
        )
        when (val tracks = state.tracks) {
            is LoadState.Loading -> {
                binding.browseTracks.isVisible = false
                binding.browseState.stateRoot.isVisible = false
                binding.browseShimmer.isVisible = true
                binding.browseShimmer.startShimmer()
                loadMoreAdapter.submit(LoadMoreAdapter.State.HIDDEN)
            }
            is LoadState.Data -> {
                if (tracks.value.isEmpty()) {
                    showEmpty(state)
                    return
                }
                stopShimmer()
                binding.browseState.stateRoot.isVisible = false
                binding.browseTracks.isVisible = true
                trackAdapter.submitList(tracks.value)
                loadMoreAdapter.submit(loadMoreState(state))
                if (!animatedOnce) {
                    animatedOnce = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(120)
                        if (view != null && binding.browseTracks.isAttachedToWindow) {
                            binding.browseTracks.staggerVisibleChildren()
                        }
                    }
                }
            }
            is LoadState.Empty -> showEmpty(state)
            is LoadState.Error -> showError(tracks.messageRes)
        }
    }

    protected fun currentTracks(): List<Track> = currentState.trackList

    private fun loadMoreState(state: BrowseDetailState): LoadMoreAdapter.State = when {
        !state.hasMore -> LoadMoreAdapter.State.HIDDEN
        state.loadMoreError -> LoadMoreAdapter.State.ERROR
        state.loadingMore -> LoadMoreAdapter.State.LOADING
        else -> LoadMoreAdapter.State.READY
    }

    private fun showEmpty(state: BrowseDetailState) {
        stopShimmer()
        binding.browseTracks.isVisible = false
        loadMoreAdapter.submit(LoadMoreAdapter.State.HIDDEN)
        showState(
            iconRes = R.drawable.ic_playlist,
            titleRes = R.string.state_empty,
            subtitleRes = state.emptyMessageRes,
            retry = false,
        )
    }

    private fun showError(@StringRes messageRes: Int) {
        stopShimmer()
        binding.browseTracks.isVisible = false
        loadMoreAdapter.submit(LoadMoreAdapter.State.HIDDEN)
        showState(
            iconRes = R.drawable.ic_cloud_off,
            titleRes = R.string.state_error,
            subtitleRes = messageRes,
            retry = true,
        )
    }

    private fun showState(
        iconRes: Int,
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        retry: Boolean,
    ) {
        with(binding.browseState) {
            stateRoot.isVisible = true
            stateIcon.setImageResource(iconRes)
            stateTitle.setText(titleRes)
            stateSubtitle.setText(subtitleRes)
            stateSubtitle.isVisible = true
            stateRetry.isVisible = retry
        }
    }

    private fun stopShimmer() {
        binding.browseShimmer.stopShimmer()
        binding.browseShimmer.isVisible = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

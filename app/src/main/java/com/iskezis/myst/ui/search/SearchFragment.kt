package com.iskezis.myst.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.ChipGroup
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.AlbumSummary
import com.iskezis.myst.data.youtube.ArtistSummary
import com.iskezis.myst.data.youtube.PlaylistSummary
import com.iskezis.myst.data.youtube.SearchFilter
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentSearchBinding
import com.iskezis.myst.ui.browse.AlbumFragment
import com.iskezis.myst.ui.browse.ArtistFragment
import com.iskezis.myst.ui.browse.PublicPlaylistFragment
import com.iskezis.myst.ui.player.TrackMenuSheet
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: SearchAdapter
    private var animatedOnce = false
    private var applyingChip = false

    private val initialQuery: String by lazy {
        requireArguments().getString(ARG_INITIAL_QUERY).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "SearchFragment.onViewCreated")
        adapter = SearchAdapter(
            onPlay = { tracks, index ->
                AppLog.i(
                    AppLog.TAG_UI,
                    "Busca: tocar ${tracks.size} faixas no índice $index",
                )
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onMore = { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
            onArtistClick = { track -> openArtistOrSearch(track) },
            onArtist = { artist -> openArtist(artist) },
            onAlbum = { album -> openAlbum(album) },
            onPlaylist = { playlist -> openPlaylist(playlist) },
            onLoadMore = { viewModel.loadMore() },
        )
        binding.searchList.layoutManager = LinearLayoutManager(requireContext())
        binding.searchList.adapter = adapter
        binding.searchBack.setOnClickListener { findNavController().navigateUp() }
        binding.searchBack.pressScale()
        binding.searchState.stateRetry.setOnClickListener { viewModel.retry() }
        binding.searchState.stateRetry.pressScale()

        binding.searchChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (applyingChip) return@setOnCheckedStateChangeListener
            val filter = checkedIds.firstOrNull()?.toFilter() ?: return@setOnCheckedStateChangeListener
            viewModel.onFilterChanged(filter)
        }

        if (savedInstanceState == null && initialQuery.isNotBlank()) {
            binding.searchInput.setText(initialQuery)
            binding.searchInput.setSelection(initialQuery.length)
            viewModel.onQueryChanged(initialQuery)
        }
        binding.searchInput.doAfterTextChanged {
            viewModel.onQueryChanged(it?.toString().orEmpty())
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.searchNow()
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(ui: SearchUiState) {
        AppLog.d(AppLog.TAG_UI, "Search render: estado=${ui.state.javaClass.simpleName}")
        applyChip(ui.filter)
        when (val state = ui.state) {
            is SearchState.Idle -> {
                stopShimmer()
                binding.searchList.isVisible = false
                showState(
                    iconRes = R.drawable.ic_search,
                    title = getString(R.string.search_initial_title),
                    subtitle = getString(R.string.search_initial_subtitle),
                    retry = false,
                )
            }
            is SearchState.Loading -> {
                binding.searchShimmer.isVisible = true
                binding.searchShimmer.startShimmer()
                binding.searchList.isVisible = false
                binding.searchState.stateRoot.isVisible = false
            }
            is SearchState.Results -> {
                stopShimmer()
                binding.searchState.stateRoot.isVisible = false
                binding.searchList.isVisible = true
                adapter.submit(
                    page = state.page,
                    filter = ui.filter,
                    loadingMore = state.loadingMore,
                    loadMoreError = state.loadMoreError,
                )
                if (!animatedOnce) {
                    animatedOnce = true
                    val list = binding.searchList
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(120)
                        if (!isAdded || view == null) return@launch
                        if (list.isAttachedToWindow) list.staggerVisibleChildren()
                    }
                }
            }
            is SearchState.Empty -> {
                stopShimmer()
                binding.searchList.isVisible = false
                showState(
                    iconRes = R.drawable.ic_music,
                    title = getString(R.string.search_empty_title),
                    subtitle = getString(R.string.search_empty_subtitle),
                    retry = false,
                )
            }
            is SearchState.Error -> {
                stopShimmer()
                binding.searchList.isVisible = false
                showState(
                    iconRes = R.drawable.ic_cloud_off,
                    title = getString(R.string.state_error),
                    subtitle = getString(state.messageRes),
                    retry = true,
                )
            }
        }
    }

    private fun applyChip(filter: SearchFilter) {
        val chipId = filter.toChipId()
        if (binding.searchChips.checkedChipId == chipId) return
        applyingChip = true
        binding.searchChips.check(chipId)
        applyingChip = false
    }

    private fun showState(iconRes: Int, title: String, subtitle: String, retry: Boolean) {
        with(binding.searchState) {
            stateRoot.isVisible = true
            stateIcon.setImageResource(iconRes)
            stateTitle.text = title
            stateSubtitle.text = subtitle
            stateSubtitle.isVisible = subtitle.isNotBlank()
            stateRetry.isVisible = retry
        }
    }

    private fun openArtist(artist: ArtistSummary) {
        findNavController().navigate(
            R.id.artistFragment,
            bundleOf(
                ArtistFragment.ARG_ARTIST_ID to artist.browseId,
                ArtistFragment.ARG_ARTIST_NAME to artist.name,
                ArtistFragment.ARG_ARTIST_THUMBNAIL to artist.thumbnailUrl.orEmpty(),
            ),
        )
    }

    private fun openAlbum(album: AlbumSummary) {
        findNavController().navigate(
            R.id.albumFragment,
            bundleOf(
                AlbumFragment.ARG_ALBUM_ID to album.browseId,
                AlbumFragment.ARG_ALBUM_TITLE to album.title,
                AlbumFragment.ARG_ALBUM_ARTIST to album.artist,
                AlbumFragment.ARG_ALBUM_THUMBNAIL to album.thumbnailUrl.orEmpty(),
            ),
        )
    }

    private fun openPlaylist(playlist: PlaylistSummary) {
        findNavController().navigate(
            R.id.publicPlaylistFragment,
            bundleOf(
                PublicPlaylistFragment.ARG_PLAYLIST_ID to playlist.browseId,
                PublicPlaylistFragment.ARG_PLAYLIST_TITLE to playlist.title,
                PublicPlaylistFragment.ARG_PLAYLIST_THUMBNAIL to playlist.thumbnailUrl.orEmpty(),
            ),
        )
    }

    private fun openArtistOrSearch(track: Track) {
        val id = track.artistId
        if (!id.isNullOrBlank()) {
            findNavController().navigate(
                R.id.artistFragment,
                bundleOf(
                    ArtistFragment.ARG_ARTIST_ID to id,
                    ArtistFragment.ARG_ARTIST_NAME to track.artist,
                ),
            )
            return
        }
        if (track.artist.isNotBlank()) {
            binding.searchInput.setText(track.artist)
            binding.searchInput.setSelection(track.artist.length)
        }
    }

    private fun stopShimmer() {
        binding.searchShimmer.stopShimmer()
        binding.searchShimmer.isVisible = false
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "SearchFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_INITIAL_QUERY = "initialQuery"
    }
}

private fun Int.toFilter(): SearchFilter = when (this) {
    R.id.searchChipSongs -> SearchFilter.SONGS
    R.id.searchChipArtists -> SearchFilter.ARTISTS
    R.id.searchChipAlbums -> SearchFilter.ALBUMS
    R.id.searchChipPlaylists -> SearchFilter.PLAYLISTS
    else -> SearchFilter.ALL
}

private fun SearchFilter.toChipId(): Int = when (this) {
    SearchFilter.ALL -> R.id.searchChipAll
    SearchFilter.SONGS -> R.id.searchChipSongs
    SearchFilter.ARTISTS -> R.id.searchChipArtists
    SearchFilter.ALBUMS -> R.id.searchChipAlbums
    SearchFilter.PLAYLISTS -> R.id.searchChipPlaylists
}

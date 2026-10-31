package com.iskezis.myst.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
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
import com.iskezis.myst.data.youtube.AlbumSummary
import com.iskezis.myst.data.youtube.ArtistPage
import com.iskezis.myst.data.youtube.ArtistSummary
import com.iskezis.myst.data.youtube.PlaylistSummary
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentArtistBinding
import com.iskezis.myst.ui.common.DownloadActions
import com.iskezis.myst.ui.common.TrackRowAdapter
import com.iskezis.myst.ui.player.TrackMenuSheet
import com.iskezis.myst.ui.search.SearchFragment
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ArtistFragment : Fragment() {

    private var _binding: FragmentArtistBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val artistId: String by lazy {
        requireArguments().getString(ARG_ARTIST_ID).orEmpty()
    }
    private val fallbackName: String by lazy {
        requireArguments().getString(ARG_ARTIST_NAME).orEmpty()
    }
    private val fallbackThumbnail: String by lazy {
        requireArguments().getString(ARG_ARTIST_THUMBNAIL).orEmpty()
    }
    private val viewModel: ArtistViewModel by viewModels {
        ArtistViewModel.factory(requireActivity().application, artistId)
    }

    private lateinit var topAdapter: TrackRowAdapter
    private lateinit var albumAdapter: AlbumCardAdapter
    private lateinit var singleAdapter: AlbumCardAdapter
    private lateinit var playlistAdapter: PlaylistCardAdapter
    private lateinit var relatedAdapter: ArtistCardAdapter

    private var currentState = ArtistUiState()
    private var animatedOnce = false
    private var preparingDownload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        topAdapter = TrackRowAdapter(
            onPlay = { tracks, index ->
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onMore = { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
            onArtistClick = { track -> openArtist(track.artistId, track.artist) },
        )
        albumAdapter = AlbumCardAdapter { album -> openAlbum(album) }
        singleAdapter = AlbumCardAdapter { album -> openAlbum(album) }
        playlistAdapter = PlaylistCardAdapter { playlist -> openPlaylist(playlist) }
        relatedAdapter = ArtistCardAdapter { artist -> openRelatedArtist(artist) }

        binding.artistTopTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.artistTopTracks.adapter = topAdapter
        binding.artistAlbumsList.layoutManager = horizontalManager()
        binding.artistAlbumsList.adapter = albumAdapter
        binding.artistSinglesList.layoutManager = horizontalManager()
        binding.artistSinglesList.adapter = singleAdapter
        binding.artistPlaylistsList.layoutManager = horizontalManager()
        binding.artistPlaylistsList.adapter = playlistAdapter
        binding.artistRelatedList.layoutManager = horizontalManager()
        binding.artistRelatedList.adapter = relatedAdapter

        binding.artistBack.setOnClickListener { findNavController().navigateUp() }
        binding.artistBack.pressScale()
        binding.artistSeeAll.setOnClickListener { onSeeAll() }
        binding.artistPlayAll.setOnClickListener { playAll(shuffle = false) }
        binding.artistShuffle.setOnClickListener { playAll(shuffle = true) }
        binding.artistDownload.setOnClickListener { prepareDownloadAll() }
        binding.artistPlayAll.pressScale()
        binding.artistShuffle.pressScale()
        binding.artistDownload.pressScale()
        binding.artistState.stateRetry.setOnClickListener { viewModel.retry() }
        binding.artistState.stateRetry.pressScale()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun horizontalManager(): LinearLayoutManager =
        LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

    private fun render(state: ArtistUiState) {
        currentState = state
        when {
            state.loading -> {
                binding.artistScroll.isVisible = false
                binding.artistState.stateRoot.isVisible = false
                binding.artistShimmer.isVisible = true
                binding.artistShimmer.startShimmer()
            }
            state.errorRes != null -> {
                stopShimmer()
                binding.artistScroll.isVisible = false
                binding.artistBarTitle.text = fallbackName.ifBlank {
                    getString(R.string.browse_artist)
                }
                showState(
                    iconRes = R.drawable.ic_cloud_off,
                    title = getString(R.string.state_error),
                    subtitle = getString(state.errorRes),
                    retry = true,
                )
            }
            state.page != null -> renderPage(state, state.page)
            else -> {
                stopShimmer()
                binding.artistScroll.isVisible = false
                binding.artistBarTitle.text = fallbackName.ifBlank {
                    getString(R.string.browse_artist)
                }
                showState(
                    iconRes = R.drawable.ic_music,
                    title = getString(R.string.state_empty),
                    subtitle = getString(R.string.browse_empty_artist),
                    retry = false,
                )
            }
        }
    }

    private fun renderPage(state: ArtistUiState, page: ArtistPage) {
        val name = page.name.ifBlank { fallbackName }
        binding.artistBarTitle.text = name.ifBlank { getString(R.string.browse_artist) }
        binding.artistName.text = name.ifBlank { getString(R.string.browse_artist_unknown) }
        binding.artistListeners.text = page.monthlyListeners
        binding.artistListeners.isVisible = page.monthlyListeners.isNotBlank()
        binding.artistCover.contentDescription = getString(
            R.string.content_cover,
            name.ifBlank { getString(R.string.browse_artist) },
        )
        val cover = page.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: fallbackThumbnail.takeIf { it.isNotBlank() }
        if (cover == null) {
            binding.artistCover.setImageDrawable(null)
        } else {
            binding.artistCover.load(cover) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }

        topAdapter.submitList(page.topTracks)
        albumAdapter.submitList(page.albums)
        singleAdapter.submitList(page.singles)
        playlistAdapter.submitList(page.playlists)
        relatedAdapter.submitList(page.relatedArtists)

        binding.artistSongsSection.isVisible = page.topTracks.isNotEmpty()
        binding.artistSeeAll.isVisible = page.topTracks.isNotEmpty() &&
            (page.songsContinuation != null || page.topSongsBrowseId != null)
        binding.artistSeeAll.isEnabled = !state.loadingMore
        binding.artistSeeAll.text = when {
            state.loadingMore -> getString(R.string.browse_loading_more)
            state.loadMoreError -> getString(R.string.state_retry)
            else -> getString(R.string.browse_see_all)
        }
        binding.artistPlayAll.isEnabled = page.topTracks.isNotEmpty()
        binding.artistShuffle.isEnabled = page.topTracks.isNotEmpty()
        binding.artistDownload.isEnabled = page.topTracks.isNotEmpty()

        binding.artistAlbumsSection.isVisible = page.albums.isNotEmpty()
        binding.artistSinglesSection.isVisible = page.singles.isNotEmpty()
        binding.artistPlaylistsSection.isVisible = page.playlists.isNotEmpty()
        binding.artistRelatedSection.isVisible = page.relatedArtists.isNotEmpty()
        binding.artistAboutSection.isVisible = page.description.isNotBlank()
        binding.artistAbout.text = page.description

        val empty = page.topTracks.isEmpty() &&
            page.albums.isEmpty() &&
            page.singles.isEmpty() &&
            page.playlists.isEmpty() &&
            page.relatedArtists.isEmpty() &&
            page.description.isBlank()
        if (empty) {
            stopShimmer()
            binding.artistScroll.isVisible = false
            showState(
                iconRes = R.drawable.ic_music,
                title = getString(R.string.state_empty),
                subtitle = getString(R.string.browse_empty_artist),
                retry = false,
            )
            return
        }

        stopShimmer()
        binding.artistState.stateRoot.isVisible = false
        binding.artistScroll.isVisible = true
        if (!animatedOnce) {
            animatedOnce = true
            viewLifecycleOwner.lifecycleScope.launch {
                delay(120)
                if (view != null && binding.artistTopTracks.isAttachedToWindow) {
                    binding.artistTopTracks.staggerVisibleChildren()
                }
            }
        }
    }

    private fun onSeeAll() {
        val state = currentState
        val page = state.page ?: return
        if (state.loadingMore) return
        if (page.songsContinuation != null) {
            viewModel.loadMore()
            return
        }
        val browseId = page.topSongsBrowseId ?: return
        findNavController().navigate(
            R.id.publicPlaylistFragment,
            bundleOf(
                PublicPlaylistFragment.ARG_PLAYLIST_ID to browseId,
                PublicPlaylistFragment.ARG_PLAYLIST_TITLE to getString(R.string.browse_top_songs),
            ),
        )
    }

    private fun playAll(shuffle: Boolean) {
        val tracks = currentState.page?.topTracks.orEmpty()
        if (tracks.isEmpty()) return
        (requireActivity().application as MystApp).playerController.play(
            tracks = tracks,
            startIndex = 0,
            shuffle = shuffle,
        )
    }

    private fun prepareDownloadAll() {
        if (preparingDownload) return
        preparingDownload = true
        val progress = Snackbar.make(
            binding.root,
            R.string.download_all_loading,
            Snackbar.LENGTH_INDEFINITE,
        )
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tracks = viewModel.loadAllSongs()
                progress.dismiss()
                DownloadActions.confirmAndDownload(this@ArtistFragment, tracks)
            } catch (cancelled: CancellationException) {
                progress.dismiss()
                throw cancelled
            } catch (t: Throwable) {
                progress.dismiss()
                Snackbar.make(
                    binding.root,
                    R.string.download_all_error,
                    Snackbar.LENGTH_LONG,
                ).show()
            } finally {
                preparingDownload = false
            }
        }
    }

    private fun openArtist(artistId: String?, artistName: String) {
        if (!artistId.isNullOrBlank()) {
            findNavController().navigate(
                R.id.artistFragment,
                bundleOf(
                    ARG_ARTIST_ID to artistId,
                    ARG_ARTIST_NAME to artistName,
                ),
            )
        } else if (artistName.isNotBlank()) {
            findNavController().navigate(
                R.id.searchFragment,
                bundleOf(SearchFragment.ARG_INITIAL_QUERY to artistName),
            )
        }
    }

    private fun openRelatedArtist(artist: ArtistSummary) {
        findNavController().navigate(
            R.id.artistFragment,
            bundleOf(
                ARG_ARTIST_ID to artist.browseId,
                ARG_ARTIST_NAME to artist.name,
                ARG_ARTIST_THUMBNAIL to artist.thumbnailUrl.orEmpty(),
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

    private fun showState(
        iconRes: Int,
        title: String,
        subtitle: String,
        retry: Boolean,
    ) {
        with(binding.artistState) {
            stateRoot.isVisible = true
            stateIcon.setImageResource(iconRes)
            stateTitle.text = title
            stateSubtitle.text = subtitle
            stateSubtitle.isVisible = subtitle.isNotBlank()
            stateRetry.isVisible = retry
        }
    }

    private fun stopShimmer() {
        binding.artistShimmer.stopShimmer()
        binding.artistShimmer.isVisible = false
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "ArtistFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_ARTIST_ID = "artistId"
        const val ARG_ARTIST_NAME = "artistName"
        const val ARG_ARTIST_THUMBNAIL = "artistThumbnail"
    }
}

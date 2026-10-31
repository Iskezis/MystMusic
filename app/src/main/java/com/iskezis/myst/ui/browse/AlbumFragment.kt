package com.iskezis.myst.ui.browse

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.ui.common.DownloadActions
import com.iskezis.myst.ui.search.SearchFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AlbumFragment : BaseBrowseDetailFragment() {

    private val albumId: String by lazy {
        requireArguments().getString(ARG_ALBUM_ID).orEmpty()
    }
    private val fallbackTitle: String by lazy {
        requireArguments().getString(ARG_ALBUM_TITLE).orEmpty()
    }
    private val fallbackArtist: String by lazy {
        requireArguments().getString(ARG_ALBUM_ARTIST).orEmpty()
    }
    private val fallbackThumbnail: String by lazy {
        requireArguments().getString(ARG_ALBUM_THUMBNAIL).orEmpty()
    }
    private val viewModel: AlbumViewModel by viewModels {
        AlbumViewModel.factory(
            app = requireActivity().application,
            albumId = albumId,
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            fallbackThumbnail = fallbackThumbnail,
        )
    }
    private var preparingDownload = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDetail(
            onPlayAll = { playAll(shuffle = false) },
            onShuffle = { playAll(shuffle = true) },
            onCopy = {},
            onDownload = { prepareDownloadAll() },
            onRetry = viewModel::retry,
            onLoadMore = viewModel::loadMore,
            onArtist = { track -> openArtist(track.artistId, track.artist) },
        )
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> renderDetail(state) }
            }
        }
    }

    private fun playAll(shuffle: Boolean) {
        val tracks = currentTracks()
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
                val tracks = viewModel.loadAllTracks()
                progress.dismiss()
                DownloadActions.confirmAndDownload(this@AlbumFragment, tracks)
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
                    ArtistFragment.ARG_ARTIST_ID to artistId,
                    ArtistFragment.ARG_ARTIST_NAME to artistName,
                ),
            )
        } else if (artistName.isNotBlank()) {
            findNavController().navigate(
                R.id.searchFragment,
                bundleOf(SearchFragment.ARG_INITIAL_QUERY to artistName),
            )
        }
    }

    companion object {
        const val ARG_ALBUM_ID = "albumId"
        const val ARG_ALBUM_TITLE = "albumTitle"
        const val ARG_ALBUM_ARTIST = "albumArtist"
        const val ARG_ALBUM_THUMBNAIL = "albumThumbnail"
    }
}

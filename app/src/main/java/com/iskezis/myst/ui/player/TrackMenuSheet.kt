package com.iskezis.myst.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.downloads.DownloadService
import com.iskezis.myst.data.downloads.DownloadStatus
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.SheetTrackMenuBinding
import com.iskezis.myst.ui.browse.BrowseNavigation
import com.iskezis.myst.ui.common.DownloadActions
import com.iskezis.myst.ui.common.toBundle
import com.iskezis.myst.ui.common.toTrack
import com.iskezis.myst.ui.playlists.AddToPlaylistSheet
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackMenuSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTrackMenuBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val track: Track? get() = arguments?.toTrack()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetTrackMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val track = track
        if (track == null) {
            AppLog.w(AppLog.TAG_UI, "TrackMenuSheet sem faixa nos argumentos")
            dismissAllowingStateLoss()
            return
        }
        AppLog.d(AppLog.TAG_UI, "TrackMenuSheet: ${track.videoId}")
        val app = requireActivity().application as MystApp

        binding.menuTitle.text = track.title.ifBlank { getString(R.string.player_unknown_title) }
        binding.menuArtist.text = track.artist.ifBlank { getString(R.string.player_unknown_artist) }
        binding.menuCover.load(track.artworkUrl()) {
            crossfade(true)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }

        binding.menuPlayNow.setOnClickListener {
            AppLog.i(AppLog.TAG_UI, "Menu: tocar agora ${track.videoId}")
            app.playerController.play(listOf(track))
            dismiss()
        }
        binding.menuPlayNext.setOnClickListener {
            AppLog.i(AppLog.TAG_UI, "Menu: tocar em seguida ${track.videoId}")
            app.playerController.playNext(track)
            Toast.makeText(requireContext(), R.string.track_queued, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        binding.menuAddPlaylist.setOnClickListener {
            dismiss()
            AddToPlaylistSheet.newInstance(track)
                .show(parentFragmentManager, AddToPlaylistSheet.TAG)
        }

        val userId = app.session.mystUserId
        binding.menuDownload.isVisible = userId > 0L
        var currentDownload: DownloadEntity? = null
        binding.menuDownload.setOnClickListener {
            val item = currentDownload
            when (item?.status) {
                DownloadStatus.DONE -> confirmRemoveDownload(app, userId, track)
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                    DownloadService.cancel(requireContext(), track.videoId)
                    AppLog.i(AppLog.TAG_DOWNLOAD, "Menu: cancelar ${track.videoId}")
                }
                DownloadStatus.FAILED -> {
                    AppLog.i(AppLog.TAG_DOWNLOAD, "Menu: tentar de novo ${track.videoId}")
                    DownloadActions.retry(this, track)
                }
                else -> {
                    AppLog.i(AppLog.TAG_DOWNLOAD, "Menu: baixar ${track.videoId}")
                    DownloadActions.downloadSingle(this, track)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.downloads.observeOne(userId, track.videoId).collect { item ->
                    currentDownload = item
                    updateDownloadRow(item)
                }
            }
        }

        binding.menuGoArtist.setOnClickListener {
            val opened = BrowseNavigation.openArtistOrSearch(
                requireContext(),
                track.artistId,
                track.artist,
            )
            if (opened) {
                dismiss()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.track_menu_artist_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        binding.menuGoAlbum.setOnClickListener {
            val albumId = track.albumId
            val albumName = track.album
            if (albumId.isNullOrBlank() && albumName.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    R.string.track_menu_album_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            val opened = BrowseNavigation.openAlbumOrSearch(
                requireContext(),
                albumId,
                albumName,
            )
            if (opened) {
                dismiss()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.track_menu_album_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun updateDownloadRow(item: DownloadEntity?) {
        binding.menuDownloadText.setText(
            when (item?.status) {
                DownloadStatus.DONE -> R.string.track_download_remove
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> R.string.track_download_cancel
                DownloadStatus.FAILED -> R.string.track_download_retry
                else -> R.string.track_download
            },
        )
    }

    private fun confirmRemoveDownload(app: MystApp, userId: Long, track: Track) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.downloads_remove_one_confirm_title)
            .setMessage(
                getString(
                    R.string.downloads_remove_one_confirm_message,
                    track.title.ifBlank { getString(R.string.player_unknown_title) },
                ),
            )
            .setNegativeButton(R.string.download_all_cancel, null)
            .setPositiveButton(R.string.downloads_remove_one_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            app.downloads.remove(userId, track.videoId)
                        }
                    }.onSuccess {
                        Toast.makeText(
                            requireContext(),
                            R.string.track_download_removed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        dismissAllowingStateLoss()
                    }.onFailure {
                        AppLog.failure(
                            AppLog.TAG_DOWNLOAD,
                            "Falha removendo download ${track.videoId}",
                            it,
                        )
                        Toast.makeText(
                            requireContext(),
                            R.string.downloads_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TrackMenuSheet"

        fun newInstance(track: Track): TrackMenuSheet = TrackMenuSheet().apply {
            arguments = track.toBundle()
        }
    }
}

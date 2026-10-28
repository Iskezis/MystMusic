package com.iskezis.myst.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.SheetTrackMenuBinding
import com.iskezis.myst.ui.common.toBundle
import com.iskezis.myst.ui.common.toTrack
import com.iskezis.myst.ui.playlists.AddToPlaylistSheet

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
            dismissAllowingStateLoss()
            return
        }
        val app = requireActivity().application as MystApp

        binding.menuTitle.text = track.title.ifBlank { getString(R.string.player_unknown_title) }
        binding.menuArtist.text = track.artist.ifBlank { getString(R.string.player_unknown_artist) }
        binding.menuCover.load(track.thumbnailUrl) {
            crossfade(true)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }

        binding.menuPlayNow.setOnClickListener {
            app.playerController.play(listOf(track))
            dismiss()
        }
        binding.menuPlayNext.setOnClickListener {
            app.playerController.playNext(track)
            Toast.makeText(requireContext(), R.string.track_queued, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        binding.menuAddPlaylist.setOnClickListener {
            dismiss()
            AddToPlaylistSheet.newInstance(track)
                .show(parentFragmentManager, AddToPlaylistSheet.TAG)
        }
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

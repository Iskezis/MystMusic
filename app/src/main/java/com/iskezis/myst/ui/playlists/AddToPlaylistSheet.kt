package com.iskezis.myst.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.DialogPlaylistNameBinding
import com.iskezis.myst.databinding.SheetAddToPlaylistBinding
import com.iskezis.myst.ui.common.toBundle
import com.iskezis.myst.ui.common.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AddToPlaylistSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddToPlaylistBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val track: Track? get() = arguments?.toTrack()
    private lateinit var adapter: PlaylistPickAdapter
    private var busy = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAddToPlaylistBinding.inflate(inflater, container, false)
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
        val userId = app.session.session?.userId

        binding.addTrackName.text = track.title.ifBlank { getString(R.string.player_unknown_title) }
        adapter = PlaylistPickAdapter { playlist -> addToPlaylist(app, userId, playlist, track) }
        binding.playlistList.layoutManager = LinearLayoutManager(requireContext())
        binding.playlistList.adapter = adapter
        binding.addNewPlaylist.setOnClickListener { showCreateDialog(app, userId, track) }

        if (userId == null) {
            showEmpty(getString(R.string.error_login_required))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.playlists.observePlaylists(userId)
                    .catch {
                        binding.playlistProgress.isVisible = false
                        showEmpty(getString(R.string.error_generic))
                    }
                    .collect { playlists ->
                        binding.playlistProgress.isVisible = false
                        if (playlists.isEmpty()) {
                            showEmpty(getString(R.string.sheet_no_playlists))
                        } else {
                            binding.playlistState.isVisible = false
                            binding.playlistList.isVisible = true
                            adapter.submitList(playlists)
                        }
                    }
            }
        }
    }

    private fun showEmpty(message: String) {
        binding.playlistState.isVisible = true
        binding.playlistList.isVisible = false
        binding.playlistStateText.text = message
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.playlistProgress.isVisible = value
        binding.playlistList.isVisible = !value
        binding.addNewPlaylist.isEnabled = !value
    }

    private fun addToPlaylist(app: MystApp, userId: Long?, playlist: PlaylistEntity, track: Track) {
        if (busy || userId == null) return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val added = app.playlists.addTrack(playlist.id, track)
                val message = if (added) {
                    getString(R.string.track_added, playlist.name)
                } else {
                    getString(R.string.track_already_added)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                setBusy(false)
                Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateDialog(app: MystApp, userId: Long?, track: Track) {
        if (busy || userId == null) return
        val dialogBinding = DialogPlaylistNameBinding.inflate(layoutInflater)
        dialogBinding.dialogPlaylistInput.doAfterTextChanged {
            dialogBinding.dialogPlaylistLayout.error = null
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlists_new)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.playlist_create, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.dialogPlaylistInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    dialogBinding.dialogPlaylistLayout.error = getString(R.string.playlist_name_error)
                    return@setOnClickListener
                }
                val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                positive.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val playlistId = app.playlists.create(userId, name)
                        app.playlists.addTrack(playlistId, track)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.track_added, name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                        dismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        positive.isEnabled = true
                        Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        binding.playlistList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddToPlaylistSheet"

        fun newInstance(track: Track): AddToPlaylistSheet = AddToPlaylistSheet().apply {
            arguments = track.toBundle()
        }
    }
}

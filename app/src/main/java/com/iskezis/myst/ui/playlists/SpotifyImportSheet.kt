package com.iskezis.myst.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.spotify.SpotifyImportException
import com.iskezis.myst.data.spotify.SpotifyImporter
import com.iskezis.myst.data.spotify.SpotifyLinkException
import com.iskezis.myst.data.spotify.SpotifyUnavailableException
import com.iskezis.myst.databinding.SheetSpotifyImportBinding
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.shake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SpotifyImportSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSpotifyImportBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var job: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetSpotifyImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.spotifyCancel.setOnClickListener {
            if (job?.isActive == true) {
                job?.cancel()
                resetUi()
            } else {
                dismiss()
            }
        }
        binding.spotifyImport.setOnClickListener { startImport() }
        binding.spotifyImport.pressScale()
        binding.spotifyCancel.pressScale()
        binding.spotifyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startImport()
                true
            } else {
                false
            }
        }
    }

    private fun startImport() {
        if (job?.isActive == true) return
        val app = requireActivity().application as MystApp
        val userId = app.session.session?.userId
        if (userId == null) {
            showError(getString(R.string.error_login_required))
            return
        }
        val link = binding.spotifyInput.text?.toString().orEmpty()
        if (SpotifyImporter.parsePlaylistId(link) == null) {
            binding.spotifyLayout.error = getString(R.string.spotify_invalid)
            binding.spotifyInput.shake()
            return
        }
        binding.spotifyLayout.error = null
        binding.spotifyError.isVisible = false
        setImporting(true)

        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val summary = app.spotify.importPlaylist(link, userId) { done, total ->
                    binding.spotifyProgress.isIndeterminate = false
                    binding.spotifyProgress.max = total.coerceAtLeast(1)
                    binding.spotifyProgress.progress = done
                    binding.spotifyProgressLabel.text = getString(
                        R.string.spotify_mapping,
                        done,
                        total,
                    )
                }
                val partial = if (summary.notFound > 0) {
                    getString(R.string.spotify_partial, summary.notFound)
                } else {
                    null
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.spotify_success, summary.found),
                    Toast.LENGTH_LONG,
                ).show()
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        EXTRA_PLAYLIST_ID to summary.playlistId,
                        EXTRA_MESSAGE to partial,
                    ),
                )
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (linkError: SpotifyLinkException) {
                binding.spotifyLayout.error = getString(R.string.spotify_invalid)
                binding.spotifyInput.shake()
                setImporting(false)
            } catch (privateError: SpotifyUnavailableException) {
                showError(getString(R.string.spotify_private))
                setImporting(false)
            } catch (importError: SpotifyImportException) {
                showError(getString(R.string.spotify_error))
                setImporting(false)
            } catch (t: Throwable) {
                showError(getString(R.string.spotify_error))
                setImporting(false)
            }
        }
    }

    private fun showError(message: String) {
        binding.spotifyError.text = message
        binding.spotifyError.isVisible = true
    }

    private fun setImporting(importing: Boolean) {
        binding.spotifyInput.isEnabled = !importing
        binding.spotifyImport.isEnabled = !importing
        binding.spotifyProgressGroup.isVisible = importing
        if (importing) {
            binding.spotifyProgress.isIndeterminate = true
            binding.spotifyProgressLabel.setText(R.string.spotify_loading)
            binding.spotifyCancel.setText(R.string.spotify_cancel)
        } else {
            binding.spotifyCancel.setText(R.string.common_cancel)
        }
    }

    private fun resetUi() {
        job = null
        setImporting(false)
        binding.spotifyProgress.isIndeterminate = true
        binding.spotifyProgress.progress = 0
        binding.spotifyProgressLabel.setText(R.string.spotify_loading)
    }

    override fun onDestroyView() {
        job?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SpotifyImportSheet"
        const val RESULT_KEY = "spotify_import_result"
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_MESSAGE = "playlist_message"

        fun newInstance(): SpotifyImportSheet = SpotifyImportSheet()
    }
}

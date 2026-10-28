package com.iskezis.myst.ui.playlists

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
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.R
import com.iskezis.myst.databinding.FragmentPlaylistsBinding
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: PlaylistsViewModel by viewModels {
        PlaylistsViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: PlaylistGridAdapter
    private var currentState = PlaylistsUiState()

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
        adapter = PlaylistGridAdapter(
            onNew = { showCreateDialog() },
            onOpen = { playlist -> openPlaylist(playlist.id) },
        )
        binding.playlistsList.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.playlistsList.adapter = adapter
        binding.spotifyButton.setOnClickListener {
            SpotifyImportSheet.newInstance()
                .show(childFragmentManager, SpotifyImportSheet.TAG)
        }
        binding.playlistsState.stateRetry.setOnClickListener {
            if (currentState.errorRes != null) viewModel.retry() else showCreateDialog()
        }

        childFragmentManager.setFragmentResultListener(
            SpotifyImportSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val playlistId = bundle.getLong(SpotifyImportSheet.EXTRA_PLAYLIST_ID, -1L)
            val message = bundle.getString(SpotifyImportSheet.EXTRA_MESSAGE)
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

    private fun render(state: PlaylistsUiState) {
        when {
            state.loading -> {
                binding.playlistsShimmer.isVisible = true
                binding.playlistsShimmer.startShimmer()
                binding.playlistsList.isVisible = false
                binding.playlistsState.stateRoot.isVisible = false
            }
            state.errorRes != null -> {
                stopShimmer()
                binding.playlistsList.isVisible = false
                with(binding.playlistsState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_cloud_off)
                    stateTitle.setText(R.string.state_error)
                    stateSubtitle.text = getString(state.errorRes)
                    stateSubtitle.isVisible = true
                    stateRetry.setText(R.string.state_retry)
                    stateRetry.isVisible = true
                }
            }
            state.playlists.isEmpty() -> {
                stopShimmer()
                binding.playlistsList.isVisible = false
                with(binding.playlistsState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_playlist)
                    stateTitle.setText(R.string.state_empty)
                    stateSubtitle.setText(R.string.playlists_empty)
                    stateSubtitle.isVisible = true
                    stateRetry.setText(R.string.playlists_new)
                    stateRetry.isVisible = true
                }
            }
            else -> {
                stopShimmer()
                binding.playlistsState.stateRoot.isVisible = false
                binding.playlistsList.isVisible = true
                adapter.submitList(
                    buildList {
                        add(PlaylistGridItem.New)
                        state.playlists.forEach { add(PlaylistGridItem.Item(it)) }
                    },
                )
            }
        }
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
        findNavController().navigate(
            R.id.playlistDetailFragment,
            bundleOf(ARG_PLAYLIST_ID to id),
        )
    }

    override fun onDestroyView() {
        binding.playlistsList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
    }
}

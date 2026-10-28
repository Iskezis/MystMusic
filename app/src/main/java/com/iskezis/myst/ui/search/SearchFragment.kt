package com.iskezis.myst.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.databinding.FragmentSearchBinding
import com.iskezis.myst.ui.common.TrackRowAdapter
import com.iskezis.myst.ui.player.TrackMenuSheet
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: TrackRowAdapter

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
        adapter = TrackRowAdapter(
            onPlay = { tracks, index ->
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onMore = { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
        )
        binding.searchList.layoutManager = LinearLayoutManager(requireContext())
        binding.searchList.adapter = adapter
        binding.searchBack.setOnClickListener { findNavController().navigateUp() }
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
        binding.searchState.stateRetry.setOnClickListener { viewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(ui: SearchUiState) {
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
                adapter.submitList(state.tracks)
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

    private fun stopShimmer() {
        binding.searchShimmer.stopShimmer()
        binding.searchShimmer.isVisible = false
    }

    override fun onDestroyView() {
        binding.searchList.adapter = null
        super.onDestroyView()
        _binding = null
    }
}

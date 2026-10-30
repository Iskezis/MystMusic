package com.iskezis.myst.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.databinding.FragmentHomeBinding
import com.iskezis.myst.ui.common.YouTubeLoginFragment
import com.iskezis.myst.ui.player.TrackMenuSheet
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import kotlinx.coroutines.launch

class HomeFragment : YouTubeLoginFragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: HomeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "HomeFragment.onViewCreated")
        adapter = HomeAdapter(
            onSearch = { findNavController().navigate(R.id.searchFragment) },
            onAvatar = { findNavController().navigate(R.id.settingsFragment) },
            onStats = { findNavController().navigate(R.id.statsFragment) },
            onPlay = { tracks, index ->
                AppLog.i(
                    AppLog.TAG_UI,
                    "Home: tocar ${tracks.size} faixas no índice $index " +
                        "(videoId=${tracks.getOrNull(index)?.videoId})",
                )
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onMore = { track ->
                TrackMenuSheet.newInstance(track)
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
            onRetryRecent = viewModel::retryRecent,
            onRetryTrending = viewModel::retryTrending,
            onRetryRecommended = viewModel::retryRecommended,
        )
        binding.homeList.layoutManager = LinearLayoutManager(requireContext())
        binding.homeList.adapter = adapter
        binding.homeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.homeList.setHasFixedSize(false)
        binding.homeYtBanner.ytBannerAction.setOnClickListener { requestYouTubeLogin() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                }
            }
        }
    }

    override fun onYouTubeRelinked() {
        viewModel.onYouTubeRelinked()
    }

    override fun onResume() {
        super.onResume()
        // Foto e nome podem ter mudado nas Configurações.
        viewModel.refreshAccount()
    }

    private fun render(state: HomeUiState) {
        AppLog.d(
            AppLog.TAG_UI,
            "Home render: recentes=${state.recent.javaClass.simpleName} " +
                "emAlta=${state.trending.javaClass.simpleName} " +
                "recomendados=${state.recommended.javaClass.simpleName}",
        )
        binding.homeRefresh.isRefreshing = state.refreshing
        binding.homeYtBanner.ytBannerRoot.isVisible = state.sessionExpired
        adapter.submitList(
            listOf(
                HomeItem.Greeting(state.accountName, state.avatarPath),
                HomeItem.Search,
                HomeItem.Header(HomeSection.RECENT),
                HomeItem.Carousel(HomeSection.RECENT, state.recent),
                HomeItem.Header(HomeSection.TRENDING),
                HomeItem.Carousel(HomeSection.TRENDING, state.trending),
                HomeItem.Header(HomeSection.RECOMMENDED),
                HomeItem.Carousel(HomeSection.RECOMMENDED, state.recommended),
            ),
        )
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "HomeFragment.onDestroyView")
        // Mantém o adapter anexado: limpá-lo durante o layout/scroll da saída
        // de tela provoca IllegalStateException no RecyclerView. A view e o
        // adapter são descartados juntos quando o fragment é destruído.
        super.onDestroyView()
        _binding = null
    }
}

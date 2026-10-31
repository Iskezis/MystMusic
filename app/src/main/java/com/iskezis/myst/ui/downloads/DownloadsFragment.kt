package com.iskezis.myst.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.downloads.DownloadStatus
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.databinding.FragmentDownloadsBinding
import com.iskezis.myst.ui.common.DownloadActions
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.ui.player.TrackMenuSheet
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch

/**
 * Tela Baixadas: lista completa (fila, progresso, falhas e concluídas),
 * espaço usado/livre, pasta, remoção individual e em lote, além do acesso à
 * pasta pública e ao cache de streaming.
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: DownloadsViewModel by viewModels {
        DownloadsViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: DownloadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = DownloadAdapter(
            onPlay = ::playDownload,
            onRetry = { item ->
                DownloadActions.retry(this, app().downloads.trackFor(item))
            },
            onCancel = viewModel::remove,
            onRemove = ::confirmRemove,
            onMore = { item ->
                TrackMenuSheet.newInstance(app().downloads.trackFor(item))
                    .show(childFragmentManager, TrackMenuSheet.TAG)
            },
        )
        binding.downloadsList.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadsList.adapter = adapter

        binding.downloadsBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
        }
        binding.downloadsBack.pressScale()
        binding.downloadsRemoveAll.setOnClickListener { confirmRemoveAll() }
        binding.downloadsRecover.setOnClickListener { viewModel.recover() }
        binding.downloadsRecover.pressScale()
        binding.downloadsStorage.setOnClickListener {
            runCatching { findNavController().navigate(R.id.storageFragment) }
                .onFailure {
                    AppLog.failure(AppLog.TAG_UI, "Falha abrindo Armazenamento", it)
                }
        }
        binding.downloadsRemoveAll.pressScale()
        binding.downloadsStorage.pressScale()
        binding.downloadsState.stateRetry.setOnClickListener { viewModel.retry() }
        binding.downloadsState.stateRetry.pressScale()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSpace()
    }

    override fun onDestroyView() {
        binding.downloadsShimmer.stopShimmer()
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: DownloadsUiState) {
        binding.downloadsSummary.text = getString(
            R.string.storage_downloads_used,
            formatBytes(state.usedBytes),
            state.count,
        )
        binding.downloadsFree.text = getString(
            R.string.downloads_free,
            formatBytes(state.freeBytes),
        )
        binding.downloadsPath.text = getString(R.string.downloads_path, state.path)
        binding.downloadsRecover.isVisible = state.activeCount > 0
        binding.downloadsRecoverHint.isVisible = state.activeCount > 0
        binding.downloadsRemoveAll.isEnabled =
            state.totalCount > 0 && !state.removingAll
        binding.downloadsRemoveAll.setText(
            if (state.removingAll) R.string.downloads_removing
            else R.string.downloads_remove_all,
        )

        when (val items = state.items) {
            is LoadState.Loading -> {
                binding.downloadsShimmer.isVisible = true
                binding.downloadsShimmer.startShimmer()
                binding.downloadsList.isVisible = false
                binding.downloadsState.stateRoot.isVisible = false
            }
            is LoadState.Data -> {
                stopShimmer()
                binding.downloadsList.isVisible = true
                binding.downloadsState.stateRoot.isVisible = false
                adapter.submitList(items.value)
            }
            is LoadState.Empty -> {
                stopShimmer()
                binding.downloadsList.isVisible = false
                showState(
                    icon = R.drawable.ic_arrow_down,
                    title = R.string.downloads_empty_title,
                    subtitle = R.string.downloads_empty_subtitle,
                    showRetry = false,
                )
            }
            is LoadState.Error -> {
                stopShimmer()
                binding.downloadsList.isVisible = false
                showState(
                    icon = R.drawable.ic_cloud_off,
                    title = items.messageRes,
                    subtitle = null,
                    showRetry = true,
                )
            }
        }

        state.messageRes?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    private fun showState(
        icon: Int,
        title: Int,
        subtitle: Int?,
        showRetry: Boolean,
    ) {
        binding.downloadsState.stateRoot.isVisible = true
        binding.downloadsState.stateIcon.setImageResource(icon)
        binding.downloadsState.stateTitle.setText(title)
        binding.downloadsState.stateSubtitle.isVisible = subtitle != null
        subtitle?.let { binding.downloadsState.stateSubtitle.setText(it) }
        binding.downloadsState.stateRetry.isVisible = showRetry
    }

    private fun stopShimmer() {
        binding.downloadsShimmer.stopShimmer()
        binding.downloadsShimmer.isVisible = false
    }

    private fun playDownload(item: DownloadEntity) {
        val state = viewModel.state.value
        val done = (state.items as? LoadState.Data)?.value.orEmpty()
            .filter { it.status == DownloadStatus.DONE }
        val index = done.indexOfFirst { it.videoId == item.videoId }
        if (index < 0) {
            Snackbar.make(
                binding.root,
                R.string.downloads_error_action,
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        val tracks = done.map { app().downloads.trackFor(it) }
        app().playerController.play(tracks, index)
    }

    private fun confirmRemove(item: DownloadEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.downloads_remove_one_confirm_title)
            .setMessage(
                getString(
                    R.string.downloads_remove_one_confirm_message,
                    item.title.ifBlank { getString(R.string.player_unknown_title) },
                ),
            )
            .setNegativeButton(R.string.download_all_cancel, null)
            .setPositiveButton(R.string.downloads_remove_one_confirm) { _, _ ->
                viewModel.remove(item)
            }
            .show()
    }

    private fun confirmRemoveAll() {
        val state = viewModel.state.value
        if (state.totalCount <= 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.downloads_remove_all_confirm_title)
            .setMessage(
                getString(
                    R.string.downloads_remove_all_confirm_message,
                    state.totalCount,
                    formatBytes(state.usedBytes),
                ),
            )
            .setNegativeButton(R.string.download_all_cancel, null)
            .setPositiveButton(R.string.downloads_remove_all_confirm) { _, _ ->
                viewModel.removeAll()
            }
            .show()
    }

    private fun app(): MystApp = requireActivity().application as MystApp
}

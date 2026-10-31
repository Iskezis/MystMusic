package com.iskezis.myst.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AutoAccountBackup
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshSpace()
        toast(
            if (granted) R.string.downloads_permission_granted
            else R.string.downloads_permission_denied,
        )
    }

    private val allFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshSpace()
        val granted = app().downloadStorage.hasPublicAccess()
        toast(
            if (granted) R.string.downloads_permission_granted
            else R.string.downloads_permission_denied,
        )
    }

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
        binding.downloadsStorage.setOnClickListener { showStorageDialog() }
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

    private fun showStorageDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    StorageInfo(
                        cacheBytes = app().audioCache.usedBytes(),
                        path = app().downloadStorage.displayPath(),
                        publicAccess = app().downloadStorage.hasPublicAccess(),
                    )
                }
                if (!isAdded) return@launch
                val message = buildString {
                    append(getString(R.string.storage_cache_used, formatBytes(info.cacheBytes)))
                    append("\n\n")
                    append(getString(R.string.downloads_path, info.path))
                    append("\n\n")
                    append(
                        if (info.publicAccess) {
                            getString(R.string.downloads_permission_granted)
                        } else {
                            getString(R.string.downloads_permission_message)
                        },
                    )
                }
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.storage_title)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.storage_cache_clear) { _, _ ->
                        app().audioCache.clear()
                        viewModel.refreshSpace()
                        toast(R.string.storage_cache_cleared)
                    }
                if (BuildConfig.FLAVOR != "tv" && !info.publicAccess) {
                    builder.setNeutralButton(R.string.downloads_permission_button) { _, _ ->
                        requestStorageAccess()
                    }
                }
                builder.show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha lendo o armazenamento", t)
                toast(R.string.downloads_error)
            }
        }
    }

    private fun requestStorageAccess() {
        if (BuildConfig.FLAVOR == "tv") return
        val storage = app().downloadStorage
        if (storage.hasPublicAccess()) {
            toast(R.string.downloads_permission_granted)
            return
        }
        val runtimePermission = AutoAccountBackup.runtimePermission()
        if (runtimePermission != null) {
            runCatching { storagePermission.launch(runtimePermission) }.onFailure { t ->
                AppLog.failure(AppLog.TAG_AUTH, "Falha pedindo armazenamento", t)
                toast(R.string.downloads_permission_denied)
            }
            return
        }
        val intent = AutoAccountBackup.allFilesSettingsIntent(requireContext())
        if (intent == null) {
            toast(R.string.downloads_permission_denied)
            return
        }
        runCatching { allFilesAccess.launch(intent) }.onFailure { t ->
            AppLog.failure(AppLog.TAG_AUTH, "Tela de acesso a arquivos indisponível", t)
            toast(R.string.downloads_permission_denied)
        }
    }

    private fun app(): MystApp = requireActivity().application as MystApp

    private fun toast(messageRes: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_LONG).show()
    }

    private data class StorageInfo(
        val cacheBytes: Long,
        val path: String,
        val publicAccess: Boolean,
    )
}

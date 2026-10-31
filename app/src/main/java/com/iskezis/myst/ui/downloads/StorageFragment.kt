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
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AutoAccountBackup
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.databinding.FragmentStorageBinding
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch

/**
 * Hub de armazenamento: resumo do espaço, acesso às músicas baixadas, ao
 * cache de streaming e à pasta pública (quando o aparelho permite).
 */
class StorageFragment : Fragment() {

    private var _binding: FragmentStorageBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: StorageViewModel by viewModels {
        StorageViewModel.factory(requireActivity().application)
    }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refresh()
        toast(
            if (granted) R.string.storage_permission_granted
            else R.string.storage_permission_denied,
        )
    }

    private val allFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refresh()
        toast(
            if (app().downloadStorage.hasPublicAccess()) R.string.storage_permission_granted
            else R.string.storage_permission_denied,
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
        _binding = FragmentStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.storageBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
        }
        binding.storageBack.pressScale()
        binding.storageDownloadsCard.setOnClickListener {
            navigate(R.id.downloadsFragment)
        }
        binding.storageCacheCard.setOnClickListener {
            navigate(R.id.cacheFragment)
        }
        binding.storageDownloadsCard.pressScale()
        binding.storageCacheCard.pressScale()
        binding.storagePermissionButton.setOnClickListener { requestStorageAccess() }
        binding.storagePermissionButton.pressScale()
        binding.storageState.stateRetry.setOnClickListener { viewModel.refresh() }
        binding.storageState.stateRetry.pressScale()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        binding.storageShimmer.stopShimmer()
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: StorageUiState) {
        when (val load = state.load) {
            is LoadState.Loading -> {
                binding.storageContent.isVisible = false
                binding.storageState.stateRoot.isVisible = false
                binding.storageShimmer.isVisible = true
                binding.storageShimmer.startShimmer()
            }
            is LoadState.Error -> {
                stopShimmer()
                binding.storageContent.isVisible = false
                showError(load.messageRes)
            }
            is LoadState.Data -> {
                stopShimmer()
                binding.storageContent.isVisible = true
                binding.storageState.stateRoot.isVisible = false
                renderSummary(load.value)
            }
            is LoadState.Empty -> {
                stopShimmer()
                binding.storageContent.isVisible = false
                showError(R.string.storage_error)
            }
        }
    }

    private fun renderSummary(summary: StorageSummary) {
        binding.storageTotal.text = getString(
            R.string.storage_summary_total,
            formatBytes(summary.downloadsBytes + summary.cacheBytes),
        )
        binding.storageFree.text = getString(
            R.string.storage_summary_free,
            formatBytes(summary.freeBytes),
        )
        binding.storageDownloadsSummary.text = getString(
            R.string.storage_downloads_summary,
            formatBytes(summary.downloadsBytes),
            summary.downloadsCount,
        )
        binding.storageCacheSummary.text = getString(
            R.string.storage_cache_summary,
            formatBytes(summary.cacheBytes),
            formatBytes(summary.cacheMaxBytes),
        )
        binding.storagePath.text = getString(R.string.storage_path_label, summary.path)
        binding.storagePermissionText.setText(
            if (summary.publicAccess) R.string.storage_permission_ok
            else R.string.storage_permission_message,
        )
        binding.storagePermissionButton.isVisible =
            BuildConfig.FLAVOR != "tv" && !summary.publicAccess
    }

    private fun showError(messageRes: Int) {
        binding.storageState.stateRoot.isVisible = true
        binding.storageState.stateIcon.setImageResource(R.drawable.ic_cloud_off)
        binding.storageState.stateTitle.setText(messageRes)
        binding.storageState.stateSubtitle.isVisible = false
        binding.storageState.stateRetry.isVisible = true
    }

    private fun stopShimmer() {
        binding.storageShimmer.stopShimmer()
        binding.storageShimmer.isVisible = false
    }

    private fun navigate(destination: Int) {
        runCatching { findNavController().navigate(destination) }
            .onFailure { AppLog.failure(AppLog.TAG_UI, "Falha abrindo armazenamento", it) }
    }

    private fun requestStorageAccess() {
        if (BuildConfig.FLAVOR == "tv") return
        val storage = app().downloadStorage
        if (storage.hasPublicAccess()) {
            toast(R.string.storage_permission_granted)
            return
        }
        val runtimePermission = AutoAccountBackup.runtimePermission()
        if (runtimePermission != null) {
            runCatching { storagePermission.launch(runtimePermission) }.onFailure { t ->
                AppLog.failure(AppLog.TAG_AUTH, "Falha pedindo armazenamento", t)
                toast(R.string.storage_permission_denied)
            }
            return
        }
        val intent = AutoAccountBackup.allFilesSettingsIntent(requireContext())
        if (intent == null) {
            toast(R.string.storage_permission_denied)
            return
        }
        runCatching { allFilesAccess.launch(intent) }.onFailure { t ->
            AppLog.failure(AppLog.TAG_AUTH, "Tela de acesso a arquivos indisponível", t)
            toast(R.string.storage_permission_denied)
        }
    }

    private fun app(): MystApp = requireActivity().application as MystApp

    private fun toast(messageRes: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_LONG).show()
    }
}

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
import com.iskezis.myst.R
import com.iskezis.myst.data.downloads.StoragePreferences
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.databinding.FragmentCacheBinding
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch

/**
 * Cache de streaming: lista o que está gravado, permite tocar, remover uma
 * faixa ou limpar tudo, além de escolher limite de tamanho e retenção.
 */
class CacheFragment : Fragment() {

    private var _binding: FragmentCacheBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: CacheViewModel by viewModels {
        CacheViewModel.factory(requireActivity().application)
    }
    private lateinit var adapter: CacheAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCacheBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = CacheAdapter(
            onPlay = viewModel::play,
            onRemove = ::confirmRemove,
        )
        binding.cacheList.layoutManager = LinearLayoutManager(requireContext())
        binding.cacheList.adapter = adapter

        binding.cacheBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
        }
        binding.cacheBack.pressScale()
        binding.cacheClear.setOnClickListener { confirmClear() }
        binding.cacheClear.pressScale()
        binding.cacheSizeRow.setOnClickListener { showSizeDialog() }
        binding.cacheAgeRow.setOnClickListener { showAgeDialog() }
        binding.cacheSizeRow.pressScale()
        binding.cacheAgeRow.pressScale()
        binding.cacheEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setEnabled(checked)
        }
        binding.cacheState.stateRetry.setOnClickListener { viewModel.refresh() }
        binding.cacheState.stateRetry.pressScale()

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
        binding.cacheShimmer.stopShimmer()
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: CacheUiState) {
        binding.cacheSummary.text = getString(
            R.string.cache_used_of,
            formatBytes(state.usedBytes),
            formatBytes(state.maxBytes),
        )
        binding.cacheCount.text = getString(R.string.storage_cache_count, state.count)
        binding.cacheFree.text = getString(
            R.string.storage_summary_free,
            formatBytes(state.freeBytes),
        )
        binding.cacheSizeValue.text = formatBytes(state.maxBytes)
        binding.cacheAgeValue.text = ageLabel(state.ageDays)
        binding.cacheClear.isEnabled = state.count > 0 && !state.clearing
        if (binding.cacheEnabledSwitch.isChecked != state.enabled) {
            binding.cacheEnabledSwitch.setOnCheckedChangeListener(null)
            binding.cacheEnabledSwitch.isChecked = state.enabled
            binding.cacheEnabledSwitch.setOnCheckedChangeListener { _, checked ->
                viewModel.setEnabled(checked)
            }
        }

        when (val load = state.load) {
            is LoadState.Loading -> {
                binding.cacheList.isVisible = false
                binding.cacheState.stateRoot.isVisible = false
                binding.cacheShimmer.isVisible = true
                binding.cacheShimmer.startShimmer()
            }
            is LoadState.Data -> {
                stopShimmer()
                binding.cacheList.isVisible = true
                binding.cacheState.stateRoot.isVisible = false
                adapter.submitList(load.value)
            }
            is LoadState.Empty -> {
                stopShimmer()
                binding.cacheList.isVisible = false
                showState(
                    icon = R.drawable.ic_cache,
                    title = R.string.cache_empty_title,
                    subtitle = R.string.cache_empty_subtitle,
                    retry = false,
                )
            }
            is LoadState.Error -> {
                stopShimmer()
                binding.cacheList.isVisible = false
                showState(
                    icon = R.drawable.ic_cloud_off,
                    title = load.messageRes,
                    subtitle = null,
                    retry = true,
                )
            }
        }

        state.messageRes?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    private fun ageLabel(days: Int): String = when {
        days <= 0 -> getString(R.string.cache_age_forever)
        days == 1 -> getString(R.string.cache_age_one_day)
        else -> getString(R.string.cache_age_days, days)
    }

    private fun showState(icon: Int, title: Int, subtitle: Int?, retry: Boolean) {
        binding.cacheState.stateRoot.isVisible = true
        binding.cacheState.stateIcon.setImageResource(icon)
        binding.cacheState.stateTitle.setText(title)
        binding.cacheState.stateSubtitle.isVisible = subtitle != null
        subtitle?.let { binding.cacheState.stateSubtitle.setText(it) }
        binding.cacheState.stateRetry.isVisible = retry
    }

    private fun stopShimmer() {
        binding.cacheShimmer.stopShimmer()
        binding.cacheShimmer.isVisible = false
    }

    private fun showSizeDialog() {
        val options = StoragePreferences.MAX_CACHE_OPTIONS
        val labels = options.map { formatBytes(it) }.toTypedArray()
        val current = viewModel.state.value.maxBytes
        val checked = options.indexOfFirst { it == current }
            .takeIf { it >= 0 }
            ?: options.indexOfFirst { it >= current }.takeIf { it >= 0 }
            ?: options.lastIndex
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_cache_size_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setMaxBytes(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.download_all_cancel, null)
            .show()
    }

    private fun showAgeDialog() {
        val options = StoragePreferences.AGE_OPTIONS_DAYS
        val labels = options.map { ageLabel(it) }.toTypedArray()
        val current = viewModel.state.value.ageDays
        val checked = options.indexOf(current).takeIf { it >= 0 } ?: 2
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_cache_age_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setAgeDays(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.download_all_cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (viewModel.state.value.count <= 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_cache_clear_confirm_title)
            .setMessage(R.string.storage_cache_clear_confirm_message)
            .setNegativeButton(R.string.download_all_cancel, null)
            .setPositiveButton(R.string.storage_cache_clear_confirm) { _, _ ->
                viewModel.clear()
            }
            .show()
    }

    private fun confirmRemove(item: CachedTrackItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cache_remove)
            .setMessage(item.title)
            .setNegativeButton(R.string.download_all_cancel, null)
            .setPositiveButton(R.string.cache_remove) { _, _ ->
                viewModel.remove(item)
            }
            .show()
    }

}

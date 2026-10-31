package com.iskezis.myst.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.databinding.SheetQueueBinding
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class QueueSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQueueBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: QueueAdapter
    private var lastTrackId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val controller = (requireActivity().application as? MystApp)?.playerController
        if (controller == null) {
            AppLog.w(AppLog.TAG_UI, "QueueSheet sem PlayerController")
            showEmpty(getString(R.string.player_connection_error))
            return
        }
        adapter = QueueAdapter { index ->
            controller.seekToIndex(index)
            dismissAllowingStateLoss()
        }
        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter
        binding.queueClose.setOnClickListener { dismiss() }
        lastTrackId = controller.state.value.track?.videoId
        renderQueue(controller)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    controller.state.collect { state ->
                        val trackId = state.track?.videoId
                        if (trackId != lastTrackId) {
                            lastTrackId = trackId
                            renderQueue(controller)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha observando a fila", t)
                    showEmpty(getString(R.string.player_queue_error))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun renderQueue(controller: com.iskezis.myst.player.PlayerController) {
        try {
            val queue = controller.queue()
            adapter.submit(queue, controller.currentIndex())
            binding.queueList.isVisible = queue.isNotEmpty()
            binding.queueEmpty.isVisible = queue.isEmpty()
            binding.queueCount.text = resources.getQuantityString(
                R.plurals.player_queue_count,
                queue.size,
                queue.size,
            )
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha montando a fila", t)
            showEmpty(getString(R.string.player_queue_error))
        }
    }

    private fun showEmpty(message: String) {
        _binding ?: return
        binding.queueList.isVisible = false
        binding.queueEmpty.isVisible = true
        binding.queueCount.text = ""
        binding.queueEmptyTitle.text = message
        binding.queueEmptyHint.text = ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "QueueSheet"

        fun newInstance(): QueueSheet = QueueSheet()
    }
}

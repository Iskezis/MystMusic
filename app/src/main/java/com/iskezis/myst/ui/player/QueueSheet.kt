package com.iskezis.myst.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.SheetQueueBinding
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class QueueSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQueueBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: QueueAdapter
    private var lastQueueKey: String? = null

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
        lastQueueKey = null
        updateQueue(controller)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    controller.state.collect {
                        // A fila também pode mudar sem trocar de faixa
                        // ("tocar em seguida"); por isso a assinatura inclui
                        // todos os videoIds, o índice atual e o repeat.
                        updateQueue(controller)
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

    private fun updateQueue(controller: com.iskezis.myst.player.PlayerController) {
        try {
            val queue = controller.queue()
            val current = controller.currentIndex()
            val repeatMode = controller.state.value.repeatMode
            val key = queueKey(queue, current, repeatMode)
            if (key == lastQueueKey) return
            lastQueueKey = key
            val items = buildQueueItems(queue, current, repeatMode)
            adapter.submitList(items)
            binding.queueList.isVisible = items.isNotEmpty()
            binding.queueEmpty.isVisible = items.isEmpty()
            binding.queueCount.text = resources.getQuantityString(
                R.plurals.player_queue_count,
                queue.size,
                queue.size,
            )
            if (items.isEmpty()) {
                binding.queueEmptyTitle.text = getString(R.string.player_queue_empty)
                binding.queueEmptyHint.text = getString(R.string.player_queue_empty_hint)
                binding.queueEmptyHint.isVisible = true
            }
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha montando a fila", t)
            showEmpty(getString(R.string.player_queue_error))
        }
    }

    /**
     * A atual fica sempre no topo, em "Tocando agora". Logo abaixo vêm as
     * próximas na ordem real da fila; com repeat all, a lista continua do
     * começo para refletir o que o player fará.
     */
    private fun buildQueueItems(
        queue: List<Track>,
        current: Int,
        repeatMode: Int,
    ): List<QueueListItem> {
        if (queue.isEmpty() || current !in queue.indices) return emptyList()
        val items = mutableListOf<QueueListItem>()
        items += QueueListItem.Header(R.string.player_now_playing)
        items += QueueListItem.Entry(queue[current], current, isCurrent = true)

        val upcoming = mutableListOf<Int>()
        for (index in current + 1 until queue.size) upcoming += index
        if (repeatMode == Player.REPEAT_MODE_ALL) {
            for (index in 0 until current) upcoming += index
        }
        if (upcoming.isNotEmpty()) {
            items += QueueListItem.Header(R.string.player_queue_next)
            upcoming.forEach { index ->
                items += QueueListItem.Entry(queue[index], index, isCurrent = false)
            }
        }
        return items
    }

    private fun queueKey(queue: List<Track>, current: Int, repeatMode: Int): String =
        buildString {
            append(current)
            append('|')
            append(repeatMode)
            queue.forEach { track ->
                append('|')
                append(track.videoId)
            }
        }

    private fun showEmpty(message: String) {
        _binding ?: return
        binding.queueList.isVisible = false
        binding.queueEmpty.isVisible = true
        binding.queueCount.text = ""
        binding.queueEmptyTitle.text = message
        binding.queueEmptyHint.isVisible = false
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

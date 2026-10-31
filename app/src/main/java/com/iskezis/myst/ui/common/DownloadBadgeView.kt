package com.iskezis.myst.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.downloads.DownloadStatus
import com.iskezis.myst.databinding.ViewDownloadBadgeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Selo discreto de download usado nas listas. Um observador global
 * ([com.iskezis.myst.data.downloads.DownloadIndex]) alimenta todos os selos,
 * então rolar uma lista grande não dispara consultas ao Room.
 */
class DownloadBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewDownloadBadgeBinding.inflate(
        LayoutInflater.from(context),
        this,
    )
    private var job: Job? = null
    private var currentVideoId: String? = null

    var videoId: String?
        get() = currentVideoId
        set(value) {
            if (currentVideoId == value) return
            currentVideoId = value
            update()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: run {
            update()
            return
        }
        job?.cancel()
        job = owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (context.applicationContext as MystApp).downloadIndex.items.collect {
                    update()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        super.onDetachedFromWindow()
    }

    private fun update() {
        val id = currentVideoId
        if (id.isNullOrBlank()) {
            show(null)
            return
        }
        val app = context.applicationContext as MystApp
        show(app.downloadIndex.status(id))
    }

    private fun show(item: DownloadEntity?) {
        binding.downloadProgress.isVisible = false
        binding.downloadDone.isVisible = false
        binding.downloadFailed.isVisible = false
        if (item == null) {
            isVisible = false
            return
        }
        when (item.status) {
            DownloadStatus.DONE -> {
                isVisible = true
                binding.downloadDone.isVisible = true
            }
            DownloadStatus.QUEUED -> {
                isVisible = true
                binding.downloadProgress.isVisible = true
                binding.downloadProgress.isIndeterminate = true
            }
            DownloadStatus.DOWNLOADING -> {
                isVisible = true
                binding.downloadProgress.isVisible = true
                if (item.totalBytes > 0L) {
                    binding.downloadProgress.isIndeterminate = false
                    val percent = (item.bytes * 100 / item.totalBytes)
                        .coerceIn(0, 100).toInt()
                    binding.downloadProgress.setProgressCompat(percent, true)
                } else {
                    binding.downloadProgress.isIndeterminate = true
                }
            }
            DownloadStatus.FAILED -> {
                isVisible = true
                binding.downloadFailed.isVisible = true
            }
            else -> isVisible = false
        }
    }
}

package com.iskezis.myst.ui.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.downloads.DownloadStatus
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.databinding.ItemDownloadBinding
import java.io.File

/**
 * Linha da tela Baixadas. Mostra o estado real (fila, progresso, falha,
 * concluída) e deixa tocar para ouvir, tentar de novo, cancelar ou remover.
 */
class DownloadAdapter(
    private val onPlay: (DownloadEntity) -> Unit,
    private val onRetry: (DownloadEntity) -> Unit,
    private val onCancel: (DownloadEntity) -> Unit,
    private val onRemove: (DownloadEntity) -> Unit,
    private val onMore: (DownloadEntity) -> Unit,
) : ListAdapter<DownloadEntity, DownloadAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding, onPlay, onRetry, onCancel, onRemove, onMore)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        private val binding: ItemDownloadBinding,
        private val onPlay: (DownloadEntity) -> Unit,
        private val onRetry: (DownloadEntity) -> Unit,
        private val onCancel: (DownloadEntity) -> Unit,
        private val onRemove: (DownloadEntity) -> Unit,
        private val onMore: (DownloadEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadEntity) {
            val context = binding.root.context
            val app = context.applicationContext as MystApp
            val title = item.title.ifBlank { context.getString(R.string.player_unknown_title) }
            binding.downloadTitle.text = title
            binding.downloadArtist.text = item.artist.ifBlank {
                context.getString(R.string.browse_artist_unknown)
            }
            binding.downloadCover.contentDescription = context.getString(
                R.string.downloads_cover_description,
                title,
            )

            val cover = app.downloads.localCover(item.videoId, item.coverPath)
            if (cover != null) {
                binding.downloadCover.load(File(cover.absolutePath)) {
                    crossfade(true)
                    placeholder(R.drawable.bg_cover_placeholder)
                    error(R.drawable.bg_cover_placeholder)
                }
            } else if (!item.thumbnailUrl.isNullOrBlank()) {
                binding.downloadCover.load(item.thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_cover_placeholder)
                    error(R.drawable.bg_cover_placeholder)
                }
            } else {
                binding.downloadCover.setImageResource(R.drawable.bg_cover_placeholder)
            }

            bindStatus(item)
            bindAction(item)

            binding.root.setOnClickListener {
                when (item.status) {
                    DownloadStatus.DONE -> onPlay(item)
                    DownloadStatus.FAILED -> onRetry(item)
                    else -> Unit
                }
            }
            binding.root.setOnLongClickListener {
                onMore(item)
                true
            }
        }

        private fun bindStatus(item: DownloadEntity) {
            val context = binding.root.context
            when (item.status) {
                DownloadStatus.QUEUED -> {
                    binding.downloadStatus.setText(R.string.downloads_status_queued)
                    binding.downloadProgress.isVisible = true
                    binding.downloadProgress.isIndeterminate = true
                }
                DownloadStatus.DOWNLOADING -> {
                    binding.downloadProgress.isVisible = true
                    if (item.totalBytes > 0L) {
                        val percent = (item.bytes * 100 / item.totalBytes)
                            .coerceIn(0, 100).toInt()
                        binding.downloadProgress.isIndeterminate = false
                        binding.downloadProgress.setProgressCompat(percent, true)
                        binding.downloadStatus.text = context.getString(
                            R.string.downloads_status_downloading,
                            percent,
                        )
                    } else {
                        binding.downloadProgress.isIndeterminate = true
                        binding.downloadStatus.setText(R.string.downloads_status_waiting)
                    }
                }
                DownloadStatus.DONE -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadStatus.text = formatBytes(item.bytes)
                }
                DownloadStatus.FAILED -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadStatus.text = item.error
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.downloads_status_failed)
                }
                else -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadStatus.setText(R.string.downloads_status_failed)
                }
            }
        }

        private fun bindAction(item: DownloadEntity) {
            when (item.status) {
                DownloadStatus.DONE, DownloadStatus.FAILED -> {
                    binding.downloadAction.visibility = View.VISIBLE
                    binding.downloadAction.setImageResource(R.drawable.ic_close)
                    binding.downloadAction.contentDescription =
                        binding.root.context.getString(R.string.downloads_remove)
                    binding.downloadAction.setOnClickListener { onRemove(item) }
                }
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                    binding.downloadAction.visibility = View.VISIBLE
                    binding.downloadAction.setImageResource(R.drawable.ic_close)
                    binding.downloadAction.contentDescription =
                        binding.root.context.getString(R.string.downloads_cancel)
                    binding.downloadAction.setOnClickListener { onCancel(item) }
                }
                else -> {
                    binding.downloadAction.visibility = View.GONE
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadEntity>() {
        override fun areItemsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity): Boolean =
            oldItem.userId == newItem.userId && oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity): Boolean =
            oldItem == newItem
    }
}

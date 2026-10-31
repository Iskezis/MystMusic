package com.iskezis.myst.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemQueueHeaderBinding
import com.iskezis.myst.databinding.ItemQueueTrackBinding
import com.iskezis.myst.util.formatDuration

/** Cabeçalhos e faixas exibidos na fila, sempre começando pela atual. */
sealed interface QueueListItem {
    data class Header(@StringRes val titleRes: Int) : QueueListItem
    data class Entry(
        val track: Track,
        val queueIndex: Int,
        val isCurrent: Boolean,
    ) : QueueListItem
}

class QueueAdapter(
    private val onTrackClick: (Int) -> Unit,
) : ListAdapter<QueueListItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is QueueListItem.Header -> TYPE_HEADER
        is QueueListItem.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                ItemQueueHeaderBinding.inflate(inflater, parent, false),
            )
            else -> EntryHolder(
                ItemQueueTrackBinding.inflate(inflater, parent, false),
                onTrackClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is QueueListItem.Header -> (holder as HeaderHolder).bind(item)
            is QueueListItem.Entry -> (holder as EntryHolder).bind(item)
        }
    }

    private class HeaderHolder(
        private val binding: ItemQueueHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QueueListItem.Header) {
            binding.queueHeaderTitle.setText(item.titleRes)
        }
    }

    private class EntryHolder(
        private val binding: ItemQueueTrackBinding,
        private val onTrackClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QueueListItem.Entry) {
            val context = binding.root.context
            val track = item.track
            binding.queueRowIndex.text = (item.queueIndex + 1).toString()
            binding.queueRowTitle.text = track.title.ifBlank {
                context.getString(R.string.player_unknown_title)
            }
            binding.queueRowArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.queueRowDuration.text = formatDuration(track.durationMs)
            binding.queueRowCurrent.isVisible = item.isCurrent
            binding.queueRow.isSelected = item.isCurrent
            binding.queueRowCover.load(track.artworkUrl()) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.queueRow.setOnClickListener {
                onTrackClick(item.queueIndex)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<QueueListItem>() {
        override fun areItemsTheSame(oldItem: QueueListItem, newItem: QueueListItem): Boolean =
            when {
                oldItem is QueueListItem.Header && newItem is QueueListItem.Header ->
                    oldItem.titleRes == newItem.titleRes
                oldItem is QueueListItem.Entry && newItem is QueueListItem.Entry ->
                    oldItem.queueIndex == newItem.queueIndex &&
                        oldItem.track.videoId == newItem.track.videoId
                else -> false
            }

        override fun areContentsTheSame(oldItem: QueueListItem, newItem: QueueListItem): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }
}

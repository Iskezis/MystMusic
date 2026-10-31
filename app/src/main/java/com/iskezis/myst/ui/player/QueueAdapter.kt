package com.iskezis.myst.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemQueueTrackBinding
import com.iskezis.myst.util.formatDuration

class QueueAdapter(
    private val onTrackClick: (Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    private var items: List<Track> = emptyList()
    private var currentIndex: Int = -1

    fun submit(tracks: List<Track>, current: Int) {
        items = tracks
        currentIndex = current
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position, position == currentIndex)
    }

    inner class ViewHolder(
        private val binding: ItemQueueTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, position: Int, isCurrent: Boolean) {
            val context = binding.root.context
            binding.queueRowIndex.text = (position + 1).toString()
            binding.queueRowTitle.text = track.title.ifBlank {
                context.getString(R.string.player_unknown_title)
            }
            binding.queueRowArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.queueRowDuration.text = formatDuration(track.durationMs)
            binding.queueRowCurrent.isVisible = isCurrent
            binding.queueRow.isSelected = isCurrent
            binding.queueRowCover.load(track.artworkUrl()) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.queueRow.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onTrackClick(index)
            }
        }
    }
}

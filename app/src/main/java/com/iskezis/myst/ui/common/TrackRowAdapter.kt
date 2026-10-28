package com.iskezis.myst.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemTrackRowBinding
import com.iskezis.myst.util.formatDuration

class TrackRowAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
) : ListAdapter<Track, TrackRowAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTrackRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), currentList, onPlay, onMore)
    }

    class Holder(private val binding: ItemTrackRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            track: Track,
            tracks: List<Track>,
            onPlay: (List<Track>, Int) -> Unit,
            onMore: (Track) -> Unit,
        ) {
            val context = binding.root.context
            binding.rowTitle.text = track.title.ifBlank { context.getString(R.string.player_unknown_title) }
            binding.rowArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.rowCover.contentDescription = context.getString(R.string.content_cover, track.title)
            binding.rowCover.load(track.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.rowDuration.text = formatDuration(track.durationMs)
            binding.rowDuration.visibility = if (track.durationMs > 0) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                val index = tracks.indexOfFirst { it.videoId == track.videoId }
                onPlay(tracks, if (index >= 0) index else 0)
            }
            binding.rowMore.setOnClickListener { onMore(track) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
    }
}

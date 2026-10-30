package com.iskezis.myst.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemTrackRowBinding

class TrackRowAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
    private val onArtistClick: ((Track) -> Unit)? = null,
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
        holder.bind(getItem(position), currentList, onPlay, onMore, onArtistClick)
    }

    class Holder(private val binding: ItemTrackRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            track: Track,
            tracks: List<Track>,
            onPlay: (List<Track>, Int) -> Unit,
            onMore: (Track) -> Unit,
            onArtistClick: ((Track) -> Unit)?,
        ) {
            TrackRowBinder.bind(binding, track, tracks, onPlay, onMore, onArtistClick)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
    }
}

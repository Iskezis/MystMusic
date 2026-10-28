package com.iskezis.myst.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemTrackCardBinding

class TrackCardAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
) : ListAdapter<Track, TrackCardAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTrackCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), currentList, onPlay, onMore)
    }

    class Holder(private val binding: ItemTrackCardBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            track: Track,
            tracks: List<Track>,
            onPlay: (List<Track>, Int) -> Unit,
            onMore: (Track) -> Unit,
        ) {
            binding.cardTitle.text = track.title.ifBlank {
                binding.root.context.getString(R.string.player_unknown_title)
            }
            binding.cardArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.cardCover.contentDescription = binding.root.context.getString(
                R.string.content_cover,
                track.title,
            )
            binding.cardCover.load(track.artworkUrl()) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.cardRoot.setOnClickListener {
                val index = tracks.indexOfFirst { it.videoId == track.videoId }
                onPlay(tracks, if (index >= 0) index else 0)
            }
            binding.cardMore.setOnClickListener { onMore(track) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
    }
}

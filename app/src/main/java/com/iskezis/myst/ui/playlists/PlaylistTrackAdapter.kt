package com.iskezis.myst.ui.playlists

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemPlaylistTrackBinding
import com.iskezis.myst.util.formatDuration

class PlaylistTrackAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onRemove: (Track, Int) -> Unit,
    private val editable: Boolean = true,
) : RecyclerView.Adapter<PlaylistTrackAdapter.Holder>() {

    private var items: List<Track> = emptyList()
    var onDragStart: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun submit(list: List<Track>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].videoId == list[newPos].videoId
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos] == list[newPos]
        })
        items = list.toList()
        diff.dispatchUpdatesTo(this)
    }

    fun current(): List<Track> = items
    fun itemAt(position: Int): Track? = items.getOrNull(position)

    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        items = mutable
        notifyItemMoved(from, to)
        return true
    }

    fun removeAt(position: Int): Track? {
        if (position !in items.indices) return null
        val mutable = items.toMutableList()
        val removed = mutable.removeAt(position)
        items = mutable
        notifyItemRemoved(position)
        return removed
    }

    fun insert(position: Int, track: Track) {
        val mutable = items.toMutableList()
        val safe = position.coerceIn(0, mutable.size)
        mutable.add(safe, track)
        items = mutable
        notifyItemInserted(safe)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPlaylistTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], items, onPlay, onRemove, editable)
        if (editable) {
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart?.invoke(holder)
                }
                false
            }
        } else {
            holder.dragHandle.setOnTouchListener(null)
        }
    }

    class Holder(val binding: ItemPlaylistTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        val dragHandle: View get() = binding.trackDrag

        fun bind(
            track: Track,
            tracks: List<Track>,
            onPlay: (List<Track>, Int) -> Unit,
            onRemove: (Track, Int) -> Unit,
            editable: Boolean,
        ) {
            val context = binding.root.context
            binding.trackDrag.isVisible = editable
            binding.trackMore.isVisible = editable
            binding.trackTitle.text = track.title.ifBlank {
                context.getString(R.string.player_unknown_title)
            }
            binding.trackArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.trackCover.contentDescription = context.getString(
                R.string.content_cover,
                track.title,
            )
            binding.trackCover.load(track.artworkUrl()) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.trackDuration.text = formatDuration(track.durationMs)
            binding.trackDuration.visibility =
                if (track.durationMs > 0) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                val index = tracks.indexOfFirst { it.videoId == track.videoId }
                onPlay(tracks, if (index >= 0) index else 0)
            }
            binding.trackMore.setOnClickListener(
                if (editable) {
                    View.OnClickListener {
                        val index = bindingAdapterPosition
                        if (index != RecyclerView.NO_POSITION) {
                            onRemove(track, index)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

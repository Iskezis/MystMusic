package com.iskezis.myst.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemRecentTileBinding

/**
 * Recentes compactos: quatro capas visíveis por vez e o restante ao deslizar
 * para o lado, sempre do mais recente para o mais antigo.
 */
class RecentTileAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
) : ListAdapter<Track, RecentTileAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRecentTileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        val resources = parent.resources
        val gap = resources.getDimensionPixelSize(R.dimen.space_s)
        val margins = resources.getDimensionPixelSize(R.dimen.screen_margin) * 2
        val available = resources.displayMetrics.widthPixels - margins - gap * (COLUMNS - 1)
        val width = (available / COLUMNS).coerceAtLeast(1)
        binding.root.layoutParams = RecyclerView.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = gap
        }
        return Holder(binding, onPlay, onMore)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), currentList)
    }

    class Holder(
        private val binding: ItemRecentTileBinding,
        private val onPlay: (List<Track>, Int) -> Unit,
        private val onMore: (Track) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, tracks: List<Track>) {
            val context = binding.root.context
            val title = track.title.ifBlank {
                context.getString(R.string.player_unknown_title)
            }
            binding.recentTitle.text = title
            binding.recentArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.recentCover.contentDescription = context.getString(
                R.string.content_cover,
                title,
            )
            binding.recentCover.load(track.artworkUrl()) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.recentDownloadBadge.videoId = track.videoId
            binding.root.contentDescription = context.getString(R.string.content_song, title)
            binding.root.setOnClickListener {
                val index = tracks.indexOfFirst { it.videoId == track.videoId }
                onPlay(tracks, index.coerceAtLeast(0))
            }
            binding.root.setOnLongClickListener {
                onMore(track)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
    }

    private companion object {
        const val COLUMNS = 4
    }
}

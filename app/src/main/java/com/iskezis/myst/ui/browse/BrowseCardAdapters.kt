package com.iskezis.myst.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.AlbumSummary
import com.iskezis.myst.data.youtube.ArtistSummary
import com.iskezis.myst.data.youtube.PlaylistSummary
import com.iskezis.myst.databinding.ItemArtistCardBinding
import com.iskezis.myst.databinding.ItemBrowseCardBinding

class AlbumCardAdapter(
    private val onClick: (AlbumSummary) -> Unit,
) : ListAdapter<AlbumSummary, AlbumCardAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBrowseCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class Holder(
        private val binding: ItemBrowseCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: AlbumSummary, onClick: (AlbumSummary) -> Unit) {
            val context = binding.root.context
            binding.browseCardTitle.text = album.title
            binding.browseCardSubtitle.text = listOf(album.artist, album.year)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { context.getString(R.string.browse_album) }
            binding.browseCardCover.contentDescription = context.getString(
                R.string.content_cover,
                album.title,
            )
            binding.browseCardCover.load(album.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.root.setOnClickListener { onClick(album) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AlbumSummary>() {
        override fun areItemsTheSame(oldItem: AlbumSummary, newItem: AlbumSummary): Boolean =
            oldItem.browseId == newItem.browseId

        override fun areContentsTheSame(oldItem: AlbumSummary, newItem: AlbumSummary): Boolean =
            oldItem == newItem
    }
}

class PlaylistCardAdapter(
    private val onClick: (PlaylistSummary) -> Unit,
) : ListAdapter<PlaylistSummary, PlaylistCardAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBrowseCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class Holder(
        private val binding: ItemBrowseCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistSummary, onClick: (PlaylistSummary) -> Unit) {
            val context = binding.root.context
            binding.browseCardTitle.text = playlist.title
            val count = if (playlist.trackCount > 0) {
                context.getString(R.string.browse_playlist_meta, playlist.trackCount)
            } else {
                ""
            }
            binding.browseCardSubtitle.text = listOf(playlist.author, count)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { context.getString(R.string.browse_playlist) }
            binding.browseCardCover.contentDescription = context.getString(
                R.string.content_cover,
                playlist.title,
            )
            binding.browseCardCover.load(playlist.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_playlist_placeholder)
                error(R.drawable.bg_playlist_placeholder)
            }
            binding.root.setOnClickListener { onClick(playlist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistSummary>() {
        override fun areItemsTheSame(
            oldItem: PlaylistSummary,
            newItem: PlaylistSummary,
        ): Boolean = oldItem.browseId == newItem.browseId

        override fun areContentsTheSame(
            oldItem: PlaylistSummary,
            newItem: PlaylistSummary,
        ): Boolean = oldItem == newItem
    }
}

class ArtistCardAdapter(
    private val onClick: (ArtistSummary) -> Unit,
) : ListAdapter<ArtistSummary, ArtistCardAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemArtistCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class Holder(
        private val binding: ItemArtistCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: ArtistSummary, onClick: (ArtistSummary) -> Unit) {
            val context = binding.root.context
            binding.artistCardName.text = artist.name
            binding.artistCardSubtitle.text = artist.subtitle.ifBlank {
                context.getString(R.string.browse_artist)
            }
            binding.artistCardCover.contentDescription = context.getString(
                R.string.content_cover,
                artist.name,
            )
            binding.artistCardCover.load(artist.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.root.setOnClickListener { onClick(artist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ArtistSummary>() {
        override fun areItemsTheSame(oldItem: ArtistSummary, newItem: ArtistSummary): Boolean =
            oldItem.browseId == newItem.browseId

        override fun areContentsTheSame(oldItem: ArtistSummary, newItem: ArtistSummary): Boolean =
            oldItem == newItem
    }
}

package com.iskezis.myst.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.databinding.ItemPlaylistCardBinding
import com.iskezis.myst.databinding.ItemPlaylistNewBinding

sealed interface PlaylistGridItem {
    data object New : PlaylistGridItem
    data class Item(val playlist: PlaylistEntity) : PlaylistGridItem
}

class PlaylistGridAdapter(
    private val onNew: () -> Unit,
    private val onOpen: (PlaylistEntity) -> Unit,
) : ListAdapter<PlaylistGridItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is PlaylistGridItem.New) TYPE_NEW else TYPE_PLAYLIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_NEW) {
            NewHolder(ItemPlaylistNewBinding.inflate(inflater, parent, false), onNew)
        } else {
            PlaylistHolder(ItemPlaylistCardBinding.inflate(inflater, parent, false), onOpen)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PlaylistGridItem.New -> Unit
            is PlaylistGridItem.Item -> (holder as PlaylistHolder).bind(item.playlist)
        }
    }

    private class NewHolder(
        private val binding: ItemPlaylistNewBinding,
        onNew: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.newPlaylistCard.setOnClickListener { onNew() }
            binding.newPlaylistCard.contentDescription =
                binding.root.context.getString(R.string.playlists_new)
        }
    }

    private class PlaylistHolder(
        private val binding: ItemPlaylistCardBinding,
        private val onOpen: (PlaylistEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistEntity) {
            val context = binding.root.context
            binding.playlistName.text = playlist.name
            binding.playlistInitial.text = playlist.name.trim().take(1).uppercase()
            binding.playlistInitial.visibility =
                if (playlist.coverUri.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.playlistCover.contentDescription = context.getString(
                R.string.playlist_default_cover,
            )
            if (playlist.coverUri.isNullOrBlank()) {
                binding.playlistCover.setImageDrawable(null)
            } else {
                binding.playlistCover.load(playlist.coverUri) {
                    crossfade(true)
                    placeholder(R.drawable.bg_playlist_placeholder)
                    error(R.drawable.bg_playlist_placeholder)
                }
            }
            binding.playlistCard.contentDescription = playlist.name
            binding.playlistCard.setOnClickListener { onOpen(playlist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistGridItem>() {
        override fun areItemsTheSame(oldItem: PlaylistGridItem, newItem: PlaylistGridItem): Boolean =
            when {
                oldItem is PlaylistGridItem.New && newItem is PlaylistGridItem.New -> true
                oldItem is PlaylistGridItem.Item && newItem is PlaylistGridItem.Item ->
                    oldItem.playlist.id == newItem.playlist.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: PlaylistGridItem, newItem: PlaylistGridItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_NEW = 0
        private const val TYPE_PLAYLIST = 1
    }
}

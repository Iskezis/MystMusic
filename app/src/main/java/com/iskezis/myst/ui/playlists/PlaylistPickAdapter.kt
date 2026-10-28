package com.iskezis.myst.ui.playlists

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.databinding.ItemPlaylistPickBinding

class PlaylistPickAdapter(
    private val onClick: (PlaylistEntity) -> Unit,
) : ListAdapter<PlaylistEntity, PlaylistPickAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPlaylistPickBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding, onClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        private val binding: ItemPlaylistPickBinding,
        private val onClick: (PlaylistEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistEntity) {
            binding.pickName.text = playlist.name
            binding.pickCover.contentDescription = binding.root.context.getString(
                R.string.playlist_default_cover,
            )
            if (playlist.coverUri.isNullOrBlank()) {
                binding.pickCover.setImageResource(R.drawable.bg_playlist_placeholder)
            } else {
                binding.pickCover.load(playlist.coverUri) {
                    crossfade(true)
                    placeholder(R.drawable.bg_playlist_placeholder)
                    error(R.drawable.bg_playlist_placeholder)
                }
            }
            binding.root.setOnClickListener { onClick(playlist) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistEntity>() {
        override fun areItemsTheSame(oldItem: PlaylistEntity, newItem: PlaylistEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PlaylistEntity, newItem: PlaylistEntity): Boolean =
            oldItem == newItem
    }
}

package com.iskezis.myst.ui.downloads

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.databinding.ItemCacheTrackBinding

class CacheAdapter(
    private val onPlay: (CachedTrackItem) -> Unit,
    private val onRemove: (CachedTrackItem) -> Unit,
) : ListAdapter<CachedTrackItem, CacheAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCacheTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onPlay, onRemove)
    }

    class Holder(private val binding: ItemCacheTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: CachedTrackItem,
            onPlay: (CachedTrackItem) -> Unit,
            onRemove: (CachedTrackItem) -> Unit,
        ) {
            val context = binding.root.context
            binding.cacheTitle.text = item.title.ifBlank {
                context.getString(R.string.cache_unknown_title)
            }
            binding.cacheArtist.text = item.artist.ifBlank {
                context.getString(R.string.cache_unknown_artist)
            }
            binding.cacheSize.text = formatBytes(item.bytes)
            binding.cacheCover.contentDescription = context.getString(
                R.string.content_cover,
                item.title,
            )
            binding.cacheCover.load(item.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.cacheRow.setOnClickListener { onPlay(item) }
            binding.cacheRemove.setOnClickListener { onRemove(item) }
            binding.cacheRemove.contentDescription = context.getString(
                R.string.cache_remove,
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<CachedTrackItem>() {
        override fun areItemsTheSame(oldItem: CachedTrackItem, newItem: CachedTrackItem): Boolean =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(
            oldItem: CachedTrackItem,
            newItem: CachedTrackItem,
        ): Boolean = oldItem == newItem
    }
}

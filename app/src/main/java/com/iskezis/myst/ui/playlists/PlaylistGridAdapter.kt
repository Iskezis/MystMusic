package com.iskezis.myst.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.databinding.ItemPlaylistCardBinding
import com.iskezis.myst.databinding.ItemPlaylistNewBinding
import com.iskezis.myst.databinding.ItemPlaylistSectionHeaderBinding
import com.iskezis.myst.databinding.ItemPlaylistSectionLoadingBinding
import com.iskezis.myst.databinding.ItemPlaylistSectionStateBinding
import com.iskezis.myst.databinding.ItemYtPlaylistCardBinding

sealed interface PlaylistGridItem {
    data object New : PlaylistGridItem

    data class Item(val playlist: PlaylistEntity) : PlaylistGridItem

    data class Header(
        @StringRes val titleRes: Int,
        val subtitle: String? = null,
        @StringRes val actionRes: Int? = null,
    ) : PlaylistGridItem

    data class YtItem(val playlist: YtPlaylistEntity) : PlaylistGridItem

    data class Status(
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int? = null,
        val iconRes: Int = R.drawable.ic_cloud_off,
        val retry: Boolean = false,
    ) : PlaylistGridItem

    data object Loading : PlaylistGridItem
}

class PlaylistGridAdapter(
    private val onNew: () -> Unit,
    private val onOpen: (PlaylistEntity) -> Unit,
    private val onOpenYt: (YtPlaylistEntity) -> Unit,
    private val onRetryYoutube: () -> Unit,
) : ListAdapter<PlaylistGridItem, RecyclerView.ViewHolder>(Diff) {

    fun isFullSpan(position: Int): Boolean = when (currentList.getOrNull(position)) {
        is PlaylistGridItem.Header,
        is PlaylistGridItem.Status,
        is PlaylistGridItem.Loading,
        -> true
        null -> true
        else -> false
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PlaylistGridItem.New -> TYPE_NEW
        is PlaylistGridItem.Item -> TYPE_PLAYLIST
        is PlaylistGridItem.Header -> TYPE_HEADER
        is PlaylistGridItem.YtItem -> TYPE_YT_PLAYLIST
        is PlaylistGridItem.Status -> TYPE_STATUS
        is PlaylistGridItem.Loading -> TYPE_LOADING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_NEW -> NewHolder(ItemPlaylistNewBinding.inflate(inflater, parent, false), onNew)
            TYPE_PLAYLIST -> PlaylistHolder(
                ItemPlaylistCardBinding.inflate(inflater, parent, false),
                onOpen,
            )
            TYPE_HEADER -> HeaderHolder(
                ItemPlaylistSectionHeaderBinding.inflate(inflater, parent, false),
                onRetryYoutube,
            )
            TYPE_YT_PLAYLIST -> YtPlaylistHolder(
                ItemYtPlaylistCardBinding.inflate(inflater, parent, false),
                onOpenYt,
            )
            TYPE_STATUS -> StatusHolder(
                ItemPlaylistSectionStateBinding.inflate(inflater, parent, false),
                onRetryYoutube,
            )
            else -> LoadingHolder(
                ItemPlaylistSectionLoadingBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PlaylistGridItem.New -> Unit
            is PlaylistGridItem.Item -> (holder as PlaylistHolder).bind(item.playlist)
            is PlaylistGridItem.Header -> (holder as HeaderHolder).bind(item)
            is PlaylistGridItem.YtItem -> (holder as YtPlaylistHolder).bind(item.playlist)
            is PlaylistGridItem.Status -> (holder as StatusHolder).bind(item)
            is PlaylistGridItem.Loading -> (holder as LoadingHolder).bind()
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
            binding.playlistInitial.isVisible = playlist.coverUri.isNullOrBlank()
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

    private class HeaderHolder(
        private val binding: ItemPlaylistSectionHeaderBinding,
        private val onRetryYoutube: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlaylistGridItem.Header) {
            val context = binding.root.context
            binding.sectionTitle.setText(item.titleRes)
            binding.sectionSubtitle.isVisible = item.subtitle != null
            binding.sectionSubtitle.text = item.subtitle.orEmpty()
            val action = item.actionRes
            binding.sectionAction.isVisible = action != null
            if (action != null) {
                binding.sectionAction.setText(action)
                binding.sectionAction.setOnClickListener { onRetryYoutube() }
            } else {
                binding.sectionAction.setOnClickListener(null)
            }
            binding.root.contentDescription = context.getString(item.titleRes)
        }
    }

    private class YtPlaylistHolder(
        private val binding: ItemYtPlaylistCardBinding,
        private val onOpen: (YtPlaylistEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: YtPlaylistEntity) {
            val context = binding.root.context
            binding.ytPlaylistName.text = playlist.title
            binding.ytPlaylistInitial.text = playlist.title.trim().take(1).uppercase()
            val hasCover = !playlist.thumbnailUrl.isNullOrBlank()
            binding.ytPlaylistInitial.isVisible = !hasCover
            binding.ytPlaylistCover.contentDescription = context.getString(
                R.string.content_cover,
                playlist.title,
            )
            if (hasCover) {
                binding.ytPlaylistCover.load(playlist.thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_playlist_placeholder)
                    error(R.drawable.bg_playlist_placeholder)
                }
            } else {
                binding.ytPlaylistCover.setImageDrawable(null)
            }
            binding.ytPlaylistCount.isVisible = playlist.trackCount > 0
            if (playlist.trackCount > 0) {
                binding.ytPlaylistCount.text = context.getString(
                    R.string.playlist_track_count,
                    playlist.trackCount,
                )
            }
            binding.ytPlaylistCard.contentDescription = playlist.title
            binding.ytPlaylistCard.setOnClickListener { onOpen(playlist) }
        }
    }

    private class StatusHolder(
        private val binding: ItemPlaylistSectionStateBinding,
        private val onRetryYoutube: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlaylistGridItem.Status) {
            val context = binding.root.context
            binding.sectionStateIcon.setImageResource(item.iconRes)
            binding.sectionStateTitle.setText(item.titleRes)
            binding.sectionStateSubtitle.isVisible = item.subtitleRes != null
            item.subtitleRes?.let(binding.sectionStateSubtitle::setText)
            binding.sectionStateRetry.isVisible = item.retry
            binding.sectionStateRetry.setOnClickListener(
                if (item.retry) View.OnClickListener { onRetryYoutube() } else null,
            )
            binding.root.contentDescription = context.getString(item.titleRes)
        }
    }

    private class LoadingHolder(
        private val binding: ItemPlaylistSectionLoadingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.root.startShimmer()
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistGridItem>() {
        override fun areItemsTheSame(oldItem: PlaylistGridItem, newItem: PlaylistGridItem): Boolean =
            when {
                oldItem is PlaylistGridItem.New && newItem is PlaylistGridItem.New -> true
                oldItem is PlaylistGridItem.Item && newItem is PlaylistGridItem.Item ->
                    oldItem.playlist.id == newItem.playlist.id
                oldItem is PlaylistGridItem.Header && newItem is PlaylistGridItem.Header ->
                    oldItem.titleRes == newItem.titleRes
                oldItem is PlaylistGridItem.YtItem && newItem is PlaylistGridItem.YtItem ->
                    oldItem.playlist.ytId == newItem.playlist.ytId
                oldItem is PlaylistGridItem.Status && newItem is PlaylistGridItem.Status ->
                    oldItem.titleRes == newItem.titleRes
                oldItem is PlaylistGridItem.Loading && newItem is PlaylistGridItem.Loading -> true
                else -> false
            }

        override fun areContentsTheSame(oldItem: PlaylistGridItem, newItem: PlaylistGridItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_NEW = 0
        private const val TYPE_PLAYLIST = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_YT_PLAYLIST = 3
        private const val TYPE_STATUS = 4
        private const val TYPE_LOADING = 5
    }
}

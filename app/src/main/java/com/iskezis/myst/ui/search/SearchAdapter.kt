package com.iskezis.myst.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.AlbumSummary
import com.iskezis.myst.data.youtube.ArtistSummary
import com.iskezis.myst.data.youtube.PlaylistSummary
import com.iskezis.myst.data.youtube.SearchFilter
import com.iskezis.myst.data.youtube.SearchPage
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemSearchAlbumBinding
import com.iskezis.myst.databinding.ItemSearchArtistBinding
import com.iskezis.myst.databinding.ItemSearchMoreBinding
import com.iskezis.myst.databinding.ItemSearchPlaylistBinding
import com.iskezis.myst.databinding.ItemSectionHeaderBinding
import com.iskezis.myst.databinding.ItemTrackRowBinding
import com.iskezis.myst.ui.common.TrackRowBinder

internal sealed interface SearchItem {
    data class Header(val key: String, @StringRes val titleRes: Int) : SearchItem
    data class TrackItem(val track: Track) : SearchItem
    data class ArtistItem(val artist: ArtistSummary) : SearchItem
    data class AlbumItem(val album: AlbumSummary) : SearchItem
    data class PlaylistItem(val playlist: PlaylistSummary) : SearchItem
    data class LoadMore(val loading: Boolean, val error: Boolean) : SearchItem
}

/**
 * Lista misturada da busca: cabeçalhos de seção, faixas, artistas, álbuns,
 * playlists e o rodapé de continuação. O filtro ativo decide quais seções
 * aparecem; em "Tudo" as quatro aparecem na ordem em que são lidas.
 */
internal class SearchAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
    private val onArtistClick: (Track) -> Unit,
    private val onArtist: (ArtistSummary) -> Unit,
    private val onAlbum: (AlbumSummary) -> Unit,
    private val onPlaylist: (PlaylistSummary) -> Unit,
    private val onLoadMore: () -> Unit,
) : ListAdapter<SearchItem, RecyclerView.ViewHolder>(Diff) {

    private var tracks: List<Track> = emptyList()

    fun submit(
        page: SearchPage,
        filter: SearchFilter,
        loadingMore: Boolean,
        loadMoreError: Boolean,
    ) {
        tracks = page.tracks
        submitList(buildItems(page, filter, loadingMore, loadMoreError))
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchItem.Header -> TYPE_HEADER
        is SearchItem.TrackItem -> TYPE_TRACK
        is SearchItem.ArtistItem -> TYPE_ARTIST
        is SearchItem.AlbumItem -> TYPE_ALBUM
        is SearchItem.PlaylistItem -> TYPE_PLAYLIST
        is SearchItem.LoadMore -> TYPE_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                ItemSectionHeaderBinding.inflate(inflater, parent, false),
            )
            TYPE_TRACK -> TrackHolder(
                ItemTrackRowBinding.inflate(inflater, parent, false),
            )
            TYPE_ARTIST -> ArtistHolder(
                ItemSearchArtistBinding.inflate(inflater, parent, false),
            )
            TYPE_ALBUM -> AlbumHolder(
                ItemSearchAlbumBinding.inflate(inflater, parent, false),
            )
            TYPE_PLAYLIST -> PlaylistHolder(
                ItemSearchPlaylistBinding.inflate(inflater, parent, false),
            )
            else -> MoreHolder(
                ItemSearchMoreBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchItem.Header -> (holder as HeaderHolder).bind(item)
            is SearchItem.TrackItem -> (holder as TrackHolder).bind(item.track)
            is SearchItem.ArtistItem -> (holder as ArtistHolder).bind(item.artist)
            is SearchItem.AlbumItem -> (holder as AlbumHolder).bind(item.album)
            is SearchItem.PlaylistItem -> (holder as PlaylistHolder).bind(item.playlist)
            is SearchItem.LoadMore -> (holder as MoreHolder).bind(item)
        }
    }

    private fun buildItems(
        page: SearchPage,
        filter: SearchFilter,
        loadingMore: Boolean,
        loadMoreError: Boolean,
    ): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        fun addTracks() {
            if (page.tracks.isEmpty()) return
            items.add(SearchItem.Header("songs", R.string.search_section_songs))
            page.tracks.forEach { items.add(SearchItem.TrackItem(it)) }
        }
        fun addArtists() {
            if (page.artists.isEmpty()) return
            items.add(SearchItem.Header("artists", R.string.search_section_artists))
            page.artists.forEach { items.add(SearchItem.ArtistItem(it)) }
        }
        fun addAlbums() {
            if (page.albums.isEmpty()) return
            items.add(SearchItem.Header("albums", R.string.search_section_albums))
            page.albums.forEach { items.add(SearchItem.AlbumItem(it)) }
        }
        fun addPlaylists() {
            if (page.playlists.isEmpty()) return
            items.add(SearchItem.Header("playlists", R.string.search_section_playlists))
            page.playlists.forEach { items.add(SearchItem.PlaylistItem(it)) }
        }

        when (filter) {
            SearchFilter.ALL -> {
                addTracks()
                addArtists()
                addAlbums()
                addPlaylists()
            }
            SearchFilter.SONGS -> addTracks()
            SearchFilter.ARTISTS -> addArtists()
            SearchFilter.ALBUMS -> addAlbums()
            SearchFilter.PLAYLISTS -> addPlaylists()
        }
        if (page.continuation != null) {
            items.add(SearchItem.LoadMore(loadingMore, loadMoreError))
        }
        return items
    }

    private class HeaderHolder(
        private val binding: ItemSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchItem.Header) {
            binding.sectionTitle.setText(item.titleRes)
            binding.sectionSubtitle.isVisible = false
        }
    }

    private inner class TrackHolder(
        private val binding: ItemTrackRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: Track) {
            TrackRowBinder.bind(
                binding = binding,
                track = track,
                tracks = tracks,
                onPlay = onPlay,
                onMore = onMore,
                onArtistClick = onArtistClick,
            )
        }
    }

    private inner class ArtistHolder(
        private val binding: ItemSearchArtistBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(artist: ArtistSummary) {
            val context = binding.root.context
            binding.searchArtistName.text = artist.name
            binding.searchArtistSubtitle.text = artist.subtitle.ifBlank {
                context.getString(R.string.browse_artist)
            }
            binding.searchArtistCover.contentDescription = context.getString(
                R.string.content_cover,
                artist.name,
            )
            binding.searchArtistCover.load(artist.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.root.setOnClickListener { onArtist(artist) }
        }
    }

    private inner class AlbumHolder(
        private val binding: ItemSearchAlbumBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: AlbumSummary) {
            val context = binding.root.context
            binding.searchAlbumTitle.text = album.title
            binding.searchAlbumSubtitle.text = listOf(album.artist, album.year)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { context.getString(R.string.browse_album) }
            binding.searchAlbumCover.contentDescription = context.getString(
                R.string.content_cover,
                album.title,
            )
            binding.searchAlbumCover.load(album.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.root.setOnClickListener { onAlbum(album) }
        }
    }

    private inner class PlaylistHolder(
        private val binding: ItemSearchPlaylistBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(playlist: PlaylistSummary) {
            val context = binding.root.context
            binding.searchPlaylistTitle.text = playlist.title
            val count = if (playlist.trackCount > 0) {
                context.getString(R.string.browse_playlist_meta, playlist.trackCount)
            } else {
                ""
            }
            binding.searchPlaylistSubtitle.text = listOf(playlist.author, count)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { context.getString(R.string.browse_playlist) }
            binding.searchPlaylistCover.contentDescription = context.getString(
                R.string.content_cover,
                playlist.title,
            )
            binding.searchPlaylistCover.load(playlist.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_playlist_placeholder)
                error(R.drawable.bg_playlist_placeholder)
            }
            binding.root.setOnClickListener { onPlaylist(playlist) }
        }
    }

    private inner class MoreHolder(
        private val binding: ItemSearchMoreBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchItem.LoadMore) {
            val context = binding.root.context
            val loading = item.loading
            binding.searchMoreProgress.isVisible = loading
            binding.searchMoreText.setText(
                when {
                    loading -> R.string.search_loading_more
                    item.error -> R.string.state_retry
                    else -> R.string.search_load_more
                },
            )
            val colorAttr = if (item.error) R.attr.mystError else R.attr.mystAccentText
            binding.searchMoreText.setTextColor(
                MaterialColors.getColor(binding.searchMoreText, colorAttr),
            )
            binding.root.isClickable = !loading
            binding.root.isFocusable = !loading
            binding.root.setOnClickListener(
                if (loading) null else View.OnClickListener { onLoadMore() },
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchItem>() {
        override fun areItemsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean =
            when {
                oldItem is SearchItem.Header && newItem is SearchItem.Header ->
                    oldItem.key == newItem.key
                oldItem is SearchItem.TrackItem && newItem is SearchItem.TrackItem ->
                    oldItem.track.videoId == newItem.track.videoId
                oldItem is SearchItem.ArtistItem && newItem is SearchItem.ArtistItem ->
                    oldItem.artist.browseId == newItem.artist.browseId
                oldItem is SearchItem.AlbumItem && newItem is SearchItem.AlbumItem ->
                    oldItem.album.browseId == newItem.album.browseId
                oldItem is SearchItem.PlaylistItem && newItem is SearchItem.PlaylistItem ->
                    oldItem.playlist.browseId == newItem.playlist.browseId
                oldItem is SearchItem.LoadMore && newItem is SearchItem.LoadMore -> true
                else -> false
            }

        override fun areContentsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TRACK = 1
        const val TYPE_ARTIST = 2
        const val TYPE_ALBUM = 3
        const val TYPE_PLAYLIST = 4
        const val TYPE_MORE = 5
    }
}

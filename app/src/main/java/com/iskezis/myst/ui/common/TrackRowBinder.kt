package com.iskezis.myst.ui.common

import android.view.View
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemTrackRowBinding
import com.iskezis.myst.util.formatDuration

/** Liga uma linha de faixa usada na busca, no artista e nos detalhes. */
object TrackRowBinder {

    fun bind(
        binding: ItemTrackRowBinding,
        track: Track,
        tracks: List<Track>,
        onPlay: (List<Track>, Int) -> Unit,
        onMore: (Track) -> Unit,
        onArtistClick: ((Track) -> Unit)? = null,
    ) {
        val context = binding.root.context
        binding.rowTitle.text = track.title.ifBlank {
            context.getString(R.string.player_unknown_title)
        }
        binding.rowArtist.text = track.artist.ifBlank {
            context.getString(R.string.player_unknown_artist)
        }
        binding.rowCover.contentDescription = context.getString(
            R.string.content_cover,
            track.title,
        )
        binding.rowCover.load(track.artworkUrl()) {
            crossfade(true)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }
        binding.rowDuration.text = formatDuration(track.durationMs)
        binding.rowDuration.visibility =
            if (track.durationMs > 0) View.VISIBLE else View.GONE

        val artistClickable = onArtistClick != null && track.artist.isNotBlank()
        binding.rowArtist.isClickable = artistClickable
        binding.rowArtist.isFocusable = artistClickable
        binding.rowArtist.setOnClickListener(
            if (artistClickable) {
                View.OnClickListener { onArtistClick?.invoke(track) }
            } else {
                null
            },
        )

        binding.root.setOnClickListener {
            val index = tracks.indexOfFirst { it.videoId == track.videoId }
            onPlay(tracks, if (index >= 0) index else 0)
        }
        binding.root.setOnLongClickListener {
            onMore(track)
            true
        }
        binding.rowMore.setOnClickListener { onMore(track) }
    }
}

package com.iskezis.myst.ui.browse

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.iskezis.myst.MainActivity

/**
 * Abre uma tela de navegação musical (artista, álbum ou playlist) na
 * MainActivity, mesmo quando o toque parte do PlayerActivity. CLEAR_TOP +
 * SINGLE_TOP evitam empilhar uma segunda MainActivity.
 */
object BrowseNavigation {

    const val EXTRA_ARTIST_ID = "browse_artist_id"
    const val EXTRA_ARTIST_NAME = "browse_artist_name"
    const val EXTRA_ARTIST_THUMBNAIL = "browse_artist_thumbnail"

    const val EXTRA_ALBUM_ID = "browse_album_id"
    const val EXTRA_ALBUM_TITLE = "browse_album_title"
    const val EXTRA_ALBUM_ARTIST = "browse_album_artist"
    const val EXTRA_ALBUM_THUMBNAIL = "browse_album_thumbnail"

    const val EXTRA_PLAYLIST_ID = "browse_playlist_id"
    const val EXTRA_PLAYLIST_TITLE = "browse_playlist_title"
    const val EXTRA_PLAYLIST_THUMBNAIL = "browse_playlist_thumbnail"

    const val EXTRA_SEARCH_QUERY = "browse_search_query"

    fun openArtist(
        context: Context,
        artistId: String,
        artistName: String = "",
        thumbnailUrl: String = "",
    ) {
        launch(
            context,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ARTIST_ID, artistId)
                .putExtra(EXTRA_ARTIST_NAME, artistName)
                .putExtra(EXTRA_ARTIST_THUMBNAIL, thumbnailUrl),
        )
    }

    fun openAlbum(
        context: Context,
        albumId: String,
        albumTitle: String = "",
        albumArtist: String = "",
        thumbnailUrl: String = "",
    ) {
        launch(
            context,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId)
                .putExtra(EXTRA_ALBUM_TITLE, albumTitle)
                .putExtra(EXTRA_ALBUM_ARTIST, albumArtist)
                .putExtra(EXTRA_ALBUM_THUMBNAIL, thumbnailUrl),
        )
    }

    fun openPlaylist(
        context: Context,
        playlistId: String,
        playlistTitle: String = "",
        thumbnailUrl: String = "",
    ) {
        launch(
            context,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_PLAYLIST_TITLE, playlistTitle)
                .putExtra(EXTRA_PLAYLIST_THUMBNAIL, thumbnailUrl),
        )
    }

    fun openSearch(context: Context, query: String) {
        if (query.isBlank()) return
        launch(
            context,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SEARCH_QUERY, query),
        )
    }

    /** Abre o artista; sem ID, cai na busca pelo nome. */
    fun openArtistOrSearch(
        context: Context,
        artistId: String?,
        artistName: String,
    ): Boolean {
        if (!artistId.isNullOrBlank()) {
            openArtist(context, artistId, artistName)
            return true
        }
        if (artistName.isNotBlank()) {
            openSearch(context, artistName)
            return true
        }
        return false
    }

    /** Abre o álbum; sem ID, cai na busca pelo título. */
    fun openAlbumOrSearch(
        context: Context,
        albumId: String?,
        albumTitle: String,
    ): Boolean {
        if (!albumId.isNullOrBlank()) {
            openAlbum(context, albumId, albumTitle)
            return true
        }
        if (albumTitle.isNotBlank()) {
            openSearch(context, albumTitle)
            return true
        }
        return false
    }

    private fun launch(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

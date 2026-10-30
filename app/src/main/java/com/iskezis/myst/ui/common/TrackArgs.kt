package com.iskezis.myst.ui.common

import android.os.Bundle
import com.iskezis.myst.data.youtube.Track

private const val KEY_VIDEO_ID = "track_video_id"
private const val KEY_TITLE = "track_title"
private const val KEY_ARTIST = "track_artist"
private const val KEY_ALBUM = "track_album"
private const val KEY_THUMB = "track_thumb"
private const val KEY_DURATION = "track_duration"
private const val KEY_ARTIST_ID = "track_artist_id"
private const val KEY_ALBUM_ID = "track_album_id"

fun Track.toBundle(): Bundle = Bundle().apply {
    putString(KEY_VIDEO_ID, videoId)
    putString(KEY_TITLE, title)
    putString(KEY_ARTIST, artist)
    putString(KEY_ALBUM, album)
    putString(KEY_THUMB, thumbnailUrl)
    putLong(KEY_DURATION, durationMs)
    putString(KEY_ARTIST_ID, artistId)
    putString(KEY_ALBUM_ID, albumId)
}

fun Bundle.toTrack(): Track? {
    val videoId = getString(KEY_VIDEO_ID)?.takeIf { it.isNotBlank() } ?: return null
    return Track(
        videoId = videoId,
        title = getString(KEY_TITLE).orEmpty(),
        artist = getString(KEY_ARTIST).orEmpty(),
        thumbnailUrl = getString(KEY_THUMB),
        durationMs = getLong(KEY_DURATION, 0L),
        album = getString(KEY_ALBUM).orEmpty(),
        artistId = getString(KEY_ARTIST_ID),
        albumId = getString(KEY_ALBUM_ID),
    )
}

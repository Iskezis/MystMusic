package com.iskezis.myst.ui.browse

import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.MusicException
import com.iskezis.myst.data.youtube.SessionExpiredException
import java.io.IOException

internal fun Throwable.browseErrorRes(): Int = when (this) {
    is SessionExpiredException -> R.string.yt_session_expired
    is MusicException -> R.string.error_youtube
    is IOException -> R.string.error_network
    else -> R.string.error_generic
}

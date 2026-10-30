package com.iskezis.myst.ui.browse

import androidx.annotation.StringRes
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.ui.common.LoadState

/**
 * Estado compartilhado pelas telas de álbum e playlist pública. O cabeçalho
 * fica sempre visível quando há dados; a lista de faixas é paginada.
 */
data class BrowseDetailState(
    val title: String = "",
    val subtitle: String = "",
    val meta: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val tracks: LoadState<List<Track>> = LoadState.Loading,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val copying: Boolean = false,
    val showCopy: Boolean = false,
    @StringRes val emptyMessageRes: Int = R.string.browse_empty_album,
) {
    val trackList: List<Track>
        get() = (tracks as? LoadState.Data)?.value.orEmpty()
}

package com.iskezis.myst.ui.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemQueueHeaderBinding
import com.iskezis.myst.databinding.ItemQueueTrackBinding
import com.iskezis.myst.ui.common.AdaptiveContentColors
import com.iskezis.myst.ui.common.ContentPalette
import com.iskezis.myst.util.UiAnimations.focusScale
import com.iskezis.myst.util.formatDuration

/** Cabeçalhos e faixas exibidos na fila, sempre começando pela atual. */
sealed interface QueueListItem {
    data class Header(@StringRes val titleRes: Int) : QueueListItem
    data class Entry(
        val track: Track,
        val queueIndex: Int,
        val isCurrent: Boolean,
    ) : QueueListItem
}

class QueueAdapter(
    initialPalette: ContentPalette,
    private val onTrackClick: (Int) -> Unit,
) : ListAdapter<QueueListItem, RecyclerView.ViewHolder>(Diff) {

    private var palette = initialPalette

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is QueueListItem.Header -> TYPE_HEADER
        is QueueListItem.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                ItemQueueHeaderBinding.inflate(inflater, parent, false),
            )
            else -> EntryHolder(
                ItemQueueTrackBinding.inflate(inflater, parent, false),
                onTrackClick,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is QueueListItem.Header -> (holder as HeaderHolder).bind(item, palette)
            is QueueListItem.Entry -> (holder as EntryHolder).bind(item, palette)
        }
    }

    /**
     * Atualiza as cores dos itens já visíveis sem recriar a lista. A transição
     * de paleta acontece em ~300 ms; usar notifyDataSetChanged a cada frame
     * faria o DiffUtil trabalhar sem necessidade em filas de 500 itens.
     */
    fun updatePalette(next: ContentPalette, recyclerView: RecyclerView) {
        if (palette == next) return
        palette = next
        for (index in 0 until recyclerView.childCount) {
            when (val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))) {
                is HeaderHolder -> holder.applyPalette(next)
                is EntryHolder -> holder.applyPalette(next)
            }
        }
    }

    private class HeaderHolder(
        private val binding: ItemQueueHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QueueListItem.Header, palette: ContentPalette) {
            binding.queueHeaderTitle.setText(item.titleRes)
            applyPalette(palette)
        }

        fun applyPalette(palette: ContentPalette) {
            binding.queueHeaderTitle.applyAdaptiveColor(palette.secondary, palette)
        }
    }

    private class EntryHolder(
        private val binding: ItemQueueTrackBinding,
        private val onTrackClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var item: QueueListItem.Entry? = null

        init {
            binding.queueRow.focusScale(1.02f)
        }

        fun bind(entry: QueueListItem.Entry, palette: ContentPalette) {
            item = entry
            val context = binding.root.context
            val track = entry.track
            binding.queueRowIndex.text = (entry.queueIndex + 1).toString()
            binding.queueRowTitle.text = track.title.ifBlank {
                context.getString(R.string.player_unknown_title)
            }
            binding.queueRowArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.queueRowDuration.text = formatDuration(track.durationMs)
            binding.queueRowCurrent.isVisible = entry.isCurrent
            binding.queueRow.isSelected = entry.isCurrent
            binding.queueRowCover.contentDescription = context.getString(
                R.string.content_cover,
                track.title.ifBlank { context.getString(R.string.player_unknown_title) },
            )
            val artwork = track.artworkUrl()
            if (artwork.isNullOrBlank()) {
                binding.queueRowCover.setImageResource(R.drawable.bg_cover_placeholder)
            } else {
                binding.queueRowCover.load(artwork) {
                    crossfade(true)
                    placeholder(R.drawable.bg_cover_placeholder)
                    error(R.drawable.bg_cover_placeholder)
                }
            }
            binding.queueRow.setOnClickListener {
                onTrackClick(entry.queueIndex)
            }
            applyPalette(palette)
        }

        fun applyPalette(palette: ContentPalette) {
            val entry = item ?: return
            binding.queueRowTitle.applyAdaptiveColor(palette.primary, palette)
            binding.queueRowArtist.applyAdaptiveColor(palette.secondary, palette)
            binding.queueRowIndex.applyAdaptiveColor(palette.secondary, palette)
            binding.queueRowDuration.applyAdaptiveColor(palette.secondary, palette)
            binding.queueRowCurrent.applyAdaptiveColor(palette.primary, palette)
            binding.queueRow.background = rowBackground(
                palette = palette,
                current = entry.isCurrent,
            )
        }

        private fun rowBackground(palette: ContentPalette, current: Boolean): StateListDrawable {
            val radius = binding.queueRow.resources.displayMetrics.density * 16f
            // A paleta escolhida por AdaptiveContentColors sempre tem preto ou
            // branco com contraste máximo. A linha atual recebe 16% da cor do
            // conteúdo; as demais ficam transparentes para não escurecer a capa.
            val currentFill = AdaptiveContentColors.withAlpha(palette.primary, 0x29)
            val focusedFill = AdaptiveContentColors.withAlpha(palette.primary, 0x24)
            val pressedFill = AdaptiveContentColors.withAlpha(palette.primary, 0x1F)
            fun shape(fill: Int, strokeWidth: Float = 0f, strokeColor: Int = 0): GradientDrawable =
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(fill)
                    if (strokeWidth > 0f) {
                        setStroke(
                            (strokeWidth * binding.queueRow.resources.displayMetrics.density).toInt(),
                            strokeColor,
                        )
                    }
                }

            return StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    shape(focusedFill, 2f, palette.primary),
                )
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    shape(pressedFill),
                )
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    shape(if (current) currentFill else Color.TRANSPARENT),
                )
                addState(
                    intArrayOf(),
                    shape(if (current) currentFill else Color.TRANSPARENT),
                )
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<QueueListItem>() {
        override fun areItemsTheSame(oldItem: QueueListItem, newItem: QueueListItem): Boolean =
            when {
                oldItem is QueueListItem.Header && newItem is QueueListItem.Header ->
                    oldItem.titleRes == newItem.titleRes
                oldItem is QueueListItem.Entry && newItem is QueueListItem.Entry ->
                    oldItem.queueIndex == newItem.queueIndex &&
                        oldItem.track.videoId == newItem.track.videoId
                else -> false
            }

        override fun areContentsTheSame(oldItem: QueueListItem, newItem: QueueListItem): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }
}

private fun TextView.applyAdaptiveColor(color: Int, palette: ContentPalette) {
    setTextColor(ColorStateList.valueOf(color))
    if (palette.isDarkContent) {
        setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    } else {
        setShadowLayer(5f, 0f, 2f, 0x99000000.toInt())
    }
}

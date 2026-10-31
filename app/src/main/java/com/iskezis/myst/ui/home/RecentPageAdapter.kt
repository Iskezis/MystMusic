package com.iskezis.myst.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.recyclerview.widget.RecyclerView
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemRecentPageBinding
import com.iskezis.myst.databinding.ItemTrackRowBinding
import com.iskezis.myst.ui.common.TrackRowBinder

/**
 * Recentes em páginas horizontais de quatro músicas. Cada página mantém a
 * altura de quatro linhas (as vagas sobrando ficam vazias) para o snap não
 * mudar a altura da Home ao deslizar.
 */
class RecentPageAdapter(
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
) : RecyclerView.Adapter<RecentPageAdapter.Holder>() {

    private var allTracks: List<Track> = emptyList()
    private var pages: List<List<Track>> = emptyList()

    fun submit(tracks: List<Track>) {
        if (tracks == allTracks && pages.isNotEmpty()) return
        allTracks = tracks
        pages = tracks.chunked(PAGE_SIZE)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRecentPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        binding.root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return Holder(binding, onPlay, onMore)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(pages[position], allTracks)
    }

    class Holder(
        private val binding: ItemRecentPageBinding,
        private val onPlay: (List<Track>, Int) -> Unit,
        private val onMore: (Track) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pageTracks: List<Track>, allTracks: List<Track>) {
            val context = binding.root.context
            val inflater = LayoutInflater.from(context)
            val rowHeight = context.resources.getDimensionPixelSize(R.dimen.track_row_height)
            binding.recentPageRows.removeAllViews()
            for (position in 0 until PAGE_SIZE) {
                if (position < pageTracks.size) {
                    val row = ItemTrackRowBinding.inflate(inflater, binding.recentPageRows, false)
                    TrackRowBinder.bind(
                        binding = row,
                        track = pageTracks[position],
                        tracks = allTracks,
                        onPlay = onPlay,
                        onMore = onMore,
                    )
                    binding.recentPageRows.addView(
                        row.root,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            rowHeight,
                        ),
                    )
                } else {
                    binding.recentPageRows.addView(
                        Space(context),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            rowHeight,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 4
    }
}

package com.iskezis.myst.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ItemBrowseHeaderBinding

data class BrowseHeader(
    val title: String = "",
    val subtitle: String = "",
    val meta: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val showCopy: Boolean = false,
    val copying: Boolean = false,
    val canPlay: Boolean = false,
    val showDownload: Boolean = true,
)

/**
 * Cabeçalho único de álbum/playlist. Fica no topo do RecyclerView para rolar
 * junto com as faixas sem espremer a lista em telas pequenas.
 */
class BrowseHeaderAdapter(
    private val onPlayAll: () -> Unit,
    private val onShuffle: () -> Unit,
    private val onCopy: () -> Unit,
    private val onDownload: () -> Unit,
) : RecyclerView.Adapter<BrowseHeaderAdapter.Holder>() {

    private var header = BrowseHeader()

    fun submit(newHeader: BrowseHeader) {
        header = newHeader
        if (itemCount > 0) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBrowseHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(header)
    }

    inner class Holder(
        private val binding: ItemBrowseHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.browseHeaderPlayAll.setOnClickListener { onPlayAll() }
            binding.browseHeaderShuffle.setOnClickListener { onShuffle() }
            binding.browseHeaderCopy.setOnClickListener { onCopy() }
            binding.browseHeaderDownload.setOnClickListener { onDownload() }
        }

        fun bind(current: BrowseHeader) {
            val context = binding.root.context
            binding.browseHeaderTitle.text = current.title
            binding.browseHeaderSubtitle.text = current.subtitle
            binding.browseHeaderSubtitle.isVisible = current.subtitle.isNotBlank()
            binding.browseHeaderMeta.text = current.meta
            binding.browseHeaderMeta.isVisible = current.meta.isNotBlank()
            binding.browseHeaderDescription.text = current.description
            binding.browseHeaderDescription.isVisible = current.description.isNotBlank()
            binding.browseHeaderInitial.text = current.title.trim().take(1).uppercase()
            binding.browseHeaderCover.contentDescription = context.getString(
                R.string.content_cover,
                current.title,
            )

            if (current.thumbnailUrl.isNullOrBlank()) {
                binding.browseHeaderCover.setImageDrawable(null)
                binding.browseHeaderInitial.isVisible = true
            } else {
                binding.browseHeaderInitial.isVisible = false
                binding.browseHeaderCover.load(current.thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_playlist_placeholder)
                    error(R.drawable.bg_playlist_placeholder)
                }
            }

            binding.browseHeaderPlayAll.isEnabled = current.canPlay
            binding.browseHeaderShuffle.isEnabled = current.canPlay
            binding.browseHeaderDownload.isVisible = current.showDownload
            binding.browseHeaderDownload.isEnabled = current.canPlay
            binding.browseHeaderCopy.isVisible = current.showCopy
            binding.browseHeaderCopy.isEnabled = !current.copying && current.canPlay
            binding.browseHeaderCopy.setText(
                if (current.copying) R.string.browse_copying else R.string.browse_copy_to_myst,
            )
        }
    }
}

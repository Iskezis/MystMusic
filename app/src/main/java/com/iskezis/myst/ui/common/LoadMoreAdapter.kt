package com.iskezis.myst.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ItemSearchMoreBinding

/**
 * Rodapé de lista com três estados: carregar mais, carregando e erro.
 * Fica em um ConcatAdapter separado para não misturar com as faixas.
 */
class LoadMoreAdapter(
    @StringRes private val readyTextRes: Int,
    @StringRes private val loadingTextRes: Int,
    private val onLoadMore: () -> Unit,
) : RecyclerView.Adapter<LoadMoreAdapter.Holder>() {

    enum class State {
        HIDDEN,
        READY,
        LOADING,
        ERROR,
    }

    private var state = State.HIDDEN

    fun submit(newState: State) {
        if (state == newState) return
        state = newState
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (state == State.HIDDEN) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSearchMoreBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(state)
    }

    inner class Holder(
        private val binding: ItemSearchMoreBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(current: State) {
            val loading = current == State.LOADING
            binding.searchMoreProgress.isVisible = loading
            binding.searchMoreText.setText(
                when (current) {
                    State.READY -> readyTextRes
                    State.LOADING -> loadingTextRes
                    State.ERROR -> R.string.state_retry
                    State.HIDDEN -> readyTextRes
                },
            )
            val colorAttr = if (current == State.ERROR) R.attr.mystError else R.attr.mystAccentText
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
}

package com.iskezis.myst.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.iskezis.myst.R
import com.iskezis.myst.util.UiAnimations

/**
 * Fundo de capa borrada compartilhado pelo player e pela fila em tela cheia.
 *
 * Mantém o mesmo recorte `centerCrop` e faz o crossfade entre o placeholder e
 * a capa real (ou entre duas faixas) sem deixar um post atrasado sobrescrever
 * o fundo mais novo. O bitmap não é reciclado aqui: ele pertence ao
 * [BackdropCache] e pode ser usado pelas duas telas ao mesmo tempo.
 */
class CoverBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var transitionToken = 0

    init {
        scaleType = ScaleType.CENTER_CROP
        setImageResource(R.drawable.bg_cover_placeholder)
    }

    fun showPlaceholder() {
        transitionToken++
        setImageResource(R.drawable.bg_cover_placeholder)
    }

    fun showBitmap(bitmap: Bitmap, durationMs: Int = DEFAULT_CROSSFADE_MS) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val drawable = BitmapDrawable(resources, bitmap).apply { setFilterBitmap(true) }
        val previous = this.drawable
        if (durationMs <= 0 || !UiAnimations.enabled(context) || previous == null) {
            transitionToken++
            setImageDrawable(drawable)
            return
        }
        transitionToken++
        val token = transitionToken
        val transition = TransitionDrawable(arrayOf(previous, drawable)).apply {
            isCrossFadeEnabled = true
        }
        setImageDrawable(transition)
        transition.startTransition(durationMs)
        postDelayed({
            if (token == transitionToken && this.drawable === transition) {
                setImageDrawable(drawable)
            }
        }, durationMs.toLong() + SETTLE_DELAY_MS)
    }

    private companion object {
        const val DEFAULT_CROSSFADE_MS = 250
        const val SETTLE_DELAY_MS = 40L
    }
}

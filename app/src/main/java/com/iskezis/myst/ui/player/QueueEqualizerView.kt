package com.iskezis.myst.ui.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.util.UiAnimations
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Indicador de equalizador do cartão "Tocando agora".
 *
 * A TV 32 bits usa barras estáticas para economizar CPU; no celular as barras
 * só animam enquanto a faixa está tocando e as animações do sistema estão
 * ligadas.
 */
class QueueEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val rect = RectF()
    private val barCount = 4
    private val staticHeights = floatArrayOf(0.42f, 0.72f, 0.54f, 0.88f)
    private val animatorAllowed = BuildConfig.FLAVOR != "tv" && UiAnimations.enabled(context)
    private var playing = false
    private var attached = false
    private var animator: ValueAnimator? = null
    private var phase = 0f

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBarColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        updateAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (playing && attached && animatorAllowed) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener { animation ->
                        phase = animation.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val gap = width * 0.16f / (barCount - 1)
        val barWidth = max(1f, (width - gap * (barCount - 1)) / barCount)
        val minHeight = height * 0.18f
        val maxHeight = height * 0.92f
        val animated = animator != null
        val wavePhase = phase * (2f * PI.toFloat())

        for (index in 0 until barCount) {
            val fraction = if (animated) {
                val wave = (sin((wavePhase + index * 1.45f).toDouble()) + 1.0) / 2.0
                (0.22 + wave * 0.78).toFloat()
            } else {
                staticHeights[index]
            }
            val barHeight = minHeight + (maxHeight - minHeight) * fraction
            val left = index * (barWidth + gap)
            val top = height - barHeight
            rect.set(left, top, left + barWidth, height)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}

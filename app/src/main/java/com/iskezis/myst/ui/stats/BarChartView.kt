package com.iskezis.myst.ui.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.iskezis.myst.R
import com.iskezis.myst.util.UiAnimations
import kotlin.math.min

/**
 * Gráfico de barras simples, desenhado em Canvas para não adicionar dependência
 * e funcionar igual no celular e na TV. Recebe valores em milissegundos; o
 * fragment cuida do texto acessível.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var labelEvery = 1
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val topPadding = 8f * density
    private val labelGap = 8f * density
    private val minBarHeight = 2f * density
    private val maxBarWidth = 24f * density

    init {
        val typed = context.obtainStyledAttributes(
            intArrayOf(
                R.attr.mystAccent,
                R.attr.mystOnSurfaceDim,
                R.attr.mystStroke,
            ),
        )
        val accent = typed.getColor(0, Color.parseColor("#38BDF8"))
        val label = typed.getColor(1, Color.GRAY)
        val grid = typed.getColor(2, Color.LTGRAY)
        typed.recycle()

        barPaint.color = accent
        labelPaint.color = label
        labelPaint.textSize = resources.getDimension(R.dimen.text_label)
        labelPaint.textAlign = Paint.Align.CENTER
        gridPaint.color = grid
        gridPaint.strokeWidth = density
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
    }

    fun setData(values: List<Float>, labels: List<String>, labelEvery: Int = 1) {
        this.values = values
        this.labels = labels
        this.labelEvery = labelEvery.coerceAtLeast(1)
        startAnimation()
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        if (!isAttachedToWindow || !UiAnimations.enabled(context)) {
            progress = 1f
            invalidate()
            return
        }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelHeight = labelPaint.textSize + labelGap
        val chartTop = topPadding
        val chartBottom = height - labelHeight
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val chartWidth = width.toFloat()
        if (chartWidth <= 0f || chartHeight <= 0f) return

        val baseline = chartBottom
        canvas.drawLine(0f, baseline, chartWidth, baseline, gridPaint)
        canvas.drawLine(
            0f,
            chartTop + chartHeight / 2f,
            chartWidth,
            chartTop + chartHeight / 2f,
            gridPaint,
        )

        if (values.isEmpty()) return
        val maxValue = values.maxOrNull() ?: 0f
        val slot = chartWidth / values.size
        val barWidth = min(slot * 0.62f, maxBarWidth).coerceAtLeast(2f * density)
        val radius = min(4f * density, barWidth / 2f)
        values.forEachIndexed { index, value ->
            val fraction = if (maxValue > 0f) (value / maxValue).coerceIn(0f, 1f) else 0f
            val barHeight = (chartHeight * fraction * progress).coerceAtLeast(
                if (value > 0f) minBarHeight else 0f,
            )
            if (barHeight > 0f) {
                val centerX = index * slot + slot / 2f
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f
                val top = chartBottom - barHeight
                canvas.drawRoundRect(
                    RectF(left, top, right, chartBottom),
                    radius,
                    radius,
                    barPaint,
                )
            }
            if (index % labelEvery == 0) {
                val label = labels.getOrNull(index).orEmpty()
                if (label.isNotBlank()) {
                    canvas.drawText(
                        label,
                        index * slot + slot / 2f,
                        height - 2f * density,
                        labelPaint,
                    )
                }
            }
        }
    }
}

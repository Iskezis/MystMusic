package com.iskezis.myst.ui.player

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Círculo do play/pause preenchido com a cor do conteúdo e ícone na cor
 * oposta, conforme a capa. O anel de foco do D-pad é desenhado por dentro do
 * círculo, então continua visível em qualquer fundo.
 */
class PlayerPlayCircleDrawable(density: Float) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val focusStroke = 4f * density
    private val pressedStroke = 3f * density

    private var fillColor = 0xFFFFFFFF.toInt()
    private var ringColor = 0xFF000000.toInt()
    private var alpha = 255
    private var focused = false
    private var pressed = false

    fun setColors(fill: Int, ring: Int) {
        if (fill == fillColor && ring == ringColor) return
        fillColor = fill
        ringColor = ring
        invalidateSelf()
    }

    override fun onStateChange(state: IntArray): Boolean {
        val newFocused = state.contains(android.R.attr.state_focused)
        val newPressed = state.contains(android.R.attr.state_pressed)
        if (newFocused == focused && newPressed == pressed) return false
        focused = newFocused
        pressed = newPressed
        return true
    }

    override fun draw(canvas: Canvas) {
        val radius = min(bounds.width(), bounds.height()) / 2f
        if (radius <= 0f) return
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        fillPaint.color = applyAlpha(fillColor, alpha)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        if (!focused && !pressed) return
        ringPaint.color = applyAlpha(ringColor, alpha)
        ringPaint.strokeWidth = if (focused) focusStroke else pressedStroke
        val ringRadius = (radius - ringPaint.strokeWidth / 2f - 2f).coerceAtLeast(0f)
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
    }

    override fun setAlpha(alpha: Int) {
        if (this.alpha == alpha) return
        this.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Barra fina do player desenhada com as cores da paleta atual. O SeekBar
 * continua fornecendo o level (0..10000) normalmente.
 */
class PlayerProgressDrawable(density: Float) : Drawable() {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barHeight = 4f * density
    private val cornerRadius = barHeight / 2f
    private var trackColor = 0x40FFFFFF
    private var progressColor = 0xFFFFFFFF.toInt()
    private var alpha = 255

    fun setColors(track: Int, progress: Int) {
        if (track == trackColor && progress == progressColor) return
        trackColor = track
        progressColor = progress
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val centerY = bounds.exactCenterY()
        val top = centerY - barHeight / 2f
        val bottom = centerY + barHeight / 2f
        trackPaint.color = applyAlpha(trackColor, alpha)
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            top,
            bounds.right.toFloat(),
            bottom,
            cornerRadius,
            cornerRadius,
            trackPaint,
        )
        val fraction = level.coerceIn(0, 10000) / 10000f
        val progressRight = bounds.left + bounds.width() * fraction
        if (progressRight <= bounds.left) return
        progressPaint.color = applyAlpha(progressColor, alpha)
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            top,
            progressRight,
            bottom,
            cornerRadius,
            cornerRadius,
            progressPaint,
        )
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun getIntrinsicHeight(): Int = barHeight.roundToInt()

    override fun setAlpha(alpha: Int) {
        if (this.alpha == alpha) return
        this.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        progressPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Multiplica o alpha da cor pelo alpha do drawable sem perder a transparência. */
private fun applyAlpha(color: Int, alpha: Int): Int {
    val sourceAlpha = (color ushr 24) and 0xFF
    val resultAlpha = (sourceAlpha * alpha.coerceIn(0, 255) + 127) / 255
    return (color and 0x00FFFFFF) or (resultAlpha shl 24)
}

package com.iskezis.myst.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Recorte 1:1 com máscara circular. O usuário arrasta e usa dois dedos para
 * ampliar; a imagem nunca sai da área de recorte, então o JPEG final não tem
 * bordas vazias.
 */
class AvatarCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val imageRect = RectF()
    private val mappedRect = RectF()
    private val path = Path()
    private val density = resources.displayMetrics.density

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private var bitmap: Bitmap? = null
    private var cropSide = 0f
    private var minScale = 1f
    private var maxScale = 4f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = bitmap != null

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (bitmap == null) return false
                val current = currentScale()
                if (current <= 0f) return false
                val target = (current * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = target / current
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                invalidate()
                return true
            }
        },
    )

    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        reset()
        invalidate()
    }

    fun clear() {
        bitmap = null
        cropSide = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (cropSide <= 0f) return
        canvas.drawBitmap(image, matrix, imagePaint)

        val cx = width / 2f
        val cy = height / 2f
        val radius = cropSide / 2f
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, scrimPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    clamp()
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Gera o bitmap final no tamanho pedido, sem depender de como a View foi medida. */
    fun cropBitmap(size: Int): Bitmap? {
        val image = bitmap ?: return null
        if (cropSide <= 0f || size <= 0) return null
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val outputScale = size / cropSide
        val cropMatrix = Matrix(matrix)
        cropMatrix.postTranslate(-((width - cropSide) / 2f), -((height - cropSide) / 2f))
        cropMatrix.postScale(outputScale, outputScale)
        canvas.drawBitmap(image, cropMatrix, imagePaint)
        return output
    }

    private fun reset() {
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        cropSide = min(width, height).toFloat()
        val scale = max(cropSide / image.width, cropSide / image.height)
        minScale = scale
        maxScale = scale * 4f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (width - image.width * scale) / 2f,
            (height - image.height * scale) / 2f,
        )
        clamp()
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun clamp() {
        val image = bitmap ?: return
        if (cropSide <= 0f) return
        val left = (width - cropSide) / 2f
        val top = (height - cropSide) / 2f
        val right = left + cropSide
        val bottom = top + cropSide
        imageRect.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
        matrix.mapRect(mappedRect, imageRect)

        var dx = 0f
        var dy = 0f
        if (mappedRect.width() <= cropSide) {
            dx = (left + right) / 2f - (mappedRect.left + mappedRect.right) / 2f
        } else {
            if (mappedRect.left > left) dx = left - mappedRect.left
            if (mappedRect.right < right) dx = right - mappedRect.right
        }
        if (mappedRect.height() <= cropSide) {
            dy = (top + bottom) / 2f - (mappedRect.top + mappedRect.bottom) / 2f
        } else {
            if (mappedRect.top > top) dy = top - mappedRect.top
            if (mappedRect.bottom < bottom) dy = bottom - mappedRect.bottom
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }
}

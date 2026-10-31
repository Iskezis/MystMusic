package com.iskezis.myst.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Desfoque real da capa do player, sem depender de RenderScript/RenderEffect.
 *
 * A imagem é reduzida para no máximo 256 px (mantendo a proporção), recebe
 * três passadas de box blur separável e é desenhada de volta pelo ImageView
 * com filtragem bilinear. O resultado é um fundo suave em qualquer API,
 * inclusive na TV, em vez da miniatura pixelizada usada antes.
 */
object BlurUtils {

    private const val MAX_SIZE = 256
    private const val RADIUS = 12
    private const val PASSES = 3

    fun blur(source: Bitmap): Bitmap {
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val scale = MAX_SIZE.toFloat() / maxOf(width, height)
        val targetWidth = (width * scale).toInt().coerceIn(1, MAX_SIZE)
        val targetHeight = (height * scale).toInt().coerceIn(1, MAX_SIZE)

        // Desenhar em um bitmap ARGB próprio evita getPixels() em bitmaps
        // HARDWARE (Coil pode usá-los) e mantém a proporção original.
        val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(
            source,
            null,
            Rect(0, 0, targetWidth, targetHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )

        var pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        repeat(PASSES) {
            pixels = boxBlur(pixels, targetWidth, targetHeight, RADIUS)
        }
        scaled.recycle()

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return result
    }

    private fun boxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ): IntArray {
        val r = radius.coerceAtLeast(1)
        val horizontal = IntArray(pixels.size)
        val vertical = IntArray(pixels.size)
        blurHorizontal(pixels, horizontal, width, height, r)
        blurVertical(horizontal, vertical, width, height, r)
        return vertical
    }

    private fun blurHorizontal(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = source[row + offset.coerceIn(0, width - 1)]
                alpha += color ushr 24 and 0xFF
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
            }
            for (x in 0 until width) {
                target[row + x] = (alpha / window shl 24) or
                    (red / window shl 16) or
                    (green / window shl 8) or
                    (blue / window)
                val leaving = source[row + (x - radius).coerceIn(0, width - 1)]
                val entering = source[row + (x + radius + 1).coerceIn(0, width - 1)]
                alpha += (entering ushr 24 and 0xFF) - (leaving ushr 24 and 0xFF)
                red += (entering ushr 16 and 0xFF) - (leaving ushr 16 and 0xFF)
                green += (entering ushr 8 and 0xFF) - (leaving ushr 8 and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private fun blurVertical(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = source[offset.coerceIn(0, height - 1) * width + x]
                alpha += color ushr 24 and 0xFF
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
            }
            for (y in 0 until height) {
                target[y * width + x] = (alpha / window shl 24) or
                    (red / window shl 16) or
                    (green / window shl 8) or
                    (blue / window)
                val leaving = source[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                alpha += (entering ushr 24 and 0xFF) - (leaving ushr 24 and 0xFF)
                red += (entering ushr 16 and 0xFF) - (leaving ushr 16 and 0xFF)
                green += (entering ushr 8 and 0xFF) - (leaving ushr 8 and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}

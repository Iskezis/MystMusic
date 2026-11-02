package com.iskezis.myst.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Paleta de conteúdo (texto, ícones e controles) desenhada sobre a capa
 * borrada do player. `isDarkContent` é true quando o conteúdo deve ser preto.
 */
data class ContentPalette(
    val primary: Int,
    val secondary: Int,
    val isDarkContent: Boolean,
    val track: Int,
    val disabled: Int,
    val error: Int,
    val opposite: Int,
    val ripple: Int,
)

/**
 * Escolhe preto ou branco para o conteúdo do player a partir da luminância
 * real do fundo (capa borrada), sem depender do tema do app.
 *
 * - Contraste relativo WCAG entre o fundo e branco/preto.
 * - Histerese de 15%: capas de luminância média não ficam piscando entre as
 *   duas opções a cada faixa ou pequena mudança de blur.
 * - Amostragem em bitmap reduzido, sempre fora da main thread.
 *
 * Reutilizado na fase 22 (fila em tela cheia).
 */
object AdaptiveContentColors {

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val darkContent = ContentPalette(
        primary = BLACK,
        secondary = 0xA6000000.toInt(), // preto a 65%
        isDarkContent = true,
        track = 0x40000000, // preto a 25%
        disabled = 0x61000000, // preto a 38%
        error = 0xFFB91C1C.toInt(),
        opposite = WHITE,
        ripple = 0x1F000000, // preto a 12%
    )

    private val lightContent = ContentPalette(
        primary = WHITE,
        secondary = 0xB3FFFFFF.toInt(), // branco a 70%
        isDarkContent = false,
        track = 0x40FFFFFF, // branco a 25%
        disabled = 0x61FFFFFF, // branco a 38%
        error = 0xFFFCA5A5.toInt(),
        opposite = BLACK,
        ripple = 0x1FFFFFFF, // branco a 12%
    )

    /** Paleta para quando ainda não há fundo medido (tema claro/escuro). */
    fun paletteForDarkBackground(darkBackground: Boolean): ContentPalette =
        if (darkBackground) lightContent else darkContent

    /** Luminância relativa WCAG 2.x de uma cor ARGB (alpha é ignorado). */
    fun relativeLuminance(argb: Int): Double {
        val red = linear((argb shr 16 and 0xFF) / 255.0)
        val green = linear((argb shr 8 and 0xFF) / 255.0)
        val blue = linear((argb and 0xFF) / 255.0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /**
     * Escolhe a paleta de maior contraste com o fundo. Se já existe uma paleta
     * anterior, a troca só acontece quando a opção nova ganha pelo menos 15%
     * de contraste sobre a atual.
     */
    fun paletteFor(
        backgroundLuminance: Double,
        previous: ContentPalette?,
    ): ContentPalette {
        val luminance = backgroundLuminance.coerceIn(0.0, 1.0)
        val whiteContrast = contrastWithWhite(luminance)
        val blackContrast = contrastWithBlack(luminance)
        val prefersDarkContent = blackContrast >= whiteContrast
        if (previous == null) {
            return if (prefersDarkContent) darkContent else lightContent
        }
        if (previous.isDarkContent == prefersDarkContent) return previous
        val currentContrast = if (previous.isDarkContent) blackContrast else whiteContrast
        val newContrast = max(whiteContrast, blackContrast)
        return if (newContrast >= currentContrast * 1.15) {
            if (prefersDarkContent) darkContent else lightContent
        } else {
            previous
        }
    }

    /** Contraste da luminância do fundo com branco puro. */
    fun contrastWithWhite(backgroundLuminance: Double): Double =
        1.05 / (backgroundLuminance.coerceIn(0.0, 1.0) + 0.05)

    /** Contraste da luminância do fundo com preto puro. */
    fun contrastWithBlack(backgroundLuminance: Double): Double =
        (backgroundLuminance.coerceIn(0.0, 1.0) + 0.05) / 0.05

    /**
     * Média da luminância relativa dentro de [region] (0..1 em relação ao
     * bitmap). Usa no máximo 32x32 amostras e ignora pixels transparentes.
     */
    fun sampleLuminance(bitmap: Bitmap, region: RectF): Double {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0.0
        val left = floor(region.left.coerceIn(0f, 1f) * bitmap.width)
            .toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(region.top.coerceIn(0f, 1f) * bitmap.height)
            .toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(region.right.coerceIn(0f, 1f) * bitmap.width)
            .toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(region.bottom.coerceIn(0f, 1f) * bitmap.height)
            .toInt().coerceIn(top + 1, bitmap.height)
        val stepX = max(1, (right - left) / MAX_SAMPLES_PER_ZONE)
        val stepY = max(1, (bottom - top) / MAX_SAMPLES_PER_ZONE)
        var sum = 0.0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                if ((color ushr 24) >= MIN_ALPHA) {
                    sum += relativeLuminance(color)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) sum / count else 0.0
    }

    /**
     * Recria o que o ImageView `centerCrop` mostra na tela em um bitmap
     * reduzido (no máximo [maxSize] px no maior lado). As zonas medidas nesse
     * bitmap correspondem exatamente às posições da tela.
     */
    fun screenSample(
        source: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        maxSize: Int = DEFAULT_SAMPLE_SIZE,
    ): Bitmap {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val safeSize = maxSize.coerceAtLeast(8)
        val outScale = safeSize.toFloat() / max(width, height)
        val outWidth = (width * outScale).roundToInt().coerceIn(1, safeSize)
        val outHeight = (height * outScale).roundToInt().coerceIn(1, safeSize)
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val cover = max(
            outWidth / source.width.toFloat(),
            outHeight / source.height.toFloat(),
        )
        val drawWidth = source.width * cover
        val drawHeight = source.height * cover
        val left = (outWidth - drawWidth) / 2f
        val top = (outHeight - drawHeight) / 2f
        Canvas(output).drawBitmap(
            source,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    /**
     * Versão de [screenSample] para drawables (placeholder sem capa). O
     * drawable é desenhado na proporção da tela, como o ImageView faria.
     */
    fun renderSample(
        drawable: Drawable,
        viewWidth: Int,
        viewHeight: Int,
        maxSize: Int = DEFAULT_SAMPLE_SIZE,
    ): Bitmap {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val safeSize = maxSize.coerceAtLeast(8)
        val outScale = safeSize.toFloat() / max(width, height)
        val outWidth = (width * outScale).roundToInt().coerceIn(1, safeSize)
        val outHeight = (height * outScale).roundToInt().coerceIn(1, safeSize)
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawable.setBounds(0, 0, outWidth, outHeight)
        drawable.draw(canvas)
        return output
    }

    /** Aplica um alpha de 0..255 sobre a cor, preservando o RGB. */
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun linear(channel: Double): Double =
        if (channel <= 0.04045) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }

    private const val MAX_SAMPLES_PER_ZONE = 32
    private const val MIN_ALPHA = 0x20
    private const val DEFAULT_SAMPLE_SIZE = 64
}

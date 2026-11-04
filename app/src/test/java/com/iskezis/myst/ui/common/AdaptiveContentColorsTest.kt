package com.iskezis.myst.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveContentColorsTest {

    @Test
    fun `relative luminance follows wcag extremes`() {
        assertEquals(1.0, AdaptiveContentColors.relativeLuminance(0xFFFFFFFF.toInt()), 0.0001)
        assertEquals(0.0, AdaptiveContentColors.relativeLuminance(0xFF000000.toInt()), 0.0001)
    }

    @Test
    fun `mid gray has expected relative luminance`() {
        assertEquals(
            0.2158,
            AdaptiveContentColors.relativeLuminance(0xFF808080.toInt()),
            0.001,
        )
    }

    @Test
    fun `light background uses black content`() {
        assertTrue(AdaptiveContentColors.paletteFor(1.0, null).isDarkContent)
        assertEquals(
            0xFF000000.toInt(),
            AdaptiveContentColors.paletteFor(1.0, null).primary,
        )
    }

    @Test
    fun `dark background uses white content`() {
        assertFalse(AdaptiveContentColors.paletteFor(0.0, null).isDarkContent)
        assertEquals(
            0xFFFFFFFF.toInt(),
            AdaptiveContentColors.paletteFor(0.0, null).primary,
        )
    }

    @Test
    fun `hysteresis keeps previous palette when gain is under fifteen percent`() {
        val previous = AdaptiveContentColors.paletteFor(0.5, null)
        assertTrue(previous.isDarkContent)

        val kept = AdaptiveContentColors.paletteFor(0.17, previous)

        assertTrue(kept.isDarkContent)
        assertEquals(previous, kept)
    }

    @Test
    fun `palette switches when contrast gain reaches fifteen percent`() {
        val previous = AdaptiveContentColors.paletteFor(0.5, null)
        assertTrue(previous.isDarkContent)

        val switched = AdaptiveContentColors.paletteFor(0.10, previous)

        assertFalse(switched.isDarkContent)
    }

    @Test
    fun `with alpha preserves rgb`() {
        assertEquals(
            0x80ABCDEF.toInt(),
            AdaptiveContentColors.withAlpha(0xFFABCDEF.toInt(), 0x80),
        )
    }

    @Test
    fun `blend interpolates palette colors and clamps the fraction`() {
        val blackOnLight = AdaptiveContentColors.paletteFor(1.0, null)
        val whiteOnDark = AdaptiveContentColors.paletteFor(0.0, null)

        assertEquals(blackOnLight, AdaptiveContentColors.blend(blackOnLight, whiteOnDark, 0f))
        assertEquals(whiteOnDark, AdaptiveContentColors.blend(blackOnLight, whiteOnDark, 1f))
        assertEquals(whiteOnDark, AdaptiveContentColors.blend(blackOnLight, whiteOnDark, 2f))

        val middle = AdaptiveContentColors.blend(blackOnLight, whiteOnDark, 0.5f)
        assertEquals(0xFF808080.toInt(), middle.primary)
        assertEquals(0xAD808080.toInt(), middle.secondary)
        assertFalse(middle.isDarkContent)
    }
}

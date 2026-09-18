package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartPaletteTest {

    /** WCAG relative luminance. Pure arithmetic, so it runs without Android or Compose. */
    private fun luminance(argb: Long): Double {
        fun channel(c: Long): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Contrast against white, which is what every slice is drawn on. */
    private fun contrastOnWhite(argb: Long): Double = 1.05 / (luminance(argb) + 0.05)

    @Test fun everyRampColourIsVisibleOnWhite() {
        // 3:1 is the WCAG minimum for a non-text element the user must be able to distinguish.
        ChartPalette.RAMP.forEach { c ->
            val ratio = contrastOnWhite(c)
            assertTrue("%06X is %.2f:1 on white, under 3:1".format(c and 0xFFFFFF, ratio), ratio >= 3.0)
        }
    }

    @Test fun uncategorisedGreyIsAlsoVisible() {
        assertTrue(contrastOnWhite(ChartPalette.UNCATEGORISED) >= 3.0)
    }

    @Test fun uncategorisedIsNotInTheRamp() {
        // It has to stay visually distinct from a real category, not just be another slice colour.
        assertFalse(ChartPalette.RAMP.contains(ChartPalette.UNCATEGORISED))
    }

    @Test fun rampColoursAreUnique() {
        assertEquals(ChartPalette.RAMP.size, ChartPalette.RAMP.toSet().size)
    }

    @Test fun rampIsBigEnoughForTheTopNRollup() {
        // The ring rolls up beyond the top 7, so the ramp must comfortably cover that plus a legend.
        assertTrue(ChartPalette.RAMP.size >= 10)
    }

    @Test fun forIndexWrapsInsteadOfThrowing() {
        assertEquals(ChartPalette.RAMP[0], ChartPalette.forIndex(0))
        assertEquals(ChartPalette.RAMP[0], ChartPalette.forIndex(ChartPalette.RAMP.size))
        assertEquals(ChartPalette.RAMP[1], ChartPalette.forIndex(ChartPalette.RAMP.size + 1))
    }

    @Test fun forIndexHandlesNegativeIndex() {
        // Kotlin's % keeps the sign, so a negative index would otherwise blow up the list access.
        assertEquals(ChartPalette.RAMP.last(), ChartPalette.forIndex(-1))
    }

    @Test fun brandCoralWouldFailSoItIsNotInTheRamp() {
        // Documents why one of the four brand colours is missing: 2.29:1, invisible as a slice.
        val coral = 0xFFF79070L
        assertTrue(contrastOnWhite(coral) < 3.0)
        assertFalse(ChartPalette.RAMP.contains(coral))
    }
}

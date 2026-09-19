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

    @Test fun bothNeutralsAreAlsoVisible() {
        assertTrue(contrastOnWhite(ChartPalette.UNCATEGORISED) >= 3.0)
        assertTrue(contrastOnWhite(ChartPalette.OTHER_ROLLUP) >= 3.0)
    }

    @Test fun theTwoNeutralsAreTellableApart() {
        // They can appear in the same ring, and "not sorted yet" and "small categories combined"
        // mean different things, so they must not render as the same grey.
        val a = luminance(ChartPalette.UNCATEGORISED)
        val b = luminance(ChartPalette.OTHER_ROLLUP)
        assertTrue("neutrals are too close: $a vs $b", maxOf(a, b) / minOf(a, b) >= 1.5)
    }

    @Test fun uncategorisedIsNotInTheRamp() {
        // It has to stay visually distinct from a real category, not just be another slice colour.
        assertFalse(ChartPalette.RAMP.contains(ChartPalette.UNCATEGORISED))
    }

    @Test fun rampColoursAreUnique() {
        assertEquals(ChartPalette.RAMP.size, ChartPalette.RAMP.toSet().size)
    }

    @Test fun rampHasTwentyColours() {
        // Twenty is the ceiling on categories that can each hold a unique colour.
        assertEquals(20, ChartPalette.RAMP.size)
    }

    /** CIE Lab, so "different" means different to an eye rather than different in hex. */
    private fun lab(argb: Long): Triple<Double, Double, Double> {
        fun ch(c: Long): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = ch((argb shr 16) and 0xFF); val g = ch((argb shr 8) and 0xFF); val b = ch(argb and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun fi(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        return Triple(116 * fi(y) - 16, 500 * (fi(x) - fi(y)), 200 * (fi(y) - fi(z)))
    }

    private fun deltaE(a: Long, b: Long): Double {
        val (l1, a1, b1) = lab(a); val (l2, a2, b2) = lab(b)
        return Math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }

    @Test fun everyPairOfRampColoursIsClearlyDifferent() {
        // 2.3 is roughly the threshold of "just about tellable apart"; a chart needs far more than
        // that, because two slices are compared across a gap rather than side by side. Picking
        // twenty colours by eye reliably produces pairs that fail this while looking fine in a row.
        var worst = Double.MAX_VALUE
        var pair = ""
        for (i in ChartPalette.RAMP.indices) {
            for (j in i + 1 until ChartPalette.RAMP.size) {
                val d = deltaE(ChartPalette.RAMP[i], ChartPalette.RAMP[j])
                if (d < worst) { worst = d; pair = "$i/$j" }
            }
        }
        assertTrue("closest pair $pair is only %.1f apart".format(worst), worst >= 15.0)
    }

    @Test fun neitherNeutralCollidesWithTheRamp() {
        ChartPalette.RAMP.forEach {
            assertTrue(deltaE(it, ChartPalette.UNCATEGORISED) >= 15.0)
            assertTrue(deltaE(it, ChartPalette.OTHER_ROLLUP) >= 15.0)
        }
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

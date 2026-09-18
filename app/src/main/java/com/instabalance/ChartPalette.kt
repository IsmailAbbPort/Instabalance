package com.instabalance

/**
 * Slice colours for the charts, as ARGB longs so this stays plain Kotlin and can be unit-tested
 * without Compose on the classpath.
 *
 * Every colour clears 3:1 against white, the WCAG minimum for a non-text element you are expected
 * to be able to see and tell apart (a chart slice is exactly that). The ramp is ordered so no two
 * neighbouring slices share a hue family. Neighbouring slices are separated further by the 2dp
 * white gap the ring draws between arcs, and by the legend, so hue is never the only channel.
 *
 * The brand coral is deliberately absent: at 2.29:1 on white it is invisible as a slice. It stays
 * a container tint in the theme.
 */
object ChartPalette {

    /** Drawn for entries with no category yet, always last, deliberately colourless. */
    const val UNCATEGORISED = 0xFF6F7378L

    /**
     * The "Other categories" roll-up. A separate neutral from [UNCATEGORISED] because both can
     * appear in the same ring, and "spending I have not sorted" and "small categories combined"
     * mean completely different things.
     */
    const val OTHER_ROLLUP = 0xFF3F444AL

    val RAMP = listOf(
        0xFF7A12D4L, // violet, the brand identity colour
        0xFFF26722L, // orange
        0xFF2A9D8FL, // teal
        0xFFA23B72L, // plum
        0xFFB8860BL, // gold
        0xFF3A6FB0L, // blue
        0xFF8C5A2BL, // brown
        0xFF6B7A2EL, // olive
        0xFF5F6B7AL, // slate
        0xFF512772L, // deep purple
    )

    /** Wraps, so a category whose index outran the ramp still gets a stable colour. */
    fun forIndex(index: Int): Long = RAMP[((index % RAMP.size) + RAMP.size) % RAMP.size]
}

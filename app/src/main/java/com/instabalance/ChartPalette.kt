package com.instabalance

/**
 * Slice colours for the charts, as ARGB longs so this stays plain Kotlin and can be unit-tested
 * without Compose on the classpath.
 *
 * Generated rather than hand-picked. Twenty-four colours that are all legible on white AND all
 * clearly different from each other is a genuine constraint problem, and picking by eye produces
 * pairs that look fine in a swatch row and identical in a pie chart. Every colour clears 3:1
 * against white, and the closest of the 276 possible pairs is 18.6 apart in CIE Lab, where 2.3 is
 * the threshold of "just about tellable apart". Both checks are pinned by tests, so a hand edit
 * that breaks either one fails.
 *
 * Twenty-four is also the ceiling on categories that can each hold a unique colour, which is what
 * [Categories] enforces. Twenty presets ship, so four are free for categories of your own.
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
        0xFFDF646FL, // rose
        0xFFB24432L, // rust
        0xFF7F2C00L, // burnt umber
        0xFFB88030L, // bronze
        0xFF7B6700L, // dark gold
        0xFF3B4E00L, // dark olive
        0xFF689940L, // moss
        0xFF007931L, // green
        0xFF00582BL, // forest
        0xFF00A28DL, // turquoise
        0xFF007D86L, // teal
        0xFF005980L, // petrol
        0xFF009DD6L, // sky
        0xFF0074C0L, // blue
        0xFF004CA6L, // navy
        0xFF7286E1L, // periwinkle
        0xFF7856B1L, // violet
        0xFF761E7CL, // plum
        0xFFD266A6L, // pink
        0xFFB73767L, // magenta
        // Appended when Investment became a preset and the first twenty were all spoken for.
        // Chosen by farthest-point search seeded with the twenty above, so each one is as far from
        // everything already here as the gamut allows: the closest of all 276 pairs is still 18.6,
        // which is the tightest pair among the original twenty. Adding them cost no distinctness.
        0xFF5B3237L, // maroon
        0xFFA089A8L, // mauve
        0xFF98916EL, // khaki
        0xFFD65FE5L, // orchid
    )

    /** Wraps, so a category whose index outran the ramp still gets a stable colour. */
    fun forIndex(index: Int): Long = RAMP[((index % RAMP.size) + RAMP.size) % RAMP.size]
}

package com.instabalance

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Taken from screenshots of the real InstaPay app, not from its logo. Three things define the look:
 *
 * 1. A vivid violet gradient header with an orange-to-coral blob overlapping it. That pairing is
 *    the app's signature; see [BrandHeader].
 * 2. The page ground is a light lavender grey, never white. White is reserved for cards, which is
 *    what lets them read as cards with almost no shadow.
 * 3. Orange is the interactive colour (links, actions) and violet is the identity colour (header,
 *    active nav). InstaPay does not use purple for buttons.
 */

private val Violet = Color(0xFF7A12D4)
private val VioletBright = Color(0xFF8E24E8)
private val VioletDeep = Color(0xFF4A0B85)
private val Orange = Color(0xFFF26722)
private val Coral = Color(0xFFF79070)
private val Ground = Color(0xFFF4F4F9)
private val Ink = Color(0xFF1A1A1F)
private val Muted = Color(0xFF9A9AA5)

/**
 * Colours Material3 has no slot for. The transaction row needs a success green and two direction
 * badges, and none of them map onto primary/secondary/tertiary without lying about their meaning.
 */
data class BrandColors(
    val violetBright: Color = VioletBright,
    val positive: Color = Color(0xFF0F7A5C),
    val positiveContainer: Color = Color(0xFFCDF3E4),
    val sentBadge: Color = Color(0xFF2E6BFF),
    val receivedBadge: Color = Color(0xFF17C39A),
    val blobStart: Color = Orange,
    val blobEnd: Color = Coral,
)

val LocalBrand = staticCompositionLocalOf { BrandColors() }

private val InstaBalanceColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE0FB),
    onPrimaryContainer = VioletDeep,

    // Orange is every interactive affordance, which is why it is secondary and not an accent
    // buried in tertiary: it is reached for far more often than the violet.
    secondary = Orange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDE0D2),
    onSecondaryContainer = Color(0xFF8A3208),

    // Coral is 2.29:1 on white, so it is a tint and never carries text or meaning on its own.
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFDE8E1),
    onTertiaryContainer = Color(0xFF8A3208),

    background = Ground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFEFF6),
    onSurfaceVariant = Muted,
    outline = Color(0xFFE4E4EC),
)

// Amounts use tabular figures ("tnum") so a changing balance does not jitter its own width.
private val InstaBalanceTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp),
    )
}

private val InstaBalanceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun InstaBalanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstaBalanceColors,
        typography = InstaBalanceTypography,
        shapes = InstaBalanceShapes,
        content = content,
    )
}

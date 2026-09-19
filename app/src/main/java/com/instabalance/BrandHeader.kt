package com.instabalance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The violet gradient header with the orange blob cutting across its top left. This pairing is
 * what makes InstaPay recognisable at a glance, so it is a real component rather than a TopAppBar:
 * the blob is a drawn path, and the content below is expected to overlap the header's bottom edge
 * the way InstaPay's promo card overlaps its purple.
 */
@Composable
internal fun BrandHeader(
    greeting: String,
    title: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrand.current
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier
            .fillMaxWidth()
            // Tall on purpose: InstaPay's purple runs well past the top of the card that overlaps
            // it, so purple stays visible down both sides of the card rather than being a thin
            // band above it.
            .height(236.dp)
            .clip(BottomRoundedShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Brightest at the very top, where the status bar sits, deepening downward.
            drawRect(
                Brush.linearGradient(
                    colors = listOf(brand.violetBright, scheme.primary, brand.violetDeep),
                    start = Offset(w * 0.1f, 0f),
                    end = Offset(w, h),
                )
            )

            // The chevrons InstaPay sets into its purple: barely-there arrows pointing right,
            // echoing the ">>" of the logo. They must stay almost subliminal, so white at a few
            // percent rather than a second colour.
            val chevron = Stroke(width = w * 0.075f)
            listOf(0.46f, 0.66f, 0.86f).forEach { startX ->
                drawPath(
                    Path().apply {
                        moveTo(w * startX, -h * 0.10f)
                        lineTo(w * (startX + 0.16f), h * 0.52f)
                        lineTo(w * startX, h * 1.14f)
                    },
                    color = Color.White.copy(alpha = 0.055f),
                    style = chevron,
                )
            }

            // Two stacked tabs, not one blob. InstaPay layers a pale coral sheet behind a deeper
            // orange one, offset down and to the right, so the coral reads as the shadow-side edge
            // of a folded ribbon running off the left of the screen. Drawing a single shape (which
            // is what this was) loses the whole effect.
            //
            // Each tab is a rectangle anchored off the left edge with one big rounded end, and a
            // bottom edge that sags as it travels back to the left.
            fun tab(endX: Float, topY: Float, bottomY: Float, leftBottomY: Float) = Path().apply {
                moveTo(-w * 0.14f, topY)
                lineTo(w * (endX - 0.12f), topY)
                cubicTo(
                    w * endX, topY + (bottomY - topY) * 0.24f,
                    w * endX, bottomY - (bottomY - topY) * 0.22f,
                    w * (endX - 0.14f), bottomY,
                )
                lineTo(-w * 0.14f, leftBottomY)
                close()
            }

            // Coral first: wider, and reaching further down, so only its lower-right fringe shows.
            drawPath(
                tab(endX = 0.60f, topY = -h * 0.04f, bottomY = h * 0.80f, leftBottomY = h * 1.02f),
                color = brand.blobEnd,
            )
            // Orange on top, tighter and higher. The greeting and the name sit on this one.
            drawPath(
                tab(endX = 0.50f, topY = -h * 0.04f, bottomY = h * 0.58f, leftBottomY = h * 0.74f),
                color = brand.blobStart,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    }
}

private val BottomRoundedShape = androidx.compose.foundation.shape.RoundedCornerShape(
    bottomStart = 28.dp,
    bottomEnd = 28.dp,
)

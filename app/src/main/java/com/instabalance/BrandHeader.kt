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
import androidx.compose.ui.geometry.Size
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
            // 28.6% of the reference screen's height, measured: InstaPay's purple runs well past
            // the top of the card that overlaps it, so purple stays visible down both sides of the
            // card rather than being a thin band above it.
            .height(262.dp)
            .clip(BottomRoundedShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Measured off the real app: the purple runs bright on the LEFT to deep on the RIGHT.
            // It is a horizontal gradient, not the diagonal one this had before.
            drawRect(
                Brush.linearGradient(
                    colors = listOf(brand.violetBright, scheme.primary, brand.violetDeep),
                    start = Offset(0f, 0f),
                    end = Offset(w, h * 0.35f),
                )
            )

            // The strip behind the status bar is a flat, brighter violet than the body below it.
            drawRect(color = brand.violetStatus, size = Size(w, h * STATUS_STRIP))

            // The chevrons InstaPay sets into its purple: barely-there arrows pointing right,
            // echoing the ">>" of the logo. They must stay almost subliminal, so white at a few
            // percent rather than a second colour.
            val chevron = Stroke(width = w * 0.075f)
            listOf(0.52f, 0.72f, 0.92f).forEach { startX ->
                drawPath(
                    Path().apply {
                        moveTo(w * startX, h * STATUS_STRIP)
                        lineTo(w * (startX + 0.14f), h * 0.60f)
                        lineTo(w * startX, h * 1.14f)
                    },
                    color = Color.White.copy(alpha = 0.045f),
                    style = chevron,
                )
            }

            // Two stacked sheets, not one blob: InstaPay layers a flat coral sheet behind a
            // gradient orange one, so the coral reads as the shadow-side edge of a ribbon running
            // off the left of the screen. Every coordinate below was read off a screenshot of the
            // real app by sampling where one colour becomes the other, then divided by the header
            // size, rather than guessed. Earlier attempts at this by eye kept producing an oval.

            // Coral: behind, wider, sweeping all the way down to the bottom-left corner. Only its
            // lower-right fringe and a strip down the left of the card ever show.
            drawPath(
                Path().apply {
                    moveTo(-w * 0.05f, h * 0.142f)
                    cubicTo(w * 0.30f, h * 0.185f, w * 0.48f, h * 0.260f, w * 0.520f, h * 0.400f)
                    lineTo(w * 0.520f, h * 0.470f)
                    cubicTo(w * 0.515f, h * 0.570f, w * 0.495f, h * 0.605f, w * 0.455f, h * 0.630f)
                    cubicTo(w * 0.300f, h * 0.760f, w * 0.120f, h * 0.900f, -w * 0.05f, h * 1.04f)
                    close()
                },
                color = brand.coral,
            )

            // Orange: in front, with its own gradient. The greeting and the name sit on this one.
            drawPath(
                Path().apply {
                    moveTo(-w * 0.05f, h * 0.142f)
                    cubicTo(w * 0.26f, h * 0.175f, w * 0.44f, h * 0.235f, w * 0.481f, h * 0.330f)
                    lineTo(w * 0.481f, h * 0.400f)
                    cubicTo(w * 0.478f, h * 0.495f, w * 0.462f, h * 0.525f, w * 0.434f, h * 0.548f)
                    cubicTo(w * 0.280f, h * 0.505f, w * 0.120f, h * 0.405f, -w * 0.05f, h * 0.382f)
                    close()
                },
                Brush.linearGradient(
                    colors = listOf(brand.orangeLight, brand.orangeDeep),
                    start = Offset(0f, h * 0.142f),
                    end = Offset(w * 0.48f, h * 0.55f),
                ),
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

/** Fraction of the header taken by the flat, brighter strip behind the status bar. Measured. */
private const val STATUS_STRIP = 0.131f

private val BottomRoundedShape = androidx.compose.foundation.shape.RoundedCornerShape(
    bottomStart = 28.dp,
    bottomEnd = 28.dp,
)

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
            .height(172.dp)
            .clip(BottomRoundedShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    colors = listOf(brand.violetBright, scheme.primary),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )

            // The blob: a rounded wedge anchored off the left edge, angled down to the right, so it
            // reads as one continuous shape running off-screen rather than a decoration floating
            // on top. Proportional to the header so it holds its shape at any width.
            val w = size.width
            val h = size.height
            val blob = Path().apply {
                moveTo(-w * 0.10f, 0f)
                lineTo(w * 0.42f, 0f)
                cubicTo(
                    w * 0.56f, h * 0.10f,
                    w * 0.56f, h * 0.42f,
                    w * 0.40f, h * 0.52f,
                )
                lineTo(-w * 0.10f, h * 0.52f)
                close()
            }
            drawPath(
                blob,
                Brush.linearGradient(
                    colors = listOf(brand.blobStart, brand.blobEnd),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.5f, h * 0.5f),
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

private val BottomRoundedShape = androidx.compose.foundation.shape.RoundedCornerShape(
    bottomStart = 28.dp,
    bottomEnd = 28.dp,
)

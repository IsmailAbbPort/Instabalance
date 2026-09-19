package com.instabalance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Hand-drawn charts. A library would be the heaviest dependency in the app, and the geometry that
 * could actually be wrong (angles, hit testing) lives in [Insights] as pure functions with tests,
 * so what is left here is drawing.
 */

private const val RING_START_DEGREES = -90f // 12 o'clock

@Composable
internal fun DonutChart(
    slices: List<Slice>,
    colourOf: (Slice) -> Color,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    centreLabel: String,
    centreValue: String,
    modifier: Modifier = Modifier,
) {
    val angles = Insights.sliceAngles(slices)
    val gapColour = MaterialTheme.colorScheme.surface

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .pointerInput(angles) {
                    detectTapGestures { offset ->
                        // `size` here is the IntSize of the pointer-input area, not a Compose Size.
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val v = offset - centre
                        val radius = hypot(v.x, v.y)
                        val outer = minOf(size.width, size.height) / 2f
                        // Ignore taps in the hole and outside the ring, so the centre label is not
                        // a giant invisible button for whichever slice happens to be first.
                        if (radius < outer * 0.55f || radius > outer) return@detectTapGestures
                        val degrees = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat() + 90f
                        onSelect(Insights.tapToSliceIndex(degrees, angles))
                    }
                }
        ) {
            if (angles.isEmpty()) return@Canvas
            val strokeWidth = size.minDimension * 0.22f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

            angles.forEachIndexed { i, (start, sweep) ->
                val selected = i == selectedIndex
                val grow = if (selected) strokeWidth * 0.14f else 0f
                drawArc(
                    color = colourOf(slices[i]),
                    startAngle = RING_START_DEGREES + start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset - grow / 2f, inset - grow / 2f),
                    size = Size(arcSize.width + grow, arcSize.height + grow),
                    style = Stroke(width = strokeWidth + grow),
                )
            }
            // White separators drawn after, so neighbouring slices never bleed together. This is
            // the separation the palette deliberately does not try to get from luminance.
            if (angles.size > 1) {
                angles.forEach { (start, _) ->
                    drawArc(
                        color = gapColour,
                        startAngle = RING_START_DEGREES + start - 0.6f,
                        sweepAngle = 1.2f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth * 1.3f),
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centreValue,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                centreLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ChartLegend(
    slices: List<Slice>,
    nameOf: (Slice) -> String,
    colourOf: (Slice) -> Color,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val total = slices.sumOf { it.amountMinor }.coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEachIndexed { i, slice ->
            val selected = i == selectedIndex
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendSwatch(colourOf(slice))
                Spacer(Modifier.width(10.dp))
                Text(
                    nameOf(slice),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                // The percentage is spelled out, so the chart never relies on hue alone.
                Text(
                    "${slice.amountMinor * 100 / total}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    Money.formatMinor(slice.amountMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(colour: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(colour) }
}

/**
 * Bars with a baseline and three gridlines. Labels are drawn as composables above the Canvas
 * rather than measured text inside it: text inside a Canvas under a scroll container has known
 * placement quirks and is not worth the risk for an axis.
 */
@Composable
internal fun BarChart(
    buckets: List<Bucket>,
    highlightLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val max = buckets.maxOfOrNull { it.amountMinor } ?: 0L
    val bar = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outline

    val chartHeight = 140.dp
    val axisLabel = MaterialTheme.typography.labelSmall
    val axisColour = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            // The y axis lives outside the Canvas as ordinary Text, aligned to the same quarters
            // the gridlines use. Measuring text inside a Canvas under a scroll container has known
            // placement quirks, and an axis that drifts is worse than no axis.
            Column(
                Modifier.height(chartHeight).padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                // Top to bottom: full scale, three quarters, half, quarter, zero.
                listOf(1.0, 0.75, 0.5, 0.25, 0.0).forEach { fraction ->
                    Text(
                        Money.formatCompactMinor((max * fraction).toLong()),
                        style = axisLabel,
                        color = axisColour,
                    )
                }
            }

            Canvas(Modifier.weight(1f).height(chartHeight)) {
                val count = buckets.size
                if (count == 0) return@Canvas

                repeat(3) { i ->
                    val y = size.height * (i + 1) / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)

                if (max <= 0L) return@Canvas
                val slot = size.width / count
                val width = (slot * 0.6f).coerceAtMost(28.dp.toPx())
                val radius = androidx.compose.ui.geometry.CornerRadius(width / 3f, width / 3f)

                buckets.forEachIndexed { i, b ->
                    if (b.amountMinor <= 0L) return@forEachIndexed
                    val h = size.height * (b.amountMinor.toFloat() / max.toFloat())
                    val x = slot * i + (slot - width) / 2f
                    drawRoundRect(
                        color = if (highlightLast && i == count - 1) highlight else bar,
                        topLeft = Offset(x, size.height - h),
                        size = Size(width, h),
                        cornerRadius = radius,
                    )
                }
            }
        }
        if (buckets.size >= 2) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(buckets.first().label, style = axisLabel, color = axisColour)
                Text("EGP", style = axisLabel, color = axisColour)
                Text(buckets.last().label, style = axisLabel, color = axisColour)
            }
        }
    }
}

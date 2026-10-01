package com.instabalance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.wrapContentWidth
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

/**
 * [tail] is the categories the "Other categories" row stands for. A roll-up that only ever shows a
 * total is a dead end: it tells you a number and refuses to say what it is made of, so the row
 * opens. Tail rows are not arcs on the ring, so they do not select anything; they are there to be
 * read.
 */
@Composable
internal fun ChartLegend(
    slices: List<Slice>,
    nameOf: (Slice) -> String,
    colourOf: (Slice) -> Color,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    tail: List<Slice> = emptyList(),
    tailExpanded: Boolean = false,
    onToggleTail: () -> Unit = {},
) {
    val total = slices.sumOf { it.amountMinor }.coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEachIndexed { i, slice ->
            val isRollup = slice.categoryId == Insights.OTHER_ROLLUP && tail.isNotEmpty()
            LegendRow(
                name = nameOf(slice),
                colour = colourOf(slice),
                amountMinor = slice.amountMinor,
                total = total,
                bold = i == selectedIndex,
                trailing = if (isRollup) {
                    if (tailExpanded) "Hide the ${tail.size}" else "Show the ${tail.size}"
                } else null,
                onClick = if (isRollup) onToggleTail else ({ onSelect(i) }),
            )
            if (isRollup && tailExpanded) {
                tail.forEach { small ->
                    LegendRow(
                        name = nameOf(small),
                        colour = colourOf(small),
                        amountMinor = small.amountMinor,
                        total = total,
                        bold = false,
                        indent = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendRow(
    name: String,
    colour: Color,
    amountMinor: Long,
    total: Long,
    bold: Boolean,
    trailing: String? = null,
    indent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 2.dp)
            .padding(start = if (indent) 20.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(colour)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            )
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        // The percentage is spelled out, so the chart never relies on hue alone.
        Text(
            "${amountMinor * 100 / total}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            Money.formatMinor(amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LegendSwatch(colour: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(colour) }
}

/**
 * The balance over time, as a line.
 *
 * Scaled between its own lowest and highest point rather than from zero. A balance that moves
 * between 60,000 and 70,000 is a flat line on a zero-based axis, which hides the only thing the
 * chart is for. The y axis prints both ends, so the scale is never a guess.
 *
 * Shares [Bucket] and the axis machinery with [BarChart] deliberately: the two sit one above the
 * other and their dates have to line up.
 */
@Composable
internal fun LineChart(
    points: List<Bucket>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant

    val chartHeight = 140.dp
    val axisLabel = MaterialTheme.typography.labelSmall
    val axisColour = MaterialTheme.colorScheme.onSurfaceVariant

    val low = points.minOfOrNull { it.amountMinor } ?: 0L
    val high = points.maxOfOrNull { it.amountMinor } ?: 0L
    // A flat line would divide by zero. Give it a band so it draws through the middle.
    val span = (high - low).coerceAtLeast(1L)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier.height(chartHeight).padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                listOf(1.0, 0.75, 0.5, 0.25, 0.0).forEach { fraction ->
                    Text(
                        Money.formatCompactMinor(low + (span * fraction).toLong()),
                        style = axisLabel,
                        color = axisColour,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .pointerInput(points.size) {
                            detectTapGestures { offset ->
                                if (points.isEmpty()) return@detectTapGestures
                                val i = (offset.x / (size.width.toFloat() / points.size)).toInt()
                                onSelect(i.coerceIn(0, points.lastIndex))
                            }
                        }
                ) {
                    val count = points.size
                    if (count == 0) return@Canvas

                    repeat(3) { i ->
                        val y = size.height * (i + 1) / 4f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    drawLine(
                        grid, Offset(0f, size.height), Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )

                    // Points sit in the middle of the same slots the bar chart uses, so a date
                    // label lines up with the point it names in both charts.
                    val slot = size.width / count
                    fun xAt(i: Int) = slot * i + slot / 2f
                    fun yAt(v: Long) =
                        size.height - size.height * ((v - low).toFloat() / span.toFloat())

                    if (count == 1) {
                        drawCircle(line, radius = 4.dp.toPx(), center = Offset(xAt(0), yAt(points[0].amountMinor)))
                        return@Canvas
                    }

                    val stroke = 2.dp.toPx()
                    points.forEachIndexed { i, p ->
                        if (i == 0) return@forEachIndexed
                        drawLine(
                            line,
                            Offset(xAt(i - 1), yAt(points[i - 1].amountMinor)),
                            Offset(xAt(i), yAt(p.amountMinor)),
                            strokeWidth = stroke,
                        )
                    }

                    // Only the ends and the selection get a dot. A dot per day turns a thirty day
                    // line into a dotted rule.
                    setOf(0, count - 1, selectedIndex.coerceIn(0, count - 1)).forEach { i ->
                        drawCircle(
                            if (i == selectedIndex) highlight else line,
                            radius = if (i == selectedIndex) 5.dp.toPx() else 3.dp.toPx(),
                            center = Offset(xAt(i), yAt(points[i].amountMinor)),
                        )
                    }
                }

                if (points.size >= 2) {
                    Spacer(Modifier.height(4.dp))
                    val labelled = Insights.axisLabelIndices(points.size)
                    Row(Modifier.fillMaxWidth()) {
                        points.forEachIndexed { i, p ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (i in labelled) {
                                    Text(
                                        p.label,
                                        style = axisLabel,
                                        color = axisColour,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.wrapContentWidth(unbounded = true),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (points.size >= 2) {
            Spacer(Modifier.height(2.dp))
            Text(
                "EGP",
                style = axisLabel,
                color = axisColour,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
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
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = buckets.maxOfOrNull { it.amountMinor } ?: 0L
    val bar = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant

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

            // The labels live in this column rather than under the whole Row, so they are exactly
            // as wide as the plot area. Sharing the Row with the y axis shifted every label by the
            // width of the y axis text, which is why even the two that used to be drawn did not
            // line up with their bars.
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .pointerInput(buckets.size) {
                            // Tapping anywhere in a bar's column selects it, not just the drawn
                            // bar: a near-zero day is a 2px target otherwise.
                            detectTapGestures { offset ->
                                if (buckets.isEmpty()) return@detectTapGestures
                                val i = (offset.x / (size.width.toFloat() / buckets.size)).toInt()
                                onSelect(i.coerceIn(0, buckets.lastIndex))
                            }
                        }
                ) {
                    val count = buckets.size
                    if (count == 0) return@Canvas

                    repeat(3) { i ->
                        val y = size.height * (i + 1) / 4f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    drawLine(
                        grid, Offset(0f, size.height), Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )

                    if (max <= 0L) return@Canvas
                    val slot = size.width / count
                    val width = (slot * 0.6f).coerceAtMost(28.dp.toPx())
                    val radius = androidx.compose.ui.geometry.CornerRadius(width / 3f, width / 3f)

                    buckets.forEachIndexed { i, b ->
                        val selected = i == selectedIndex
                        // A selected empty day still needs a visible marker, or tapping it looks
                        // broken.
                        val h = if (b.amountMinor <= 0L) {
                            if (selected) 3.dp.toPx() else return@forEachIndexed
                        } else {
                            size.height * (b.amountMinor.toFloat() / max.toFloat())
                        }
                        val x = slot * i + (slot - width) / 2f
                        drawRoundRect(
                            color = when {
                                selected -> highlight
                                highlightLast && i == count - 1 -> highlight.copy(alpha = 0.55f)
                                else -> bar
                            },
                            topLeft = Offset(x, size.height - h),
                            size = Size(width, h),
                            cornerRadius = radius,
                        )
                    }
                }

                if (buckets.size >= 2) {
                    Spacer(Modifier.height(4.dp))
                    // One equal-width cell per bar, matching the canvas's own `width / count`
                    // slots, so a label sits under the bar it names. Only some cells carry text:
                    // thirty dates cannot fit at a readable size, and labelling only the two ends
                    // made you count bars to find a day.
                    val labelled = Insights.axisLabelIndices(buckets.size)
                    Row(Modifier.fillMaxWidth()) {
                        buckets.forEachIndexed { i, b ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (i in labelled) {
                                    // Unbounded, or the label is measured against one bar's slot
                                    // and "12" comes out as "1". Thirty bars across a phone is
                                    // about a digit and a half each; the neighbours are blank, so
                                    // the label simply overhangs them and stays centred on its bar.
                                    Text(
                                        b.label,
                                        style = axisLabel,
                                        color = axisColour,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.wrapContentWidth(unbounded = true),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (buckets.size >= 2) {
            Spacer(Modifier.height(2.dp))
            Text(
                "EGP",
                style = axisLabel,
                color = axisColour,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

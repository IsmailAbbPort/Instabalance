package com.instabalance

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

private enum class Period(val label: String) { MONTH("This month"), DAYS_30("30 days"), MONTHS_6("6 months") }

/**
 * Mutually exclusive options with a pill that slides to the one you picked.
 *
 * Material's SegmentedButton was doing two things wrong here. It puts a tick in front of the
 * selected label, which shoves every label sideways the moment you choose and leaves the row a
 * different width than it started. And it swaps the selection with no transition at all, so on a
 * screen where three charts redraw in the same frame there is nothing to say the toggle is what
 * changed. The slide is the whole point: it shows which option you came from.
 */
@Composable
private fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val pill = MaterialTheme.colorScheme.secondaryContainer
    val onPill = MaterialTheme.colorScheme.secondary
    val onTrack = MaterialTheme.colorScheme.onSurfaceVariant

    // No bounce. The pill is reporting a choice, not celebrating it.
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "segmentPosition",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    ) {
        val slot = maxWidth / options.size
        Box(
            Modifier
                .offset(x = slot * position)
                .width(slot)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(CircleShape)
                .background(pill)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                // Read off where the pill actually IS, not off which option is selected. Driving it
                // from the selection meant the label turned orange the instant you tapped and the
                // pill then caught up, so every tap read as two events: a selection, and then a
                // highlight arriving to confirm it. Tied to the travelling pill there is one.
                val arrival = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        // No ripple, for the same reason: it is a third flash of "something
                        // happened here" landing before the thing has happened.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = lerp(onTrack, onPill, arrival),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Named categories the ring shows before anything is folded into "Other categories". A twelfth
 * category is a sliver worth hiding; an eleventh is not, which is why the roll-up also needs at
 * least two to fold (see [Insights.rollUp]).
 */
private const val LEGEND_CATEGORIES = 10

/**
 * The charts. Everything here recomputes from a [LedgerData] snapshot inside `remember(data)`, so
 * a transaction captured in the background produces a new object, a recomposition and fresh
 * numbers, never a half-updated read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsSection(data: LedgerData) {
    // Saveable, not remembered: the tab no longer stays composed while you are on the other one, so
    // a plain remember would throw away which period you were reading every time you glanced at
    // Home. It survives process death for free, which is the same answer to the same question.
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var periodIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var selectedBar by rememberSaveable { mutableIntStateOf(-1) }
    var selectedPoint by rememberSaveable { mutableIntStateOf(-1) }
    var tailExpanded by rememberSaveable { mutableStateOf(false) }

    val period = Period.entries[periodIndex]
    val type = if (showIncome) EntryType.CREDIT else EntryType.DEBIT
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(data) { Instant.now() }

    // Off the main thread, and the cards are skeletons until it lands.
    //
    // This is four passes over the whole ledger, and the balance series alone is a scan per day it
    // draws, so on a real ledger it is the one piece of work on this screen that can miss a frame.
    // It used to run inside composition, which meant the jank landed exactly when you switched tab
    // or changed period, and the screen simply stopped for the length of it.
    val charts by produceState<ChartData?>(initialValue = null, data, type, period) {
        value = null
        value = withContext(Dispatchers.Default) { computeCharts(data, type, period, now, zone) }
    }

    fun nameOf(slice: Slice): String = when (slice.categoryId) {
        null -> "Uncategorised"
        Insights.OTHER_ROLLUP -> "Other categories"
        else -> Categories.byId(data.categories, slice.categoryId)?.name ?: "Unknown"
    }

    fun colourOf(slice: Slice): Color = when (slice.categoryId) {
        null -> Color(ChartPalette.UNCATEGORISED)
        Insights.OTHER_ROLLUP -> Color(ChartPalette.OTHER_ROLLUP)
        else -> Color(
            ChartPalette.forIndex(
                Categories.byId(data.categories, slice.categoryId)?.colorIndex ?: 0
            )
        )
    }

    // What the charts below are OF, in the same white card the balance sits in on the other tab.
    // Both tabs then open on a card of the same shape in the same place, so swiping between them
    // moves the content and leaves the header where it was rather than appearing to jump.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SegmentedToggle(
                    options = listOf("Expenses", "Income"),
                    selectedIndex = if (showIncome) 1 else 0,
                    onSelect = { i ->
                        showIncome = i == 1; selected = -1; selectedBar = -1; tailExpanded = false
                    },
                )
                SegmentedToggle(
                    options = Period.entries.map { it.label },
                    selectedIndex = periodIndex,
                    onSelect = { i ->
                        periodIndex = i
                        selected = -1; selectedBar = -1; tailExpanded = false
                    },
                )
            }
        }

        val ready = charts
        if (ready == null) {
            ChartSkeletons()
            return@Column
        }

        val slices = ready.rollup.visible
        val total = slices.sumOf { it.amountMinor }

        InsightCard(if (showIncome) "Income by category" else "Spend by category") {
            if (slices.isEmpty() || total == 0L) {
                EmptyChart(if (showIncome) "No income in this period yet" else "No expenses in this period yet")
            } else {
                // getOrNull, not slices[selected]: the selection survives ledger changes, so
                // filing the last uncategorised entry while its legend row is selected shrinks
                // the list under a now out-of-range index.
                val pickedSlice = slices.getOrNull(selected)
                DonutChart(
                    slices = slices,
                    colourOf = ::colourOf,
                    selectedIndex = selected,
                    onSelect = { selected = if (it == selected) -1 else it },
                    centreLabel = pickedSlice?.let { nameOf(it) } ?: "total",
                    centreValue = Money.formatMinor(pickedSlice?.amountMinor ?: total),
                )
                Spacer(Modifier.height(16.dp))
                ChartLegend(
                    slices = slices,
                    nameOf = ::nameOf,
                    colourOf = ::colourOf,
                    selectedIndex = selected,
                    onSelect = { selected = if (it == selected) -1 else it },
                    tail = ready.rollup.tail,
                    tailExpanded = tailExpanded,
                    onToggleTail = { tailExpanded = !tailExpanded },
                )
            }
        }

        // Above the spending bars on purpose: the bars say where it went, the line says whether you
        // are running down, and the second question is the one you open the app asking.
        InsightCard("Balance over time") {
            val series = ready.series
            if (series.size < 2) {
                EmptyChart("Set your balance once, and this fills in from there")
            } else {
                val picked = series.getOrNull(selectedPoint)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        picked?.fullLabel ?: "Tap for the balance that day",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (picked != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (picked != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (picked != null) {
                        Text(
                            "${Money.formatMinor(picked.amountMinor)} EGP",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LineChart(
                    points = series,
                    selectedIndex = selectedPoint,
                    onSelect = { selectedPoint = if (it == selectedPoint) -1 else it },
                )
            }
        }

        InsightCard("Over time") {
            val buckets = ready.buckets
            if (buckets.all { it.amountMinor == 0L }) {
                EmptyChart("Nothing in this period yet")
            } else {
                // Thirty dates will not fit along an axis at a readable size, so a bar says which
                // day it is by being tapped. The readout keeps a fixed height whether or not
                // anything is selected, so the card does not jump as you tap along the chart.
                val picked = buckets.getOrNull(selectedBar)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        picked?.fullLabel ?: "Tap a bar for its day",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (picked != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (picked != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (picked != null) {
                        Text(
                            "${Money.formatMinor(picked.amountMinor)} EGP",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                BarChart(
                    buckets = buckets,
                    highlightLast = true,
                    selectedIndex = selectedBar,
                    onSelect = { selectedBar = if (it == selectedBar) -1 else it },
                )
            }
        }

        InsightCard("This month vs last") {
            val c = ready.comparison
            ComparisonRow("So far this month", c.current, MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            ComparisonRow("Same days last month", c.previousWindow, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            ComparisonRow("All of last month", c.previousFull,
                MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Everything the four cards draw, computed in one pass off the main thread. */
private data class ChartData(
    val rollup: Insights.Rollup,
    val series: List<Bucket>,
    val buckets: List<Bucket>,
    val comparison: MonthComparison,
)

private fun computeCharts(
    data: LedgerData,
    type: EntryType,
    period: Period,
    now: Instant,
    zone: ZoneId,
): ChartData {
    val today = now.atZone(zone).toLocalDate()
    val from = when (period) {
        Period.MONTH -> today.withDayOfMonth(1)
        Period.DAYS_30 -> today.minusDays(29)
        Period.MONTHS_6 -> YearMonth.from(today).minusMonths(5).atDay(1)
    }
    return ChartData(
        rollup = Insights.rollUp(
            Insights.byCategory(
                data.entries, type, from.atStartOfDay(zone).toInstant(), now, zone,
            ),
            LEGEND_CATEGORIES,
        ),
        series = Insights.balanceSeries(
            data.entries,
            when (period) {
                // Six months of daily points is unreadable on a phone, and the balance line is
                // about the shape of the last few weeks either way.
                Period.MONTHS_6 -> 90
                Period.MONTH -> today.dayOfMonth
                Period.DAYS_30 -> 30
            },
            now, zone,
        ),
        buckets = when (period) {
            Period.MONTHS_6 -> Insights.monthly(data.entries, type, 6, now, zone)
            // Month to date, matching the donut above it. Drawing a rolling 30 days here while the
            // ring showed this month made the two disagree on the same card.
            Period.MONTH -> Insights.daily(data.entries, type, today.dayOfMonth, now, zone)
            Period.DAYS_30 -> Insights.daily(data.entries, type, 30, now, zone)
        },
        comparison = Insights.monthComparison(data.entries, type, now, zone),
    )
}

/**
 * Cards of the right shape with their contents greyed out, held while [computeCharts] runs.
 *
 * The cards themselves are real, so the page keeps its height and nothing below jumps when the
 * numbers land. A spinner would say "wait" without saying what for; this says what is coming.
 */
@Composable
private fun ChartSkeletons() = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    InsightCard("Spend by category") {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            ShimmerBlock(Modifier.size(180.dp).clip(CircleShape))
        }
        Spacer(Modifier.height(16.dp))
        repeat(2) {
            ShimmerBlock(Modifier.fillMaxWidth().height(14.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.height(10.dp))
        }
    }
    InsightCard("Balance over time") {
        ShimmerBlock(Modifier.fillMaxWidth().height(140.dp).clip(MaterialTheme.shapes.small))
    }
    InsightCard("Over time") {
        ShimmerBlock(Modifier.fillMaxWidth().height(140.dp).clip(MaterialTheme.shapes.small))
    }
    InsightCard("This month vs last") {
        repeat(3) {
            ShimmerBlock(Modifier.fillMaxWidth().height(14.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * A placeholder that breathes rather than sits there, so a slow ledger reads as working rather
 * than as stuck. One infinite transition drives every block on screen.
 */
@Composable
private fun ShimmerBlock(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)))
}

@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyChart(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 20.dp),
    )
}

@Composable
private fun ComparisonRow(label: String, amountMinor: Long, colour: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            Money.formatMinor(amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colour,
        )
    }
}

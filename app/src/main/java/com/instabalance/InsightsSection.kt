package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

private enum class Period(val label: String) { MONTH("This month"), DAYS_30("30 days"), MONTHS_6("6 months") }

/**
 * The charts. Everything here recomputes from a [LedgerData] snapshot inside `remember(data)`, so
 * a transaction captured in the background produces a new object, a recomposition and fresh
 * numbers, never a half-updated read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsSection(data: LedgerData) {
    var showIncome by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf(Period.MONTH) }
    var selected by remember { mutableIntStateOf(-1) }

    val type = if (showIncome) EntryType.CREDIT else EntryType.DEBIT
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(data) { Instant.now() }

    val slices = remember(data, type, period) {
        val today = now.atZone(zone).toLocalDate()
        val from = when (period) {
            Period.MONTH -> today.withDayOfMonth(1)
            Period.DAYS_30 -> today.minusDays(29)
            Period.MONTHS_6 -> YearMonth.from(today).minusMonths(5).atDay(1)
        }
        Insights.topN(
            Insights.byCategory(
                data.entries, type,
                from.atStartOfDay(zone).toInstant(), now, zone,
            ),
            7,
        )
    }

    val total = slices.sumOf { it.amountMinor }

    fun nameOf(slice: Slice): String = when (slice.categoryId) {
        null -> "Uncategorised"
        Insights.OTHER_ROLLUP -> "Other categories"
        else -> Categories.byId(data.categories, slice.categoryId)?.name ?: "Unknown"
    }

    fun colourOf(slice: Slice): Color = when (slice.categoryId) {
        null -> Color(ChartPalette.UNCATEGORISED)
        Insights.OTHER_ROLLUP -> Color(ChartPalette.UNCATEGORISED)
        else -> Color(
            ChartPalette.forIndex(
                Categories.byId(data.categories, slice.categoryId)?.colorIndex ?: 0
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Insights", style = MaterialTheme.typography.titleMedium)
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { i, income ->
                SegmentedButton(
                    selected = showIncome == income,
                    onClick = { showIncome = income; selected = -1 },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(if (income) "Income" else "Expenses") }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = period == p,
                    onClick = { period = p; selected = -1 },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                ) { Text(p.label, style = MaterialTheme.typography.labelMedium) }
            }
        }

        InsightCard(if (showIncome) "Income by category" else "Spend by category") {
            if (slices.isEmpty() || total == 0L) {
                EmptyChart(if (showIncome) "No income in this period yet" else "No expenses in this period yet")
            } else {
                DonutChart(
                    slices = slices,
                    colourOf = ::colourOf,
                    selectedIndex = selected,
                    onSelect = { selected = if (it == selected) -1 else it },
                    centreLabel = if (selected >= 0) nameOf(slices[selected]) else "total",
                    centreValue = Money.formatMinor(
                        if (selected >= 0) slices[selected].amountMinor else total
                    ),
                )
                Spacer(Modifier.height(16.dp))
                ChartLegend(slices, ::nameOf, ::colourOf, selected) {
                    selected = if (it == selected) -1 else it
                }
            }
        }

        InsightCard("Over time") {
            val buckets = remember(data, type, period) {
                if (period == Period.MONTHS_6) Insights.monthly(data.entries, type, 6, now, zone)
                else Insights.daily(data.entries, type, 30, now, zone)
            }
            if (buckets.all { it.amountMinor == 0L }) {
                EmptyChart("Nothing in this period yet")
            } else {
                BarChart(buckets, highlightLast = true)
            }
        }

        InsightCard("This month vs last") {
            val c = remember(data, type) { Insights.monthComparison(data.entries, type, now, zone) }
            ComparisonRow("So far this month", c.current, MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            ComparisonRow("Same days last month", c.previousWindow, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            ComparisonRow("All of last month", c.previousFull,
                MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

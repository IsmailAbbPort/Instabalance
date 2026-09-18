package com.instabalance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The one place you have to look to know where you stand. The bar changes colour as it goes, so
 * the state is readable without reading the numbers, and the numbers are there anyway so colour is
 * never the only signal.
 */
@Composable
internal fun BudgetCard(status: BudgetStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val barColour = when {
        status.percent >= 100 -> scheme.error
        status.percent >= 90 -> scheme.secondary
        else -> scheme.primary
    }

    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Monthly budget", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${status.percent}%", fontWeight = FontWeight.Bold, color = barColour)
            }
            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(scheme.surfaceVariant)
            ) {
                // Clamped so 180 percent does not try to draw past the end of the track.
                val fraction = (status.percent.coerceIn(0, 100)) / 100f
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(barColour)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "${Money.formatMinor(status.spentMinor)} of ${Money.formatMinor(status.limitMinor)} EGP",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${status.daysLeft} days left  •  on track for ${Money.formatMinor(status.projectedMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** The tappable "N to categorise" banner. Hidden entirely at zero rather than showing a proud 0. */
@Composable
internal fun InboxBanner(count: Int, onClick: () -> Unit) {
    if (count == 0) return
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 transaction to categorise" else "$count transactions to categorise",
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSecondaryContainer,
                )
                Text(
                    "Tap to file them",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSecondaryContainer,
                )
            }
            Text("›", color = scheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
        }
    }
}

/** Kept out of the composable so the "no budget set" case has one obvious home. */
internal fun budgetStatusOrNull(data: LedgerData): BudgetStatus? {
    val limit = data.monthlyBudgetMinor ?: return null
    if (limit <= 0L) return null
    return Budget.status(
        data.entries, limit, java.time.Instant.now(), java.time.ZoneId.systemDefault()
    )
}

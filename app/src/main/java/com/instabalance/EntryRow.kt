package com.instabalance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One transaction row, shared by the Home list and the All transactions screen. A top-level
 * `private` in Kotlin is file-scoped, so this cannot live beside either of the two screens that
 * draw it without the other growing a second copy that then drifts.
 *
 * Laid out like InstaPay's own transaction row: the amount leads at the left, the category sits at
 * the right, and the counterparty and date sit underneath.
 */
@Composable
internal fun EntryRow(e: Entry, data: LedgerData, onOpen: (Entry) -> Unit) {
    val brand = LocalBrand.current
    val category = Categories.byId(data.categories, e.categoryId)
    val sign = when (e.type) {
        EntryType.CREDIT -> "+"
        EntryType.DEBIT -> "-"
        EntryType.ANCHOR -> "="
    }

    Card(
        onClick = { onOpen(e) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$sign ${Money.formatMinor(e.amountMinor)} EGP",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (e.type) {
                        EntryType.CREDIT -> brand.positive
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (e.type != EntryType.ANCHOR) {
                    CategoryPill(category)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(e.displayCounterparty(), style = MaterialTheme.typography.bodyMedium)
            // Only when it is not already the line above: displayCounterparty falls back to the
            // note when the message named nobody, which is most of them.
            if (e.note.isNotBlank() && e.note != e.displayCounterparty()) {
                Text(
                    e.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                e.displayDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CategoryPill(category: Category?) {
    val scheme = MaterialTheme.colorScheme
    val uncategorised = category == null
    Row(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (uncategorised) scheme.surfaceVariant else scheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(category, size = 8)
        Spacer(Modifier.width(6.dp))
        Text(
            category?.name ?: "Uncategorised",
            style = MaterialTheme.typography.labelSmall,
            color = if (uncategorised) scheme.onSurfaceVariant else scheme.onPrimaryContainer,
        )
    }
}

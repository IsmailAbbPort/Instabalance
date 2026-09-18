package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything about one entry, and the only place it can be deleted.
 *
 * Delete used to sit as an icon on every row in the list, one tap from gone, with no confirmation
 * and no undo, on a ledger whose whole point is being an accurate record of money.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailSheet(
    entry: Entry,
    data: LedgerData,
    onDismiss: () -> Unit,
    onChangeCategory: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    val category = Categories.byId(data.categories, entry.categoryId)
    val sign = when (entry.type) {
        EntryType.CREDIT -> "+"
        EntryType.DEBIT -> "-"
        EntryType.ANCHOR -> "="
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$sign ${Money.formatMinor(entry.amountMinor)} EGP",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (entry.type == EntryType.CREDIT) LocalBrand.current.positive
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(entry.displayCounterparty(), style = MaterialTheme.typography.bodyLarge)
            Text(
                entry.displayDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Captured by ${entry.source.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entry.type != EntryType.ANCHOR) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Category", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AssistChip(
                        onClick = onChangeCategory,
                        leadingIcon = { CategoryDot(category) },
                        label = { Text(category?.name ?: "Uncategorised") },
                    )
                }
                if (entry.categoryFromRule) {
                    Text(
                        "Filed automatically by a merchant rule. Tap to change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!entry.rawText.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Hide original message" else "Show original message")
                }
                if (showRaw) {
                    Text(
                        entry.rawText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = {
                Text(
                    if (entry.type == EntryType.ANCHOR) {
                        "Your balance will be recalculated from the previous re-sync."
                    } else {
                        "Your balance will change by ${Money.formatMinor(entry.amountMinor)} EGP. " +
                            "This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.deleteEntry(entry.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

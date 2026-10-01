package com.instabalance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The swatch that identifies a category everywhere it appears. */
@Composable
internal fun CategoryDot(category: Category?, size: Int = 10) {
    val colour = category?.let { Color(ChartPalette.forIndex(it.colorIndex)) }
        ?: Color(ChartPalette.UNCATEGORISED)
    Box(Modifier.size(size.dp).clip(CircleShape).background(colour))
}

/**
 * One sheet, never a sheet on top of a sheet: creating a category happens inline here, because
 * sending someone to a second screen mid-triage is how a review queue stops getting used.
 *
 * Tapping a chip assigns immediately and dismisses. If the entry names a merchant nobody has
 * filed before, the sheet also offers to remember it, pre-checked, so one tap both files this
 * transaction and every future one from the same place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CategoryPickerSheet(
    entry: Entry,
    data: LedgerData,
    recentIds: List<String>,
    onDismiss: () -> Unit,
    onPicked: (categoryId: String, learnPattern: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = Categories.visibleFor(data.categories, entry.type)

    val existingRule = MerchantRules.match(data.merchantRules, entry.merchant)
    val canLearn = !entry.merchant.isNullOrBlank() && existingRule == null
    var learn by remember { mutableStateOf(canLearn) }
    var pattern by remember {
        mutableStateOf(entry.merchant?.let { MerchantRules.proposePattern(it) } ?: "")
    }

    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun pick(id: String) {
        onPicked(id, if (learn && pattern.isNotBlank()) pattern else null)
    }

    // Suggested: what a rule would have said, then where this exact amount went last time, then
    // what you reached for most recently. All three are just shortcuts into the same list below,
    // never a different set of choices.
    val suggested = buildList {
        existingRule?.categoryId?.let { add(it) }
        addAll(amountTwinCategoryIds(data.entries, entry))
        addAll(recentIds)
    }.distinct().mapNotNull { id -> options.firstOrNull { it.id == id } }.take(4)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                if (entry.type == EntryType.CREDIT) "File this income" else "File this expense",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(if (entry.type == EntryType.CREDIT) "+" else "-")
                    append(" ")
                    append(Money.formatMinor(entry.amountMinor))
                    entry.merchant?.let { append("  •  $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (suggested.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Suggested", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggested.forEach { CategoryChip(it) { pick(it.id) } }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("All", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            // Twenty chips wrap to four rows, and without the vertical spacing those rows sit flush
            // against each other and read as one squashed block.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { CategoryChip(it) { pick(it.id) } }
                FilterChip(
                    selected = false,
                    onClick = { creating = !creating },
                    label = { Text(if (creating) "Cancel" else "New category") },
                )
            }

            if (creating) {
                Spacer(Modifier.height(12.dp))
                // Stacked, not side by side: sharing the row with the button left the name field
                // about half a phone wide, which is not enough to read what you are typing.
                val clash = Categories.nameTaken(data.categories, newName)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = clash,
                    supportingText = if (clash) {
                        { Text(duplicateNameMessage(data.categories, newName)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = newName.isNotBlank() && !clash,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val kind = if (entry.type == EntryType.CREDIT) CategoryKind.INCOME
                        else CategoryKind.EXPENSE
                        // First unused colour, so a category made mid-triage does not come out
                        // looking identical to one that already exists.
                        LedgerRepository.addCategory(
                            newName, kind,
                            Categories.firstFreeColour(data.categories) ?: 0,
                        )?.let { pick(it) }
                    },
                ) { Text("Add") }
            }

            if (canLearn) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // One line beside the switch. The second line used to say "Matches anything
                    // containing X", which restated the field directly below it and wrapped to
                    // three lines, squeezing both against each other.
                    Text(
                        "Always file this merchant here",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = learn, onCheckedChange = { learn = it })
                }
                if (learn) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.uppercase() },
                        label = { Text("Match on") },
                        supportingText = { Text("Any shop whose name contains this.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (existingRule != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "A rule already files \"${existingRule.pattern}\". Picking here changes only " +
                        "this transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun CategoryChip(category: Category, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        leadingIcon = { CategoryDot(category) },
        label = { Text(category.name, fontWeight = FontWeight.Medium) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

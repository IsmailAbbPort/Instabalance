package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Long enough that a normal sentence is one write, short enough to beat a fast dismiss. */
private const val NOTE_SAVE_DELAY_MS = 600L

/**
 * Everything about one entry, and the only place it can be deleted.
 *
 * Delete used to sit as an icon on every row in the list, one tap from gone, with no confirmation
 * and no undo, on a ledger whose whole point is being an accurate record of money. It now has
 * both: [onDeleted] hands the removed entry back so the screen underneath can offer to put it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailSheet(
    original: Entry,
    data: LedgerData,
    onDismiss: () -> Unit,
    onChangeCategory: () -> Unit,
    onDeleted: (Entry) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var makingRule by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current

    // Resolved from the live ledger by id, not the snapshot the caller captured on tap: saving a
    // note here would otherwise leave the sheet rendering the entry as it was before the save.
    val entry = data.entries.firstOrNull { it.id == original.id } ?: original
    var note by remember(original.id) { mutableStateOf(entry.note) }

    // Typing is the save. A write re-encrypts and rewrites the whole ledger, so it is debounced
    // rather than done per keystroke, and flushed on the way out so closing the sheet quickly
    // never loses the last few characters.
    LaunchedEffect(note, entry.id) {
        if (note.trim() == entry.note) return@LaunchedEffect
        delay(NOTE_SAVE_DELAY_MS)
        LedgerRepository.setNote(entry.id, note)
    }
    val latestNote by rememberUpdatedState(note)
    DisposableEffect(entry.id) {
        val id = entry.id
        onDispose {
            val saved = LedgerRepository.data.value.entries.firstOrNull { it.id == id }?.note
            if (saved != null && latestNote.trim() != saved) LedgerRepository.setNote(id, latestNote)
        }
    }

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
            // A rule needs a shop to match on and a category to file into. Rather than vanish when
            // one is missing, the chip stays put and a line underneath says which, because a
            // control that is simply absent is one you never find out existed.
            val merchant = entry.merchant
            val existingRule = MerchantRules.match(data.merchantRules, merchant)
            val canOfferRule = entry.type != EntryType.ANCHOR &&
                !merchant.isNullOrBlank() && existingRule == null

            // What it was on the left, what you can do about it on the right, the two beside each
            // other. The chips carry their own meaning, so the word "Category" beside one was a
            // label for something already labelled.
            //
            // The whole of the left side is one column, shop and date included, so the pills sit
            // alongside the details rather than pushing them down past the second chip.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "$sign ${Money.formatMinor(entry.amountMinor)} EGP",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.type == EntryType.CREDIT) LocalBrand.current.positive
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        entry.displayCounterparty(data.merchantRules),
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
                }
                if (entry.type != EntryType.ANCHOR) {
                    Spacer(Modifier.width(12.dp))
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistChip(
                            onClick = onChangeCategory,
                            leadingIcon = { CategoryDot(category) },
                            label = { Text(category?.name ?: "Uncategorised") },
                        )
                        if (canOfferRule) {
                            AssistChip(
                                enabled = entry.categoryId != null,
                                onClick = { makingRule = true },
                                label = { Text("Add as Merchant Rule") },
                            )
                        }
                    }
                }
            }

            if (entry.type != EntryType.ANCHOR) {
                val footnote = when {
                    merchant.isNullOrBlank() ->
                        "This message names no shop, so there is nothing for a rule to match."
                    existingRule != null ->
                        "A rule already files \"${existingRule.pattern}\" here. Edit it in " +
                            "Settings, Merchant rules."
                    entry.categoryId == null ->
                        "Give it a category first, and a rule will file every future transaction " +
                            "from this shop the same way."
                    entry.categoryFromRule ->
                        "Filed automatically by a merchant rule. Tap the category to change it."
                    else -> null
                }
                footnote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                placeholder = { Text("What was this for?") },
                supportingText = { Text("Saved as you type") },
                modifier = Modifier.fillMaxWidth(),
            )

            // An InstaPay transfer is announced twice and the dedupe keeps whichever landed first,
            // usually the bank SMS. That is the worse one to read: it calls the sender "**" and
            // splices their name into the middle of its own support line, where the app's
            // notification simply names them. Show that one when Learning mode kept it.
            val capture = remember(entry.id, data.captures, data.learningMode, data.smsConfig) {
                if (data.learningMode) captureFor(entry, data.captures, data.smsConfig) else null
            }
            val original = capture?.text ?: entry.rawText

            if (!original.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "Hide original message" else "Show original message")
                    }
                    ConfirmingTextButton(label = "Copy", confirmedLabel = "Copied!") {
                        clipboard.setText(AnnotatedString(original))
                    }
                }
                if (showRaw) {
                    if (capture != null) {
                        Text(
                            if (capture.channel == "NOTIFICATION") {
                                "As the app itself announced it, kept by Learning mode."
                            } else {
                                "As it arrived, kept by Learning mode."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        original,
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

    if (makingRule) {
        val merchant = entry.merchant.orEmpty()
        val categoryId = entry.categoryId
        if (categoryId == null) {
            makingRule = false
        } else {
            MakeRuleDialog(
                merchant = merchant,
                categoryName = Categories.byId(data.categories, categoryId)?.name ?: "this category",
                entries = data.entries,
                categoryId = categoryId,
                onDismiss = { makingRule = false },
                onConfirm = { pattern, alsoApply ->
                    makingRule = false
                    val rule = LedgerRepository.addRule(pattern, categoryId, System.currentTimeMillis())
                    if (rule != null && alsoApply) LedgerRepository.applyRuleToUncategorised(rule)
                },
            )
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
                        "Your balance will change by ${Money.formatMinor(entry.amountMinor)} EGP."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = LedgerRepository.deleteEntry(entry.id)
                    confirmDelete = false
                    onDismiss()
                    // After the dismiss: the sheet is what closes, so the Undo has to be offered by
                    // the screen underneath it.
                    removed?.let(onDeleted)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Turns one transaction into a standing rule.
 *
 * The pattern is a suggestion, not a decision: it is matched with `contains` against a normalised
 * merchant, so the difference between "TALABAT" and "TALABAT MAADI" is the difference between
 * catching every Talabat order and catching one branch. The count underneath is the point of
 * showing it at all, because it says out loud what the rule is about to do before it does it.
 */
@Composable
private fun MakeRuleDialog(
    merchant: String,
    categoryName: String,
    entries: List<Entry>,
    categoryId: String,
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, alsoApply: Boolean) -> Unit,
) {
    var pattern by remember { mutableStateOf(MerchantRules.proposePattern(merchant)) }
    var alsoApply by remember { mutableStateOf(true) }

    val normalised = MerchantRules.normalise(pattern)
    // Counted with the pure function, which changes nothing. The writing version is only ever
    // reached from the confirm button.
    val wouldCatch = remember(normalised, entries) {
        if (normalised.isEmpty()) 0
        else MerchantRules.applyToUncategorised(
            entries,
            MerchantRule(pattern = normalised, categoryId = categoryId, createdAt = 0L),
        ).second
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Always file this shop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Anything whose shop name contains this goes to $categoryName.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.uppercase() },
                    label = { Text("Match on") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "From this transaction: $merchant",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (wouldCatch > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoApply, onCheckedChange = { alsoApply = it })
                        Text(
                            if (wouldCatch == 1) "Also file 1 transaction already waiting"
                            else "Also file $wouldCatch transactions already waiting",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "Only ones you have not sorted yourself. Nothing you have already filed " +
                            "by hand is touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalised.isNotEmpty(),
                onClick = { onConfirm(pattern, alsoApply) },
            ) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

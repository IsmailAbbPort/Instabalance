package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * What the re-sync dialog says underneath the amount.
 *
 * Two numbers are already in hand at this moment: what the ledger thinks you have, and what you are
 * typing in from the real app. Until now the difference was absorbed silently, which meant there
 * was no way to tell a capture that is working from one that has been quietly missing messages for
 * a fortnight. Naming the gap is the whole feature; it is also the thing to look at later when
 * asking why the numbers drifted.
 *
 * [since] is when the ledger was last anchored, so the sentence can say what period the gap covers.
 */
internal fun anchorDeltaText(
    typedMinor: Long?,
    currentMinor: Long,
    since: Long?,
    now: Long = System.currentTimeMillis(),
): String {
    val ledger = "The app has you at ${Money.formatMinor(currentMinor)} EGP"
    if (typedMinor == null) return "$ledger."

    val drift = typedMinor - currentMinor
    if (drift == 0L) return "$ledger, which matches exactly."

    val size = Money.formatMinor(kotlin.math.abs(drift))
    val direction = if (drift > 0) "more" else "less"
    val period = sinceText(since, now)
    return "$ledger. That is $size EGP $direction than recorded$period, " +
        "so something moved without a message."
}

private fun sinceText(since: Long?, now: Long): String {
    if (since == null) return ""
    val days = ((now - since) / (24 * 60 * 60 * 1000L)).toInt()
    return when {
        days < 0 -> ""
        days == 0 -> " since the last sync today"
        days == 1 -> " in the last day"
        else -> " over the last $days days"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmountDialog(
    kind: Dialog,
    categories: List<Category>,
    currentBalanceMinor: Long,
    lastAnchorAt: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, String?) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }

    val title = when (kind) {
        Dialog.CREDIT -> "Money received"
        Dialog.DEBIT -> "Money sent"
        Dialog.ANCHOR -> "Set current balance"
        Dialog.NONE -> ""
    }
    // An anchor is a re-sync of the balance, not money moving, so it is never categorised.
    val options = when (kind) {
        Dialog.CREDIT -> Categories.visibleFor(categories, EntryType.CREDIT)
        Dialog.DEBIT -> Categories.visibleFor(categories, EntryType.DEBIT)
        else -> emptyList()
    }
    val minor = Money.parseToMinor(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = sanitizeAmount(it) },
                    label = { Text("Amount (EGP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                // Only on a re-sync, where the app already knows both numbers and has until now
                // thrown the comparison away. The gap between them is money that moved without a
                // message the app ever saw, which is the one measure of whether the capture is
                // working at all.
                if (kind == Dialog.ANCHOR) {
                    Text(
                        anchorDeltaText(minor, currentBalanceMinor, lastAnchorAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (options.isNotEmpty()) {
                    Text(
                        "Category (optional)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Nothing is selected by default: a wrong silent default is worse than the
                    // entry turning up in the review inbox, where it is visible and one tap to fix.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        options.forEach { c ->
                            FilterChip(
                                selected = categoryId == c.id,
                                onClick = { categoryId = if (categoryId == c.id) null else c.id },
                                leadingIcon = { CategoryDot(c) },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = minor != null && minor > 0,
                onClick = { minor?.let { onConfirm(it, note.trim(), categoryId) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun PasscodeSetupDialog(onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length == PIN_LENGTH && pin.all { it.isDigit() } && pin == confirm

    fun digits(s: String) = s.filter { it.isDigit() }.take(PIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a $PIN_LENGTH-digit passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digits(it) },
                    label = { Text("New passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = digits(it) },
                    label = { Text("Confirm passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (confirm.length == PIN_LENGTH && pin != confirm) {
                    Text("Passcodes don't match", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { LedgerRepository.setPasscode(pin); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

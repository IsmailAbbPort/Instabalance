package com.instabalance

import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * The handful of UI symbols used by more than one screen file. A top-level `private` in Kotlin is
 * visible only within its own file, so these cannot live beside any single screen that uses them.
 * `internal` keeps them off the module's public surface, which is what `private` was buying.
 */

internal const val PIN_LENGTH = 4

/**
 * Whether the destructive developer tools are offered at all.
 *
 * `BuildConfig.DEBUG` on its own is the wrong test, and that is the entire point of this. Until the
 * release build is signed, the APK installed on the real phone IS the debug one, so a debug-only
 * check puts "Load sample data" and "Clear all transactions" on the device holding the real ledger,
 * which is exactly where they must never be. Running on an emulator is what actually separates a
 * development machine from a phone; the debug half keeps the tools out of a release build once one
 * exists, so this stays correct after the release signing is finished.
 */
internal val isDevSandbox: Boolean by lazy {
    BuildConfig.DEBUG && looksLikeEmulator(
        hardware = Build.HARDWARE.orEmpty(),
        fingerprint = Build.FINGERPRINT.orEmpty(),
        product = Build.PRODUCT.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )
}

/**
 * `ranchu` is the current Android emulator's hardware and `goldfish` the older one, which is the
 * most reliable signal. The rest cover system images that report something else.
 *
 * The values are passed in rather than read from [Build] so this can be tested against a real
 * handset's properties. Testing the case that matters, a phone being told no, is otherwise
 * impossible without the phone in hand, and getting it wrong puts "Clear all transactions" on the
 * device holding the only copy of the ledger.
 */
internal fun looksLikeEmulator(
    hardware: String,
    fingerprint: String,
    product: String,
    model: String,
): Boolean =
    hardware.lowercase().let { it.contains("ranchu") || it.contains("goldfish") } ||
        fingerprint.lowercase().let { it.contains("generic") || it.contains("/sdk_gphone") } ||
        product.lowercase().startsWith("sdk") ||
        model.contains("Emulator")

internal enum class Dialog { NONE, CREDIT, DEBIT, ANCHOR }

/** How long a button says what it just did before offering the action again. */
internal const val CONFIRM_LABEL_MS = 1_000L

/**
 * A text button that confirms in place: "Save watched apps" becomes "Saved!" for a second.
 *
 * These actions write silently and leave the screen looking identical, so without this there is
 * no way to tell a tap that worked from a tap that missed. Confirming on the button itself rather
 * than with a toast, because a toast covers the bottom of the screen, which is where the buttons
 * usually are.
 */
@Composable
internal fun ConfirmingTextButton(
    label: String,
    confirmedLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(confirmed) {
        if (confirmed) {
            delay(CONFIRM_LABEL_MS)
            confirmed = false
        }
    }
    TextButton(
        modifier = modifier,
        enabled = enabled,
        onClick = { onClick(); confirmed = true },
    ) { Text(if (confirmed) confirmedLabel else label) }
}

/** Keeps only digits and a single decimal point, so money fields accept numbers only. */
internal fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot == -1) filtered
    else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

/**
 * What to call the other party on a row. An InstaPay send seen only as a bank SMS names nobody, so
 * it gets an honest label rather than a blank.
 *
 * A matching rule's [MerchantRule.label] wins, which is the only way "UBER TRIP HELP.UBER.COM" ever
 * reads as "Uber". Passing no rules leaves the raw string, which is what the pickers want: they are
 * showing you what the message said while you decide what to do about it.
 */
internal fun Entry.displayCounterparty(rules: List<MerchantRule> = emptyList()): String = when {
    !merchant.isNullOrBlank() ->
        MerchantRules.match(rules, merchant)?.label?.takeIf { it.isNotBlank() } ?: merchant
    note.isNotBlank() -> note
    source == Source.FEE -> "InstaPay transfer fee"
    type == EntryType.ANCHOR -> "Balance re-synced"
    type == EntryType.CREDIT -> "Money received"
    else -> "InstaPay transfer"
}

/**
 * What the Undo bar says about a deleted entry. Names the amount rather than the shop, because the
 * amount is what just moved on the balance card behind the bar and an anchor has no shop at all.
 */
internal fun deletedMessage(entry: Entry): String = when (entry.type) {
    EntryType.ANCHOR -> "Re-sync deleted"
    else -> "Deleted ${Money.formatMinor(entry.amountMinor)} EGP"
}

/**
 * Offers to put back what was just deleted, and puts it back if asked. Shared by both screens that
 * can open the detail sheet, so the wording and the window are the same wherever you delete from.
 */
internal suspend fun offerUndoDelete(snackbar: SnackbarHostState, removed: Entry) {
    val result = snackbar.showSnackbar(deletedMessage(removed), actionLabel = "Undo")
    if (result == SnackbarResult.ActionPerformed) LedgerRepository.restoreEntry(removed)
}

/** English explicitly, so an ar-EG device does not render these in Arabic-Indic digits. */
private val rowDateFmt = java.time.format.DateTimeFormatter
    .ofPattern("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH)

internal fun Entry.displayDate(): String =
    java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .format(rowDateFmt)

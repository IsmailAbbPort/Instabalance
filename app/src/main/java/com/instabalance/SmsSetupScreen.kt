package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Point the reader at your own bank.
 *
 * The parser's words used to be hardcoded to one bank, so everyone else got automatic capture that
 * silently did nothing. This screen turns those words into something you can edit, and pairs it
 * with the thing that makes editing them possible: your own captured messages, and a live readout
 * of exactly what the app makes of one. You change a word and immediately see whether the message
 * now reads as money in, money out, or nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmsSetupScreen(onBack: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    val config = data.smsConfig
    var showHelp by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf("") }

    // Whichever message is being looked at: a tapped capture, or something pasted in.
    val sample = testText.ifBlank { data.captures.firstOrNull()?.text.orEmpty() }
    val result = remember(sample, config) {
        if (sample.isBlank()) null else BalanceParser.parse(sample, config)
    }

    fun update(block: (SmsConfig) -> SmsConfig) = LedgerRepository.setSmsConfig(block(config))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading your bank's SMS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "How this works")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            SetupCard("1. Record your messages") {
                Text(
                    "Turn this on, then wait for your bank to text you. The message appears below " +
                        "so you can see the exact wording. Turn it off once you are set up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Record incoming messages", Modifier.weight(1f))
                    Switch(
                        checked = data.learningMode,
                        onCheckedChange = { LedgerRepository.setLearningMode(it) },
                    )
                }
            }

            SetupCard("2. Try it on a real message") {
                if (data.captures.isEmpty() && testText.isBlank()) {
                    Text(
                        "No messages recorded yet. Turn on recording above, or paste one of your " +
                            "bank's messages here to try it straight away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (data.captures.isNotEmpty()) {
                    Text(
                        "Tap a recorded message to test it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    data.captures.take(6).forEach { capture ->
                        Card(
                            onClick = { testText = capture.text },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    capture.packageOrSender,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(capture.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text("Message to test") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                if (sample.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    ParseResult(result)
                }
            }

            SetupCard("3. Your bank's words") {
                Text(
                    "Add the words your bank actually uses. Everything already here is what a " +
                        "typical Egyptian bank sends, so you may only need to add one or two.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                WordList(
                    "Money came in", config.creditWords,
                    "A word meaning your balance went up, like credited or received.",
                ) { update { c -> c.copy(creditWords = it) } }

                WordList(
                    "Money went out", config.debitWords,
                    "A word meaning your balance went down, like charged, debited or purchase.",
                ) { update { c -> c.copy(debitWords = it) } }

                WordList(
                    "Currency", config.currencyWords,
                    "How the amount is marked, like EGP or جم.",
                ) { update { c -> c.copy(currencyWords = it) } }

                WordList(
                    "Remaining balance", config.balanceLabels,
                    "The phrase before your LEFTOVER balance. Getting this right matters most: " +
                        "without it the app can record your whole balance as a purchase.",
                ) { update { c -> c.copy(balanceLabels = it) } }

                WordList(
                    "Amount is introduced by", config.amountLabels,
                    "Optional. A phrase that comes right before the amount, like by or بمبلغ.",
                ) { update { c -> c.copy(amountLabels = it) } }

                WordList(
                    "Money came back", config.reversalWords,
                    "A word meaning a transaction was undone, which flips its direction.",
                ) { update { c -> c.copy(reversalWords = it) } }

                WordList(
                    "The other party is after", config.merchantLabels,
                    "What comes before the shop or person, like from. Used to learn rules.",
                ) { update { c -> c.copy(merchantLabels = it) } }

                WordList(
                    "Never a transaction", config.ignoreWords,
                    "A message containing one of these is ignored completely, like OTP.",
                ) { update { c -> c.copy(ignoreWords = it) } }
            }

            SetupCard("4. Which senders to read") {
                Text(
                    if (config.senders.isEmpty()) {
                        "Reading every sender. That is fine, since a message only counts when it " +
                            "also looks like a transaction, but naming your bank is safer."
                    } else {
                        "Only these senders are read."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                WordList("Senders", config.senders, "Your bank's sender ID, like EGBANK.") {
                    update { c -> c.copy(senders = it) }
                }

                val seen = data.captures.filter { it.channel == "SMS" }
                    .map { it.packageOrSender }.distinct()
                    .filterNot { s -> config.senders.any { it.equals(s, ignoreCase = true) } }
                if (seen.isNotEmpty()) {
                    Text(
                        "Recorded senders, tap to add:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRowOfChips(seen) { s -> update { c -> c.copy(senders = c.senders + s) } }
                }
            }

            SetupCard("Start over") {
                Text(
                    "Puts every word back to the Egyptian bank defaults. Your transactions are " +
                        "not touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { LedgerRepository.setSmsConfig(SmsConfig()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset to defaults") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHelp) SmsHelpDialog { showHelp = false }
}

/** What the app makes of the message, in the same words the user would use. */
@Composable
private fun ParseResult(result: ParsedTxn?) {
    val scheme = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    val good = result != null

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (good) brand.positiveContainer else scheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (good) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (good) brand.positive else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (good) "Read as a transaction" else "Not read as a transaction",
                    fontWeight = FontWeight.SemiBold,
                    color = if (good) brand.positive else scheme.onSurfaceVariant,
                )
            }
            if (result != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (result.type == EntryType.CREDIT) "Money in" else "Money out",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${Money.formatMinor(result.amountMinor)} EGP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    result.merchant?.let { "From: $it" }
                        ?: "No shop or person named, so this one cannot learn a rule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Check that a word below appears in the message, and that the amount sits " +
                        "next to something in your currency list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordList(
    title: String,
    words: List<String>,
    hint: String,
    onChange: (List<String>) -> Unit,
) {
    var adding by remember { mutableStateOf("") }

    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            words.forEach { w ->
                InputChip(
                    selected = false,
                    onClick = { onChange(words - w) },
                    label = { Text(w) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove $w",
                            modifier = Modifier.width(16.dp))
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = adding,
                onValueChange = { adding = it },
                label = { Text("Add a word") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = adding.isNotBlank() && words.none { it.equals(adding.trim(), true) },
                onClick = { onChange(words + adding.trim()); adding = "" },
            ) { Text("Add") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOfChips(items: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { AssistChip(onClick = { onPick(it) }, label = { Text(it) }) }
    }
}

@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SmsHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading your bank's SMS") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Bank messages are all shaped the same way, whichever bank sent them: a word " +
                        "saying which way the money went, an amount next to a currency, usually " +
                        "your remaining balance, and sometimes who you paid.",
                )
                Text(
                    "Only the words differ. So instead of the app knowing one bank, you tell it " +
                        "your bank's words, and the same reader works.",
                )
                Text("How to set it up", fontWeight = FontWeight.Medium)
                Text(
                    "Turn on recording, wait for a message from your bank, then tap it to test. " +
                        "The result tells you whether it was read as money in, money out, or not " +
                        "read at all. Add whatever word was missing and the result updates as you " +
                        "type. Turn recording off when you are done."
                )
                Text("The one to get right", fontWeight = FontWeight.Medium)
                Text(
                    "Remaining balance. If your bank writes your leftover balance in the message " +
                        "and the app does not know that phrase, it can read your entire balance as " +
                        "the size of the purchase. Test one message that shows a balance and " +
                        "check the amount is the transaction, not the balance."
                )
                Text("Privacy", fontWeight = FontWeight.Medium)
                Text(
                    "Recording only keeps messages that look financial, and only while it is " +
                        "switched on. Nothing leaves your phone, here or anywhere else in the app."
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

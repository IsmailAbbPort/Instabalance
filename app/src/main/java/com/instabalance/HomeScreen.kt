package com.instabalance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun HomeScreen(onSettings: () -> Unit, onInbox: () -> Unit, onAllTransactions: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var picking by remember { mutableStateOf<Entry?>(null) }

    val balance = LedgerRepository.balanceMinor(data)
    val lastAnchor = LedgerRepository.lastAnchorTimestamp(data)
    val pending = remember(data) { Insights.uncategorisedCount(data.entries) }
    val budget = remember(data) { budgetStatusOrNull(data) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        BrandHeader(
            greeting = rememberGreeting(),
            title = "Fuck Instapay",
            onSettings = onSettings,
        )

        Column(
            Modifier
                // The whole content block rides up into the header, the way InstaPay's promo card
                // sits over the purple, so purple stays visible down both sides of the card. Done
                // once here rather than per item, or every offset would leave its layout gap
                // behind. The matching Spacer at the bottom gives the scroll its height back.
                .offset(y = (-84).dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BalanceCard(balance, lastAnchor)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Orange, not violet: in InstaPay the violet is identity (header, active nav) and
                // orange is every interactive affordance. Purple buttons read as the wrong app.
                Button(
                    onClick = { dialog = Dialog.CREDIT },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Received")
                }
                Button(
                    onClick = { dialog = Dialog.DEBIT },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Icon(Icons.Default.Remove, null); Spacer(Modifier.width(4.dp)); Text("Sent")
                }
            }
            OutlinedButton(
                onClick = { dialog = Dialog.ANCHOR },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Text("Set balance (re-sync from the real app)")
            }

            InboxBanner(pending, onInbox)
            budget?.let { BudgetCard(it) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                // Only once there is more than this list shows: a "View all" that leads to the
                // same eight rows is a dead end.
                if (data.entries.size > 8) {
                    TextButton(onClick = onAllTransactions) { Text("View all") }
                }
            }
            if (data.entries.isEmpty()) {
                Text("Nothing yet. Add a transaction or set your balance.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                EntryList(data.entries, data) { detail = it }
            }

            if (data.entries.any { it.type != EntryType.ANCHOR }) {
                Spacer(Modifier.height(4.dp))
                InsightsSection(data)
            }
            // Gives back the height the offset above took out of the scroll.
            Spacer(Modifier.height(92.dp))
        }
    }

    detail?.let { entry ->
        EntryDetailSheet(
            entry = entry,
            data = data,
            onDismiss = { detail = null },
            onChangeCategory = { detail = null; picking = entry },
        )
    }

    picking?.let { entry ->
        CategoryPickerSheet(
            entry = entry,
            data = data,
            recentIds = recentCategoryIds(data.entries),
            onDismiss = { picking = null },
            onPicked = { id, pattern ->
                picking = null
                LedgerRepository.setCategory(setOf(entry.id), id)
                if (pattern != null) {
                    LedgerRepository.addRule(pattern, id, System.currentTimeMillis())
                }
            },
        )
    }

    if (dialog != Dialog.NONE) {
        AmountDialog(
            kind = dialog,
            categories = data.categories,
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { minor, note, categoryId ->
                val now = System.currentTimeMillis()
                when (dialog) {
                    Dialog.CREDIT ->
                        LedgerRepository.addManual(EntryType.CREDIT, minor, note, now, categoryId)
                    Dialog.DEBIT ->
                        LedgerRepository.addManual(EntryType.DEBIT, minor, note, now, categoryId)
                    Dialog.ANCHOR -> LedgerRepository.setBalance(minor, note, now)
                    Dialog.NONE -> {}
                }
                dialog = Dialog.NONE
            }
        )
    }
}

@Composable
private fun BalanceCard(balanceMinor: Long, lastAnchor: Long?, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "Available balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Money.formatMinor(balanceMinor),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "EGP",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                syncedAgoText(lastAnchor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Matches InstaPay's time-of-day greeting above the name. Pure and hour-driven so the boundaries
 * can be tested without waiting for the afternoon.
 */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    else -> "Good Evening"
}

/**
 * Re-reads the clock every time the app comes back to the foreground. Calling the clock once
 * during composition left the greeting frozen at whatever it said when the screen was first drawn,
 * so an app opened before five and resumed at nine still said Good Evening.
 */
@Composable
private fun rememberGreeting(): String {
    fun now() = greetingForHour(
        java.time.LocalTime.now().hour
    )

    var greeting by remember { mutableStateOf(now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) greeting = now()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return greeting
}

private fun syncedAgoText(lastAnchor: Long?): String {
    if (lastAnchor == null) return "Never synced. Set your balance from the real app once."
    val days = ((System.currentTimeMillis() - lastAnchor) / (24 * 60 * 60 * 1000L)).toInt()
    val trust = when {
        days <= 1 -> "high confidence"
        days <= 7 -> "ok"
        else -> "getting stale, consider re-syncing"
    }
    val whenStr = when (days) {
        0 -> "today"
        1 -> "1 day ago"
        else -> "$days days ago"
    }
    return "Last synced $whenStr • $trust"
}

@Composable
private fun EntryList(entries: List<Entry>, data: LedgerData, onOpen: (Entry) -> Unit) {
    // Not a LazyColumn: this sits inside a vertically scrolling Column, and nesting the two makes
    // the inner list fight the outer scroll. The recent list is deliberately short anyway.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.sortedByDescending { it.timestamp }.take(8).forEach { e ->
            EntryRow(e, data, onOpen)
        }
    }
}


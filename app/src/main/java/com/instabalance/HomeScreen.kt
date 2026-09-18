package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeScreen() {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(Dialog.NONE) }

    val balance = LedgerRepository.balanceMinor(data)
    val lastAnchor = LedgerRepository.lastAnchorTimestamp(data)

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BalanceCard(balance, lastAnchor)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = Dialog.CREDIT }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Received")
            }
            Button(onClick = { dialog = Dialog.DEBIT }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Remove, null); Spacer(Modifier.width(4.dp)); Text("Sent")
            }
        }
        OutlinedButton(onClick = { dialog = Dialog.ANCHOR }, modifier = Modifier.fillMaxWidth()) {
            Text("Set balance (re-sync from the real app)")
        }

        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        if (data.entries.isEmpty()) {
            Text("Nothing yet. Add a transaction or set your balance.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            EntryList(data.entries)
        }

        HorizontalDivider()
        SettingsPanel(data)
    }

    if (dialog != Dialog.NONE) {
        AmountDialog(
            kind = dialog,
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { minor, note ->
                val now = System.currentTimeMillis()
                when (dialog) {
                    Dialog.CREDIT -> LedgerRepository.addManual(EntryType.CREDIT, minor, note, now)
                    Dialog.DEBIT -> LedgerRepository.addManual(EntryType.DEBIT, minor, note, now)
                    Dialog.ANCHOR -> LedgerRepository.setBalance(minor, note, now)
                    Dialog.NONE -> {}
                }
                dialog = Dialog.NONE
            }
        )
    }
}

@Composable
private fun BalanceCard(balanceMinor: Long, lastAnchor: Long?) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("EGP", style = MaterialTheme.typography.titleMedium)
            Text(
                Money.formatMinor(balanceMinor),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(syncedAgoText(lastAnchor), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun syncedAgoText(lastAnchor: Long?): String {
    if (lastAnchor == null) return "Never synced — set your balance from the real app once."
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
private fun EntryList(entries: List<Entry>) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(entries.sortedByDescending { it.timestamp }, key = { it.id }) { e ->
            EntryRow(e)
        }
    }
}

@Composable
private fun EntryRow(e: Entry) {
    val (label, sign) = when (e.type) {
        EntryType.CREDIT -> "Received" to "+"
        EntryType.DEBIT -> (if (e.source == Source.FEE) "Fee" else "Sent") to "-"
        EntryType.ANCHOR -> "Set balance" to "="
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("$label  •  ${e.source.name.lowercase()}", style = MaterialTheme.typography.labelMedium)
                Text(dateFmt.format(Date(e.timestamp)), style = MaterialTheme.typography.bodySmall)
                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
            }
            Text("$sign ${Money.formatMinor(e.amountMinor)}", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { LedgerRepository.deleteEntry(e.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

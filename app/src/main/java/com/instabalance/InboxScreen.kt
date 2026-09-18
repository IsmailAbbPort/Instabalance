package com.instabalance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Triage for everything the app captured but could not file. Most rows are cleared with a single
 * tap on an inline chip, without ever leaving the list: an inbox that needs a sheet per entry is
 * an inbox nobody finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(onBack: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var picking by remember { mutableStateOf<Entry?>(null) }
    var backlogDismissed by remember { mutableStateOf(false) }

    val pending = remember(data) {
        data.entries
            .filter { it.type != EntryType.ANCHOR && it.categoryId == null }
            .sortedByDescending { it.timestamp }
    }

    val recent = remember(data) { recentCategoryIds(data.entries) }

    fun file(entry: Entry, categoryId: String, learnPattern: String?) {
        val previous = LedgerRepository.setCategory(setOf(entry.id), categoryId)
        val name = Categories.byId(data.categories, categoryId)?.name ?: "category"

        var extra = 0
        if (learnPattern != null) {
            LedgerRepository.addRule(learnPattern, categoryId, System.currentTimeMillis())
            val rule = LedgerRepository.data.value.merchantRules.last()
            extra = MerchantRules.applyToUncategorised(
                LedgerRepository.data.value.entries, rule
            ).second
        }

        scope.launch {
            val message = if (extra > 0) "Filed as $name. $extra more matched." else "Filed as $name"
            if (snackbar.showSnackbar(message, actionLabel = "Undo") == SnackbarResult.ActionPerformed) {
                LedgerRepository.restoreCategories(previous)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("To categorise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (pending.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("All caught up", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "InstaPay sends arrive as a bank SMS that names no recipient, so those will " +
                        "keep landing here for you to file. Card purchases name the shop, so they " +
                        "can learn themselves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Every existing user arrives with a backlog they have no interest in reliving. One tap
            // clears the history and leaves the badge meaning "since you started categorising".
            if (pending.size > 20 && !backlogDismissed) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${pending.size} transactions have no category",
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "File everything before today as Other, and start fresh from here.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = {
                                    val zone = ZoneId.systemDefault()
                                    val startOfToday = Instant.now().atZone(zone)
                                        .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                                    val older = pending
                                        .filter { it.timestamp < startOfToday }
                                        .map { it.id }
                                        .toSet()
                                    val previous = LedgerRepository.setCategory(
                                        older, Categories.OTHER_EXPENSE
                                    )
                                    scope.launch {
                                        if (snackbar.showSnackbar(
                                                "Filed ${older.size} as Other", actionLabel = "Undo"
                                            ) == SnackbarResult.ActionPerformed
                                        ) {
                                            LedgerRepository.restoreCategories(previous)
                                        }
                                    }
                                }) { Text("File older as Other") }
                                TextButton(onClick = { backlogDismissed = true }) { Text("Not now") }
                            }
                        }
                    }
                }
            }

            items(pending, key = { it.id }) { entry ->
                InboxRow(
                    entry = entry,
                    data = data,
                    recent = recent,
                    onPick = { picking = entry },
                    onFile = { id -> file(entry, id, null) },
                )
            }
        }
    }

    picking?.let { entry ->
        CategoryPickerSheet(
            entry = entry,
            data = data,
            recentIds = recent,
            onDismiss = { picking = null },
            onPicked = { id, pattern -> picking = null; file(entry, id, pattern) },
        )
    }
}

@Composable
private fun InboxRow(
    entry: Entry,
    data: LedgerData,
    recent: List<String>,
    onPick: () -> Unit,
    onFile: (String) -> Unit,
) {
    val suggestion = MerchantRules.match(data.merchantRules, entry.merchant)?.categoryId
    val quick = buildList {
        suggestion?.let { add(it) }
        addAll(recent)
        add(if (entry.type == EntryType.CREDIT) Categories.OTHER_INCOME else Categories.OTHER_EXPENSE)
    }.distinct()
        .mapNotNull { id -> Categories.visibleFor(data.categories, entry.type).firstOrNull { it.id == id } }
        .take(3)

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayCounterparty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        entry.displayDate(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    (if (entry.type == EntryType.CREDIT) "+ " else "- ") +
                        Money.formatMinor(entry.amountMinor),
                    fontWeight = FontWeight.Bold,
                    color = if (entry.type == EntryType.CREDIT) LocalBrand.current.positive
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.forEach { c ->
                    AssistChip(
                        onClick = { onFile(c.id) },
                        leadingIcon = { CategoryDot(c) },
                        label = { Text(c.name, style = MaterialTheme.typography.labelMedium) },
                    )
                }
                AssistChip(onClick = onPick, label = { Text("More") })
            }
        }
    }
}

/** The three categories most recently chosen by hand, newest first. */
internal fun recentCategoryIds(entries: List<Entry>): List<String> =
    entries.asSequence()
        .filter { it.categoryId != null && !it.categoryFromRule }
        .sortedByDescending { it.timestamp }
        .mapNotNull { it.categoryId }
        .distinct()
        .take(3)
        .toList()

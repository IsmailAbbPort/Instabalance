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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Every transaction, with filters. The Home list is deliberately the last eight, so this is where
 * you come to answer a question rather than to glance.
 *
 * All the filtering is [TransactionFilters], which is pure and unit-tested. Nothing on this screen
 * decides what matches; it only collects a [TransactionFilter] and renders the answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionsScreen(onBack: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TransactionFilter()) }
    var showFilters by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var picking by remember { mutableStateOf<Entry?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember(data) { LocalDate.now(zone) }

    val results = remember(data, filter, today) {
        TransactionFilters.apply(data.entries, filter, data.categories, today, zone)
    }
    val totals = remember(results) { TransactionFilters.totals(results) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All transactions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Badged rather than merely highlighted: an active filter that is not obvious
                    // turns an empty list into a bug report.
                    BadgedBox(badge = { if (filter.isActive) Badge() }) {
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filters")
                        }
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { filter = filter.copy(query = it) },
                    label = { Text("Search shop, note or category") },
                    singleLine = true,
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) {
                            IconButton(onClick = { filter = filter.copy(query = "") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TotalsBar(totals)
                if (filter.isActive) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { filter = TransactionFilter(sortBy = filter.sortBy) }) {
                        Text("Clear filters")
                    }
                }
            }

            if (results.isEmpty()) {
                Text(
                    if (data.entries.isEmpty()) "Nothing recorded yet."
                    else "No transactions match these filters.",
                    Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // A LazyColumn here, unlike the Home list: this one can be thousands of rows and
                // is not nested inside another scroller.
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.id }) { e ->
                        EntryRow(e, data) { detail = it }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filter = filter,
            categories = data.categories,
            onChange = { filter = it },
            onDismiss = { showFilters = false },
        )
    }

    detail?.let { entry ->
        EntryDetailSheet(
            original = entry,
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
}

/**
 * The running total of whatever is on screen. The reason to have it: "everything in Groceries in
 * August" is a question about a number, and without this you would reach for a calculator.
 */
@Composable
private fun TotalsBar(totals: TransactionTotals) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                if (totals.count == 1) "1 transaction" else "${totals.count} transactions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                TotalCell("In", totals.incomeMinor, LocalBrand.current.positive, Modifier.weight(1f))
                TotalCell("Out", totals.expenseMinor, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                TotalCell(
                    "Net", totals.netMinor,
                    if (totals.netMinor < 0) MaterialTheme.colorScheme.error else LocalBrand.current.positive,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TotalCell(label: String, amountMinor: Long, colour: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Money.formatMinor(amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colour,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: TransactionFilter,
    categories: List<Category>,
    onChange: (TransactionFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Held as text, not Long, so a half-typed "1" is not repeatedly parsed and snapped back.
    var minText by remember { mutableStateOf(filter.minMinor?.let { Money.formatMinor(it) } ?: "") }
    var maxText by remember { mutableStateOf(filter.maxMinor?.let { Money.formatMinor(it) } ?: "") }
    var fromText by remember { mutableStateOf(filter.customFrom?.toString() ?: "") }
    var toText by remember { mutableStateOf(filter.customTo?.toString() ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleMedium)

            SheetLabel("Direction")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Direction.entries.forEach { d ->
                    FilterChip(
                        selected = filter.direction == d,
                        onClick = { onChange(filter.copy(direction = d)) },
                        label = {
                            Text(
                                when (d) {
                                    Direction.ALL -> "All"
                                    Direction.INCOME -> "Income"
                                    Direction.EXPENSE -> "Expenses"
                                }
                            )
                        },
                    )
                }
            }

            SheetLabel("When")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateRange.entries.forEach { r ->
                    FilterChip(
                        selected = filter.range == r,
                        onClick = { onChange(filter.copy(range = r)) },
                        label = { Text(r.label) },
                    )
                }
            }
            if (filter.range == DateRange.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = {
                            fromText = it
                            onChange(filter.copy(customFrom = it.toLocalDateOrNull()))
                        },
                        label = { Text("From") },
                        placeholder = { Text("2026-09-01") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = {
                            toText = it
                            onChange(filter.copy(customTo = it.toLocalDateOrNull()))
                        },
                        label = { Text("To") },
                        placeholder = { Text("2026-09-30") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Leave one side blank for everything before or after the other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SheetLabel("Amount (EGP)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = {
                        val clean = sanitizeAmount(it)
                        minText = clean
                        onChange(filter.copy(minMinor = Money.parseToMinor(clean)))
                    },
                    label = { Text("At least") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = {
                        val clean = sanitizeAmount(it)
                        maxText = clean
                        onChange(filter.copy(maxMinor = Money.parseToMinor(clean)))
                    },
                    label = { Text("At most") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            SheetLabel("Category")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Uncategorised first: "what have I not sorted" is the most common reason to come
                // here, and hunting for it at the end of twenty chips would be silly.
                FilterChip(
                    selected = null in filter.categoryIds,
                    onClick = { onChange(filter.copy(categoryIds = filter.categoryIds.toggle(null))) },
                    label = { Text("Uncategorised") },
                )
                categories.filter { !it.hidden }.forEach { c ->
                    FilterChip(
                        selected = c.id in filter.categoryIds,
                        onClick = { onChange(filter.copy(categoryIds = filter.categoryIds.toggle(c.id))) },
                        leadingIcon = { CategoryDot(c) },
                        label = { Text(c.name) },
                    )
                }
            }

            SheetLabel("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { s ->
                    FilterChip(
                        selected = filter.sortBy == s,
                        onClick = { onChange(filter.copy(sortBy = s)) },
                        label = {
                            Text(
                                when (s) {
                                    SortBy.NEWEST -> "Newest"
                                    SortBy.OLDEST -> "Oldest"
                                    SortBy.LARGEST -> "Largest"
                                    SortBy.SMALLEST -> "Smallest"
                                }
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show balance re-syncs", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The \"Set balance\" entries. They are a correction, not money moving, so " +
                            "they are hidden and never counted in the totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = filter.includeAnchors,
                    onCheckedChange = { onChange(filter.copy(includeAnchors = it)) },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(onClick = onDismiss) { Text("Done") }
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {
                        minText = ""; maxText = ""; fromText = ""; toText = ""
                        onChange(TransactionFilter(sortBy = filter.sortBy))
                    },
                    label = { Text("Reset") },
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

/** Tapping a selected chip clears it, which is what makes a multi-select feel like one. */
private fun Set<String?>.toggle(id: String?): Set<String?> =
    if (id in this) this - id else this + id

/** A half-typed date is simply "no bound yet" rather than an error the user has to clear. */
private fun String.toLocalDateOrNull(): LocalDate? =
    try {
        LocalDate.parse(trim())
    } catch (e: DateTimeParseException) {
        null
    }

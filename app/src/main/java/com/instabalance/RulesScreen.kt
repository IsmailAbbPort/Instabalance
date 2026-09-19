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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RulesScreen(onBack: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<MerchantRule?>(null) }
    var editing by remember { mutableStateOf<MerchantRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("New rule")
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Merchant rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "What are merchant rules?",
                        )
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
        if (data.merchantRules.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No rules yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A rule files a shop's transactions for you, automatically, even while the " +
                        "app is closed. The usual way to get one is to categorise a transaction " +
                        "that names a shop and say yes when asked to remember it. Tap the question " +
                        "mark above for the longer version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showHelp) RulesHelpDialog { showHelp = false }
            if (creating) {
                RuleEditSheet(null, data, onDismiss = { creating = false })
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Longest pattern first, which is also the order they are applied in, so the list
            // reads the way the matching actually behaves.
            items(
                data.merchantRules.sortedByDescending { it.pattern.length },
                key = { it.id },
            ) { rule ->
                val category = Categories.byId(data.categories, rule.categoryId)
                Card(
                    onClick = { editing = rule },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.pattern, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryDot(category)
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    category?.name ?: "Unknown category",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { confirmDelete = rule }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete rule")
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "When two rules match the same merchant, the longer pattern wins. Tap a rule " +
                        "to change what it matches or where it files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(72.dp))
            }
        }
    }

    if (showHelp) RulesHelpDialog { showHelp = false }

    if (creating) {
        RuleEditSheet(null, data, onDismiss = { creating = false })
    }

    editing?.let { rule ->
        RuleEditSheet(rule, data, onDismiss = { editing = null })
    }

    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this rule?") },
            text = {
                Text(
                    "Transactions already filed keep their category. Only future ones matching " +
                        "\"${rule.pattern}\" stop being filed automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.deleteRule(rule.id)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Create or edit. One sheet for both, because the fields are identical and the only difference is
 * whether an id already exists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditSheet(rule: MerchantRule?, data: LedgerData, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pattern by remember(rule?.id) { mutableStateOf(rule?.pattern ?: "") }
    var categoryId by remember(rule?.id) { mutableStateOf(rule?.categoryId) }
    var alsoApply by remember(rule?.id) { mutableStateOf(false) }

    val normalised = MerchantRules.normalise(pattern)
    // Every category, not just one direction: a refund from a shop you usually spend at is income.
    val options = data.categories.filterNot { it.hidden }

    // What saying yes to "also apply" would actually do, counted before the user commits to it.
    val wouldMatch = remember(normalised, categoryId, data.entries) {
        if (normalised.isEmpty() || categoryId == null) 0
        else MerchantRules.applyToUncategorised(
            data.entries,
            MerchantRule(pattern = normalised, categoryId = categoryId!!, createdAt = 0),
        ).second
    }

    // Entries this rule filed itself, which would otherwise keep pointing at the old category and
    // quietly disagree with the rule that put them there. Hand-filed entries are never counted.
    val alreadyFiled = remember(rule?.id, categoryId, data.entries) {
        if (rule == null || categoryId == rule.categoryId) 0
        else MerchantRules.ruleOwnedCount(data.entries, rule)
    }
    var moveExisting by remember(rule?.id) { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                if (rule == null) "New rule" else "Edit rule",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("Match transactions containing") },
                supportingText = {
                    Text(
                        if (normalised.isEmpty()) "For example PAYMOB, CARREFOUR, or ATM"
                        else "Matches any merchant containing \"$normalised\""
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("File it under", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { c ->
                    FilterChip(
                        selected = categoryId == c.id,
                        onClick = { categoryId = c.id },
                        leadingIcon = { CategoryDot(c) },
                        label = { Text(c.name) },
                    )
                }
            }

            if (alreadyFiled > 0) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Move $alreadyFiled transaction" +
                                (if (alreadyFiled == 1) "" else "s") + " this rule already filed",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Off means they keep the old category and stop matching this rule.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = moveExisting, onCheckedChange = { moveExisting = it })
                }
            }

            if (wouldMatch > 0) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Also file $wouldMatch existing transaction" +
                                if (wouldMatch == 1) "" else "s",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Only ones you have not categorised yourself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = alsoApply, onCheckedChange = { alsoApply = it })
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    enabled = normalised.isNotEmpty() && categoryId != null,
                    onClick = {
                        val id = categoryId ?: return@TextButton
                        if (rule == null) {
                            LedgerRepository.addRule(normalised, id, System.currentTimeMillis())
                        } else {
                            // Move the entries this rule filed BEFORE changing it, while they can
                            // still be identified by the category the old rule put them in.
                            if (alreadyFiled > 0 && moveExisting) {
                                LedgerRepository.recategoriseRuleOwned(rule, id)
                            }
                            LedgerRepository.updateRule(rule.id, normalised, id)
                        }
                        if (alsoApply) {
                            LedgerRepository.applyRuleToUncategorised(
                                MerchantRule(pattern = normalised, categoryId = id, createdAt = 0)
                            )
                        }
                        onDismiss()
                    },
                ) { Text(if (rule == null) "Create" else "Save") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RulesHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What are merchant rules?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "A rule means: always file anything from this shop under this category.",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Your transactions arrive on their own, from your bank's SMS, while the app " +
                        "is closed. Nobody is there to categorise them. A card purchase names the " +
                        "shop it came from, so once you have told the app where PAYMOB belongs, " +
                        "every future PAYMOB charge files itself the moment the message lands."
                )
                Text("The normal way to make one", fontWeight = FontWeight.Medium)
                Text(
                    "You do not usually come here to write rules. Categorise something from your " +
                        "review list, and if it names a shop the app offers to remember it. Say " +
                        "yes and the rule appears on this screen."
                )
                Text("Worth knowing", fontWeight = FontWeight.Medium)
                Text(
                    "A longer pattern beats a shorter one, so a rule for PAYMOB RAF wins over a " +
                        "rule for PAYMOB.\n\n" +
                        "A rule never changes a category you chose by hand, and it never rewrites " +
                        "your history on its own: applying one to existing transactions is always " +
                        "a separate, counted choice.\n\n" +
                        "Keep patterns specific. A rule matching just AL or EL will catch far more " +
                        "than you meant."
                )
                Text("What rules cannot do", fontWeight = FontWeight.Medium)
                Text(
                    "An InstaPay transfer seen as a bank SMS names nobody, only a reference " +
                        "number. There is nothing for a rule to match, so those will always come " +
                        "to your review list to be filed by hand."
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
internal fun CategoriesScreen(onBack: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("New category")
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(data.categories, key = { it.id }) { category ->
                CategoryRow(
                    category = category,
                    onEdit = { editing = category.id },
                    onToggleHidden = { LedgerRepository.setCategoryHidden(category.id, it) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hiding keeps every transaction filed under a category and keeps it in your " +
                        "charts. It only stops the category being offered when you file something new.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { id ->
        Categories.byId(data.categories, id)?.let { category ->
            CategoryEditSheet(
                category = category,
                entryCount = Categories.entryCount(data.entries, id),
                onDismiss = { editing = null },
            )
        }
    }

    if (creating) {
        NewCategorySheet(
            takenColours = data.categories.map { it.colorIndex },
            onDismiss = { creating = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewCategorySheet(takenColours: List<Int>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CategoryKind.EXPENSE) }
    // Opens on a colour nothing is using yet, so two categories do not look alike by default.
    var colour by remember {
        mutableStateOf(ChartPalette.RAMP.indices.firstOrNull { it !in takenColours } ?: 0)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text("New category", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Applies to", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryKind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = {
                            Text(
                                when (k) {
                                    CategoryKind.EXPENSE -> "Expenses"
                                    CategoryKind.INCOME -> "Income"
                                    CategoryKind.BOTH -> "Both"
                                }
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Colour", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChartPalette.RAMP.indices.forEach { i ->
                    Swatch(
                        colour = Color(ChartPalette.forIndex(i)),
                        selected = i == colour,
                        onClick = { colour = i },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        LedgerRepository.addCategory(name, kind, colour)
                        onDismiss()
                    },
                ) { Text("Create") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onEdit: () -> Unit, onToggleHidden: (Boolean) -> Unit) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryDot(category, size = 14)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(category.name, fontWeight = FontWeight.Medium)
                Text(
                    category.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = !category.hidden, onCheckedChange = { onToggleHidden(!it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditSheet(category: Category, entryCount: Int, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(category.id) { mutableStateOf(category.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (category.preset) category.name else "Edit category",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (!category.preset) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Colour", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChartPalette.RAMP.indices.forEach { i ->
                    Swatch(
                        colour = Color(ChartPalette.forIndex(i)),
                        selected = i == category.colorIndex,
                        onClick = { LedgerRepository.recolourCategory(category.id, i) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(onClick = {
                    if (!category.preset && name.isNotBlank()) {
                        LedgerRepository.renameCategory(category.id, name)
                    }
                    onDismiss()
                }) { Text("Done") }
                Spacer(Modifier.weight(1f))
                if (!category.preset) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (category.preset) {
                Text(
                    "Built-in categories can be recoloured and hidden. Renaming and deleting are " +
                        "for your own categories, because the app files things like fees and " +
                        "cash withdrawals here by name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${category.name}?") },
            text = {
                Text(
                    if (entryCount == 0) "No transactions are filed under it."
                    else "$entryCount transaction${if (entryCount == 1) "" else "s"} will go back " +
                        "to your review list. Any rule pointing at it is removed too."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.deleteCategory(category.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Swatch(colour: Color, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = colour,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
        } else null,
        modifier = Modifier.size(36.dp),
    ) {}
}

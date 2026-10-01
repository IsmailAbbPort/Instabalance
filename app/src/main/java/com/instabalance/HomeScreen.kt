package com.instabalance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
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

/**
 * The two halves of Home.
 *
 * One scrolling page had grown to a balance card, three buttons, a review banner, two budget cards,
 * eight transactions and the entire charts section, which is a long way to travel to reach a chart
 * and a longer way back. The cut is where the money you are looking at stops and the summary of it
 * starts.
 */
internal enum class HomeTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    INSIGHTS("Insights", Icons.Default.PieChart),
}

/** Height the tab bar takes out of the bottom of each page, above the system navigation bar. */
private val TAB_BAR_SPACE = 84.dp

@Composable
internal fun HomeScreen(onSettings: () -> Unit, onInbox: () -> Unit, onAllTransactions: () -> Unit) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var picking by remember { mutableStateOf<Entry?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val balance = LedgerRepository.balanceMinor(data)
    val lastAnchor = remember(data) { LedgerRepository.lastAnchor(data) }
    val pending = remember(data) { Insights.uncategorisedCount(data.entries) }
    val budget = remember(data) { budgetStatusOrNull(data) }
    val categoryBudgets = remember(data) {
        Budget.categoryStatuses(
            data.entries, data.categories,
            java.time.Instant.now(), java.time.ZoneId.systemDefault(),
        )
    }

    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }

    // Back on the second tab returns to the first rather than leaving the app, which is what every
    // bottom bar on the phone does and what the hardware key is expected to do here.
    BackHandler(enabled = tab != HomeTab.HOME) { tab = HomeTab.HOME }

    // One page, whose content changes. There is no second copy of the header and the background to
    // slide a tab's worth of identical purple across, which is all the swipe ever actually showed.
    Box(Modifier.fillMaxSize()) {
        BrandPage(onSettings, scrollKey = tab) {
            when (tab) {
                HomeTab.HOME -> {
                    BalanceCard(balance, lastAnchor)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Orange, not violet: in InstaPay the violet is identity (header, active nav)
                        // and orange is every interactive affordance. Purple buttons read as the
                        // wrong app.
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
                    CategoryBudgetsCard(categoryBudgets)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                        // Only once there is more than this list shows: a "View all" that leads to
                        // the same eight rows is a dead end.
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
                }

                HomeTab.INSIGHTS -> {
                    if (data.entries.any { it.type != EntryType.ANCHOR }) {
                        InsightsSection(data)
                    } else {
                        Text(
                            "Nothing to chart yet. Charts appear once you have a transaction or two.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        HomeTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Home is not a Scaffold (the header deliberately draws its own insets), so the Undo bar
        // is placed here rather than handed to one. Lifted clear of the tab bar, or it covers the
        // only two controls that can dismiss what it is talking about.
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = TAB_BAR_SPACE),
        )
    }

    detail?.let { entry ->
        EntryDetailSheet(
            original = entry,
            data = data,
            onDismiss = { detail = null },
            onChangeCategory = { detail = null; picking = entry },
            onDeleted = { removed -> scope.launch { offerUndoDelete(snackbar, removed) } },
        )
    }

    picking?.let { entry ->
        CategoryPickerSheet(
            entry = entry,
            data = data,
            recentIds = recentCategoryIds(data.entries),
            // Back to the detail sheet either way, because that is where you came from and it is
            // where the note lives. Filing something is usually the moment you want to write one.
            onDismiss = { picking = null; detail = entry },
            onPicked = { id, pattern ->
                picking = null
                LedgerRepository.setCategory(setOf(entry.id), id)
                if (pattern != null) {
                    LedgerRepository.addRule(pattern, id, System.currentTimeMillis())
                }
                detail = entry
            },
        )
    }

    if (dialog != Dialog.NONE) {
        AmountDialog(
            kind = dialog,
            categories = data.categories,
            currentBalanceMinor = balance,
            lastAnchorAt = lastAnchor?.timestamp,
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

/**
 * The violet header and the content block that rides up into it. One of these, for both tabs.
 *
 * The header, the background and the scroll container are the same objects whichever tab is
 * showing, so the greeting, the name and the gear cannot drift between tabs: there is only ever
 * one of each on screen.
 */
@Composable
private fun BrandPage(
    onSettings: () -> Unit,
    /**
     * Changing this starts the page at the top again. Switching tab keeps the scroll container but
     * replaces everything in it, and landing halfway down a different tab's content is disorienting
     * in a way that scrolling there yourself is not.
     */
    scrollKey: Any? = null,
    /**
     * How far the content rides up into the header, the way InstaPay's promo card sits over its
     * purple. Both tabs use the same figure and both open on a white card, which is what keeps the
     * greeting, the name and the gear in identical positions when you swipe between them.
     */
    overlap: Dp = 84.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(scrollKey) { scroll.scrollTo(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .navigationBarsPadding()
    ) {
        BrandHeader(
            greeting = rememberGreeting(),
            title = "Fuck Instapay",
            onSettings = onSettings,
        )

        Column(
            Modifier
                // Riding up into the header is what keeps purple visible down both sides of the
                // first card, the way InstaPay's promo card sits over its purple. Done once here
                // rather than per item, or every offset would leave its layout gap behind. The
                // matching Spacer at the bottom gives the scroll its height back.
                .offset(y = -overlap)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            // Gives back the height the offset above took out of the scroll, plus room for the tab
            // bar, so the last card can always be scrolled clear of it.
            Spacer(Modifier.height(overlap + 8.dp + TAB_BAR_SPACE))
        }
    }
}

/**
 * A white sheet with rounded top corners sitting over the page, the way InstaPay's own bar does,
 * rather than a bar wedged into the bottom edge.
 *
 * Violet for the selected tab, matching InstaPay, where violet is identity and orange is action: a
 * tab is neither being pressed nor being acted on, it is saying where you are. No pill behind the
 * icon, because the violet already says it and the pill is a second answer to the same question.
 */
@Composable
private fun HomeTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun BalanceCard(balanceMinor: Long, lastAnchor: Entry?, modifier: Modifier = Modifier) {
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
                syncedAgoText(
                    lastAnchor?.timestamp,
                    automatic = lastAnchor?.source == Source.SMS,
                ),
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

/**
 * [automatic] is a sync the app did for itself, off a bank message that reported the balance. It is
 * named rather than passed off as your own, because the two are not equally trustworthy: you read
 * yours off the real app, while this one is only as right as the message it came from.
 */
internal fun syncedAgoText(
    lastAnchor: Long?,
    automatic: Boolean = false,
    now: Long = System.currentTimeMillis(),
): String {
    if (lastAnchor == null) return "Never synced. Set your balance from the real app once."
    val days = ((now - lastAnchor) / (24 * 60 * 60 * 1000L)).toInt()
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
    val how = if (automatic) " (automatic, from your bank)" else ""
    return "Last synced $whenStr$how • $trust"
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


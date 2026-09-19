package com.instabalance

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Screens. Sheets (entry detail, the category picker) deliberately are NOT routes: they close with
 * a dismiss and must never be somewhere the back stack can strand you.
 */
internal enum class Route { HOME, INBOX, TRANSACTIONS, SETTINGS, CATEGORIES, RULES, SMS_SETUP }

/**
 * A back stack small enough not to justify a navigation library, which would be the single
 * heaviest dependency in an app that otherwise has almost none.
 *
 * Survives rotation and process death via rememberSaveable. The lock in MainActivity.onStop is
 * unaffected: Gate decides lock-versus-content before this is ever consulted.
 */
internal class NavStack(initial: List<Route>) {
    private val stack = mutableStateListOf<Route>().apply { addAll(initial) }

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(route: Route) {
        // Re-tapping the screen you are already on should do nothing, not grow the stack.
        if (stack.last() != route) stack.add(route)
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }

    fun snapshot(): List<Route> = stack.toList()
}

@Composable
internal fun rememberNavStack(): NavStack {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { it.snapshot().map(Route::name) },
            restore = { NavStack(it.map(Route::valueOf)) },
        )
    ) { NavStack(listOf(Route.HOME)) }

    BackHandler(enabled = stack.canGoBack) { stack.back() }
    return remember(stack) { stack }
}

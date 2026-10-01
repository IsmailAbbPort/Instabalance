package com.instabalance

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

/**
 * [backEnabled] is false while the app lock is showing. The stack lives above the lock so that
 * unlocking returns you to the screen you left, which also means it is still here while the
 * passcode is up: without this, Back on the lock screen would quietly pop a screen you cannot see
 * instead of leaving the app.
 *
 * No saver: surviving a rotation is not enough and never was. What has to survive is the process
 * being killed while the app sits in the background, which is [SessionResume].
 */
@Composable
internal fun rememberNavStack(initial: List<Route>, backEnabled: Boolean = true): NavStack {
    val stack = remember { NavStack(initial) }
    BackHandler(enabled = backEnabled && stack.canGoBack) { stack.back() }
    return stack
}

/** Where you were and what you were filtering, and when that stopped being worth restoring. */
internal data class Resumed(val routes: List<Route>, val filter: TransactionFilter)

@Serializable
private data class SavedSession(
    val routes: List<String>,
    val filter: List<String>,
    val savedAt: Long,
)

/**
 * Puts you back where you were, but only for a while.
 *
 * Coming back to a filtered transaction list two minutes after glancing at a message is the whole
 * point. Coming back to it tomorrow morning is not: by then the app should open on your balance
 * like any other day, and a filter you have forgotten setting is just a list with rows missing.
 *
 * Kept in its own small encrypted file rather than in the ledger. The ledger is rewritten and
 * re-encrypted whole on every change, which is far too much work for "which screen was open", and
 * a search query can name a person, so it does not belong in plain preferences either.
 */
internal object SessionResume {

    /** How long a screen stays worth returning to. */
    const val WINDOW_MS = 15 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var file: File

    fun init(context: Context) {
        if (!::file.isInitialized) {
            file = File(context.applicationContext.filesDir, "session.enc")
        }
    }

    /**
     * Also false when [savedAt] is in the future, which is a clock that moved backwards rather
     * than a session from later today. Restoring on that reading could pin the app to one screen
     * for as long as the clock stayed wrong.
     */
    fun isFresh(savedAt: Long, now: Long, windowMs: Long = WINDOW_MS): Boolean =
        savedAt <= now && now - savedAt <= windowMs

    fun encode(routes: List<Route>, filter: TransactionFilter, now: Long): String =
        json.encodeToString(
            SavedSession(routes.map(Route::name), TransactionFilters.save(filter), now)
        )

    /** Null when stale, unreadable, or naming a screen this build no longer has. */
    fun decode(text: String, now: Long, windowMs: Long = WINDOW_MS): Resumed? {
        val saved = runCatching { json.decodeFromString<SavedSession>(text) }.getOrNull() ?: return null
        if (!isFresh(saved.savedAt, now, windowMs)) return null
        val routes = runCatching { saved.routes.map(Route::valueOf) }.getOrNull() ?: return null
        if (routes.isEmpty() || routes.first() != Route.HOME) return null
        return Resumed(routes, TransactionFilters.restore(saved.filter))
    }

    fun load(now: Long): Resumed? {
        if (!::file.isInitialized) return null
        return SecureStore.readString(file)?.let { decode(it, now) }
    }

    fun save(routes: List<Route>, filter: TransactionFilter, now: Long) {
        if (!::file.isInitialized) return
        runCatching { SecureStore.writeString(file, encode(routes, filter, now)) }
    }
}

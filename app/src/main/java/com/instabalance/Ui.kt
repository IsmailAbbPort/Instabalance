package com.instabalance

/**
 * The handful of UI symbols used by more than one screen file. A top-level `private` in Kotlin is
 * visible only within its own file, so these cannot live beside any single screen that uses them.
 * `internal` keeps them off the module's public surface, which is what `private` was buying.
 */

internal const val PIN_LENGTH = 4

internal enum class Dialog { NONE, CREDIT, DEBIT, ANCHOR }

/** Keeps only digits and a single decimal point, so money fields accept numbers only. */
internal fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot == -1) filtered
    else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

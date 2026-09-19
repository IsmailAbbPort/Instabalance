package com.instabalance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which way the money went. [ALL] is the default, so the screen opens on everything. */
enum class Direction { ALL, INCOME, EXPENSE }

/** Newest first is what you want nine times in ten; by amount is for "what was the big one". */
enum class SortBy { NEWEST, OLDEST, LARGEST, SMALLEST }

/**
 * Named windows, because typing two dates to mean "this month" is friction for the common case.
 * [CUSTOM] is what the two date fields feed.
 */
enum class DateRange(val label: String) {
    ANY("Any time"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_30("Last 30 days"),
    LAST_90("Last 90 days"),
    THIS_YEAR("This year"),
    CUSTOM("Custom"),
}

/**
 * Everything the transactions screen filters on, as one value.
 *
 * A data class rather than a pile of separate states so the filtering is a pure function of it,
 * testable without Compose, and so "is anything filtered?" is one comparison against the default.
 *
 * A null in [categoryIds] means the uncategorised bucket. It has to be selectable: "what have I
 * not sorted yet" is one of the questions this screen exists to answer.
 */
data class TransactionFilter(
    val direction: Direction = Direction.ALL,
    val categoryIds: Set<String?> = emptySet(),
    val range: DateRange = DateRange.ANY,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val minMinor: Long? = null,
    val maxMinor: Long? = null,
    val query: String = "",
    val sortBy: SortBy = SortBy.NEWEST,
    /** Anchors are balance re-syncs, not money moving, so they are off unless asked for. */
    val includeAnchors: Boolean = false,
) {
    /** Whether anything is narrowing the list, which is what the "Clear" affordance keys off. */
    val isActive: Boolean get() = this != TransactionFilter(sortBy = sortBy)
}

/** What the screen shows above the list. Recomputed from the filtered set, never accumulated. */
data class TransactionTotals(
    val count: Int,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

object TransactionFilters {

    /**
     * Resolves a named range to concrete dates. Returns null for [DateRange.ANY], and for a
     * [DateRange.CUSTOM] whose fields are still blank, which both mean "do not filter by date".
     * Inclusive at both ends: a range ending today must contain today.
     */
    fun resolveRange(filter: TransactionFilter, today: LocalDate): Pair<LocalDate, LocalDate>? =
        when (filter.range) {
            DateRange.ANY -> null
            DateRange.THIS_MONTH -> today.withDayOfMonth(1) to today
            DateRange.LAST_MONTH -> {
                val firstOfLast = today.withDayOfMonth(1).minusMonths(1)
                firstOfLast to firstOfLast.withDayOfMonth(firstOfLast.lengthOfMonth())
            }
            DateRange.LAST_30 -> today.minusDays(29) to today
            DateRange.LAST_90 -> today.minusDays(89) to today
            DateRange.THIS_YEAR -> today.withDayOfYear(1) to today
            DateRange.CUSTOM -> {
                val from = filter.customFrom
                val to = filter.customTo
                when {
                    from == null && to == null -> null
                    // One end left blank means open-ended on that side rather than no filter, so a
                    // half-filled custom range still does something sensible.
                    from == null -> LocalDate.MIN to to!!
                    to == null -> from to LocalDate.MAX
                    // Entered backwards: honour what was meant rather than matching nothing.
                    from.isAfter(to) -> to to from
                    else -> from to to
                }
            }
        }

    fun apply(
        entries: List<Entry>,
        filter: TransactionFilter,
        categories: List<Category>,
        today: LocalDate,
        zone: ZoneId,
    ): List<Entry> {
        val window = resolveRange(filter, today)
        val needle = filter.query.trim()

        val matched = entries.asSequence()
            .filter { filter.includeAnchors || it.type != EntryType.ANCHOR }
            .filter {
                when (filter.direction) {
                    Direction.ALL -> true
                    Direction.INCOME -> it.type == EntryType.CREDIT
                    Direction.EXPENSE -> it.type == EntryType.DEBIT
                }
            }
            .filter { filter.categoryIds.isEmpty() || it.categoryId in filter.categoryIds }
            .filter {
                window == null || run {
                    val d = Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
                    !d.isBefore(window.first) && !d.isAfter(window.second)
                }
            }
            .filter { filter.minMinor == null || it.amountMinor >= filter.minMinor }
            .filter { filter.maxMinor == null || it.amountMinor <= filter.maxMinor }
            .filter { needle.isEmpty() || it.matches(needle, categories) }
            .toList()

        return when (filter.sortBy) {
            SortBy.NEWEST -> matched.sortedByDescending { it.timestamp }
            SortBy.OLDEST -> matched.sortedBy { it.timestamp }
            SortBy.LARGEST -> matched.sortedByDescending { it.amountMinor }
            SortBy.SMALLEST -> matched.sortedBy { it.amountMinor }
        }
    }

    /**
     * Search covers the merchant, the note and the category name. The category name is in there
     * because "groceries" is what a person types when they mean "things I filed as Groceries",
     * and being told there are no results would be simply wrong.
     *
     * The raw message body is deliberately NOT searched: it holds account digits and balances, and
     * matching on them would surface rows for reasons the user cannot see.
     */
    private fun Entry.matches(needle: String, categories: List<Category>): Boolean {
        if (merchant?.contains(needle, ignoreCase = true) == true) return true
        if (note.contains(needle, ignoreCase = true)) return true
        val name = Categories.byId(categories, categoryId)?.name
        return name?.contains(needle, ignoreCase = true) == true
    }

    /** Anchors never count: an anchor's amount is a balance, and summing it with spending is nonsense. */
    fun totals(entries: List<Entry>): TransactionTotals = TransactionTotals(
        count = entries.size,
        incomeMinor = entries.filter { it.type == EntryType.CREDIT }.sumOf { it.amountMinor },
        expenseMinor = entries.filter { it.type == EntryType.DEBIT }.sumOf { it.amountMinor },
    )
}

package com.instabalance

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One arc of the ring. A null [categoryId] is the deliberate "Uncategorised" slice. */
data class Slice(val categoryId: String?, val amountMinor: Long)

/** One bar. [label] is already formatted for the axis. */
data class Bucket(val label: String, val amountMinor: Long)

/**
 * Month to date against the same number of days of the previous month. Comparing a partial month
 * against a whole one makes every early-month figure look like a collapse, so [previousWindow] is
 * the honest comparison and [previousFull] is offered alongside it.
 */
data class MonthComparison(
    val current: Long,
    val previousWindow: Long,
    val previousFull: Long,
)

/**
 * Every aggregate the charts and the budget read, as pure functions.
 *
 * Two rules hold throughout, and the tests pin both:
 *  - ANCHOR entries are never spending. They are a re-sync of the balance, so counting one would
 *    show a month where you "spent" your entire balance.
 *  - The zone is always passed in. Egypt observes DST again since 2023, so a fixed 24 hour day
 *    misfiles entries either side of the switch; java.time with an explicit zone does not.
 */
object Insights {

    /** The roll-up slice id for everything past the top N. Not a real category. */
    const val OTHER_ROLLUP = "__other__"

    private fun spendable(entries: List<Entry>, type: EntryType) =
        entries.asSequence().filter { it.type == type && it.type != EntryType.ANCHOR }

    private fun Entry.dateIn(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun Entry.monthIn(zone: ZoneId): YearMonth = YearMonth.from(dateIn(zone))

    /** What the review inbox lists, and what the Home badge counts. */
    fun uncategorisedCount(entries: List<Entry>): Int =
        entries.count { it.type != EntryType.ANCHOR && it.categoryId == null }

    /**
     * Totals per category over a window, biggest first. Uncategorised entries are their own slice
     * rather than dropped: a ring that quietly hides half the month is worse than one that admits
     * it does not know.
     */
    fun byCategory(
        entries: List<Entry>,
        type: EntryType,
        from: Instant,
        to: Instant,
        zone: ZoneId,
    ): List<Slice> {
        val fromDate = from.atZone(zone).toLocalDate()
        val toDate = to.atZone(zone).toLocalDate()
        return spendable(entries, type)
            .filter { val d = it.dateIn(zone); !d.isBefore(fromDate) && !d.isAfter(toDate) }
            .groupBy { it.categoryId }
            .map { (id, es) -> Slice(id, es.sumOf { it.amountMinor }) }
            .sortedWith(
                // Uncategorised sinks to the bottom whatever its size, so a real category is
                // always what the eye lands on first.
                compareBy<Slice> { it.categoryId == null }.thenByDescending { it.amountMinor }
            )
    }

    /** Beyond [n], slices become one roll-up, so the ring never grows untappable slivers. */
    fun topN(slices: List<Slice>, n: Int): List<Slice> {
        if (slices.size <= n) return slices
        val head = slices.take(n)
        val rest = slices.drop(n).sumOf { it.amountMinor }
        return if (rest > 0) head + Slice(OTHER_ROLLUP, rest) else head
    }

    /** One bucket per day, including the empty ones: a gap in a bar chart has to be visible. */
    fun daily(
        entries: List<Entry>,
        type: EntryType,
        days: Int,
        now: Instant,
        zone: ZoneId,
    ): List<Bucket> {
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays((days - 1).toLong())
        val totals = spendable(entries, type)
            .filter { val d = it.dateIn(zone); !d.isBefore(start) && !d.isAfter(today) }
            .groupBy { it.dateIn(zone) }
            .mapValues { (_, es) -> es.sumOf { it.amountMinor } }
        return (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            Bucket(d.dayOfMonth.toString(), totals[d] ?: 0L)
        }
    }

    /** One bucket per month, most recent last. */
    fun monthly(
        entries: List<Entry>,
        type: EntryType,
        months: Int,
        now: Instant,
        zone: ZoneId,
    ): List<Bucket> {
        val thisMonth = YearMonth.from(now.atZone(zone).toLocalDate())
        val start = thisMonth.minusMonths((months - 1).toLong())
        val totals = spendable(entries, type)
            .filter { val m = it.monthIn(zone); !m.isBefore(start) && !m.isAfter(thisMonth) }
            .groupBy { it.monthIn(zone) }
            .mapValues { (_, es) -> es.sumOf { it.amountMinor } }
        return (0 until months).map { i ->
            val m = start.plusMonths(i.toLong())
            Bucket(MONTH_LABELS[m.monthValue - 1], totals[m] ?: 0L)
        }
    }

    /** Total for the month [now] falls in. What the budget measures against. */
    fun spentInMonth(entries: List<Entry>, now: Instant, zone: ZoneId): Long {
        val month = YearMonth.from(now.atZone(zone).toLocalDate())
        return spendable(entries, EntryType.DEBIT)
            .filter { it.monthIn(zone) == month }
            .sumOf { it.amountMinor }
    }

    fun monthComparison(
        entries: List<Entry>,
        type: EntryType,
        now: Instant,
        zone: ZoneId,
    ): MonthComparison {
        val today = now.atZone(zone).toLocalDate()
        val thisMonth = YearMonth.from(today)
        val lastMonth = thisMonth.minusMonths(1)
        // On the 31st compared against February, clamp rather than run off the end of the month.
        val dayCutoff = minOf(today.dayOfMonth, lastMonth.lengthOfMonth())

        var current = 0L
        var previousWindow = 0L
        var previousFull = 0L
        spendable(entries, type).forEach {
            val d = it.dateIn(zone)
            when (YearMonth.from(d)) {
                thisMonth -> current += it.amountMinor
                lastMonth -> {
                    previousFull += it.amountMinor
                    if (d.dayOfMonth <= dayCutoff) previousWindow += it.amountMinor
                }
                else -> Unit
            }
        }
        return MonthComparison(current, previousWindow, previousFull)
    }

    // ---- ring geometry (pure, so the Canvas has nothing to get wrong) ------

    /** Sweep angles in degrees, in slice order. Empty when there is nothing to draw. */
    fun sliceAngles(slices: List<Slice>): List<Pair<Float, Float>> {
        val total = slices.sumOf { it.amountMinor }
        if (total <= 0L) return emptyList()
        var start = 0f
        return slices.map {
            val sweep = (it.amountMinor.toDouble() / total * 360.0).toFloat()
            val pair = start to sweep
            start += sweep
            pair
        }
    }

    /** Which slice a tap at [angleDeg] clockwise from 12 o'clock landed on, or -1 for none. */
    fun tapToSliceIndex(angleDeg: Float, angles: List<Pair<Float, Float>>): Int {
        val a = ((angleDeg % 360f) + 360f) % 360f
        return angles.indexOfFirst { (start, sweep) -> a >= start && a < start + sweep }
    }

    private val MONTH_LABELS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}

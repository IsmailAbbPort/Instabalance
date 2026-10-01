package com.instabalance

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One arc of the ring. A null [categoryId] is the deliberate "Uncategorised" slice. */
data class Slice(val categoryId: String?, val amountMinor: Long)

/**
 * One bar. [label] is the short axis tick ("18", "Sep"); [fullLabel] is what the readout shows
 * when the bar is tapped ("18 Sep 2026", "September 2026"). Tapping is how a bar says which day it
 * is: thirty labels along an axis would have to be unreadably small to fit.
 */
data class Bucket(val label: String, val fullLabel: String, val amountMinor: Long)

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

    /**
     * What the ring draws, plus what got folded into the roll-up. The tail is kept rather than
     * summed away so the legend can open it: "Other categories" as a dead end tells you a number
     * and refuses to say what it is made of.
     */
    data class Rollup(val visible: List<Slice>, val tail: List<Slice>)

    /**
     * Fewer than this in the tail and rolling up is not worth it: one category folded away costs
     * its name and saves a single row, which is a worse chart than just showing it.
     */
    private const val MIN_ROLLUP = 2

    /**
     * Beyond [n], slices become one roll-up, so the ring never grows untappable slivers.
     *
     * The uncategorised slice is never rolled up. It sorts last, so a naive take(n) would fold it
     * into "Other categories" and the ring would quietly stop admitting how much it does not know,
     * which is the exact dishonesty having a separate slice exists to prevent.
     *
     * Zero-amount slices never count toward the tail, because no arc is drawn for them and a
     * roll-up that stands for nothing is just a row saying zero.
     */
    fun rollUp(slices: List<Slice>, n: Int): Rollup {
        val unknown = slices.firstOrNull { it.categoryId == null }
        val known = slices.filter { it.categoryId != null }
        val tail = known.drop(n).filter { it.amountMinor > 0 }
        if (tail.size < MIN_ROLLUP) return Rollup(slices, emptyList())

        val visible = buildList {
            addAll(known.take(n))
            add(Slice(OTHER_ROLLUP, tail.sumOf { it.amountMinor }))
            unknown?.let { add(it) }
        }
        return Rollup(visible, tail)
    }

    /** Just the drawn slices, for callers that have no legend to expand. */
    fun topN(slices: List<Slice>, n: Int): List<Slice> = rollUp(slices, n).visible

    /**
     * How many bars to skip between axis labels. Thirty dates cannot fit along the axis at a
     * readable size, and a chart labelled only at its two ends makes you count bars.
     *
     * Derived rather than fixed at every third: a fixed 3 would label two of the six month bars.
     * At the default this gives every third day over thirty, every fourth over a long month, and
     * every month over six.
     */
    fun axisLabelStep(count: Int, maxLabels: Int = 10): Int {
        if (count <= 0 || maxLabels <= 0) return 1
        return if (count <= maxLabels) 1 else (count + maxLabels - 1) / maxLabels
    }

    /**
     * Which bars carry a label, counted back from the last one so the most recent bar is always
     * labelled. Today is the bar you look at first, and an axis that stops three days short of it
     * looks stale.
     */
    fun axisLabelIndices(count: Int, maxLabels: Int = 10): Set<Int> {
        if (count <= 0) return emptySet()
        val step = axisLabelStep(count, maxLabels)
        return generateSequence(count - 1) { it - step }.takeWhile { it >= 0 }.toSet()
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
            Bucket(
                label = d.dayOfMonth.toString(),
                fullLabel = "${d.dayOfMonth} ${MONTH_LABELS[d.monthValue - 1]} ${d.year}",
                amountMinor = totals[d] ?: 0L,
            )
        }
    }

    /**
     * What the balance was at [atMillis], by the same rule the headline balance uses: the most
     * recent anchor at or before that moment, plus everything signed that happened between.
     *
     * Null before the first anchor ever set. The balance is not zero then, it is unknown, and
     * drawing a confident zero is worse than drawing nothing.
     */
    fun balanceAt(entries: List<Entry>, atMillis: Long): Long? {
        val anchor = entries
            .filter { it.type == EntryType.ANCHOR && it.timestamp <= atMillis }
            .maxByOrNull { it.timestamp }
            ?: return null
        val delta = entries
            .filter { it.type != EntryType.ANCHOR }
            .filter { it.timestamp > anchor.timestamp && it.timestamp <= atMillis }
            .sumOf { if (it.type == EntryType.CREDIT) it.amountMinor else -it.amountMinor }
        return anchor.amountMinor + delta
    }

    /**
     * The closing balance at the end of each of the last [days] days, oldest first.
     *
     * The app has always known this and only ever shown today's figure. Where the money went is a
     * bar chart; whether you are running down is a line, and the two answer different questions.
     *
     * Days before the first anchor are left out rather than zeroed, so the line starts where the
     * ledger actually starts knowing. That also means a re-sync partway through the window is not
     * a step to explain away: it is the point at which the record was corrected.
     */
    fun balanceSeries(entries: List<Entry>, days: Int, now: Instant, zone: ZoneId): List<Bucket> {
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays((days - 1).toLong())
        return (0 until days).mapNotNull { i ->
            val d = start.plusDays(i.toLong())
            // End of that day, to the millisecond, so a transaction at 23:59 counts on its own day.
            val endOfDay = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            balanceAt(entries, endOfDay)?.let { balance ->
                Bucket(
                    label = d.dayOfMonth.toString(),
                    fullLabel = "${d.dayOfMonth} ${MONTH_LABELS[d.monthValue - 1]} ${d.year}",
                    amountMinor = balance,
                )
            }
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
            Bucket(
                label = MONTH_LABELS[m.monthValue - 1],
                fullLabel = "${MONTH_NAMES[m.monthValue - 1]} ${m.year}",
                amountMinor = totals[m] ?: 0L,
            )
        }
    }

    /**
     * Total for the month [now] falls in, minus anything filed under a category marked
     * [Category.excludedFromBudget]. What the budget measures against.
     *
     * [excludedIds] is passed rather than read from a singleton so this stays pure, and it is a
     * required argument rather than a defaulted one: a caller that forgets it would silently count
     * investments as spending again, which is the bug this exists to fix.
     */
    fun spentInMonth(entries: List<Entry>, now: Instant, zone: ZoneId, excludedIds: Set<String>): Long =
        monthlyDebits(entries, now, zone)
            .filter { it.categoryId !in excludedIds }
            .sumOf { it.amountMinor }

    /** This month's spend per category id, for the categories that set themselves a limit. */
    fun spentInMonthByCategory(entries: List<Entry>, now: Instant, zone: ZoneId): Map<String, Long> =
        monthlyDebits(entries, now, zone)
            .filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .mapValues { (_, es) -> es.sumOf { it.amountMinor } }

    /** The counterpart: what was left out of [spentInMonth], so the card can say so rather than hide it. */
    fun excludedInMonth(entries: List<Entry>, now: Instant, zone: ZoneId, excludedIds: Set<String>): Long =
        monthlyDebits(entries, now, zone)
            .filter { it.categoryId in excludedIds }
            .sumOf { it.amountMinor }

    private fun monthlyDebits(entries: List<Entry>, now: Instant, zone: ZoneId): Sequence<Entry> {
        val month = YearMonth.from(now.atZone(zone).toLocalDate())
        return spendable(entries, EntryType.DEBIT).filter { it.monthIn(zone) == month }
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

    // English explicitly, so an ar-EG device does not render a mix of scripts on the axis.
    private val MONTH_LABELS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

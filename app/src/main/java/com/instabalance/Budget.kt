package com.instabalance

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** What the Home card renders. [percent] is uncapped, so 120 percent still reads as 120. */
data class BudgetStatus(
    val spentMinor: Long,
    val limitMinor: Long,
    val percent: Int,
    val daysLeft: Int,
    val projectedMinor: Long,
)

/**
 * Monthly spend limit with milestone alerts.
 *
 * The whole "only the newest milestone fires" rule reduces to one stored integer. Nothing here
 * iterates milestones looking for crossings: [reachedMilestone] reports only the top one reached,
 * and it fires only if it beats what already fired this month. So a single expense taking you from
 * 20 percent to 80 percent fires 75 once, and 25 and 50 never fire at all.
 *
 * That same shape gives three other properties for free: a milestone can never fire twice, a refund
 * that drops you back under a threshold does not re-arm it (which would turn a refund plus a
 * repurchase into two notifications for the same threshold), and lowering your limit mid-month
 * does not retro-fire everything below the new figure.
 */
object Budget {

    val MILESTONES = listOf(25, 50, 75, 90, 100, 120)

    /** Highest milestone at or below the current percentage, or 0 when under the first one. */
    fun reachedMilestone(spentMinor: Long, budgetMinor: Long): Int {
        if (budgetMinor <= 0L || spentMinor <= 0L) return 0
        val percent = spentMinor.toDouble() / budgetMinor.toDouble() * 100.0
        return MILESTONES.lastOrNull { it <= percent } ?: 0
    }

    /** The alert to post, or null. Pure: no clock, no NotificationManager, no I/O. */
    fun milestoneToFire(spentMinor: Long, budgetMinor: Long, highestFired: Int): Int? =
        reachedMilestone(spentMinor, budgetMinor).takeIf { it > highestFired }

    /** "2026-09". Stored so the milestone state can reset itself on the next transaction. */
    fun monthKey(now: Instant, zone: ZoneId): String =
        YearMonth.from(now.atZone(zone).toLocalDate()).toString()

    fun status(entries: List<Entry>, limitMinor: Long, now: Instant, zone: ZoneId): BudgetStatus {
        val spent = Insights.spentInMonth(entries, now, zone)
        val today = now.atZone(zone).toLocalDate()
        val month = YearMonth.from(today)
        val dayOfMonth = today.dayOfMonth
        val length = month.lengthOfMonth()
        val percent = if (limitMinor <= 0L) 0
        else Math.round(spent.toDouble() / limitMinor.toDouble() * 100.0).toInt()
        // Straight-line from the month so far. Crude, but it is the figure people expect, and the
        // card labels it as a projection rather than a fact.
        val projected = if (dayOfMonth <= 0) spent else spent * length / dayOfMonth
        return BudgetStatus(
            spentMinor = spent,
            limitMinor = limitMinor,
            percent = percent,
            daysLeft = length - dayOfMonth,
            projectedMinor = projected,
        )
    }

    fun title(milestone: Int): String = when (milestone) {
        25 -> "Quarter of your budget used"
        50 -> "Halfway through your budget"
        75 -> "75% of your budget used"
        90 -> "Close to your budget"
        100 -> "Budget reached"
        else -> "20% over budget"
    }

    fun body(spentMinor: Long, limitMinor: Long): String =
        "EGP ${Money.formatMinor(spentMinor)} of ${Money.formatMinor(limitMinor)} this month"
}

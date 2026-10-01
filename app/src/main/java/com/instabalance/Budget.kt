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
    /**
     * Expenses this month that a category excluded from the budget. Surfaced because the spend
     * total on the charts and the figure on this card now legitimately differ, and a reader who
     * finds the gap themselves has no way to tell which number is lying.
     */
    val excludedMinor: Long = 0L,
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
/** One category's own limit and what it has used of it this month. */
data class CategoryBudgetStatus(
    val category: Category,
    val spentMinor: Long,
    val limitMinor: Long,
) {
    val percent: Int get() =
        if (limitMinor <= 0L) 0
        else Math.round(spentMinor.toDouble() / limitMinor.toDouble() * 100.0).toInt()

    val overspent: Boolean get() = spentMinor > limitMinor
}

object Budget {

    val MILESTONES = listOf(25, 50, 75, 90, 100, 120)

    /**
     * A category gets one alert a month, when it goes over, and that is the whole ladder.
     *
     * The monthly budget runs six milestones because there is exactly one of it. Running six per
     * category would mean up to six alerts times however many categories have a limit, which is how
     * a channel gets muted, and a muted channel takes the monthly budget's alerts down with it.
     * Passing the limit is the moment that actually means something.
     */
    val CATEGORY_MILESTONES = listOf(100)

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

    fun status(
        entries: List<Entry>,
        limitMinor: Long,
        now: Instant,
        zone: ZoneId,
        excludedIds: Set<String>,
    ): BudgetStatus {
        val spent = Insights.spentInMonth(entries, now, zone, excludedIds)
        val excluded = Insights.excludedInMonth(entries, now, zone, excludedIds)
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
            excludedMinor = excluded,
        )
    }

    /** Same shape as [reachedMilestone], against the shorter category ladder. */
    fun categoryReached(spentMinor: Long, limitMinor: Long): Int {
        if (limitMinor <= 0L || spentMinor <= 0L) return 0
        val percent = spentMinor.toDouble() / limitMinor.toDouble() * 100.0
        return CATEGORY_MILESTONES.lastOrNull { it <= percent } ?: 0
    }

    fun categoryMilestoneToFire(spentMinor: Long, limitMinor: Long, highestFired: Int): Int? =
        categoryReached(spentMinor, limitMinor).takeIf { it > highestFired }

    /**
     * What survives into [month] of the per-category fired state stored against [storedMonth].
     *
     * Its own function because two separate places consult it inside one lock, the monthly
     * evaluation and the category one, and the monthly evaluation writes the month stamp first.
     * Left inline, the second would see a month it considers current and keep last month's state
     * forever, so a category limit could fire once and then never again.
     */
    fun carryOverCategoryMilestones(
        storedMonth: String,
        month: String,
        stored: Map<String, Int>,
    ): Map<String, Int> = if (storedMonth == month) stored else emptyMap()

    /**
     * Every category that has set itself a limit, worst first, so the one you are about to blow is
     * the one you see. Categories with no limit are absent rather than shown at zero.
     */
    fun categoryStatuses(
        entries: List<Entry>,
        categories: List<Category>,
        now: Instant,
        zone: ZoneId,
    ): List<CategoryBudgetStatus> {
        val spent = Insights.spentInMonthByCategory(entries, now, zone)
        return categories
            .filter { !it.hidden }
            .mapNotNull { c ->
                val limit = c.budgetMinor?.takeIf { it > 0L } ?: return@mapNotNull null
                CategoryBudgetStatus(c, spent[c.id] ?: 0L, limit)
            }
            .sortedWith(compareByDescending<CategoryBudgetStatus> { it.percent }.thenBy { it.category.name })
    }

    fun categoryTitle(category: Category): String = "${category.name} is over budget"

    fun categoryBody(status: CategoryBudgetStatus): String =
        "EGP ${Money.formatMinor(status.spentMinor)} of ${Money.formatMinor(status.limitMinor)} this month"

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

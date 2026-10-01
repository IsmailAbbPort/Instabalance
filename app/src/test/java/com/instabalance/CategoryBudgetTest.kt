package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * One monthly figure is coarse. "Eating out under 3,000" is the limit people actually enforce.
 *
 * A category gets one alert a month, when it goes over. Six milestones each, across however many
 * categories have a limit, is how a channel gets muted, and a muted channel takes the monthly
 * budget's alerts down with it.
 */
class CategoryBudgetTest {

    private val cairo = ZoneId.of("Africa/Cairo")
    private val now = Instant.parse("2026-09-15T12:00:00Z")

    private var n = 0

    private fun spend(minor: Long, categoryId: String?, day: Int = 10) = Entry(
        id = "e${n++}",
        type = EntryType.DEBIT,
        amountMinor = minor,
        timestamp = java.time.LocalDate.of(2026, 9, day)
            .atTime(12, 0).atZone(cairo).toInstant().toEpochMilli(),
        categoryId = categoryId,
    )

    private fun categories(vararg limits: Pair<String, Long?>): List<Category> =
        Categories.PRESETS.map { c ->
            limits.firstOrNull { it.first == c.id }?.let { c.copy(budgetMinor = it.second) } ?: c
        }

    // ---- the ladder ---------------------------------------------------------

    @Test fun acategoryGetsOneAlertAmonthAndItIsWhenItGoesOver() {
        assertEquals(listOf(100), Budget.CATEGORY_MILESTONES)
    }

    @Test fun underTheLimitIsSilent() {
        assertEquals(0, Budget.categoryReached(299_900, 300_000))
        assertNull(Budget.categoryMilestoneToFire(299_900, 300_000, 0))
    }

    @Test fun exactlyAtTheLimitCounts() {
        assertEquals(100, Budget.categoryReached(300_000, 300_000))
    }

    @Test fun overTheLimitFiresOnce() {
        assertEquals(100, Budget.categoryMilestoneToFire(310_000, 300_000, 0))
        assertNull(Budget.categoryMilestoneToFire(310_000, 300_000, 100))
    }

    @Test fun spendingMoreAfterItAlreadyFiredDoesNotFireAgain() {
        assertNull(Budget.categoryMilestoneToFire(900_000, 300_000, 100))
    }

    @Test fun noLimitNeverFires() {
        assertEquals(0, Budget.categoryReached(500_000, 0))
    }

    // ---- the statuses -------------------------------------------------------

    @Test fun onlyCategoriesThatSetAlimitAppear() {
        val entries = listOf(spend(50_000, "eating_out"), spend(90_000, "groceries"))

        val out = Budget.categoryStatuses(entries, categories("eating_out" to 300_000), now, cairo)

        assertEquals(listOf("eating_out"), out.map { it.category.id })
    }

    @Test fun acategoryWithAlimitAndNoSpendingShowsAtZero() {
        val out = Budget.categoryStatuses(emptyList(), categories("rent" to 500_000), now, cairo)

        assertEquals(0L, out.single().spentMinor)
        assertEquals(0, out.single().percent)
    }

    @Test fun worstFirstSoTheOneYouAreAboutToBlowIsWhatYouSee() {
        val entries = listOf(spend(290_000, "eating_out"), spend(50_000, "groceries"))
        val cats = categories("eating_out" to 300_000, "groceries" to 300_000)

        val out = Budget.categoryStatuses(entries, cats, now, cairo)

        assertEquals(listOf("eating_out", "groceries"), out.map { it.category.id })
    }

    @Test fun ahiddenCategoryIsNotListed() {
        val cats = categories("eating_out" to 300_000).map {
            if (it.id == "eating_out") it.copy(hidden = true) else it
        }

        assertTrue(Budget.categoryStatuses(emptyList(), cats, now, cairo).isEmpty())
    }

    @Test fun alimitOfZeroIsNoLimit() {
        assertTrue(Budget.categoryStatuses(emptyList(), categories("rent" to 0L), now, cairo).isEmpty())
    }

    @Test fun lastMonthsSpendingDoesNotCount() {
        val august = spend(900_000, "eating_out", day = 10).copy(
            timestamp = java.time.LocalDate.of(2026, 8, 10)
                .atTime(12, 0).atZone(cairo).toInstant().toEpochMilli()
        )

        val out = Budget.categoryStatuses(listOf(august), categories("eating_out" to 300_000), now, cairo)

        assertEquals(0L, out.single().spentMinor)
    }

    @Test fun ananchorIsNeverSpending() {
        val anchor = Entry(
            type = EntryType.ANCHOR, amountMinor = 9_000_000,
            timestamp = now.toEpochMilli(), categoryId = null,
        )

        val out = Budget.categoryStatuses(listOf(anchor), categories("eating_out" to 300_000), now, cairo)

        assertEquals(0L, out.single().spentMinor)
    }

    @Test fun overspentIsAboveTheLimitNotAtIt() {
        val exactly = CategoryBudgetStatus(Categories.PRESETS.first(), 300_000, 300_000)
        val over = CategoryBudgetStatus(Categories.PRESETS.first(), 300_001, 300_000)

        assertFalse(exactly.overspent)
        assertTrue(over.overspent)
    }

    // ---- independent of the overall budget ----------------------------------

    @Test fun acategoryLeftOutOfTheBudgetCanStillHaveAlimitOfItsOwn() {
        // An investment pot is not spending, but "no more than 5,000 a month into it" still means
        // something.
        val cats = categories("investment" to 500_000).map {
            if (it.id == "investment") it.copy(excludedFromBudget = true) else it
        }
        val entries = listOf(spend(600_000, "investment"))

        val out = Budget.categoryStatuses(entries, cats, now, cairo)

        assertEquals(1, out.size)
        assertTrue(out.single().overspent)
    }

    // ---- the month stamp both budgets share ---------------------------------

    @Test fun thesameMonthKeepsWhatAlreadyFired() {
        val fired = mapOf("eating_out" to 100)

        assertEquals(fired, Budget.carryOverCategoryMilestones("2026-09", "2026-09", fired))
    }

    @Test fun anewMonthClearsTheCategoryFiredState() {
        // Without this a limit fires exactly once and then never again. Both budgets read the same
        // budgetMonth and the monthly evaluation stamps it first, so by the time the category one
        // looks, the month already appears current and last month's state would look like this
        // month's.
        val fired = mapOf("eating_out" to 100)

        assertTrue(Budget.carryOverCategoryMilestones("2026-08", "2026-09", fired).isEmpty())
    }

    @Test fun afreshLedgerWithNoStoredMonthStartsClean() {
        assertTrue(Budget.carryOverCategoryMilestones("", "2026-09", mapOf("rent" to 100)).isEmpty())
    }

    // ---- the per-category spend split ---------------------------------------

    @Test fun spendingIsSplitByCategory() {
        val entries = listOf(
            spend(10_000, "eating_out"), spend(15_000, "eating_out"), spend(50_000, "groceries"),
        )

        val out = Insights.spentInMonthByCategory(entries, now, cairo)

        assertEquals(25_000L, out["eating_out"])
        assertEquals(50_000L, out["groceries"])
    }

    @Test fun uncategorisedSpendingBelongsToNoLimit() {
        // It cannot count against a category until somebody says which, and pretending otherwise
        // would make every limit look better than it is.
        val out = Insights.spentInMonthByCategory(listOf(spend(50_000, null)), now, cairo)

        assertTrue(out.isEmpty())
    }
}

package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BudgetTest {

    private val cairo = ZoneId.of("Africa/Cairo")
    private val budget = 500_000L // EGP 5,000

    private fun at(iso: String): Instant = Instant.parse(iso)

    private fun debit(minor: Long, iso: String) =
        Entry(type = EntryType.DEBIT, amountMinor = minor, timestamp = at(iso).toEpochMilli())

    // ---- reachedMilestone ---------------------------------------------------

    @Test fun underTheFirstMilestoneIsZero() {
        assertEquals(0, Budget.reachedMilestone(124_000, budget)) // 24.8%
    }

    @Test fun exactlyOnAMilestoneCounts() {
        assertEquals(25, Budget.reachedMilestone(125_000, budget)) // exactly 25%
    }

    @Test fun reportsOnlyTheHighestReached() {
        assertEquals(75, Budget.reachedMilestone(400_000, budget)) // 80%
    }

    @Test fun pastTheLastMilestoneStaysAtTheLastMilestone() {
        assertEquals(120, Budget.reachedMilestone(2_000_000, budget)) // 400%
    }

    @Test fun aZeroOrAbsentBudgetNeverReachesAnything() {
        // Guards the divide as well as the feature being off.
        assertEquals(0, Budget.reachedMilestone(100_000, 0))
        assertEquals(0, Budget.reachedMilestone(100_000, -1))
    }

    // ---- milestoneToFire ----------------------------------------------------

    @Test fun oneBigExpenseCrossingSeveralFiresOnlyTheHighest() {
        // 20% to 80% in one transaction. 25 and 50 are crossed but must never be sent.
        assertEquals(75, Budget.milestoneToFire(400_000, budget, highestFired = 0))
    }

    @Test fun neverFiresTheSameMilestoneTwice() {
        assertNull(Budget.milestoneToFire(400_000, budget, highestFired = 75))
    }

    @Test fun firesAgainOnlyWhenAHigherMilestoneIsReached() {
        assertEquals(90, Budget.milestoneToFire(455_000, budget, highestFired = 75)) // 91%
    }

    @Test fun aRefundThatDropsYouBackDoesNotReArmTheMilestone() {
        // Refund takes 80% back down to 60%. Spending up to 80% again must stay silent, otherwise
        // a refund plus a repurchase buzzes twice for the same threshold.
        assertNull(Budget.milestoneToFire(300_000, budget, highestFired = 75)) // 60%
        assertNull(Budget.milestoneToFire(400_000, budget, highestFired = 75)) // 80% again
    }

    @Test fun loweringTheBudgetRecomputesRatherThanRetroFiring() {
        // Spend 300,000. Against 500,000 that is 60% (milestone 50). Halve the budget to 250,000
        // and it becomes 120%. The repository stores reachedMilestone at the moment of the change,
        // so nothing fires for the change itself.
        val afterChange = Budget.reachedMilestone(300_000, 250_000)
        assertEquals(120, afterChange)
        assertNull(Budget.milestoneToFire(300_000, 250_000, highestFired = afterChange))
    }

    // ---- month rollover -----------------------------------------------------

    @Test fun monthKeyIsTheLocalMonth() {
        assertEquals("2026-09", Budget.monthKey(at("2026-09-18T12:00:00Z"), cairo))
    }

    @Test fun monthKeyUsesLocalTimeNotUtc() {
        // 22:30 UTC on 30 September is already 1 October in Cairo, so the budget month has rolled.
        assertEquals("2026-10", Budget.monthKey(at("2026-09-30T22:30:00Z"), cairo))
    }

    // ---- status -------------------------------------------------------------

    @Test fun statusCountsOnlyThisMonthsDebits() {
        val entries = listOf(
            debit(100_000, "2026-09-05T10:00:00Z"),
            debit(50_000, "2026-09-17T10:00:00Z"),
            debit(999_000, "2026-08-20T10:00:00Z"), // last month, must not count
            Entry(type = EntryType.CREDIT, amountMinor = 700_000,
                timestamp = at("2026-09-10T10:00:00Z").toEpochMilli()),
            Entry(type = EntryType.ANCHOR, amountMinor = 900_000,
                timestamp = at("2026-09-02T10:00:00Z").toEpochMilli()),
        )

        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo, emptySet())

        assertEquals(150_000L, s.spentMinor)
        assertEquals(30, s.percent)
    }

    @Test fun statusProjectsAtTheCurrentDailyRate() {
        // 150,000 over 18 of September's 30 days projects to 250,000.
        val entries = listOf(debit(150_000, "2026-09-05T10:00:00Z"))
        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo, emptySet())

        assertEquals(250_000L, s.projectedMinor)
        assertEquals(12, s.daysLeft)
    }

    // ---- categories excluded from the budget --------------------------------
    //
    // Money into a fund leaves the account exactly like a purchase does, so the bank reports it
    // identically. Counting it as spending makes the budget wrong every single month.

    private fun debit(minor: Long, iso: String, categoryId: String?) =
        Entry(type = EntryType.DEBIT, amountMinor = minor, timestamp = at(iso).toEpochMilli(),
            categoryId = categoryId)

    @Test fun anExcludedCategoryDoesNotCountTowardTheBudget() {
        val entries = listOf(
            debit(100_000, "2026-09-05T10:00:00Z", "groceries"),
            debit(300_000, "2026-09-06T10:00:00Z", Categories.INVESTMENT),
        )

        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo,
            setOf(Categories.INVESTMENT))

        assertEquals(100_000L, s.spentMinor)
        assertEquals(20, s.percent)
    }

    @Test fun theExcludedAmountIsReportedRatherThanSilentlyDropped() {
        val entries = listOf(
            debit(100_000, "2026-09-05T10:00:00Z", "groceries"),
            debit(300_000, "2026-09-06T10:00:00Z", Categories.INVESTMENT),
        )

        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo,
            setOf(Categories.INVESTMENT))

        assertEquals(300_000L, s.excludedMinor)
    }

    @Test fun anUncategorisedExpenseStillCounts() {
        // The app cannot exclude what it has not been told about, and quietly ignoring unsorted
        // spending is the dangerous direction to be wrong in.
        val entries = listOf(debit(300_000, "2026-09-06T10:00:00Z", null))

        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo,
            setOf(Categories.INVESTMENT))

        assertEquals(300_000L, s.spentMinor)
        assertEquals(0L, s.excludedMinor)
    }

    @Test fun anExcludedExpenseNeverFiresAMilestone() {
        val entries = listOf(debit(400_000, "2026-09-06T10:00:00Z", Categories.INVESTMENT))

        val spent = Insights.spentInMonth(entries, at("2026-09-18T12:00:00Z"), cairo,
            setOf(Categories.INVESTMENT))

        assertEquals(0L, spent)
        assertNull(Budget.milestoneToFire(spent, budget, highestFired = 0))
    }

    @Test fun investmentShipsExcludedSoTheOverrideWorksWithNoSetup() {
        val investment = Categories.PRESETS.first { it.id == Categories.INVESTMENT }
        assertEquals(true, investment.excludedFromBudget)
        assertEquals(CategoryKind.EXPENSE, investment.kind)
        // Nothing else ships excluded: the budget must not quietly ignore ordinary spending.
        assertEquals(
            listOf(Categories.INVESTMENT),
            Categories.PRESETS.filter { it.excludedFromBudget }.map { it.id },
        )
    }

    @Test fun existingUsersGetInvestmentOnUpgrade() {
        // ensurePresets is what carries a new preset to a ledger written before it existed.
        val old = Categories.PRESETS.filterNot { it.id == Categories.INVESTMENT }
        val upgraded = Categories.ensurePresets(old)

        val investment = upgraded.firstOrNull { it.id == Categories.INVESTMENT }
        assertEquals(true, investment?.excludedFromBudget)
    }

    @Test fun excludingACategoryCanBeUndoneAndTheMoneyComesBack() {
        val entries = listOf(debit(300_000, "2026-09-06T10:00:00Z", Categories.INVESTMENT))
        val now = at("2026-09-18T12:00:00Z")

        assertEquals(0L, Insights.spentInMonth(entries, now, cairo, setOf(Categories.INVESTMENT)))
        assertEquals(300_000L, Insights.spentInMonth(entries, now, cairo, emptySet()))
    }

    @Test fun feesCountAsSpending() {
        // A fee is real money out, so it belongs in the figure the budget measures.
        val entries = listOf(
            Entry(type = EntryType.DEBIT, amountMinor = 2_000, source = Source.FEE,
                timestamp = at("2026-09-05T10:00:00Z").toEpochMilli()),
        )
        assertEquals(2_000L, Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo, emptySet()).spentMinor)
    }
}

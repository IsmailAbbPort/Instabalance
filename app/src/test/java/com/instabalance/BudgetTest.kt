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

        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo)

        assertEquals(150_000L, s.spentMinor)
        assertEquals(30, s.percent)
    }

    @Test fun statusProjectsAtTheCurrentDailyRate() {
        // 150,000 over 18 of September's 30 days projects to 250,000.
        val entries = listOf(debit(150_000, "2026-09-05T10:00:00Z"))
        val s = Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo)

        assertEquals(250_000L, s.projectedMinor)
        assertEquals(12, s.daysLeft)
    }

    @Test fun feesCountAsSpending() {
        // A fee is real money out, so it belongs in the figure the budget measures.
        val entries = listOf(
            Entry(type = EntryType.DEBIT, amountMinor = 2_000, source = Source.FEE,
                timestamp = at("2026-09-05T10:00:00Z").toEpochMilli()),
        )
        assertEquals(2_000L, Budget.status(entries, budget, at("2026-09-18T12:00:00Z"), cairo).spentMinor)
    }
}

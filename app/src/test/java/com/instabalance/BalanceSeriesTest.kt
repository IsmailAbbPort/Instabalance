package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The app has always known the balance at every instant since the last re-sync, and has only ever
 * shown today's figure. Where the money went is a bar chart; whether you are running down is a line.
 */
class BalanceSeriesTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    /** Noon on the given day, Cairo, so nothing sits near a day boundary by accident. */
    private fun at(day: Int, hour: Int = 12): Long =
        java.time.LocalDate.of(2026, 9, day)
            .atTime(hour, 0)
            .atZone(cairo)
            .toInstant()
            .toEpochMilli()

    private var n = 0

    private fun anchor(minor: Long, day: Int, hour: Int = 12) =
        Entry(id = "a${n++}", type = EntryType.ANCHOR, amountMinor = minor, timestamp = at(day, hour))

    private fun spend(minor: Long, day: Int, hour: Int = 12) =
        Entry(id = "d${n++}", type = EntryType.DEBIT, amountMinor = minor, timestamp = at(day, hour))

    private fun receive(minor: Long, day: Int, hour: Int = 12) =
        Entry(id = "c${n++}", type = EntryType.CREDIT, amountMinor = minor, timestamp = at(day, hour))

    // ---- balanceAt ----------------------------------------------------------

    @Test fun beforeAnyAnchorTheBalanceIsUnknownRatherThanZero() {
        // Drawing a confident zero would be a claim the ledger cannot make.
        assertNull(Insights.balanceAt(listOf(anchor(100_000, 10)), at(9)))
    }

    @Test fun withNoAnchorAtAllThereIsNothingToDraw() {
        assertNull(Insights.balanceAt(listOf(spend(5_000, 10)), at(11)))
    }

    @Test fun theanchorItselfIsTheBalance() {
        assertEquals(100_000L, Insights.balanceAt(listOf(anchor(100_000, 10)), at(10, 13)))
    }

    @Test fun spendingAfterTheAnchorComesOff() {
        val entries = listOf(anchor(100_000, 10), spend(5_000, 11), receive(2_000, 12))

        assertEquals(97_000L, Insights.balanceAt(entries, at(13)))
    }

    @Test fun alaterAnchorWinsAndWhatCameBeforeItIsNotCountedTwice() {
        // A re-sync is the bank's word over ours. Everything before it is already inside its figure.
        val entries = listOf(anchor(100_000, 10), spend(5_000, 11), anchor(80_000, 12), spend(1_000, 13))

        assertEquals(79_000L, Insights.balanceAt(entries, at(14)))
    }

    @Test fun ananchorLaterThanTheMomentAskedAboutIsIgnored() {
        val entries = listOf(anchor(100_000, 10), spend(5_000, 11), anchor(80_000, 20))

        assertEquals(95_000L, Insights.balanceAt(entries, at(12)))
    }

    // ---- the series ---------------------------------------------------------

    @Test fun oneClosingBalancePerDayOldestFirst() {
        val entries = listOf(anchor(100_000, 1), spend(10_000, 2), spend(10_000, 3))
        val now = Instant.ofEpochMilli(at(3, 23))

        val out = Insights.balanceSeries(entries, 3, now, cairo)

        assertEquals(listOf(100_000L, 90_000L, 80_000L), out.map { it.amountMinor })
        assertEquals(listOf("1", "2", "3"), out.map { it.label })
    }

    @Test fun aquietDayHoldsTheBalanceRatherThanDroppingToZero() {
        val entries = listOf(anchor(100_000, 1), spend(10_000, 3))
        val now = Instant.ofEpochMilli(at(3, 23))

        val out = Insights.balanceSeries(entries, 3, now, cairo)

        assertEquals(listOf(100_000L, 100_000L, 90_000L), out.map { it.amountMinor })
    }

    @Test fun daysBeforeTheFirstAnchorAreLeftOutEntirely() {
        // The line starts where the ledger starts knowing, rather than at a made up zero.
        val entries = listOf(anchor(100_000, 3))
        val now = Instant.ofEpochMilli(at(3, 23))

        val out = Insights.balanceSeries(entries, 5, now, cairo)

        assertEquals(1, out.size)
        assertEquals("3", out.single().label)
    }

    @Test fun anemptyLedgerDrawsNothing() {
        val now = Instant.ofEpochMilli(at(3, 23))

        assertEquals(emptyList<Bucket>(), Insights.balanceSeries(emptyList(), 30, now, cairo))
    }

    @Test fun alateNightTransactionCountsOnItsOwnDay() {
        // The window is the end of the day to the millisecond, so 23:59 is not tomorrow.
        val entries = listOf(anchor(100_000, 1), spend(10_000, 2, hour = 23))
        val now = Instant.ofEpochMilli(at(2, 23))

        val out = Insights.balanceSeries(entries, 2, now, cairo)

        assertEquals(listOf(100_000L, 90_000L), out.map { it.amountMinor })
    }

    @Test fun thelastPointAgreesWithTheHeadlineBalance() {
        // The two are computed by the same rule and must never disagree on screen.
        val entries = listOf(anchor(100_000, 1), spend(10_000, 2), receive(3_000, 3))
        val now = Instant.ofEpochMilli(at(3, 23))
        val data = LedgerData(entries = entries)

        val out = Insights.balanceSeries(entries, 3, now, cairo)

        assertEquals(LedgerRepository.balanceMinor(data), out.last().amountMinor)
    }

    @Test fun aresyncPartwayThroughShowsAsAstepRatherThanBeingSmoothedAway() {
        val entries = listOf(anchor(100_000, 1), anchor(50_000, 2))
        val now = Instant.ofEpochMilli(at(3, 23))

        val out = Insights.balanceSeries(entries, 3, now, cairo)

        assertEquals(listOf(100_000L, 50_000L, 50_000L), out.map { it.amountMinor })
    }
}

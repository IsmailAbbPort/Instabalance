package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class InsightsTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    private fun at(iso: String): Instant = Instant.parse(iso)

    private fun entry(
        type: EntryType, minor: Long, iso: String, categoryId: String? = null,
        source: Source = Source.MANUAL,
    ) = Entry(type = type, amountMinor = minor, timestamp = at(iso).toEpochMilli(),
        categoryId = categoryId, source = source)

    // ---- byCategory ---------------------------------------------------------

    @Test fun anchorsAreNeverSpending() {
        // An anchor is a re-sync of the balance. Counting one would report a month in which you
        // spent your entire balance.
        val entries = listOf(
            entry(EntryType.ANCHOR, 500_000, "2026-09-10T10:00:00Z", "groceries"),
            entry(EntryType.DEBIT, 10_000, "2026-09-10T10:00:00Z", "groceries"),
        )

        val slices = Insights.byCategory(entries, EntryType.DEBIT,
            at("2026-09-01T00:00:00Z"), at("2026-09-30T00:00:00Z"), cairo)

        assertEquals(listOf(Slice("groceries", 10_000)), slices)
    }

    @Test fun filtersByDirection() {
        val entries = listOf(
            entry(EntryType.DEBIT, 10_000, "2026-09-10T10:00:00Z", "groceries"),
            entry(EntryType.CREDIT, 90_000, "2026-09-10T10:00:00Z", "salary"),
        )

        val expenses = Insights.byCategory(entries, EntryType.DEBIT,
            at("2026-09-01T00:00:00Z"), at("2026-09-30T00:00:00Z"), cairo)

        assertEquals(1, expenses.size)
        assertEquals("groceries", expenses[0].categoryId)
    }

    @Test fun feesAreOrdinarySpending() {
        val entries = listOf(entry(EntryType.DEBIT, 2_000, "2026-09-10T10:00:00Z",
            Categories.FEES, Source.FEE))

        val slices = Insights.byCategory(entries, EntryType.DEBIT,
            at("2026-09-01T00:00:00Z"), at("2026-09-30T00:00:00Z"), cairo)

        assertEquals(listOf(Slice(Categories.FEES, 2_000)), slices)
    }

    @Test fun uncategorisedIsItsOwnSliceAndSortsLast() {
        // Even when it is the biggest. A ring that hides what it does not know is a lying ring,
        // but it should not be the first thing the eye lands on either.
        val entries = listOf(
            entry(EntryType.DEBIT, 90_000, "2026-09-10T10:00:00Z", null),
            entry(EntryType.DEBIT, 10_000, "2026-09-10T10:00:00Z", "groceries"),
        )

        val slices = Insights.byCategory(entries, EntryType.DEBIT,
            at("2026-09-01T00:00:00Z"), at("2026-09-30T00:00:00Z"), cairo)

        assertEquals(listOf(Slice("groceries", 10_000), Slice(null, 90_000)), slices)
    }

    @Test fun windowExcludesEntriesOutsideIt() {
        val entries = listOf(
            entry(EntryType.DEBIT, 10_000, "2026-08-31T20:00:00Z", "groceries"),
            entry(EntryType.DEBIT, 20_000, "2026-09-15T10:00:00Z", "groceries"),
        )

        val slices = Insights.byCategory(entries, EntryType.DEBIT,
            at("2026-09-01T00:00:00Z"), at("2026-09-30T00:00:00Z"), cairo)

        assertEquals(20_000L, slices.single().amountMinor)
    }

    // ---- topN ---------------------------------------------------------------

    @Test fun topNRollsUpTheTail() {
        val slices = (1..10).map { Slice("c$it", (11 - it) * 1000L) }

        val out = Insights.topN(slices, 3)

        assertEquals(4, out.size)
        assertEquals(Insights.OTHER_ROLLUP, out.last().categoryId)
        assertEquals(slices.drop(3).sumOf { it.amountMinor }, out.last().amountMinor)
    }

    @Test fun topNLeavesAShortListAlone() {
        val slices = listOf(Slice("a", 100), Slice("b", 50))
        assertEquals(slices, Insights.topN(slices, 7))
    }

    @Test fun topNNeverRollsUpTheUncategorisedSlice() {
        // It sorts last, so a naive take(n) folds it into "Other categories" and the ring stops
        // admitting what it does not know. Seen happening on a real screen before this test existed.
        val slices = (1..10).map { Slice("c$it", (11 - it) * 1000L) } + Slice(null, 500L)

        val out = Insights.topN(slices, 3)

        assertEquals(Slice(null, 500L), out.last())
        assertTrue(out.any { it.categoryId == Insights.OTHER_ROLLUP })
        // And it must not be counted twice, once on its own and once inside the roll-up.
        assertEquals(slices.sumOf { it.amountMinor }, out.sumOf { it.amountMinor })
    }

    @Test fun topNKeepsUncategorisedWhenNothingIsRolledUp() {
        val slices = listOf(Slice("a", 100), Slice(null, 50))
        assertEquals(slices, Insights.topN(slices, 7))
    }

    // ---- rollUp: the same thing, keeping what it folded away ------------------

    @Test fun theTailIsEveryCategoryTheRollUpStandsFor() {
        val slices = (1..10).map { Slice("c$it", (11 - it) * 1000L) }

        val out = Insights.rollUp(slices, 3)

        assertEquals(listOf("c4", "c5", "c6", "c7", "c8", "c9", "c10"), out.tail.map { it.categoryId })
        // Opening the legend row must account for exactly the number printed on it.
        assertEquals(
            out.visible.first { it.categoryId == Insights.OTHER_ROLLUP }.amountMinor,
            out.tail.sumOf { it.amountMinor },
        )
    }

    @Test fun theTailKeepsTheLargestFirstOrderOfTheSlicesItCameFrom() {
        val slices = (1..10).map { Slice("c$it", (11 - it) * 1000L) }

        val tail = Insights.rollUp(slices, 3).tail

        assertEquals(tail.sortedByDescending { it.amountMinor }, tail)
    }

    @Test fun thereIsNothingToOpenWhenNothingWasRolledUp() {
        val slices = listOf(Slice("a", 100), Slice(null, 50))

        val out = Insights.rollUp(slices, 7)

        assertEquals(slices, out.visible)
        assertTrue(out.tail.isEmpty())
    }

    @Test fun oneLeftOverCategoryIsShownRatherThanRolledUp() {
        // "Other categories: 1" would replace a real name with a vaguer one and save no space.
        val slices = (1..11).map { Slice("c$it", (12 - it) * 1000L) }

        val out = Insights.rollUp(slices, 10)

        assertEquals(slices, out.visible)
        assertTrue(out.tail.isEmpty())
        assertTrue(out.visible.none { it.categoryId == Insights.OTHER_ROLLUP })
    }

    @Test fun twoLeftOverCategoriesAreWorthRollingUp() {
        val slices = (1..12).map { Slice("c$it", (13 - it) * 1000L) }

        val out = Insights.rollUp(slices, 10)

        assertEquals(listOf("c11", "c12"), out.tail.map { it.categoryId })
        assertEquals(11, out.visible.size)
        assertEquals(Insights.OTHER_ROLLUP, out.visible.last().categoryId)
    }

    @Test fun aSingleLeftOverIsShownEvenAlongsideUncategorised() {
        val slices = (1..11).map { Slice("c$it", (12 - it) * 1000L) } + Slice(null, 500L)

        val out = Insights.rollUp(slices, 10)

        assertEquals(slices, out.visible)
        assertTrue(out.tail.isEmpty())
    }

    @Test fun rollingUpNeverChangesTheTotal() {
        // The ring and the legend both sum these, so a slice counted twice or dropped would make
        // the centre figure disagree with the rows under it.
        val slices = (1..14).map { Slice("c$it", (15 - it) * 1000L) } + Slice(null, 700L)

        val out = Insights.rollUp(slices, 10)

        assertEquals(slices.sumOf { it.amountMinor }, out.visible.sumOf { it.amountMinor })
    }

    @Test fun aTailOfOnlyZeroesDrawsNoRollUpAndOffersNothingToOpen() {
        // No roll-up slice is drawn for a zero, so a legend row that could be expanded to show
        // nothing must not appear either.
        val slices = (1..5).map { Slice("c$it", 1000L) } + (6..9).map { Slice("c$it", 0L) }

        val out = Insights.rollUp(slices, 5)

        assertTrue(out.visible.none { it.categoryId == Insights.OTHER_ROLLUP })
        assertTrue(out.tail.isEmpty())
    }

    // ---- axis labels ---------------------------------------------------------

    @Test fun thirtyDaysLabelsEveryThirdBar() {
        assertEquals(3, Insights.axisLabelStep(30))
        assertEquals(10, Insights.axisLabelIndices(30).size)
    }

    @Test fun sixMonthsLabelsEveryBar() {
        // A fixed "every third" would label two of the six, which is worse than what it replaced.
        assertEquals(1, Insights.axisLabelStep(6))
        assertEquals((0..5).toSet(), Insights.axisLabelIndices(6))
    }

    @Test fun aLongMonthStaysWithinTheLabelBudget() {
        assertEquals(4, Insights.axisLabelStep(31))
        assertTrue(Insights.axisLabelIndices(31).size <= 10)
    }

    @Test fun theMostRecentBarIsAlwaysLabelled() {
        // Today is the bar you look at first; an axis that stops short of it looks stale.
        listOf(1, 5, 6, 28, 29, 30, 31, 90).forEach { count ->
            assertTrue("last bar unlabelled for $count", count - 1 in Insights.axisLabelIndices(count))
        }
    }

    @Test fun anEmptyChartHasNoLabels() {
        assertTrue(Insights.axisLabelIndices(0).isEmpty())
        assertEquals(1, Insights.axisLabelStep(0))
    }

    // ---- daily / monthly ----------------------------------------------------

    @Test fun dailyKeepsEmptyDays() {
        // A gap in spending has to be visible as a gap, not compressed out of the chart.
        val entries = listOf(entry(EntryType.DEBIT, 5_000, "2026-09-18T10:00:00Z"))

        val buckets = Insights.daily(entries, EntryType.DEBIT, 7, at("2026-09-18T12:00:00Z"), cairo)

        assertEquals(7, buckets.size)
        assertEquals(5_000L, buckets.last().amountMinor)
        assertTrue(buckets.dropLast(1).all { it.amountMinor == 0L })
    }

    @Test fun monthlyProducesOneBucketPerMonthOldestFirst() {
        val entries = listOf(
            entry(EntryType.DEBIT, 1_000, "2026-07-10T10:00:00Z"),
            entry(EntryType.DEBIT, 3_000, "2026-09-10T10:00:00Z"),
        )

        val buckets = Insights.monthly(entries, EntryType.DEBIT, 3, at("2026-09-18T12:00:00Z"), cairo)

        assertEquals(listOf("Jul", "Aug", "Sep"), buckets.map { it.label })
        assertEquals(listOf(1_000L, 0L, 3_000L), buckets.map { it.amountMinor })
    }

    @Test fun bucketsCarryAFullDateForTheTapReadout() {
        // The axis tick is "18"; the readout has to say which month and year, because thirty
        // dates cannot fit along an axis at a readable size.
        val daily = Insights.daily(emptyList(), EntryType.DEBIT, 3, at("2026-09-18T12:00:00Z"), cairo)
        assertEquals(listOf("16", "17", "18"), daily.map { it.label })
        assertEquals("18 Sep 2026", daily.last().fullLabel)

        val monthly = Insights.monthly(emptyList(), EntryType.DEBIT, 2, at("2026-09-18T12:00:00Z"), cairo)
        assertEquals("September 2026", monthly.last().fullLabel)
    }

    // ---- time zone correctness ---------------------------------------------

    @Test fun bucketsUseLocalMidnightNotUtc() {
        // 22:30 UTC on 30 September is 00:30 on 1 October in Cairo, so this belongs to October.
        val entries = listOf(entry(EntryType.DEBIT, 7_000, "2026-09-30T22:30:00Z"))

        val buckets = Insights.monthly(entries, EntryType.DEBIT, 2, at("2026-10-05T12:00:00Z"), cairo)

        assertEquals(listOf(0L, 7_000L), buckets.map { it.amountMinor })
    }

    @Test fun bucketsSurviveTheDstSwitch() {
        // Egypt runs DST again since 2023. An entry late on the night the clocks move must not slide
        // into the neighbouring day, which is exactly what fixed 24 hour arithmetic would do.
        val entries = listOf(entry(EntryType.DEBIT, 4_000, "2026-04-24T21:30:00Z"))

        val buckets = Insights.monthly(entries, EntryType.DEBIT, 2, at("2026-05-05T12:00:00Z"), cairo)

        assertEquals(listOf("Apr", "May"), buckets.map { it.label })
        assertEquals(4_000L, buckets[0].amountMinor)
    }

    // ---- monthComparison ----------------------------------------------------

    @Test fun comparisonUsesTheSameDayWindow() {
        // On the 10th, comparing against the whole of last month makes every month look like a
        // collapse. The window is days 1 to 10 of each.
        val entries = listOf(
            entry(EntryType.DEBIT, 30_000, "2026-09-05T10:00:00Z"),
            entry(EntryType.DEBIT, 20_000, "2026-08-03T10:00:00Z"),
            entry(EntryType.DEBIT, 70_000, "2026-08-25T10:00:00Z"), // after the cutoff
        )

        val c = Insights.monthComparison(entries, EntryType.DEBIT, at("2026-09-10T12:00:00Z"), cairo)

        assertEquals(30_000L, c.current)
        assertEquals(20_000L, c.previousWindow)
        assertEquals(90_000L, c.previousFull)
    }

    @Test fun comparisonClampsToAShortPreviousMonth() {
        // 31 March against February: the cutoff clamps to 28 rather than running off the month.
        val entries = listOf(entry(EntryType.DEBIT, 5_000, "2026-02-28T10:00:00Z"))

        val c = Insights.monthComparison(entries, EntryType.DEBIT, at("2026-03-31T12:00:00Z"), cairo)

        assertEquals(5_000L, c.previousWindow)
    }

    // ---- uncategorisedCount -------------------------------------------------

    @Test fun uncategorisedCountIgnoresAnchors() {
        val entries = listOf(
            entry(EntryType.ANCHOR, 500_000, "2026-09-10T10:00:00Z", null),
            entry(EntryType.DEBIT, 10_000, "2026-09-10T10:00:00Z", null),
            entry(EntryType.CREDIT, 10_000, "2026-09-10T10:00:00Z", "salary"),
        )

        assertEquals(1, Insights.uncategorisedCount(entries))
    }

    // ---- ring geometry ------------------------------------------------------

    @Test fun sliceAnglesSumTo360() {
        val angles = Insights.sliceAngles(listOf(Slice("a", 1), Slice("b", 2), Slice("c", 1)))
        val (start, sweep) = angles.last()
        assertEquals(360f, start + sweep, 0.01f)
    }

    @Test fun anEmptyOrZeroTotalDrawsNothing() {
        // Guards the divide, and stops a ring being drawn for a month with no spending.
        assertTrue(Insights.sliceAngles(emptyList()).isEmpty())
        assertTrue(Insights.sliceAngles(listOf(Slice("a", 0))).isEmpty())
    }

    @Test fun tapMapsToTheSliceUnderIt() {
        val angles = Insights.sliceAngles(listOf(Slice("a", 1), Slice("b", 1))) // 180 each

        assertEquals(0, Insights.tapToSliceIndex(90f, angles))
        assertEquals(1, Insights.tapToSliceIndex(270f, angles))
    }

    @Test fun tapWrapsRatherThanMissing() {
        val angles = Insights.sliceAngles(listOf(Slice("a", 1), Slice("b", 1)))
        assertEquals(0, Insights.tapToSliceIndex(-270f, angles))
        assertEquals(-1, Insights.tapToSliceIndex(90f, emptyList()))
    }
}

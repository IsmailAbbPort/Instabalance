package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Real entries from a real exported ledger, at the real timestamps, run through the real
 * aggregates. Reported as "two incomes I categorised show in the 6 month chart but not the 30 day
 * one", against transactions that are genuinely inside the 30 day window.
 *
 * Kept because the numbers came off a device rather than out of a fixture: if a window ever slips
 * by a day, these fail with the amounts a person would actually recognise.
 */
class IncomeWindowRegressionTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    /** The moment the backup was taken: 19 September 2026. Every window below is relative to it. */
    private val now = Instant.ofEpochMilli(1789829734721L)

    private fun credit(minor: Long, ts: Long, categoryId: String?) =
        Entry(type = EntryType.CREDIT, amountMinor = minor, timestamp = ts,
            source = Source.NOTIFICATION, categoryId = categoryId)

    private val entries = listOf(
        credit(200_000, 1785050696928L, "family"),        // 26 Jul
        credit(2_500_000, 1785245967302L, "salary"),      // 28 Jul
        credit(1_500_000, 1785589631638L, "salary"),      //  1 Aug
        credit(20_000, 1785855408658L, "other_income"),   //  4 Aug
        credit(800_000, 1787500851061L, "freelance"),     // 23 Aug
        credit(2_500_000, 1788067979756L, "salary"),      // 30 Aug
        credit(1_500_000, 1788797358918L, "salary"),      //  7 Sep
        // An expense on the same days, so a direction mix-up cannot pass unnoticed.
        Entry(type = EntryType.DEBIT, amountMinor = 99_999, timestamp = 1788797358918L,
            source = Source.SMS, categoryId = "groceries"),
    )

    private fun incomeIn(fromDays: Long): List<Slice> {
        val today = now.atZone(cairo).toLocalDate()
        val from = today.minusDays(fromDays - 1)
        return Insights.byCategory(
            entries, EntryType.CREDIT, from.atStartOfDay(cairo).toInstant(), now, cairo,
        )
    }

    @Test fun theTwoRecentSalariesAreInsideTheThirtyDayWindow() {
        val slices = incomeIn(30)
        val salary = slices.firstOrNull { it.categoryId == "salary" }

        assertTrue("salary is missing from the 30 day income breakdown entirely", salary != null)
        // 25,000 on 30 August plus 15,000 on 7 September.
        assertEquals(4_000_000L, salary!!.amountMinor)
    }

    @Test fun theOlderSalariesAreCorrectlyOutsideIt() {
        // 28 July and 1 August, which is why the 6 month figure is larger rather than the 30 day
        // one being wrong.
        val slices = incomeIn(30)
        assertEquals(4_000_000L, slices.first { it.categoryId == "salary" }.amountMinor)

        val today = now.atZone(cairo).toLocalDate()
        val sixMonths = Insights.byCategory(
            entries, EntryType.CREDIT,
            YearMonth.from(today).minusMonths(5).atDay(1).atStartOfDay(cairo).toInstant(),
            now, cairo,
        )
        assertEquals(8_000_000L, sixMonths.first { it.categoryId == "salary" }.amountMinor)
    }

    @Test fun freelanceIsAlsoInsideTheThirtyDayWindow() {
        assertEquals(800_000L, incomeIn(30).first { it.categoryId == "freelance" }.amountMinor)
    }

    @Test fun topNDoesNotDropEitherOfThem() {
        // Only two named categories in the window, so nothing should roll up. A roll-up here would
        // be exactly the "my category vanished" symptom.
        val top = Insights.topN(incomeIn(30), 7)

        assertTrue(top.any { it.categoryId == "salary" })
        assertTrue(top.any { it.categoryId == "freelance" })
        assertTrue("nothing should have rolled up", top.none { it.categoryId == Insights.OTHER_ROLLUP })
    }

    @Test fun theExpenseOnTheSameDayIsNotCountedAsIncome() {
        assertTrue(incomeIn(30).none { it.categoryId == "groceries" })
    }

    @Test fun theDailyChartPlacesBothSalariesOnTheirOwnDays() {
        val buckets = Insights.daily(entries, EntryType.CREDIT, 30, now, cairo)

        assertEquals(30, buckets.size)
        assertEquals(2_500_000L, buckets.first { it.fullLabel == "30 Aug 2026" }.amountMinor)
        assertEquals(1_500_000L, buckets.first { it.fullLabel == "7 Sep 2026" }.amountMinor)
    }
}

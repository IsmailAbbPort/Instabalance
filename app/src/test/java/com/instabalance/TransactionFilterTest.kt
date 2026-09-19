package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TransactionFilterTest {

    private val cairo = ZoneId.of("Africa/Cairo")
    private val today = LocalDate.of(2026, 9, 19)

    private fun millis(y: Int, mo: Int, d: Int): Long =
        ZonedDateTime.of(y, mo, d, 12, 0, 0, 0, cairo).toInstant().toEpochMilli()

    private fun e(
        id: String,
        type: EntryType = EntryType.DEBIT,
        amountMinor: Long = 10_000,
        date: LocalDate = today,
        categoryId: String? = null,
        merchant: String? = null,
        note: String = "",
    ) = Entry(
        id = id, type = type, amountMinor = amountMinor,
        timestamp = millis(date.year, date.monthValue, date.dayOfMonth),
        categoryId = categoryId, merchant = merchant, note = note,
    )

    private val categories = Categories.PRESETS

    private fun run(entries: List<Entry>, f: TransactionFilter) =
        TransactionFilters.apply(entries, f, categories, today, cairo)

    private fun ids(entries: List<Entry>) = entries.map { it.id }

    // ---- Anchors -----------------------------------------------------------------------------

    @Test fun anchorsAreHiddenByDefaultBecauseTheyAreNotMoneyMoving() {
        val entries = listOf(e("a"), e("anchor", type = EntryType.ANCHOR, amountMinor = 900_000))

        assertEquals(listOf("a"), ids(run(entries, TransactionFilter())))
    }

    @Test fun anchorsCanBeAskedFor() {
        val entries = listOf(e("a"), e("anchor", type = EntryType.ANCHOR))
        val out = run(entries, TransactionFilter(includeAnchors = true))

        assertEquals(2, out.size)
    }

    @Test fun anAnchorsBalanceNeverLandsInTheTotals() {
        // Summing a balance with spending produces a number that means nothing.
        val entries = listOf(e("a", amountMinor = 5_000), e("anchor", type = EntryType.ANCHOR, amountMinor = 900_000))
        val totals = TransactionFilters.totals(entries)

        assertEquals(5_000L, totals.expenseMinor)
        assertEquals(0L, totals.incomeMinor)
    }

    // ---- Direction ---------------------------------------------------------------------------

    @Test fun directionSplitsIncomeFromExpense() {
        val entries = listOf(e("in", type = EntryType.CREDIT), e("out", type = EntryType.DEBIT))

        assertEquals(listOf("in"), ids(run(entries, TransactionFilter(direction = Direction.INCOME))))
        assertEquals(listOf("out"), ids(run(entries, TransactionFilter(direction = Direction.EXPENSE))))
        assertEquals(2, run(entries, TransactionFilter(direction = Direction.ALL)).size)
    }

    // ---- Category ----------------------------------------------------------------------------

    @Test fun noCategorySelectedMeansEverythingRatherThanNothing() {
        val entries = listOf(e("a", categoryId = "groceries"), e("b", categoryId = null))
        assertEquals(2, run(entries, TransactionFilter(categoryIds = emptySet())).size)
    }

    @Test fun uncategorisedIsSelectableAsItsOwnBucket() {
        // "What have I not sorted yet" is one of the questions this screen exists to answer, and
        // null is how the ledger spells it.
        val entries = listOf(e("filed", categoryId = "groceries"), e("loose", categoryId = null))

        assertEquals(listOf("loose"), ids(run(entries, TransactionFilter(categoryIds = setOf(null)))))
    }

    @Test fun severalCategoriesAreUnionedNotIntersected() {
        val entries = listOf(
            e("a", categoryId = "groceries"),
            e("b", categoryId = "transport"),
            e("c", categoryId = "rent"),
        )
        val out = run(entries, TransactionFilter(categoryIds = setOf("groceries", "transport")))

        assertEquals(setOf("a", "b"), ids(out).toSet())
    }

    // ---- Date ranges -------------------------------------------------------------------------

    @Test fun thisMonthRunsFromTheFirstToToday() {
        val r = TransactionFilters.resolveRange(TransactionFilter(range = DateRange.THIS_MONTH), today)
        assertEquals(LocalDate.of(2026, 9, 1) to today, r)
    }

    @Test fun lastMonthIsTheWholeOfIt() {
        val r = TransactionFilters.resolveRange(TransactionFilter(range = DateRange.LAST_MONTH), today)
        assertEquals(LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31), r)
    }

    @Test fun lastThirtyDaysIncludesTodayAndIsThirtyDaysLong() {
        val r = TransactionFilters.resolveRange(TransactionFilter(range = DateRange.LAST_30), today)!!
        assertEquals(today, r.second)
        assertEquals(LocalDate.of(2026, 8, 21), r.first)
    }

    @Test fun anyTimeDoesNotFilter() {
        assertNull(TransactionFilters.resolveRange(TransactionFilter(range = DateRange.ANY), today))
    }

    @Test fun aRangeIncludesBothOfItsEndDays() {
        // An off-by-one here silently hides the oldest and newest rows, which is the kind of wrong
        // that looks like working software.
        val entries = listOf(
            e("first", date = LocalDate.of(2026, 9, 1)),
            e("last", date = today),
            e("before", date = LocalDate.of(2026, 8, 31)),
        )
        val out = run(entries, TransactionFilter(range = DateRange.THIS_MONTH))

        assertEquals(setOf("first", "last"), ids(out).toSet())
    }

    @Test fun aCustomRangeEnteredBackwardsStillMatchesWhatWasMeant() {
        val f = TransactionFilter(
            range = DateRange.CUSTOM,
            customFrom = LocalDate.of(2026, 9, 30),
            customTo = LocalDate.of(2026, 9, 1),
        )
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30),
            TransactionFilters.resolveRange(f, today))
    }

    @Test fun aHalfFilledCustomRangeIsOpenEndedRatherThanIgnored() {
        val from = TransactionFilter(range = DateRange.CUSTOM, customFrom = LocalDate.of(2026, 9, 1))
        assertEquals(LocalDate.MAX, TransactionFilters.resolveRange(from, today)!!.second)

        val to = TransactionFilter(range = DateRange.CUSTOM, customTo = LocalDate.of(2026, 9, 1))
        assertEquals(LocalDate.MIN, TransactionFilters.resolveRange(to, today)!!.first)
    }

    @Test fun anEmptyCustomRangeDoesNotFilter() {
        assertNull(TransactionFilters.resolveRange(TransactionFilter(range = DateRange.CUSTOM), today))
    }

    // ---- Amount ------------------------------------------------------------------------------

    @Test fun amountBoundsAreInclusive() {
        val entries = listOf(
            e("small", amountMinor = 1_000),
            e("mid", amountMinor = 5_000),
            e("big", amountMinor = 10_000),
        )
        val out = run(entries, TransactionFilter(minMinor = 5_000, maxMinor = 10_000))

        assertEquals(setOf("mid", "big"), ids(out).toSet())
    }

    @Test fun onlyAFloorOrOnlyACeilingIsFine() {
        val entries = listOf(e("small", amountMinor = 1_000), e("big", amountMinor = 10_000))

        assertEquals(listOf("big"), ids(run(entries, TransactionFilter(minMinor = 5_000))))
        assertEquals(listOf("small"), ids(run(entries, TransactionFilter(maxMinor = 5_000))))
    }

    // ---- Search ------------------------------------------------------------------------------

    @Test fun searchCoversMerchantNoteAndCategoryName() {
        val entries = listOf(
            e("m", merchant = "CARREFOUR CAIRO"),
            e("n", note = "birthday present"),
            e("c", categoryId = "groceries"),
            e("none", merchant = "SOMETHING ELSE"),
        )

        assertEquals(listOf("m"), ids(run(entries, TransactionFilter(query = "carrefour"))))
        assertEquals(listOf("n"), ids(run(entries, TransactionFilter(query = "birthday"))))
        // Typing the category name is what a person does when they mean "things I filed there".
        assertEquals(listOf("c"), ids(run(entries, TransactionFilter(query = "grocer"))))
    }

    @Test fun searchIsCaseInsensitiveAndIgnoresSurroundingSpace() {
        val entries = listOf(e("m", merchant = "CARREFOUR"))
        assertEquals(1, run(entries, TransactionFilter(query = "  carreFOUR ")).size)
    }

    @Test fun searchDoesNotReachIntoTheRawMessageBody() {
        // It holds account digits and balances. Matching on them surfaces rows for reasons the
        // user cannot see on screen, which reads as a bug.
        val entries = listOf(
            Entry(id = "r", type = EntryType.DEBIT, amountMinor = 100, timestamp = millis(2026, 9, 19),
                rawText = "acct 1234 avail bal 5000 SECRETWORD"),
        )
        assertTrue(run(entries, TransactionFilter(query = "SECRETWORD")).isEmpty())
    }

    // ---- Sorting -----------------------------------------------------------------------------

    @Test fun sortsByDateBothWays() {
        val entries = listOf(
            e("old", date = LocalDate.of(2026, 9, 1)),
            e("new", date = LocalDate.of(2026, 9, 18)),
        )

        assertEquals(listOf("new", "old"), ids(run(entries, TransactionFilter(sortBy = SortBy.NEWEST))))
        assertEquals(listOf("old", "new"), ids(run(entries, TransactionFilter(sortBy = SortBy.OLDEST))))
    }

    @Test fun sortsByAmountBothWays() {
        val entries = listOf(e("small", amountMinor = 100), e("big", amountMinor = 900))

        assertEquals(listOf("big", "small"), ids(run(entries, TransactionFilter(sortBy = SortBy.LARGEST))))
        assertEquals(listOf("small", "big"), ids(run(entries, TransactionFilter(sortBy = SortBy.SMALLEST))))
    }

    // ---- Totals ------------------------------------------------------------------------------

    @Test fun totalsDescribeWhatTheFiltersMatchedNotTheWholeLedger() {
        val entries = listOf(
            e("a", type = EntryType.DEBIT, amountMinor = 3_000, categoryId = "groceries"),
            e("b", type = EntryType.DEBIT, amountMinor = 2_000, categoryId = "groceries"),
            e("c", type = EntryType.DEBIT, amountMinor = 9_000, categoryId = "rent"),
            e("d", type = EntryType.CREDIT, amountMinor = 7_000, categoryId = "salary"),
        )

        val totals = TransactionFilters.totals(run(entries, TransactionFilter(categoryIds = setOf("groceries"))))

        assertEquals(2, totals.count)
        assertEquals(5_000L, totals.expenseMinor)
        assertEquals(0L, totals.incomeMinor)
    }

    @Test fun netIsIncomeMinusExpense() {
        val entries = listOf(
            e("in", type = EntryType.CREDIT, amountMinor = 10_000),
            e("out", type = EntryType.DEBIT, amountMinor = 4_000),
        )
        assertEquals(6_000L, TransactionFilters.totals(run(entries, TransactionFilter())).netMinor)
    }

    // ---- isActive ----------------------------------------------------------------------------

    @Test fun anUntouchedFilterIsNotActive() {
        assertFalse(TransactionFilter().isActive)
    }

    @Test fun changingOnlyTheSortOrderIsNotFiltering() {
        // Sorting narrows nothing, so offering "Clear filters" for it would be a lie.
        assertFalse(TransactionFilter(sortBy = SortBy.LARGEST).isActive)
    }

    @Test fun anyRealNarrowingIsActive() {
        assertTrue(TransactionFilter(direction = Direction.INCOME).isActive)
        assertTrue(TransactionFilter(categoryIds = setOf("groceries")).isActive)
        assertTrue(TransactionFilter(range = DateRange.THIS_MONTH).isActive)
        assertTrue(TransactionFilter(minMinor = 1).isActive)
        assertTrue(TransactionFilter(query = "x").isActive)
        assertTrue(TransactionFilter(includeAnchors = true).isActive)
    }

    // ---- Combination -------------------------------------------------------------------------

    @Test fun everyFilterAppliesTogether() {
        val entries = listOf(
            e("hit", amountMinor = 5_000, date = LocalDate.of(2026, 9, 10),
                categoryId = "groceries", merchant = "CARREFOUR"),
            e("wrongCategory", amountMinor = 5_000, date = LocalDate.of(2026, 9, 10),
                categoryId = "rent", merchant = "CARREFOUR"),
            e("tooSmall", amountMinor = 100, date = LocalDate.of(2026, 9, 10),
                categoryId = "groceries", merchant = "CARREFOUR"),
            e("tooOld", amountMinor = 5_000, date = LocalDate.of(2026, 7, 10),
                categoryId = "groceries", merchant = "CARREFOUR"),
            e("wrongName", amountMinor = 5_000, date = LocalDate.of(2026, 9, 10),
                categoryId = "groceries", merchant = "SEOUDI"),
        )

        val out = run(entries, TransactionFilter(
            direction = Direction.EXPENSE,
            categoryIds = setOf("groceries"),
            range = DateRange.THIS_MONTH,
            minMinor = 1_000,
            query = "carrefour",
        ))

        assertEquals(listOf("hit"), ids(out))
    }
}

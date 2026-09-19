package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The generator only ever runs in a debug build, but it feeds every screen during review, so data
 * that breaks an invariant would send me chasing a UI bug that is really a fixture bug.
 */
class SampleDataTest {

    private val now = Instant.parse("2026-09-18T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Africa/Cairo")
    private val entries = SampleData.generate(now)

    @Test fun isDeterministicForASeed() {
        assertEquals(SampleData.generate(now, seed = 3), SampleData.generate(now, seed = 3))
    }

    @Test fun producesEnoughToFillTheCharts() {
        assertTrue("only ${entries.size} entries", entries.size > 100)
    }

    @Test fun nothingIsDatedInTheFuture() {
        // A future entry would land outside every window and quietly vanish from the charts.
        assertTrue(entries.all { it.timestamp <= now })
    }

    @Test fun isSortedByTimestamp() {
        assertEquals(entries.sortedBy { it.timestamp }, entries)
    }

    @Test fun hasExactlyOneAnchorAndItComesFirst() {
        val anchors = entries.filter { it.type == EntryType.ANCHOR }
        assertEquals(1, anchors.size)
        assertEquals(entries.first().id, anchors.single().id)
    }

    @Test fun anchorsCarryNoCategory() {
        assertTrue(entries.filter { it.type == EntryType.ANCHOR }.all { it.categoryId == null })
    }

    @Test fun leavesABacklogForTheReviewInbox() {
        // Deliberately unfiled, so the inbox is not empty on first run.
        assertTrue(Insights.uncategorisedCount(entries) > 10)
    }

    @Test fun thisMonthsRingIsNotJustOneGreySlice() {
        // The first render of this fixture was 99 percent Uncategorised, which made the whole
        // chart useless for review. Most of the current month has to be filed for the ring to
        // show anything.
        val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val slices = Insights.byCategory(
            entries, EntryType.DEBIT, from, Instant.ofEpochMilli(now), zone
        )
        val total = slices.sumOf { it.amountMinor }
        val unfiled = slices.firstOrNull { it.categoryId == null }?.amountMinor ?: 0L

        assertTrue("ring has only ${slices.size} slices", slices.size >= 4)
        assertTrue("uncategorised is ${unfiled * 100 / total}% of the ring",
            unfiled * 100 / total < 60)
    }

    @Test fun sendsThatNameNobodyAreNeverFiled() {
        // No rule can ever categorise one, so pretending otherwise would misrepresent the feature.
        assertTrue(
            entries.filter { it.type == EntryType.DEBIT && it.merchant == null && it.source != Source.FEE }
                .all { it.categoryId == null }
        )
    }

    @Test fun includesSendsThatNameNobody() {
        // The structural case the whole review inbox exists for.
        assertTrue(entries.any { it.type == EntryType.DEBIT && it.merchant == null })
    }

    @Test fun includesMerchantsThatCanBeLearned() {
        assertTrue(entries.any { it.merchant?.contains("CARREFOUR") == true })
    }

    @Test fun everyCategoryIdUsedActuallyExists() {
        // A dangling id would render as "Unknown" everywhere and look like a bug in the UI.
        val known = Categories.PRESETS.map { it.id }.toSet()
        val used = entries.mapNotNull { it.categoryId }.toSet()
        assertTrue("unknown ids: ${used - known}", (used - known).isEmpty())
    }

    @Test fun feesAreFiledUnderFees() {
        val fees = entries.filter { it.source == Source.FEE }
        assertTrue(fees.isNotEmpty())
        assertTrue(fees.all { it.categoryId == Categories.FEES })
    }

    @Test fun everyAmountIsPositive() {
        assertTrue(entries.all { it.amountMinor > 0 })
    }

    @Test fun hasBothDirections() {
        assertTrue(entries.any { it.type == EntryType.CREDIT })
        assertTrue(entries.any { it.type == EntryType.DEBIT })
    }

    @Test fun spansEnoughMonthsForTheSixMonthChart() {
        val buckets = Insights.monthly(entries, EntryType.DEBIT, 6, Instant.ofEpochMilli(now), zone)
        assertTrue("only ${buckets.count { it.amountMinor > 0 }} months have spending",
            buckets.count { it.amountMinor > 0 } >= 3)
    }

    @Test fun thisMonthHasSpendingSoTheBudgetCardIsNotEmpty() {
        assertTrue(Insights.spentInMonth(entries, Instant.ofEpochMilli(now), zone, emptySet()) > 0)
    }

    @Test fun everyRuleTargetsARealCategory() {
        val known = Categories.PRESETS.map { it.id }.toSet()
        assertTrue(SampleData.rules(now).all { it.categoryId in known })
    }

    @Test fun rulePatternsAreAlreadyNormalised() {
        // Stored patterns are matched verbatim against a normalised merchant, so an unnormalised
        // one would silently never match.
        SampleData.rules(now).forEach {
            assertEquals(it.pattern, MerchantRules.normalise(it.pattern))
        }
    }
}

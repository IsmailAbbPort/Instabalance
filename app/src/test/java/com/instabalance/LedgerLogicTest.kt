package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerLogicTest {

    private fun entry(
        type: EntryType, minor: Long, ts: Long,
        source: Source = Source.MANUAL,
    ) = Entry(type = type, amountMinor = minor, timestamp = ts, source = source)

    // ---- Fee formula: 0.1%, min 0.50, cap 20.00 ----

    private val cfg = LedgerData()

    @Test fun feeBelowMinimum_usesMinimum() {
        // 0.1% of 1.00 EGP is well under 0.50 -> min applies.
        assertEquals(50L, cfg.instapaySendFeeMinor(100))
    }

    @Test fun feeAtMinimumCrossover() {
        // 0.1% of 500 EGP = exactly 0.50.
        assertEquals(50L, cfg.instapaySendFeeMinor(50_000))
    }

    @Test fun feePercentInMidRange() {
        // 0.1% of 1000 EGP = 1.00.
        assertEquals(100L, cfg.instapaySendFeeMinor(100_000))
    }

    @Test fun feeAtCap() {
        // 0.1% of 20000 EGP = 20.00 (the cap).
        assertEquals(2000L, cfg.instapaySendFeeMinor(2_000_000))
    }

    @Test fun feeAboveCap_isCapped() {
        // 0.1% of 50000 EGP = 50.00, capped to 20.00.
        assertEquals(2000L, cfg.instapaySendFeeMinor(5_000_000))
    }

    // ---- Balance math: anchor + signed deltas after it ----

    @Test fun emptyLedgerIsZero() {
        assertEquals(0L, LedgerRepository.balanceMinor(LedgerData()))
    }

    @Test fun sumsDeltasWithoutAnchor() {
        val d = LedgerData(entries = listOf(
            entry(EntryType.CREDIT, 10000, 1000),
            entry(EntryType.DEBIT, 3000, 2000),
        ))
        assertEquals(7000L, LedgerRepository.balanceMinor(d))
    }

    @Test fun anchorPlusLaterDeltas() {
        val d = LedgerData(entries = listOf(
            entry(EntryType.ANCHOR, 100000, 100),
            entry(EntryType.CREDIT, 5000, 200),
            entry(EntryType.DEBIT, 2000, 300),
        ))
        assertEquals(103000L, LedgerRepository.balanceMinor(d))
    }

    @Test fun deltasBeforeAnchorAreIgnored() {
        val d = LedgerData(entries = listOf(
            entry(EntryType.DEBIT, 9999, 50),      // before anchor -> ignored
            entry(EntryType.ANCHOR, 100000, 100),
            entry(EntryType.CREDIT, 5000, 200),
        ))
        assertEquals(105000L, LedgerRepository.balanceMinor(d))
    }

    @Test fun latestAnchorWins() {
        val d = LedgerData(entries = listOf(
            entry(EntryType.ANCHOR, 100000, 100),
            entry(EntryType.CREDIT, 100000, 200),  // between anchors -> ignored by later anchor
            entry(EntryType.ANCHOR, 200000, 300),
        ))
        assertEquals(200000L, LedgerRepository.balanceMinor(d))
    }

    // ---- Dedup rule: same direction+amount from an auto channel within 20 min ----

    private val existing = listOf(
        entry(EntryType.CREDIT, 10000, 1_000_000, Source.NOTIFICATION),
    )

    @Test fun duplicateWithinWindow() {
        val within = 1_000_000 + 10 * 60 * 1000L // +10 min
        assertTrue(LedgerRepository.isDuplicateAuto(existing, EntryType.CREDIT, 10000, within))
    }

    @Test fun notDuplicateOutsideWindow() {
        val outside = 1_000_000 + 21 * 60 * 1000L // +21 min
        assertFalse(LedgerRepository.isDuplicateAuto(existing, EntryType.CREDIT, 10000, outside))
    }

    @Test fun notDuplicateDifferentAmountOrDirection() {
        assertFalse(LedgerRepository.isDuplicateAuto(existing, EntryType.CREDIT, 9999, 1_000_000))
        assertFalse(LedgerRepository.isDuplicateAuto(existing, EntryType.DEBIT, 10000, 1_000_000))
    }

    @Test fun manualAndFeeEntriesDoNotCountAsDuplicates() {
        val manual = listOf(
            entry(EntryType.CREDIT, 10000, 1_000_000, Source.MANUAL),
            entry(EntryType.DEBIT, 50, 1_000_000, Source.FEE),
        )
        assertFalse(LedgerRepository.isDuplicateAuto(manual, EntryType.CREDIT, 10000, 1_000_000))
        assertFalse(LedgerRepository.isDuplicateAuto(manual, EntryType.DEBIT, 50, 1_000_000))
    }
}

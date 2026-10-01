package com.instabalance

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setting the balance has always had both numbers in hand, the ledger's and the one you are typing
 * in from the real app, and has always thrown the comparison away. The gap between them is money
 * that moved without a message the app ever saw, which is the one measure of whether the capture is
 * working at all.
 */
class AnchorDeltaTest {

    private val now = 1_790_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test fun beforeYouTypeItJustSaysWhatTheLedgerThinks() {
        val text = anchorDeltaText(null, 7_083_818, now - day, now)

        assertTrue(text, text.contains("70,838.18"))
    }

    @Test fun amatchIsSaidPlainly() {
        val text = anchorDeltaText(7_083_818, 7_083_818, now - day, now)

        assertTrue(text, text.contains("matches exactly"))
    }

    @Test fun lessThanRecordedMeansSomethingLeftUnseen() {
        val text = anchorDeltaText(7_080_000, 7_083_818, now - day, now)

        assertTrue(text, text.contains("38.18"))
        assertTrue(text, text.contains("less"))
    }

    @Test fun moreThanRecordedIsNamedTheOtherWay() {
        val text = anchorDeltaText(7_090_000, 7_083_818, now - day, now)

        assertTrue(text, text.contains("61.82"))
        assertTrue(text, text.contains("more"))
    }

    @Test fun thegapIsNeverShownAsNegative() {
        // "-38.18 EGP less" reads as a double negative. The direction is in the words.
        val text = anchorDeltaText(7_080_000, 7_083_818, now - day, now)

        assertTrue(text, !text.contains("-38.18"))
    }

    // ---- the period the gap covers ------------------------------------------

    @Test fun itSaysHowLongTheGapHadToOpenUp() {
        val text = anchorDeltaText(7_080_000, 7_083_818, now - 14 * day, now)

        assertTrue(text, text.contains("14 days"))
    }

    @Test fun oneDayIsNotWrittenAsOneDays() {
        val text = anchorDeltaText(7_080_000, 7_083_818, now - day, now)

        assertTrue(text, text.contains("in the last day"))
    }

    @Test fun asyncEarlierTodaySaysToday() {
        val text = anchorDeltaText(7_080_000, 7_083_818, now - 60_000, now)

        assertTrue(text, text.contains("today"))
    }

    @Test fun neverSyncedNamesNoPeriodRatherThanAmadeUpOne() {
        val text = anchorDeltaText(7_080_000, 7_083_818, null, now)

        assertTrue(text, text.contains("38.18"))
        assertTrue(text, !text.contains("days"))
    }
}

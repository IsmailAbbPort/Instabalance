package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Delete used to say "This cannot be undone" on a ledger whose whole point is being an accurate
 * record of money, while filing something in the inbox has always been one tap to take back.
 */
class UndoDeleteTest {

    private fun entry(type: EntryType, minor: Long) =
        Entry(type = type, amountMinor = minor, timestamp = 1)

    @Test fun thebarNamesTheAmountThatJustLeftTheBalance() {
        assertEquals("Deleted 198.00 EGP", deletedMessage(entry(EntryType.DEBIT, 19_800)))
    }

    @Test fun moneyInReadsTheSame() {
        // The sign is not the point here; what is gone is.
        assertEquals("Deleted 719.00 EGP", deletedMessage(entry(EntryType.CREDIT, 71_900)))
    }

    @Test fun anAnchorHasNoAmountWorthNaming() {
        // An anchor's number is a balance, not a movement, so "Deleted 6,542.73 EGP" would read as
        // money vanishing.
        assertEquals("Re-sync deleted", deletedMessage(entry(EntryType.ANCHOR, 654_273)))
    }
}

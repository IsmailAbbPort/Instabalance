package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MigrationTest {

    private fun entry(
        id: String, type: EntryType, source: Source = Source.MANUAL, categoryId: String? = null,
    ) = Entry(id = id, type = type, amountMinor = 100, timestamp = 1, source = source,
        categoryId = categoryId)

    @Test fun feeEntriesAreFiledUnderFees() {
        val data = LedgerData(entries = listOf(entry("f", EntryType.DEBIT, Source.FEE)))

        val out = Migration.apply(data)

        assertNotNull(out)
        assertEquals(Categories.FEES, out!!.entries.single().categoryId)
    }

    @Test fun anAlreadyCategorisedFeeIsLeftAlone() {
        // If the user deliberately filed a fee elsewhere, the migration must not undo that.
        val data = LedgerData(
            entries = listOf(entry("f", EntryType.DEBIT, Source.FEE, categoryId = "bills"))
        )

        assertNull(Migration.apply(data))
    }

    @Test fun manualAndAnchorEntriesAreUntouched() {
        val data = LedgerData(entries = listOf(
            entry("m", EntryType.DEBIT, Source.MANUAL),
            entry("a", EntryType.ANCHOR, Source.MANUAL),
        ))

        assertNull(Migration.apply(data))
    }

    @Test fun missingPresetsAreAppended() {
        val data = LedgerData(categories = Categories.PRESETS.filterNot { it.id == "rent" })

        val out = Migration.apply(data)

        assertNotNull(out)
        assertEquals(Categories.PRESETS.size, out!!.categories.size)
    }

    @Test fun isIdempotent() {
        // Runs on every launch, so a second pass must report nothing to do and avoid a rewrite.
        val data = LedgerData(entries = listOf(entry("f", EntryType.DEBIT, Source.FEE)))

        val once = Migration.apply(data)!!

        assertNull(Migration.apply(once))
    }

    @Test fun anUpToDateLedgerIsNotRewritten() {
        // Returning null is what stops a pointless encrypt-and-write on every cold start.
        assertNull(Migration.apply(LedgerData()))
    }
}

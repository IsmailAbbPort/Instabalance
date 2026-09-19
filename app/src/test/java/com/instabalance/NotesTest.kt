package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NotesTest {

    private val cairo = ZoneId.of("Africa/Cairo")
    private val today = LocalDate.of(2026, 9, 19)
    private val ts = ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, cairo).toInstant().toEpochMilli()

    private fun e(id: String, note: String = "", type: EntryType = EntryType.DEBIT) =
        Entry(id = id, type = type, amountMinor = 10_000, timestamp = ts, note = note)

    @Test fun writesTheNoteOnlyOnTheEntryAddressed() {
        val entries = listOf(e("a"), e("b", note = "keep me"))

        val out = applyNote(entries, "a", "bought a lamp")

        assertEquals("bought a lamp", out.first { it.id == "a" }.note)
        assertEquals("keep me", out.first { it.id == "b" }.note)
    }

    @Test fun trimsSoAStraySpaceDoesNotCountAsANote() {
        // The row shows the note only when it is non-blank, and " " would print an empty line.
        assertEquals("", applyNote(listOf(e("a")), "a", "   ").first().note)
        assertEquals("taxi", applyNote(listOf(e("a")), "a", "  taxi  ").first().note)
    }

    @Test fun clearingANoteIsAllowed() {
        val entries = listOf(e("a", note = "old"))
        assertEquals("", applyNote(entries, "a", "").first().note)
    }

    @Test fun anUnknownIdChangesNothing() {
        val entries = listOf(e("a", note = "mine"))
        assertEquals(entries, applyNote(entries, "nope", "x"))
    }

    @Test fun anAnchorCanCarryANoteUnlikeACategory() {
        // "Checked the real app after the ATM" is worth writing down on a re-sync.
        val anchor = e("a", type = EntryType.ANCHOR)
        assertEquals("after the ATM", applyNote(listOf(anchor), "a", "after the ATM").first().note)
    }

    @Test fun aNoteIsFindableFromTheTransactionsSearch() {
        // The point of being able to write one: finding it again later.
        val entries = listOf(
            e("a", note = "gift for Laila"),
            e("b", note = "petrol"),
        )
        val found = TransactionFilters.apply(
            entries, TransactionFilter(query = "laila"), Categories.PRESETS, today, cairo,
        )

        assertEquals(listOf("a"), found.map { it.id })
    }

    @Test fun aNoteWrittenNowIsFoundByTheSearchImmediately() {
        val entries = applyNote(listOf(e("a")), "a", "dentist deposit")
        val found = TransactionFilters.apply(
            entries, TransactionFilter(query = "dentist"), Categories.PRESETS, today, cairo,
        )

        assertTrue(found.isNotEmpty())
    }
}

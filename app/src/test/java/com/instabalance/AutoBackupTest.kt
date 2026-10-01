package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The backup that writes itself.
 *
 * The investigation that produced this feature took an hour of reconciliation because the only
 * copies of the ledger were three ad-hoc exports somebody happened to have made. This is the
 * machinery that means there is always a recent one.
 */
class AutoBackupTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    private fun at(y: Int, m: Int, d: Int, h: Int = 2): Long =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, cairo).toInstant().toEpochMilli()

    // ---- when one is owed ---------------------------------------------------

    @Test fun offIsNeverDue() {
        assertNull(AutoBackup.dueAt(at(2026, 10, 1), BackupFrequency.OFF, cairo))
        assertFalse(AutoBackup.isDue(at(2026, 1, 1), BackupFrequency.OFF, at(2030, 1, 1), cairo))
    }

    @Test fun turningItOnBacksUpStraightAway() {
        // Owed immediately rather than in a month, or switching it on appears to do nothing at all
        // and the first backup you actually have is a month after you asked for one.
        assertEquals(0L, AutoBackup.dueAt(0L, BackupFrequency.MONTHLY, cairo))
        assertTrue(AutoBackup.isDue(0L, BackupFrequency.MONTHLY, at(2026, 10, 1), cairo))
    }

    @Test fun dailyIsOwedTheNextDay() {
        val last = at(2026, 10, 1)
        assertEquals(at(2026, 10, 2), AutoBackup.dueAt(last, BackupFrequency.DAILY, cairo))
        assertFalse(AutoBackup.isDue(last, BackupFrequency.DAILY, at(2026, 10, 1, 23), cairo))
        assertTrue(AutoBackup.isDue(last, BackupFrequency.DAILY, at(2026, 10, 2), cairo))
    }

    @Test fun weeklyIsOwedAweekLater() {
        assertEquals(
            at(2026, 10, 8),
            AutoBackup.dueAt(at(2026, 10, 1), BackupFrequency.WEEKLY, cairo),
        )
    }

    @Test fun monthlyCountsMonthsNotThirtyDays() {
        assertEquals(
            at(2026, 11, 1),
            AutoBackup.dueAt(at(2026, 10, 1), BackupFrequency.MONTHLY, cairo),
        )
    }

    @Test fun amonthlyBackupOnTheThirtyFirstDoesNotWalkBackwards() {
        // Thirty days from 31 Jan is 2 March, so "every 30 days" drifts a monthly backup earlier
        // and earlier through the calendar. January the 31st should be February the 28th.
        assertEquals(
            at(2026, 2, 28),
            AutoBackup.dueAt(at(2026, 1, 31), BackupFrequency.MONTHLY, cairo),
        )
        assertEquals(
            at(2026, 3, 28),
            AutoBackup.dueAt(at(2026, 2, 28), BackupFrequency.MONTHLY, cairo),
        )
    }

    @Test fun aphoneThatWasOffForAmonthIsStillOwedOne() {
        // The catch-up case: the alarm never fired, so lastRunAt is ancient and it is simply due.
        assertTrue(AutoBackup.isDue(at(2026, 1, 1), BackupFrequency.MONTHLY, at(2026, 10, 1), cairo))
    }

    // ---- naming -------------------------------------------------------------

    @Test fun namesSortByDateBecauseTheStampRunsWidestUnitFirst() {
        val names = listOf(
            AutoBackup.fileName(at(2026, 12, 1), cairo),
            AutoBackup.fileName(at(2026, 2, 1), cairo),
            AutoBackup.fileName(at(2027, 1, 1), cairo),
        )
        assertEquals(names.sortedBy { it }, names.sorted())
        assertEquals(
            listOf(
                AutoBackup.fileName(at(2026, 2, 1), cairo),
                AutoBackup.fileName(at(2026, 12, 1), cairo),
                AutoBackup.fileName(at(2027, 1, 1), cairo),
            ),
            names.sorted(),
        )
    }

    @Test fun thenameSaysItWasAutomatic() {
        assertTrue(AutoBackup.fileName(at(2026, 10, 1), cairo).startsWith(AutoBackup.PREFIX))
        assertTrue(AutoBackup.fileName(at(2026, 10, 1), cairo).endsWith(".json"))
    }

    // ---- what gets deleted --------------------------------------------------

    private fun names(vararg days: Int) = days.map { AutoBackup.fileName(at(2026, 10, it), cairo) }

    @Test fun nothingIsDeletedWhileUnderTheLimit() {
        assertTrue(AutoBackup.toDelete(names(1, 2, 3), keep = 12).isEmpty())
    }

    @Test fun theOldestGoFirst() {
        val all = names(1, 2, 3, 4, 5)

        val doomed = AutoBackup.toDelete(all, keep = 3)

        assertEquals(names(1, 2), doomed)
    }

    @Test fun keepEverythingDeletesNothingHowManyThereAre() {
        val all = names(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        assertTrue(AutoBackup.toDelete(all, AutoBackup.KEEP_EVERYTHING).isEmpty())
    }

    /**
     * The folder is the phone's Downloads. Deleting anything that is not ours would mean a setting
     * about backups quietly removing somebody's documents.
     */
    @Test fun onlyOurOwnFilesAreEverDeleted() {
        val mixed = names(1, 2, 3, 4) + listOf(
            "instabalance-2026-09-19-1755.json",   // exported by hand, not ours
            "bank-statement.pdf",
            "holiday.jpg",
        )

        val doomed = AutoBackup.toDelete(mixed, keep = 1)

        assertEquals(names(1, 2, 3), doomed)
    }

    @Test fun amanualExportIsNeverCountedAsOneOfOurs() {
        val manual = listOf("instabalance-2026-09-19-1755.json")

        assertTrue(AutoBackup.ours(manual).isEmpty())
        assertTrue(AutoBackup.toDelete(manual, keep = 0).isEmpty())
    }

    // ---- skip when nothing changed ------------------------------------------

    @Test fun anunchangedLedgerFingerprintsTheSame() {
        val d = LedgerData(entries = listOf(
            Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)
        ))

        assertEquals(AutoBackup.fingerprint(d), AutoBackup.fingerprint(d.copy()))
    }

    @Test fun anewTransactionChangesTheFingerprint() {
        val one = LedgerData(entries = listOf(
            Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)
        ))
        val two = one.copy(entries = one.entries + Entry(
            id = "b", type = EntryType.CREDIT, amountMinor = 900, timestamp = 2L,
        ))

        assertNotEquals(AutoBackup.fingerprint(one), AutoBackup.fingerprint(two))
    }

    @Test fun filingSomethingChangesTheFingerprint() {
        // Categorising changes nothing about the money, and is exactly the kind of edit that would
        // otherwise be skipped and then thrown away by the retention window.
        val one = LedgerData(entries = listOf(
            Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)
        ))
        val two = one.copy(entries = listOf(one.entries[0].copy(categoryId = "groceries")))

        assertNotEquals(AutoBackup.fingerprint(one), AutoBackup.fingerprint(two))
    }

    @Test fun theOrderEntriesHappenToBeStoredInDoesNotMatter() {
        val a = Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)
        val b = Entry(id = "b", type = EntryType.CREDIT, amountMinor = 900, timestamp = 2L)

        assertEquals(
            AutoBackup.fingerprint(LedgerData(entries = listOf(a, b))),
            AutoBackup.fingerprint(LedgerData(entries = listOf(b, a))),
        )
    }

    // ---- defaults -----------------------------------------------------------

    @Test fun afreshLedgerBacksUpNothingUntilAsked() {
        // Everything this writes lands in a public folder, so it stays off until a passphrase makes
        // it safe to write at all.
        assertEquals(BackupFrequency.OFF, LedgerData().autoBackupFrequency)
        assertEquals(12, LedgerData().autoBackupKeep)
    }
}

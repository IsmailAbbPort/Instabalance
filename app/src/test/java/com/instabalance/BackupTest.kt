package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    private fun cairoMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, cairo).toInstant().toEpochMilli()

    private val march = cairoMillis(2026, 3, 12, 9, 0)
    private val september = cairoMillis(2026, 9, 19, 14, 30)

    private fun entry(id: String, type: EntryType, amountMinor: Long, timestamp: Long) =
        Entry(id = id, type = type, amountMinor = amountMinor, timestamp = timestamp)

    private fun populated() = LedgerData(
        entries = listOf(
            entry("a", EntryType.ANCHOR, 500_00, september - 1000),
            entry("b", EntryType.DEBIT, 120_00, september),
        ),
        merchantRules = listOf(MerchantRule(pattern = "PAYMOB", categoryId = "shopping", createdAt = 1)),
        monthlyBudgetMinor = 1_000_00,
        budgetMonth = "2026-09",
        highestMilestoneFired = 50,
        feePercentBps = 29,
    )

    // ---- Round trip. This is the only path the data has out of the app ----------------------

    @Test fun everythingWorthKeepingSurvivesARoundTrip() {
        val original = populated()

        val text = Backup.encode(original, now = september, appVersion = "1.0")
        val parsed = Backup.decode(text)

        assertTrue(parsed is BackupParse.Ok)
        val restored = (parsed as BackupParse.Ok).file.data
        assertEquals(original.entries, restored.entries)
        assertEquals(original.merchantRules, restored.merchantRules)
        assertEquals(original.categories, restored.categories)
        assertEquals(original.monthlyBudgetMinor, restored.monthlyBudgetMinor)
        assertEquals(original.budgetMonth, restored.budgetMonth)
        assertEquals(original.highestMilestoneFired, restored.highestMilestoneFired)
        assertEquals(original.feePercentBps, restored.feePercentBps)
        assertEquals(original.smsConfig, restored.smsConfig)
    }

    @Test fun theEnvelopeCarriesWhatIsNeededToRecogniseTheFile() {
        val text = Backup.encode(populated(), now = september, appVersion = "1.0")
        val file = (Backup.decode(text) as BackupParse.Ok).file

        assertEquals(Backup.FORMAT, file.format)
        assertEquals(Backup.SCHEMA, file.schema)
        assertEquals(september, file.exportedAt)
        assertEquals("1.0", file.appVersion)
    }

    // ---- What must never travel -------------------------------------------------------------

    @Test fun theExportCarriesNoPasscodeAndNoCapturedMessages() {
        val d = populated().copy(
            passcodeHash = "hash", passcodeSalt = "salt", biometricEnabled = true,
            learningMode = true,
            captures = listOf(DebugCapture(1, "SMS", "EGBANK", "your balance is 500")),
        )

        val out = Backup.forExport(d)

        assertNull(out.passcodeHash)
        assertNull(out.passcodeSalt)
        assertFalse(out.biometricEnabled)
        assertFalse(out.learningMode)
        assertTrue(out.captures.isEmpty())
        // The point of the export still has to be in it.
        assertEquals(d.entries, out.entries)
    }

    @Test fun theRawTextOfCapturedMessagesIsNotInTheEncodedFile() {
        val d = populated().copy(
            passcodeHash = "sensitive-hash",
            captures = listOf(DebugCapture(1, "SMS", "EGBANK", "unmistakable-capture-text")),
        )

        val text = Backup.encode(d, now = september, appVersion = "1.0")

        assertFalse(text.contains("unmistakable-capture-text"))
        assertFalse(text.contains("sensitive-hash"))
    }

    // ---- Importing must not be able to change the lock guarding the data ---------------------

    @Test fun importKeepsThisDevicesOwnPasscode() {
        val current = LedgerData(passcodeHash = "mine", passcodeSalt = "salt", biometricEnabled = true)
        val imported = populated().copy(passcodeHash = "theirs", passcodeSalt = "other", biometricEnabled = false)

        val merged = Backup.forImport(current, imported)

        assertEquals("mine", merged.passcodeHash)
        assertEquals("salt", merged.passcodeSalt)
        assertTrue(merged.biometricEnabled)
        assertEquals(imported.entries, merged.entries)
    }

    @Test fun importingIntoAFreshInstallLeavesItUnlocked() {
        val merged = Backup.forImport(LedgerData(), populated())

        assertNull(merged.passcodeHash)
        assertFalse(merged.hasPasscode)
    }

    // ---- The user picks this file from every document on the phone, so refusing is routine ----

    @Test fun rejectsAFileThatIsNotJsonAtAll() {
        val result = Backup.decode("this is a photo, not a backup")
        assertTrue(result is BackupParse.Failed)
    }

    @Test fun rejectsValidJsonThatIsSomeoneElsesFile() {
        val result = Backup.decode("""{"format":"some-other-app","schema":1}""")
        assertTrue(result is BackupParse.Failed)
    }

    @Test fun rejectsABackupFromANewerBuildRatherThanHalfReadingIt() {
        val text = Backup.encode(populated(), now = september, appVersion = "9.0")
            .replace("\"schema\": ${Backup.SCHEMA}", "\"schema\": ${Backup.SCHEMA + 1}")

        val result = Backup.decode(text)

        assertTrue(result is BackupParse.Failed)
        assertTrue((result as BackupParse.Failed).reason.contains("newer version"))
    }

    @Test fun acceptsAFileMissingFieldsAddedAfterItWasWritten() {
        // Exactly what a backup written by an older build looks like: only the fields that existed.
        val old = """
            {"format":"${Backup.FORMAT}","schema":1,"exportedAt":$september,"appVersion":"0.9",
             "data":{"entries":[{"id":"a","type":"DEBIT","amountMinor":500,"timestamp":$september}]}}
        """.trimIndent()

        val result = Backup.decode(old)

        assertTrue(result is BackupParse.Ok)
        val d = (result as BackupParse.Ok).file.data
        assertEquals(1, d.entries.size)
        // The fields it predates come back as the defaults a current file would carry.
        assertEquals(Categories.PRESETS, d.categories)
        assertNull(d.monthlyBudgetMinor)
    }

    // ---- The summary shown before anything is overwritten ------------------------------------

    @Test fun summaryCountsWhatTheImportWouldReplace() {
        val s = Backup.summarise(populated())

        assertEquals(2, s.entryCount)
        assertEquals(1, s.ruleCount)
        assertEquals(Categories.PRESETS.size, s.categoryCount)
        assertEquals(500_00L - 120_00L, s.balanceMinor)
    }

    @Test fun rangeReadsAsOneDateWhenEverythingHappenedOnOneDay() {
        val d = LedgerData(entries = listOf(entry("a", EntryType.DEBIT, 100, september)))
        assertEquals("19 Sep 2026", Backup.describeRange(Backup.summarise(d), cairo))
    }

    @Test fun rangeSpansTheOldestAndNewestTransaction() {
        val d = LedgerData(entries = listOf(
            entry("a", EntryType.DEBIT, 100, september),
            entry("b", EntryType.DEBIT, 100, march),
        ))
        assertEquals("12 Mar 2026 to 19 Sep 2026", Backup.describeRange(Backup.summarise(d), cairo))
    }

    @Test fun anEmptyBackupHasNoRangeRatherThanAFakeOne() {
        assertNull(Backup.describeRange(Backup.summarise(LedgerData()), cairo))
    }

    // ---- File name ---------------------------------------------------------------------------

    @Test fun theSuggestedNameSortsChronologicallyAndSaysWhatItIs() {
        val name = Backup.suggestedFileName(september, cairo)
        assertEquals("instabalance-2026-09-19-1430.json", name)
        assertNotNull(name)
    }
}

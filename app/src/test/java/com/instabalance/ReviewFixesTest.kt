package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the defects a code review and three security sweeps found in the previous
 * commit. Each of these fails on that commit and passes on this one.
 */
class ReviewFixesTest {

    // ---- a crafted backup could brick the app on every launch ---------------

    /**
     * `dueAt` runs from Application.onCreate. An imported file could set `autoBackupLastRunAt` to
     * Long.MAX_VALUE, and the date arithmetic then threw, so the app crashed before drawing
     * anything, for good, because the value was already on disk. Uninstalling to recover destroys
     * the Keystore key and therefore every transaction.
     */
    @Test fun anabsurdLastRunDoesNotThrow() {
        BackupFrequency.entries.filter { it != BackupFrequency.OFF }.forEach { f ->
            val due = AutoBackup.dueAt(Long.MAX_VALUE, f)
            assertEquals("$f should be owed now rather than throwing", 0L, due)
            assertTrue(AutoBackup.isDue(Long.MAX_VALUE, f, System.currentTimeMillis()))
        }
    }

    @Test fun anegativeLastRunIsTreatedAsNeverRun() {
        assertEquals(0L, AutoBackup.dueAt(Long.MIN_VALUE, BackupFrequency.DAILY))
    }

    /** The schedule belongs to this phone, not to whoever wrote the file. */
    @Test fun animportCannotSetThisPhonesBackupSchedule() {
        val current = LedgerData(
            autoBackupFrequency = BackupFrequency.OFF,
            autoBackupKeep = 12,
            autoBackupLastRunAt = 5_000L,
        )
        val imported = LedgerData(
            autoBackupFrequency = BackupFrequency.DAILY,
            autoBackupKeep = -1,
            autoBackupLastRunAt = Long.MAX_VALUE,
        )

        val merged = Backup.forImport(current, imported)

        assertEquals(BackupFrequency.OFF, merged.autoBackupFrequency)
        assertEquals(12, merged.autoBackupKeep)
        assertEquals(5_000L, merged.autoBackupLastRunAt)
    }

    /**
     * An empty sender list means "read every sender", and a file-supplied smsDefaultsVersion would
     * suppress the migration that repairs it. Which apps and senders get read is this device's
     * policy, not the backup's content.
     */
    @Test fun animportCannotWidenWhatTheAppReads() {
        val current = LedgerData()
        val imported = LedgerData(
            watchedPackages = listOf("com.whatsapp"),
            smsConfig = SmsConfig(senders = emptyList(), creditWords = listOf("a")),
            smsDefaultsVersion = 99,
        )

        val merged = Backup.forImport(current, imported)

        assertEquals(current.watchedPackages, merged.watchedPackages)
        assertEquals(current.smsConfig, merged.smsConfig)
        assertEquals(current.smsDefaultsVersion, merged.smsDefaultsVersion)
        assertTrue(merged.smsConfig.senders.isNotEmpty())
    }

    @Test fun animportStillRestoresWhatAbackupIsFor() {
        val imported = LedgerData(
            entries = listOf(Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)),
            monthlyBudgetMinor = 300_000,
        )

        val merged = Backup.forImport(LedgerData(), imported)

        assertEquals(1, merged.entries.size)
        assertEquals(300_000L, merged.monthlyBudgetMinor)
    }

    // ---- huge numbers were wrapping instead of being refused ----------------

    /**
     * BigDecimal.toLong() narrows silently, so a bank message reading 184,467,440,737,095,526.16
     * was recorded as 10.00 EGP. The same function reads the balance the automatic re-sync pins to.
     */
    @Test fun anumberTooBigForAlongIsRefusedNotWrapped() {
        assertNull(Money.parseToMinor("184467440737095526.16"))
        assertNull(Money.parseToMinor("99999999999999999999"))
        assertNull(Money.parseToMinor("184467440737095516.17"))
    }

    @Test fun ordinaryAmountsStillParse() {
        assertEquals(10_000L, Money.parseToMinor("100"))
        assertEquals(123456L, Money.parseToMinor("1,234.56"))
        assertEquals(92_233_720_368_547_758L, Money.parseToMinor("922337203685477.58"))
    }

    @Test fun ahugeAmountInAbankMessageIsNotReadAsAsmallOne() {
        val r = BalanceParser.parse(
            "[EGBANK] Your account was credited by EGP 184467440737095526.16 IPN REF# 1"
        )

        assertNull("a number we cannot represent must not become a transaction", r)
    }

    // ---- a refused category name could be stored as an id -------------------

    /**
     * addCategory returned "" when it refused a duplicate name. The callers stored that as the
     * category id: non-null, so the entry left the review inbox, while still rendering as
     * "Uncategorised" and never being findable again.
     */
    @Test fun arefusedCategoryIsNullRatherThanAnEmptyId() {
        val entries = listOf(Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L))

        val out = applyCategory(entries, setOf("a"), "")

        assertNull(out.single().categoryId)
    }

    @Test fun ablankCategoryIdIsNeverStored() {
        val entries = listOf(Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L))

        assertNull(applyCategory(entries, setOf("a"), "   ").single().categoryId)
        assertEquals("groceries", applyCategory(entries, setOf("a"), "groceries").single().categoryId)
    }

    // ---- the monthly ladder was not reset by the category pass --------------

    @Test fun anewMonthClearsTheMonthlyLadderToo() {
        assertEquals(0, Budget.carryOverMilestone("2026-08", "2026-09", 120))
        assertEquals(0, Budget.carryOverMilestone("", "2026-09", 100))
    }

    @Test fun thesameMonthKeepsTheMonthlyLadder() {
        assertEquals(120, Budget.carryOverMilestone("2026-09", "2026-09", 120))
    }

    // ---- the migration could create the clash it exists to prevent ----------

    /**
     * Renaming the preset onto a name the user already chose leaves two identical chips in every
     * picker, and the preset's own edit sheet has no name field, so its Done button is dead.
     */
    @Test fun therenameStandsDownWhenTheUserGotThereFirst() {
        val before = LedgerData(
            categories = Categories.PRESETS.map {
                when (it.id) {
                    Categories.OTHER_EXPENSE, Categories.OTHER_INCOME -> it.copy(name = "Other")
                    else -> it
                }
            } + Category("custom_x", "Other expense", CategoryKind.EXPENSE, 3),
        )

        val after = Migration.apply(before)?.categories ?: before.categories

        val names = after.map { it.name.lowercase() }
        assertEquals("no duplicates may be created", names.size, names.distinct().size)
        assertEquals("Other expense", Categories.byId(after, "custom_x")?.name)
    }

    @Test fun therenameStillHappensNormally() {
        val before = LedgerData(
            categories = Categories.PRESETS.map {
                when (it.id) {
                    Categories.OTHER_EXPENSE, Categories.OTHER_INCOME -> it.copy(name = "Other")
                    else -> it
                }
            },
        )

        val after = Migration.apply(before)!!.categories

        assertEquals("Other expense", Categories.byId(after, Categories.OTHER_EXPENSE)?.name)
        assertEquals("Other income", Categories.byId(after, Categories.OTHER_INCOME)?.name)
    }

    @Test fun apresetIsNotAddedOverAnameTheUserAlreadyUses() {
        // A preset added in a later build must not walk into a name somebody chose first.
        val mine = listOf(Category("custom_x", "Groceries", CategoryKind.EXPENSE, 3))

        val out = Categories.ensurePresets(mine)

        val names = out.map { it.name.lowercase() }
        assertEquals(names.size, names.distinct().size)
        assertFalse(out.any { it.id == "groceries" })
    }

    // ---- the backup skipped everything except transactions ------------------

    private val seed = LedgerData(
        entries = listOf(Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)),
    )

    @Test fun curatingRulesCountsAsAchange() {
        val withRule = seed.copy(
            merchantRules = listOf(MerchantRule(pattern = "SEOUDI", categoryId = "groceries", createdAt = 1L))
        )

        assertNotEquals(AutoBackup.fingerprint(seed), AutoBackup.fingerprint(withRule))
    }

    @Test fun renamingAcategoryCountsAsAchange() {
        val renamed = seed.copy(
            categories = Categories.PRESETS.map {
                if (it.id == "groceries") it.copy(name = "Supermarket") else it
            }
        )

        assertNotEquals(AutoBackup.fingerprint(seed), AutoBackup.fingerprint(renamed))
    }

    @Test fun settingAcategoryLimitCountsAsAchange() {
        val limited = seed.copy(
            categories = Categories.PRESETS.map {
                if (it.id == "eating_out") it.copy(budgetMinor = 300_000) else it
            }
        )

        assertNotEquals(AutoBackup.fingerprint(seed), AutoBackup.fingerprint(limited))
    }

    @Test fun fillingInAmerchantCountsAsAchange() {
        val named = seed.copy(entries = listOf(seed.entries[0].copy(merchant = "SEOUDI")))

        assertNotEquals(AutoBackup.fingerprint(seed), AutoBackup.fingerprint(named))
    }

    @Test fun thebackupsOwnBookkeepingIsNotAchange() {
        // Or every ledger would differ from its own last fingerprint and the skip would never fire.
        val after = seed.copy(
            autoBackupLastRunAt = 99_999L,
            autoBackupLastFingerprint = "whatever",
            autoBackupLastResult = "Saved 1 transactions.",
        )

        assertEquals(AutoBackup.fingerprint(seed), AutoBackup.fingerprint(after))
    }

    @Test fun theorderEntriesAreStoredInStillDoesNotMatter() {
        val a = Entry(id = "a", type = EntryType.DEBIT, amountMinor = 500, timestamp = 1L)
        val b = Entry(id = "b", type = EntryType.CREDIT, amountMinor = 900, timestamp = 2L)

        assertEquals(
            AutoBackup.fingerprint(LedgerData(entries = listOf(a, b))),
            AutoBackup.fingerprint(LedgerData(entries = listOf(b, a))),
        )
    }
}

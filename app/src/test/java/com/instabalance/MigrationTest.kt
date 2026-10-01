package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Already seeded, so that nothing but the fee step can be what makes this non-null.
        val data = LedgerData(
            entries = listOf(entry("f", EntryType.DEBIT, Source.FEE, categoryId = "bills")),
            seededRuleVersion = Migration.SEEDED_RULE_VERSION,
            smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
        )

        assertNull(Migration.apply(data))
    }

    @Test fun manualAndAnchorEntriesAreUntouched() {
        val data = LedgerData(
            entries = listOf(
                entry("m", EntryType.DEBIT, Source.MANUAL),
                entry("a", EntryType.ANCHOR, Source.MANUAL),
            ),
            seededRuleVersion = Migration.SEEDED_RULE_VERSION,
            smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
        )

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
        assertNull(Migration.apply(LedgerData(
                seededRuleVersion = Migration.SEEDED_RULE_VERSION,
                smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
            )))
    }

    // ---- shipped merchant rules ---------------------------------------------

    @Test fun shippedRulesAreAddedOnce() {
        val out = Migration.apply(LedgerData(), now = 42L)

        assertNotNull(out)
        val patterns = out!!.merchantRules.map { it.pattern }
        assertTrue("TALABAT" in patterns)
        assertTrue("SEOUDI" in patterns)
        assertTrue("MISR PETROLEUM" in patterns)
        assertTrue("CHILL OUT" in patterns)
        assertTrue("CHILLOUT" in patterns)
        assertEquals(Migration.SEEDED_RULE_VERSION, out.seededRuleVersion)
        assertEquals(listOf(42L), out.merchantRules.map { it.createdAt }.distinct())
    }

    @Test fun aDeletedShippedRuleStaysDeleted() {
        // The whole reason the version is stored. Without it, every launch would put it back and
        // there would be no way to tell the app you do not want it.
        val seeded = Migration.apply(LedgerData())!!
        val afterDelete = seeded.copy(
            merchantRules = seeded.merchantRules.filterNot { it.pattern == "TALABAT" }
        )

        assertNull(Migration.apply(afterDelete))
    }

    @Test fun aRuleTheUserAlreadyWroteIsNotDuplicated() {
        val mine = MerchantRule(pattern = "TALABAT", categoryId = "shopping", createdAt = 1)
        val data = LedgerData(merchantRules = listOf(mine))

        val out = Migration.apply(data)!!

        assertEquals(1, out.merchantRules.count { it.pattern == "TALABAT" })
        // And it keeps filing where the user said, not where the app would have said.
        assertEquals("shopping", out.merchantRules.first { it.pattern == "TALABAT" }.categoryId)
    }

    @Test fun shippedRulesFileIntoCategoriesThatExist() {
        val out = Migration.apply(LedgerData())!!
        out.merchantRules.forEach { rule ->
            assertNotNull(
                "rule ${rule.pattern} points at a category that does not exist",
                Categories.byId(out.categories, rule.categoryId),
            )
        }
    }

    // ---- merchant backfill ---------------------------------------------------

    private val chillOut =
        "تم الشراء بمبلغ 800 جم على الكارت رقم ++ [EGBANK] 0954 منCHILL OUT - GARDINYA SCAIRO N 07"
    private val misrPetroleum =
        "تم الشراء بمبلغ 755 جم على الكارت رقم ++ [EGBANK]\n\n0954 من MISR PETROLEUM со CAIRO"

    private fun captured(id: String, raw: String, merchant: String?) = Entry(
        id = id, type = EntryType.DEBIT, amountMinor = 80000, timestamp = 1,
        source = Source.SMS, rawText = raw, merchant = merchant,
    )

    @Test fun aMerchantTheOldParserMissedIsFilledIn() {
        val data = LedgerData(entries = listOf(captured("a", chillOut, merchant = null)))

        val out = Migration.apply(data)!!

        assertEquals("CHILL OUT - GARDINYA SCAIRO N 07", out.entries.single().merchant)
    }

    @Test fun aTruncatedMerchantIsCompleted() {
        // "MISR" is what the one-token fallback stored. It is a prefix of the real name, so
        // completing it is a repair rather than a rewrite.
        val data = LedgerData(entries = listOf(captured("a", misrPetroleum, merchant = "MISR")))

        val out = Migration.apply(data)!!

        assertEquals("MISR PETROLEUM со CAIRO", out.entries.single().merchant)
    }

    @Test fun aDifferentMerchantIsNeverOverwritten() {
        // Not a prefix, so it is somebody's edit or another parser's answer. Leave it.
        val data = LedgerData(
            entries = listOf(captured("a", misrPetroleum, merchant = "Petrol station")),
            seededRuleVersion = Migration.SEEDED_RULE_VERSION,
            smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
        )

        assertNull(Migration.apply(data))
    }

    @Test fun manualEntriesAreNeverReparsed() {
        // A manual entry's note is not a bank message, and running it through the parser would be
        // reading tea leaves.
        val data = LedgerData(
            entries = listOf(
                Entry(id = "m", type = EntryType.DEBIT, amountMinor = 100, timestamp = 1,
                    source = Source.MANUAL, rawText = chillOut, merchant = null),
            ),
            seededRuleVersion = Migration.SEEDED_RULE_VERSION,
            smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
        )

        assertNull(Migration.apply(data))
    }

    @Test fun theBackfillIsIdempotent() {
        val data = LedgerData(entries = listOf(captured("a", chillOut, merchant = null)))

        val once = Migration.apply(data)!!

        assertNull(Migration.apply(once))
    }

    @Test fun theBackfillNeverTouchesTheMoney() {
        val data = LedgerData(entries = listOf(captured("a", misrPetroleum, merchant = "MISR")))

        val out = Migration.apply(data)!!
        val before = data.entries.single()
        val after = out.entries.single()

        assertEquals(before.amountMinor, after.amountMinor)
        assertEquals(before.type, after.type)
        assertEquals(before.timestamp, after.timestamp)
        assertEquals(before.categoryId, after.categoryId)
    }
}

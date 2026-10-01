package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One InstaPay transfer arrives twice, and the dedupe keeps whichever landed first. That is usually
 * the bank SMS, which says the money came "from **", so the message that actually names the person
 * was thrown away whole. This keeps the name.
 */
class TwinMergeTest {

    private val ts = 1_700_000_000_000L

    private fun kept(
        merchant: String? = null,
        categoryId: String? = null,
        fromRule: Boolean = false,
        source: Source = Source.SMS,
        at: Long = ts,
    ) = Entry(
        id = "kept",
        type = EntryType.CREDIT,
        amountMinor = 107_000,
        timestamp = at,
        source = source,
        merchant = merchant,
        categoryId = categoryId,
        categoryFromRule = fromRule,
    )

    private fun merge(
        entries: List<Entry>,
        merchant: String? = "marrynbe@instapay",
        at: Long = ts + 4_000,
        rules: List<MerchantRule> = emptyList(),
    ) = mergeTwinInto(entries, EntryType.CREDIT, 107_000, at, merchant, rules)

    @Test fun thenotificationsNameLandsOnTheEntryTheBankSmsLeftAnonymous() {
        val out = merge(listOf(kept()))

        assertEquals("marrynbe@instapay", out?.single()?.merchant)
    }

    @Test fun nothingElseAboutTheEntryMoves() {
        val original = kept()

        val out = merge(listOf(original))!!.single()

        assertEquals(original.id, out.id)
        assertEquals(original.amountMinor, out.amountMinor)
        assertEquals(original.timestamp, out.timestamp)
        assertEquals(original.source, out.source)
    }

    @Test fun anameOnTheEntryAlreadyIsNeverOverwritten() {
        // The parser found that one in the message we kept. A second message is no reason to
        // disagree with it.
        assertNull(merge(listOf(kept(merchant = "SOMEONE ELSE"))))
    }

    @Test fun atwinThatNamesNobodyTeachesNothing() {
        assertNull(merge(listOf(kept()), merchant = null))
        assertNull(merge(listOf(kept()), merchant = "   "))
        assertNull(merge(listOf(kept()), merchant = "**"))
    }

    @Test fun thebanksMaskIsTreatedAsTheBlankItIs() {
        // The real message says "from **", and the parser records exactly that, because it cannot
        // know those two asterisks mean "we are not telling you". Without this the merge never
        // fires on a single real transfer: it only ever sees a merchant that is already set.
        val masked = kept(merchant = "**")

        assertEquals("marrynbe@instapay", merge(listOf(masked))?.single()?.merchant)
    }

    @Test fun anythingWithALetterOrDigitCountsAsAname() {
        assertNull(merge(listOf(kept(merchant = "A"))))
        assertNull(merge(listOf(kept(merchant = "**7"))))
    }

    @Test fun nothingToMergeIntoIsNotAcrash() {
        assertNull(merge(emptyList()))
    }

    // ---- the point of knowing the name --------------------------------------

    @Test fun thenameGivesTheRulesTheirFirstChanceAtIt() {
        // Patterns are stored already normalised, which is uppercase.
        val rules = listOf(MerchantRule(pattern = "MARRYNBE", categoryId = "family", createdAt = 1))

        val out = merge(listOf(kept()), rules = rules)!!.single()

        assertEquals("family", out.categoryId)
        assertTrue(out.categoryFromRule)
    }

    @Test fun acategoryChosenByHandSurvivesTheMerge() {
        val rules = listOf(MerchantRule(pattern = "MARRYNBE", categoryId = "family", createdAt = 1))

        val out = merge(listOf(kept(categoryId = "freelance")), rules = rules)!!.single()

        assertEquals("freelance", out.categoryId)
    }

    // ---- it must not reach across transactions ------------------------------

    @Test fun anentryOutsideTheWindowIsAdifferentTransaction() {
        val old = kept(at = ts - CAPTURE_MATCH_WINDOW_MS - 1)

        assertNull(merge(listOf(old)))
    }

    @Test fun exactlyAtTheWindowEdgeStillCounts() {
        val edge = kept(at = ts - CAPTURE_MATCH_WINDOW_MS)

        assertEquals("marrynbe@instapay", merge(listOf(edge), at = ts)?.single()?.merchant)
    }

    @Test fun aotherAmountIsNotTheSameTransfer() {
        val other = kept().copy(amountMinor = 107_001)

        assertNull(merge(listOf(other)))
    }

    @Test fun amanualEntryIsNeverRewrittenByAmessage() {
        // You typed that one in. A notification arriving nearby does not get to edit it.
        assertNull(merge(listOf(kept(source = Source.MANUAL))))
    }

    @Test fun theclosestInTimeWins() {
        val far = kept(at = ts - 60_000).copy(id = "far")
        val near = kept(at = ts + 1_000).copy(id = "near")

        val out = merge(listOf(far, near), at = ts)!!

        assertNull(out.first { it.id == "far" }.merchant)
        assertEquals("marrynbe@instapay", out.first { it.id == "near" }.merchant)
    }
}

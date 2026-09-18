package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantRulesTest {

    private fun rule(pattern: String, categoryId: String, createdAt: Long = 1, id: String = pattern) =
        MerchantRule(id = id, pattern = pattern, categoryId = categoryId, createdAt = createdAt)

    private fun entry(
        id: String, merchant: String?, categoryId: String? = null,
        type: EntryType = EntryType.DEBIT, fromRule: Boolean = false,
    ) = Entry(id = id, type = type, amountMinor = 100, timestamp = 1, merchant = merchant,
        categoryId = categoryId, categoryFromRule = fromRule)

    // ---- normalise ----------------------------------------------------------

    @Test fun normaliseUppercasesAndCollapsesWhitespace() {
        assertEquals("PAYMOB RAF", MerchantRules.normalise("  paymob   raf "))
    }

    @Test fun normaliseKeepsAtAndDotForInstapayAddresses() {
        assertEquals("ISMAILABB@INSTAPAY", MerchantRules.normalise("ismailabb@instapay"))
    }

    @Test fun normaliseConvertsArabicIndicDigits() {
        // A merchant string can carry a branch number in Arabic-Indic digits.
        assertEquals("STORE 123", MerchantRules.normalise("store ١٢٣"))
    }

    // ---- match --------------------------------------------------------------

    @Test fun matchesOnSubstring() {
        val rules = listOf(rule("PAYMOB", "shopping"))
        assertEquals("shopping", MerchantRules.match(rules, "PAYMOB RAF CAIRO")?.categoryId)
    }

    @Test fun longestPatternWins() {
        // The specific rule must beat the general one regardless of list order.
        val rules = listOf(rule("PAYMOB", "shopping"), rule("PAYMOB RAF", "groceries"))
        assertEquals("groceries", MerchantRules.match(rules, "PAYMOB RAF CAIRO")?.categoryId)
    }

    @Test fun equalLengthTiesBreakOnCreatedAtSoOrderNeverDecides() {
        val a = rule("AAAA", "first", createdAt = 1, id = "a")
        val b = rule("AAAA", "second", createdAt = 2, id = "b")
        assertEquals("first", MerchantRules.match(listOf(b, a), "AAAA SHOP")?.categoryId)
    }

    @Test fun noMatchAndNoMerchantBothReturnNull() {
        val rules = listOf(rule("PAYMOB", "shopping"))
        assertNull(MerchantRules.match(rules, "CARREFOUR"))
        assertNull(MerchantRules.match(rules, null))
        assertNull(MerchantRules.match(rules, "   "))
    }

    // ---- proposePattern -----------------------------------------------------

    @Test fun proposesTheBrandTokenFromALongMerchant() {
        assertEquals("PAYMOB", MerchantRules.proposePattern("PAYMOB RAF SPECIALIT CAIRO N 07"))
    }

    @Test fun proposesTwoTokensWhenTheFirstIsTooGeneric() {
        // "NEW" alone would match a third of the ledger.
        assertEquals("NEW RABIA", MerchantRules.proposePattern("New Rabia for Trading Masr Elgedida"))
    }

    @Test fun proposesTwoTokensWhenTheFirstIsTooShort() {
        assertEquals("EL SHOP", MerchantRules.proposePattern("el shop cairo"))
    }

    @Test fun proposesTheWholeAddressForAnInstapayHandle() {
        assertEquals("ISMAILABB@INSTAPAY", MerchantRules.proposePattern("ismailabb@instapay"))
    }

    @Test fun atmProposesItselfEvenThoughItIsShort() {
        // Only three characters, but there is no second token to fall back to, and one rule for
        // every ATM withdrawal is exactly what makes cash usable.
        assertEquals("ATM", MerchantRules.proposePattern("ATM"))
    }

    // ---- categoriseNew ------------------------------------------------------

    @Test fun categorisesAFreshCaptureAndMarksItAsRuleFiled() {
        val out = MerchantRules.categoriseNew(entry("a", "PAYMOB CAIRO"), listOf(rule("PAYMOB", "shopping")))

        assertEquals("shopping", out.categoryId)
        assertTrue(out.categoryFromRule)
    }

    @Test fun neverOverwritesAnExistingCategory() {
        val existing = entry("a", "PAYMOB CAIRO", categoryId = "groceries")
        assertEquals(existing, MerchantRules.categoriseNew(existing, listOf(rule("PAYMOB", "shopping"))))
    }

    @Test fun neverCategorisesAnAnchor() {
        val anchor = entry("a", "PAYMOB", type = EntryType.ANCHOR)
        assertNull(MerchantRules.categoriseNew(anchor, listOf(rule("PAYMOB", "shopping"))).categoryId)
    }

    // ---- applyToUncategorised -----------------------------------------------

    @Test fun retroApplyTouchesOnlyUncategorisedMatches() {
        val entries = listOf(
            entry("a", "PAYMOB CAIRO"),
            entry("b", "PAYMOB GIZA", categoryId = "groceries"), // hand-filed, must survive
            entry("c", "CARREFOUR"),
            entry("d", "PAYMOB", type = EntryType.ANCHOR),
        )

        val (out, count) = MerchantRules.applyToUncategorised(entries, rule("PAYMOB", "shopping"))

        assertEquals(1, count)
        assertEquals("shopping", out.first { it.id == "a" }.categoryId)
        assertEquals("groceries", out.first { it.id == "b" }.categoryId)
        assertNull(out.first { it.id == "c" }.categoryId)
        assertNull(out.first { it.id == "d" }.categoryId)
    }

    @Test fun retroApplyPreservesListLength() {
        val entries = listOf(entry("a", "PAYMOB"), entry("b", null))
        val (out, _) = MerchantRules.applyToUncategorised(entries, rule("PAYMOB", "shopping"))
        assertEquals(entries.size, out.size)
    }

    @Test fun ruleOwnedCountIgnoresHandFiledEntries() {
        val r = rule("PAYMOB", "shopping")
        val entries = listOf(
            entry("a", "PAYMOB", categoryId = "shopping", fromRule = true),
            entry("b", "PAYMOB", categoryId = "shopping", fromRule = false),
        )

        assertEquals(1, MerchantRules.ruleOwnedCount(entries, r))
        assertFalse(entries[1].categoryFromRule)
    }
}

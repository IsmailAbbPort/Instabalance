package com.instabalance

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bank prints "UBER TRIP HELP.UBER.COM" and "PAYMOB RAF SPECIALIT CAIRO N 07". A rule already
 * knows which shop that is, so it is the natural place to say what to call it.
 */
class RuleLabelTest {

    private val rules = listOf(
        MerchantRule(pattern = "UBER", categoryId = "transport", createdAt = 1, label = "Uber"),
        MerchantRule(pattern = "SEOUDI", categoryId = "groceries", createdAt = 2),
    )

    private fun entry(merchant: String?) =
        Entry(type = EntryType.DEBIT, amountMinor = 1_000, timestamp = 1, merchant = merchant)

    @Test fun thelabelIsWhatYouSee() {
        assertEquals("Uber", entry("UBER TRIP HELP.UBER.COM").displayCounterparty(rules))
    }

    @Test fun arruleWithNoLabelLeavesTheBanksOwnString() {
        assertEquals("SEOUDI-ROXY CAIRO E 07", entry("SEOUDI-ROXY CAIRO E 07").displayCounterparty(rules))
    }

    @Test fun amerchantNoRuleMatchesIsUntouched() {
        assertEquals("CARREFOUR MAADI", entry("CARREFOUR MAADI").displayCounterparty(rules))
    }

    @Test fun passingNoRulesLeavesTheRawString() {
        // Which is what the pickers want: they show what the message said while you decide.
        assertEquals("UBER TRIP HELP.UBER.COM", entry("UBER TRIP HELP.UBER.COM").displayCounterparty())
    }

    @Test fun ablankLabelIsNotAname() {
        val blank = listOf(MerchantRule(pattern = "UBER", categoryId = "t", createdAt = 1, label = "  "))

        assertEquals("UBER TRIP", entry("UBER TRIP").displayCounterparty(blank))
    }

    @Test fun anentryNamingNobodyStillFallsBackTheWayItDid() {
        assertEquals("InstaPay transfer", entry(null).displayCounterparty(rules))
    }

    @Test fun thelabelChangesNothingAboutWhatTheRuleMatches() {
        // Matching is on the pattern. A display name that also matched would be a second rule
        // nobody wrote, so a merchant named only like the label must not be caught.
        val renamed = listOf(
            MerchantRule(pattern = "PAYMOB", categoryId = "shopping", createdAt = 1, label = "Zebra"),
        )

        assertEquals("shopping", MerchantRules.match(renamed, "PAYMOB RAF CAIRO")?.categoryId)
        assertNull(MerchantRules.match(renamed, "ZEBRA CAFE"))
    }

    // ---- files written before labels existed --------------------------------

    @Test fun aruleFromAnOlderBuildDecodesWithNoLabel() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"id":"r1","pattern":"TALABAT","categoryId":"eating_out","createdAt":5}"""

        val rule = json.decodeFromString<MerchantRule>(old)

        assertEquals("TALABAT", rule.pattern)
        assertNull(rule.label)
    }
}

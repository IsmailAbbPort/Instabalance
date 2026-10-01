package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An advert landing in the ledger as a purchase.
 *
 * The real one, received on the phone: it carries the word خصم, which means "deduction" on a bank
 * statement and "discount" in an ad, and a price next to the currency, which is the parser's
 * fallback shape. Two independent guards let it through: the sender allowlist was empty, so every
 * shortcode in the country was being read, and nothing told the parser that a message selling
 * something is not a statement of fact.
 */
class MarketingSmsTest {

    private val advert = """
        50% خصم! عرض السنجل بوم بـ175ج! بس
        ساندوتش+فرايز+سلو+مشروب والتوصيل10ج فقط
        https://bit.ly/DushkaApp
        15678
    """.trimIndent()

    // ---- the guard that actually fires: the sender -------------------------

    @Test fun afreshInstallReadsTheBankAndNotEverySenderInTheCountry() {
        val config = SmsConfig()

        assertTrue(config.acceptsSender("EGBANK"))
        assertFalse(config.acceptsSender("15678"))
        assertFalse(config.acceptsSender("Dushka"))
    }

    @Test fun readingEverySenderIsStillAchoiceSomebodyCanMake() {
        // Anyone on a bank these defaults do not know needs it, so it stays expressible.
        assertTrue(SmsConfig(senders = emptyList()).acceptsSender("ANYBANK"))
    }

    // ---- the second guard: what the message itself says --------------------

    @Test fun theadvertIsNotAtransactionEvenFromAnAllowedSender() {
        assertNull(BalanceParser.parse(advert))
    }

    @Test fun alinkIsWhatGivesItAway() {
        assertTrue(SmsConfig.DEFAULT_IGNORE.containsAll(SmsConfig.DEFAULT_LINK_WORDS))
        assertNull(BalanceParser.parse("تم شراء بمبلغ 100جم https://example.com"))
        assertNull(BalanceParser.parse("You were charged by EGP 100. www.example.com"))
    }

    /**
     * خصم stays in the debit list. It is the honest word for a deduction, and the ad was caught on
     * the link and the sender, not by taking a real word out of the bank's vocabulary.
     */
    @Test fun thewordItTrippedOverStillWorksInArealMessage() {
        val r = BalanceParser.parse("[EGBANK] تم خصم مبلغ 250جم من حسابك الرصيد المتاح 1000جم")

        assertNotNull(r)
        assertEquals(EntryType.DEBIT, r!!.type)
        assertEquals(25_000L, r.amountMinor)
    }

    @Test fun everyRealMessageShapeStillParses() {
        // The fixtures the parser exists for, run again against the tightened ignore list, because
        // an over-eager junk guard that silently drops a purchase is worse than the advert was.
        listOf(
            "[EGBANK] تم سحب  1000جم من حساب 0057*100 من ATM  الرصيد المتاح  6542.73جم",
            "تم الشراء بمبلغ 165جم على الكارت رقم +++0954 من PAYMOB",
            "Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# 123",
            "Your account was credited by EGP 100 on 22-07 22:51 IPN REF# 9 from NAME",
        ).forEach { assertNotNull(it, BalanceParser.parse(it)) }
    }

    // ---- existing installs inherit both ------------------------------------

    @Test fun anoldLedgerThatReadEverySenderIsNarrowedOnce() {
        val before = LedgerData(smsConfig = SmsConfig(senders = emptyList(), ignoreWords = listOf("otp")))

        val after = Migration.apply(before)!!

        assertEquals(SmsConfig.DEFAULT_SENDERS, after.smsConfig.senders)
        assertTrue(after.smsConfig.ignoreWords.containsAll(SmsConfig.DEFAULT_LINK_WORDS))
        assertTrue(after.smsConfig.ignoreWords.contains("otp"))
        assertEquals(Migration.SMS_DEFAULTS_VERSION, after.smsDefaultsVersion)
    }

    @Test fun asenderListTheUserTypedIsNeverReplaced() {
        val before = LedgerData(smsConfig = SmsConfig(senders = listOf("MYBANK")))

        val after = Migration.apply(before)!!

        assertEquals(listOf("MYBANK"), after.smsConfig.senders)
    }

    @Test fun clearingTheListAfterwardsStays() {
        // The watermark is the whole reason this is a migration and not a line in the loader:
        // without it, "read every sender" would last exactly until the next launch.
        val migrated = Migration.apply(LedgerData())?.let { it } ?: LedgerData()
        val cleared = migrated.copy(
            smsConfig = migrated.smsConfig.copy(senders = emptyList()),
            smsDefaultsVersion = Migration.SMS_DEFAULTS_VERSION,
        )

        val again = Migration.apply(cleared)

        assertTrue(again == null || again.smsConfig.senders.isEmpty())
    }

    @Test fun thelinkWordsAreNotAddedTwice() {
        val once = Migration.apply(LedgerData(smsConfig = SmsConfig(senders = emptyList())))!!
        val twice = Migration.apply(once)

        assertTrue(twice == null || twice.smsConfig.ignoreWords == once.smsConfig.ignoreWords)
    }

    // ---- and the preset rename rides along ---------------------------------

    @Test fun thetwoOtherPresetsAreRenamedOnAnOldLedger() {
        val before = LedgerData(
            categories = Categories.PRESETS.map {
                if (it.id == Categories.OTHER_EXPENSE || it.id == Categories.OTHER_INCOME) {
                    it.copy(name = "Other")
                } else it
            }
        )

        val after = Migration.apply(before)!!

        assertEquals("Other expense", Categories.byId(after.categories, Categories.OTHER_EXPENSE)?.name)
        assertEquals("Other income", Categories.byId(after.categories, Categories.OTHER_INCOME)?.name)
    }

    @Test fun therenameLeavesEveryEntryFiledWhereItWas() {
        val before = LedgerData(
            entries = listOf(
                Entry(
                    type = EntryType.DEBIT,
                    amountMinor = 100,
                    timestamp = 1_700_000_000_000L,
                    categoryId = Categories.OTHER_EXPENSE,
                )
            ),
            categories = Categories.PRESETS.map {
                if (it.id == Categories.OTHER_EXPENSE) it.copy(name = "Other") else it
            },
        )

        val after = Migration.apply(before)!!

        assertEquals(Categories.OTHER_EXPENSE, after.entries.single().categoryId)
    }
}

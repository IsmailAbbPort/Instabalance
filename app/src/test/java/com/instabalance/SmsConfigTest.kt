package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of the configuration is that someone on a bank nobody thought about can make the app
 * work by typing their own words in. These tests are that claim, checked.
 */
class SmsConfigTest {

    // ---- sender allowlist ---------------------------------------------------

    @Test fun anEmptyAllowlistReadsEverySender() {
        assertTrue(SmsConfig().acceptsSender("ANYBANK"))
        assertTrue(SmsConfig().acceptsSender("+201234567890"))
    }

    @Test fun anAllowlistKeepsOthersOut() {
        val c = SmsConfig(senders = listOf("MyBank"))
        assertTrue(c.acceptsSender("MYBANK"))
        assertTrue(c.acceptsSender("MyBank-Alerts"))   // substring, so sub-IDs still match
        assertFalse(c.acceptsSender("SOMEONE ELSE"))
    }

    // ---- a bank nobody planned for -----------------------------------------

    /** Invented wording, deliberately sharing nothing with the Egyptian defaults. */
    private val otherBank = SmsConfig(
        creditWords = listOf("deposited"),
        debitWords = listOf("withdrawn"),
        currencyWords = listOf("USD", "$"),
        balanceLabels = listOf("Remaining funds"),
        amountLabels = listOf("of"),
        merchantLabels = listOf("at"),
    )

    @Test fun readsACreditFromAnUnplannedBank() {
        val r = BalanceParser.parse(
            "Alert: USD 250.00 was deposited to your account. Remaining funds USD 1,900.00",
            otherBank,
        )
        assertNotNull(r)
        assertEquals(EntryType.CREDIT, r!!.type)
        assertEquals(25_000L, r.amountMinor)
    }

    @Test fun readsADebitFromAnUnplannedBank() {
        val r = BalanceParser.parse(
            "USD 40.50 withdrawn at STARBUCKS. Remaining funds USD 1,859.50",
            otherBank,
        )
        assertNotNull(r)
        assertEquals(EntryType.DEBIT, r!!.type)
        assertEquals(4_050L, r.amountMinor)
    }

    @Test fun theRemainingBalanceIsNeverReadAsTheAmount() {
        // The worst failure this parser can have: recording a whole balance as a purchase. The
        // balance here is far larger than the transaction, so a wrong read is unmistakable.
        val r = BalanceParser.parse(
            "USD 40.50 withdrawn at SHOP. Remaining funds USD 9,999.00",
            otherBank,
        )
        assertEquals(4_050L, r!!.amountMinor)
    }

    @Test fun theSameMessageMeansNothingWithoutTheRightWords() {
        // Which is exactly the situation anyone not on EGBANK was in before this existed.
        assertNull(
            BalanceParser.parse(
                "Alert: USD 250.00 was deposited to your account. Remaining funds USD 1,900.00",
                SmsConfig(),
            )
        )
    }

    // ---- the defaults still describe EGBANK --------------------------------

    @Test fun defaultsStillReadTheOriginalEgyptianFormats() {
        // The configuration must not have quietly changed what already worked.
        assertEquals(
            EntryType.DEBIT,
            BalanceParser.parse("تم الشراء بمبلغ 165جم على الكارت رقم  +++0954 من PAYMOB")?.type
        )
        assertEquals(
            11_0500L,
            BalanceParser.parse(
                "Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# 19825518418"
            )?.amountMinor
        )
    }

    // ---- ignore list --------------------------------------------------------

    @Test fun anOtpIsNeverATransaction() {
        // It has a number and often the word "account", so without this gate it parses.
        assertNull(BalanceParser.parse("Your OTP is 4521. EGP 500 will be charged.", SmsConfig()))
    }

    @Test fun clearingTheIgnoreListLetsThatMessageThrough() {
        // Proves the gate is the config doing it, not a hidden hardcoded rule.
        val permissive = SmsConfig(ignoreWords = emptyList())
        assertNotNull(BalanceParser.parse("Your OTP is 4521. EGP 500 will be charged.", permissive))
    }

    // ---- direction safety ---------------------------------------------------

    @Test fun aMessageSayingBothDirectionsIsRefused() {
        // Guessing which way money went is worse than not recording it.
        assertNull(BalanceParser.parse("EGP 100 credited and charged", SmsConfig()))
    }

    @Test fun aReversalFlipsTheDirection() {
        val r = BalanceParser.parse(
            "تم الغاء الشراء بمبلغ 23.8جم على الكارت رقم +++0954 من SHOP"
        )
        assertEquals(EntryType.CREDIT, r?.type)
        assertEquals(2_380L, r?.amountMinor)
    }

    @Test fun customReversalWordsWork() {
        val c = otherBank.copy(reversalWords = listOf("refunded"))
        val r = BalanceParser.parse("USD 40.50 withdrawn at SHOP, later refunded", c)
        assertEquals(EntryType.CREDIT, r?.type)
    }

    // ---- merchant -----------------------------------------------------------

    @Test fun customMerchantLabelsFindTheShop() {
        assertEquals(
            "STARBUCKS",
            BalanceParser.parse("USD 40.50 withdrawn at STARBUCKS. Remaining funds USD 10.00", otherBank)?.merchant
        )
    }

    @Test fun aMissingMerchantNeverCostsTheTransaction() {
        val r = BalanceParser.parse("USD 40.50 withdrawn. Remaining funds USD 10.00", otherBank)
        assertNotNull(r)
        assertEquals(4_050L, r!!.amountMinor)
    }

    // ---- persistence --------------------------------------------------------

    @Test fun aLedgerWithoutAConfigGetsTheDefaults() {
        // Existing installs have no smsConfig key at all, and must keep behaving identically.
        assertEquals(SmsConfig(), LedgerData().smsConfig)
    }
}

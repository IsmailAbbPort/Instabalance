package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceParserTest {

    private fun parse(text: String) = BalanceParser.parse(text)

    private fun assertTxn(text: String, type: EntryType, minor: Long) {
        val r = parse(text)
        assertNotNull("expected a transaction from: $text", r)
        assertEquals("wrong direction for: $text", type, r!!.type)
        assertEquals("wrong amount for: $text", minor, r.amountMinor)
    }

    // ---- EGBANK Arabic ----

    @Test fun arabicWithdrawal_readsAmountNotBalance() {
        // Two numbers present: the 100 withdrawn and the 2091.36 available balance. Must pick 100.
        assertTxn(
            "تم سحب  100جم من حساب 0057*100 من ATM  الرصيد المتاح  2091.36جم",
            EntryType.DEBIT, 10000
        )
    }

    @Test fun arabicPurchase_isDebit() {
        assertTxn(
            "تم الشراء بمبلغ 165جم على الكارت رقم  +++0954 من PAYMOB RAF SPECIALIT  CAIRO N 07",
            EntryType.DEBIT, 16500
        )
    }

    @Test fun arabicCancelledPurchase_isCredit() {
        assertTxn(
            "تم الغاء الشراء بمبلغ 23.8جم على الكارت رقم  +++0954 من New Rabia for Trading Masr Elgedida",
            EntryType.CREDIT, 2380
        )
    }

    @Test fun arabicCancelledDeduction_isCredit() {
        assertTxn(
            "تم إلغاء خصم مبلغ 100جم من ATM  على حساب رقم 0057*100",
            EntryType.CREDIT, 10000
        )
    }

    @Test fun arabicIndicDigits_areNormalized() {
        assertTxn("تم الشراء بمبلغ ١٦٥جم على الكارت", EntryType.DEBIT, 16500)
    }

    // ---- EGBANK / InstaPay English ----

    @Test fun englishCharged_isDebit() {
        assertTxn(
            "Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# 19825518418 for details please call 19342",
            EntryType.DEBIT, 110500
        )
    }

    @Test fun englishCredited_isCredit() {
        assertTxn(
            "Your account was credited by EGP 100 on 22-07 22:51 IPN REF# 70794154067 from NANICE AHMED for details please call 19342",
            EntryType.CREDIT, 10000
        )
    }

    @Test fun instapayReceivedNotification_isCredit() {
        assertTxn(
            "InstaPay 22/07/2026 You have received 100.00 EGP from naniiceeabbas@instapay",
            EntryType.CREDIT, 10000
        )
    }

    @Test fun englishSent_isDebit() {
        assertTxn("You have sent 50 EGP to someone@instapay", EntryType.DEBIT, 5000)
    }

    // ---- Non-transactions ----

    @Test fun unrelatedText_isNull() {
        assertNull(parse("Hello, are we still meeting at 5?"))
        assertNull(parse(""))
        assertNull(parse("Your OTP code is 12345"))
    }
}

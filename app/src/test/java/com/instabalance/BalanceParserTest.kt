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
            "InstaPay 22/07/2026 You have received 100.00 EGP from client.invoice@instapay",
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

    // ---- Merchant extraction ----
    // Only some messages name a counterparty. The ones that do are what the category rules learn
    // from; the ones that don't are why the review inbox exists at all.

    @Test fun arabicPurchase_extractsTheShop() {
        assertEquals(
            "PAYMOB RAF SPECIALIT CAIRO N 07",
            parse("تم الشراء بمبلغ 165جم على الكارت رقم  +++0954 من PAYMOB RAF SPECIALIT  CAIRO N 07")?.merchant
        )
    }

    @Test fun arabicCancelledPurchase_extractsTheShop() {
        assertEquals(
            "New Rabia for Trading Masr Elgedida",
            parse("تم الغاء الشراء بمبلغ 23.8جم على الكارت رقم  +++0954 من New Rabia for Trading Masr Elgedida")?.merchant
        )
    }

    @Test fun arabicWithdrawal_merchantIsAtm() {
        // One rule on "ATM" then files every cash withdrawal, which is why this is worth reading.
        assertEquals(
            "ATM",
            parse("تم سحب  100جم من حساب 0057*100 من ATM  الرصيد المتاح  2091.36جم")?.merchant
        )
    }

    @Test fun englishCredited_extractsTheSender() {
        assertEquals(
            "NANICE AHMED",
            parse("Your account was credited by EGP 100 on 22-07 22:51 IPN REF# 70794154067 from NANICE AHMED for details please call 19342")?.merchant
        )
    }

    @Test fun instapayNotification_extractsTheAddress() {
        assertEquals(
            "client.invoice@instapay",
            parse("InstaPay 22/07/2026 You have received 100.00 EGP from client.invoice@instapay")?.merchant
        )
    }

    @Test fun englishSent_extractsTheRecipientAddress() {
        assertEquals("someone@instapay", parse("You have sent 50 EGP to someone@instapay")?.merchant)
    }

    @Test fun englishCharged_hasNoMerchant() {
        // The structural limit of the whole feature: an InstaPay send seen only as an EGBANK SMS
        // names nobody, so there is nothing to learn and it must land in the inbox.
        val r = parse("Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# 19825518418 for details please call 19342")
        assertNotNull(r)
        assertNull(r!!.merchant)
    }

    @Test fun aMissingMerchantNeverCostsUsTheTransaction() {
        // The amount and the direction are the money; the merchant is a convenience.
        assertTxn("تم الشراء بمبلغ ١٦٥جم على الكارت", EntryType.DEBIT, 16500)
    }

    // ---- Real EGBANK card purchases, verbatim ----
    // These arrive with the bank's own "[EGBANK]" tag wedged into the middle of the card number and
    // with the shop on its own line. Every one of them used to miss the card-purchase branch and
    // fall through to a fallback that keeps only the first word after "من", so MISR PETROLEUM was
    // stored as "MISR" (which a rule cannot tell from Banque Misr) and CHILL OUT, written with no
    // space after "من", stored nothing at all.

    @Test fun realTalabat_keepsTheShopFromTheNextLine() {
        val r = parse("[EGBANK]\n\nتم الشراء بمبلغ 113جم على الكارت رقم ++\n\n0954 من\n\nTalabat\n\nMaadi")
        assertTxn(
            "[EGBANK]\n\nتم الشراء بمبلغ 113جم على الكارت رقم ++\n\n0954 من\n\nTalabat\n\nMaadi",
            EntryType.DEBIT, 11300,
        )
        assertEquals("Talabat", r!!.merchant)
    }

    @Test fun realMisrPetroleum_keepsBothWordsNotJustMisr() {
        val r = parse("تم الشراء بمبلغ 755 جم على الكارت رقم ++ [EGBANK]\n\n0954 من MISR PETROLEUM со CAIRO")
        assertEquals("MISR PETROLEUM со CAIRO", r!!.merchant)
        assertEquals(75500, r.amountMinor)
    }

    @Test fun realChillOut_withNoSpaceAfterMin_stillNamesTheShop() {
        val r = parse("تم الشراء بمبلغ 800 جم على الكارت رقم ++ [EGBANK] 0954 منCHILL OUT - GARDINYA SCAIRO N 07")
        assertEquals("CHILL OUT - GARDINYA SCAIRO N 07", r!!.merchant)
    }

    @Test fun realChillout_theOtherSpelling() {
        val r = parse("تم الشراء بمبلغ 800 جم على الكارت رقم ++ [EGBANK] 0954 من CHILLOUT NAFAQ ELOBOURCAIRO")
        assertEquals("CHILLOUT NAFAQ ELOBOURCAIRO", r!!.merchant)
    }

    @Test fun realSeoudi_keepsTheBranchSuffix() {
        val r = parse("[EGBANK]\n\nتم الشراء بمبلغ 201.53 جم على الكارت رقم ++\n\n0954 من\n\nSEOUDI-ROXY\n\nCAIRO E 07")
        assertEquals("SEOUDI-ROXY", r!!.merchant)
        assertEquals(20153, r.amountMinor)
    }

    @Test fun aTrailingBalanceLineIsNeverReadAsTheShop() {
        // The merchant capture stops at the end of its line. Letting it run to the end of the
        // message would swallow the balance sentence banks put underneath.
        val r = parse("تم الشراء بمبلغ 50جم على الكارت رقم ++ [EGBANK] 0954 من SEOUDI-ROXY\nالرصيد المتاح 2091.36جم")
        assertEquals("SEOUDI-ROXY", r!!.merchant)
        assertEquals(5000, r.amountMinor)
    }
}

package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bank puts the true balance in its own message ("الرصيد المتاح 6542.73جم"). The parser has
 * always had to find that number, to stop it being read as the size of the purchase, and has always
 * thrown it away afterwards. It is the same figure the manual re-sync asks you to go and read off
 * the real app, arriving for free.
 */
class AutoResyncTest {

    private val ts = 1_700_000_000_000L

    // ---- the parser keeps the number ----------------------------------------

    @Test fun thewithdrawalMessageReportsItsBalance() {
        val r = BalanceParser.parse(
            "[EGBANK] تم سحب  1000جم من حساب 0057*100 من ATM  الرصيد المتاح  6542.73جم"
        )

        assertEquals(100_000L, r?.amountMinor)
        assertEquals(654_273L, r?.reportedBalanceMinor)
    }

    @Test fun thebalanceIsStillNotMistakenForTheAmount() {
        // The whole reason the clause was being found in the first place.
        val r = BalanceParser.parse(
            "تم سحب  100جم من حساب 0057*100 من ATM  الرصيد المتاح  2091.36جم"
        )

        assertEquals(10_000L, r?.amountMinor)
        assertEquals(209_136L, r?.reportedBalanceMinor)
    }

    @Test fun apurchaseCarryingAbalanceOnAsecondLineReportsItToo() {
        val r = BalanceParser.parse(
            "تم الشراء بمبلغ 50جم على الكارت رقم ++ [EGBANK] 0954 من SEOUDI-ROXY\nالرصيد المتاح 2091.36جم"
        )

        assertEquals(5_000L, r?.amountMinor)
        assertEquals(209_136L, r?.reportedBalanceMinor)
    }

    @Test fun amessageWithoutAbalanceReportsNone() {
        val r = BalanceParser.parse(
            "Your account was credited by EGP 1070 on 27-07 17:07 IPN REF# 47006796428 from **"
        )

        assertEquals(107_000L, r?.amountMinor)
        assertNull(r?.reportedBalanceMinor)
    }

    // ---- when it corrects, and when it keeps quiet ---------------------------

    @Test fun adisagreementBecomesAnanchorAtTheBanksFigure() {
        val anchor = autoResyncAnchor(654_273, 664_273, ts)

        assertNotNull(anchor)
        assertEquals(EntryType.ANCHOR, anchor!!.type)
        assertEquals(654_273L, anchor.amountMinor)
    }

    @Test fun agreementWritesNothing() {
        assertNull(autoResyncAnchor(654_273, 654_273, ts))
    }

    @Test fun amessageWithNobalanceWritesNothing() {
        assertNull(autoResyncAnchor(null, 664_273, ts))
    }

    @Test fun theanchorLandsAfterTheTransactionAndItsFee() {
        // Both are already counted in the figure the bank just reported, and the fee line shares
        // the transaction's own timestamp. An anchor on the same millisecond would count them twice.
        assertEquals(ts + 1, autoResyncAnchor(654_273, 664_273, ts)?.timestamp)
    }

    @Test fun itIsMarkedAsComingFromAmessageRatherThanFromYou() {
        // Which is what lets the balance card say so, instead of passing it off as your own sync.
        assertEquals(Source.SMS, autoResyncAnchor(654_273, 664_273, ts)?.source)
    }

    @Test fun thenoteSaysWhichWayAndByHowMuch() {
        // Visible in the list, so a correction is never something that quietly happened.
        val lower = autoResyncAnchor(654_273, 664_273, ts)!!.note
        assertTrue(lower, lower.contains("100.00"))
        assertTrue(lower, lower.contains("lower"))

        val higher = autoResyncAnchor(664_273, 654_273, ts)!!.note
        assertTrue(higher, higher.contains("100.00"))
        assertTrue(higher, higher.contains("higher"))
    }

    @Test fun anautoResyncIsNeverCategorised() {
        // An anchor is a re-sync, not money moving.
        assertNull(autoResyncAnchor(654_273, 664_273, ts)?.categoryId)
    }

    // ---- what the balance card says -----------------------------------------

    @Test fun anautomaticSyncSaysSo() {
        val text = syncedAgoText(ts, automatic = true, now = ts)

        assertTrue(text, text.contains("automatic"))
    }

    @Test fun yourOwnSyncIsNotLabelled() {
        val text = syncedAgoText(ts, automatic = false, now = ts)

        assertTrue(text, !text.contains("automatic"))
        assertTrue(text, text.contains("Last synced today"))
    }

    @Test fun neverSyncedIsUnchanged() {
        assertEquals(
            "Never synced. Set your balance from the real app once.",
            syncedAgoText(null, automatic = true, now = ts),
        )
    }
}

package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live reader only runs while the receiver is alive, so every gap (a doze, a reboot, the weeks
 * before the app was installed) is a hole in the ledger. The messages are still on the phone.
 */
class SmsBackfillTest {

    private val now = 1_790_000_000_000L
    private val minute = 60_000L

    private val config = SmsConfig(senders = listOf("EGBANK"))

    private fun sms(body: String, at: Long, sender: String = "EGBANK") = StoredSms(sender, body, at)

    private fun purchase(amount: String) =
        "تم الشراء بمبلغ ${amount}جم على الكارت رقم ++ 0954 من SEOUDI-ROXY CAIRO E 07"

    // ---- what it takes ------------------------------------------------------

    @Test fun areadableMessageFromYourBankIsImported() {
        val plan = SmsBackfill.plan(emptyList(), listOf(sms(purchase("201.53"), now)), config)

        assertEquals(1, plan.items.size)
        assertEquals(20_153L, plan.items.single().parsed.amountMinor)
    }

    @Test fun itKeepsTheTimeTheMessageArrivedNotTheTimeOfTheScan() {
        // The whole point. Imported at "now" they would all pile onto today and the charts would
        // be wrong about two periods instead of one.
        val at = now - 40L * 24 * 60 * 60 * 1000
        val plan = SmsBackfill.plan(emptyList(), listOf(sms(purchase("201.53"), at)), config)

        assertEquals(at, plan.items.single().message.sentAt)
    }

    @Test fun oldestFirstSoTheLedgerIsBuiltInOrder() {
        val messages = listOf(
            sms(purchase("30"), now),
            sms(purchase("10"), now - 100 * minute),
            sms(purchase("20"), now - 50 * minute),
        )

        val plan = SmsBackfill.plan(emptyList(), messages, config)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), plan.items.map { it.parsed.amountMinor })
    }

    // ---- what it refuses ----------------------------------------------------

    @Test fun asenderYouNeverAllowedIsNotReadAtAll() {
        val plan = SmsBackfill.plan(emptyList(), listOf(sms(purchase("201.53"), now, "MUM")), config)

        assertTrue(plan.items.isEmpty())
        // Not counted as unreadable either: it was never looked at, which is a different thing.
        assertEquals(0, plan.unreadable)
    }

    @Test fun amessageThatIsNotAtransactionIsCountedSoYouKnowTheWordsNeedWork() {
        val plan = SmsBackfill.plan(emptyList(), listOf(sms("Your OTP is 4432", now)), config)

        assertTrue(plan.items.isEmpty())
        assertEquals(1, plan.unreadable)
    }

    @Test fun anemptyBodyIsSkippedQuietly() {
        val plan = SmsBackfill.plan(emptyList(), listOf(sms("   ", now)), config)

        assertTrue(plan.items.isEmpty())
        assertEquals(0, plan.unreadable)
    }

    // ---- not importing what is already there --------------------------------

    @Test fun amessageAlreadyInTheLedgerIsSkipped() {
        val message = sms(purchase("201.53"), now)
        val existing = Entry(
            type = EntryType.DEBIT, amountMinor = 20_153, timestamp = now,
            source = Source.SMS, rawText = SmsBackfill.rawTextFor(message),
        )

        val plan = SmsBackfill.plan(listOf(existing), listOf(message), config)

        assertTrue(plan.items.isEmpty())
        assertEquals(1, plan.alreadyKnown)
    }

    @Test fun thesameTransactionCapturedLiveIsNotAddedAsecondTime() {
        // Caught live by the notification, so the raw text differs, but it is the same money.
        val existing = Entry(
            type = EntryType.DEBIT, amountMinor = 20_153, timestamp = now + minute,
            source = Source.NOTIFICATION, rawText = "something else entirely",
        )

        val plan = SmsBackfill.plan(listOf(existing), listOf(sms(purchase("201.53"), now)), config)

        assertTrue(plan.items.isEmpty())
        assertEquals(1, plan.alreadyKnown)
    }

    @Test fun scanningTwiceAddsNothingTheSecondTime() {
        val messages = listOf(sms(purchase("201.53"), now))
        val first = SmsBackfill.plan(emptyList(), messages, config)
        val written = first.items.map {
            Entry(
                type = it.parsed.type, amountMinor = it.parsed.amountMinor,
                timestamp = it.message.sentAt, source = Source.SMS, rawText = it.rawText,
            )
        }

        val second = SmsBackfill.plan(written, messages, config)

        assertTrue(second.items.isEmpty())
    }

    @Test fun thesameMessageTwiceInOneScanLandsOnce() {
        val message = sms(purchase("201.53"), now)

        val plan = SmsBackfill.plan(emptyList(), listOf(message, message), config)

        assertEquals(1, plan.items.size)
    }

    @Test fun twoRealPurchasesOfTheSameAmountFarApartBothLand() {
        // The dedupe window is twenty minutes. Buying the same thing next week is not a duplicate.
        val messages = listOf(
            sms(purchase("201.53"), now),
            sms(purchase("201.53"), now - 7L * 24 * 60 * 60 * 1000),
        )

        val plan = SmsBackfill.plan(emptyList(), messages, config)

        assertEquals(2, plan.items.size)
    }

    @Test fun twoIdenticalAmountsMinutesApartAreTreatedAsOne() {
        // Which is the same call the live path makes, right or wrong, and they must agree.
        val messages = listOf(
            sms(purchase("201.53"), now),
            sms(purchase("201.53"), now - 2 * minute),
        )

        val plan = SmsBackfill.plan(emptyList(), messages, config)

        assertEquals(1, plan.items.size)
        assertEquals(1, plan.alreadyKnown)
    }

    // ---- the window ---------------------------------------------------------

    @Test fun thecutoffIsTheWindowBackFromNow() {
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, SmsBackfill.cutoff(30, now))
    }

    @Test fun therawTextMatchesWhatTheLiveReaderWrites() {
        // SmsReceiver stores "[sender] body". If these ever drift, every rescan re-imports
        // everything, because the text dedupe stops recognising its own writes.
        assertEquals("[EGBANK] hello", SmsBackfill.rawTextFor(sms("hello", now)))
    }
}

package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One InstaPay transfer is announced twice and the dedupe keeps one of them. These are the real
 * pair for a single 1,070 EGP transfer, verbatim, which is the whole reason the detail sheet
 * bothers to look for the other one.
 */
class CaptureMatchTest {

    private val bankSms =
        "Your account was credited by EGP 1070 on 27-07 17:07 IPN REF# 47006796428 from ** " +
            "for details please call عمرو احمد فوزى مرعى 19342"

    private val instapayNotification =
        "InstaPay You have received 1070.00 EGP from marrynbe@instapay"

    private val at = 1_790_000_000_000L

    private fun entry(
        type: EntryType = EntryType.CREDIT,
        minor: Long = 107_000,
        ts: Long = at,
        source: Source = Source.SMS,
    ) = Entry(type = type, amountMinor = minor, timestamp = ts, source = source, rawText = bankSms)

    private fun capture(
        text: String,
        channel: String = "NOTIFICATION",
        ts: Long = at,
        from: String = "com.egyptianbanks.instapay",
    ) = DebugCapture(timestamp = ts, channel = channel, packageOrSender = from, text = text)

    private fun match(e: Entry, caps: List<DebugCapture>) = captureFor(e, caps, SmsConfig())

    @Test fun theNotificationIsFoundForAnEntryCapturedFromTheBankSms() {
        val found = match(entry(), listOf(capture(instapayNotification)))

        assertEquals(instapayNotification, found?.text)
    }

    @Test fun bothMessagesAgreeOnTheMoney() {
        // The premise of matching them at all. If these ever disagreed, pairing them would be
        // showing one transaction's text under another's amount.
        val fromSms = BalanceParser.parse(bankSms)!!
        val fromNotification = BalanceParser.parse(instapayNotification)!!

        assertEquals(fromSms.type, fromNotification.type)
        assertEquals(fromSms.amountMinor, fromNotification.amountMinor)
        assertEquals(107_000L, fromSms.amountMinor)
    }

    @Test fun onlyTheNotificationNamesWhoSentIt() {
        // Why this exists at all: the bank calls the sender "**".
        assertEquals("marrynbe@instapay", BalanceParser.parse(instapayNotification)?.merchant)
    }

    @Test fun aDifferentAmountNearbyIsNotThisTransaction() {
        val other = capture("InstaPay You have received 500.00 EGP from someone@instapay")

        assertNull(match(entry(), listOf(other)))
    }

    @Test fun theOppositeDirectionIsNotThisTransaction() {
        // Same amount, same minute, money going the other way. Pairing these would caption a
        // payment received with the message about one sent.
        val sent = capture("InstaPay You have sent 1070.00 EGP to marrynbe@instapay")

        assertNull(match(entry(), listOf(sent)))
    }

    @Test fun aMatchingMessageHoursAwayIsADifferentTransaction() {
        val sameAmountLater = capture(instapayNotification, ts = at + 6 * 60 * 60 * 1000L)

        assertNull(match(entry(), listOf(sameAmountLater)))
    }

    @Test fun aFewMinutesApartStillCounts() {
        // The two channels do not land at the same instant.
        val slightlyLater = capture(instapayNotification, ts = at + 4 * 60 * 1000L)

        assertEquals(instapayNotification, match(entry(), listOf(slightlyLater))?.text)
    }

    @Test fun theNotificationWinsOverAnSmsCaptureOfTheSameTransfer() {
        // Learning mode may have recorded both. The notification is the readable one.
        val caps = listOf(
            capture(bankSms, channel = "SMS", from = "EGBANK"),
            capture(instapayNotification, channel = "NOTIFICATION"),
        )

        assertEquals("NOTIFICATION", match(entry(), caps)?.channel)
    }

    @Test fun theOrderOfTheCaptureListNeverChangesTheAnswer() {
        val caps = listOf(
            capture(instapayNotification, ts = at + 60_000),
            capture(instapayNotification, ts = at + 10_000),
        )

        assertEquals(match(entry(), caps)?.timestamp, match(entry(), caps.reversed())?.timestamp)
        assertEquals(at + 10_000, match(entry(), caps)?.timestamp)
    }

    @Test fun anAnchorNeverMatches() {
        // A re-sync is not a transaction and has no message announcing it.
        assertNull(match(entry(type = EntryType.ANCHOR), listOf(capture(instapayNotification))))
    }

    @Test fun textThatIsNotATransactionIsNeverMatched() {
        val noise = capture("InstaPay Your session has expired, please sign in again")

        assertNull(match(entry(), listOf(noise)))
    }

    @Test fun noCapturesMeansNothingToShow() {
        assertNull(match(entry(), emptyList()))
    }
}

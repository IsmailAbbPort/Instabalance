package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The evening sweep for anything still uncategorised.
 *
 * A fixed hour rather than "six hours after it landed", because an offset floats: buy something at
 * ten at night and six hours later is four in the morning.
 */
class RemindersTest {

    private val cairo = ZoneId.of("Africa/Cairo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, cairo).toInstant()

    @Test fun theeveningIsNine() {
        assertEquals(21, Reminders.HOUR.hour)
        assertEquals(0, Reminders.HOUR.minute)
    }

    @Test fun thismorningGetsThisEvening() {
        assertEquals(
            at(2026, 10, 1, 21).toEpochMilli(),
            Reminders.nextTriggerAt(at(2026, 10, 1, 9), cairo),
        )
    }

    @Test fun afterNineGetsTomorrow() {
        assertEquals(
            at(2026, 10, 2, 21).toEpochMilli(),
            Reminders.nextTriggerAt(at(2026, 10, 1, 22, 30), cairo),
        )
    }

    /**
     * The receiver fires at the hour and immediately asks for its next slot. Returning the instant
     * it is standing on would schedule an alarm for now, which fires, which schedules now again.
     */
    @Test fun standingExactlyOnTheHourGetsTomorrowNotNow() {
        assertEquals(
            at(2026, 10, 2, 21).toEpochMilli(),
            Reminders.nextTriggerAt(at(2026, 10, 1, 21), cairo),
        )
    }

    @Test fun thelastEveningOfAmonthRollsIntoTheNext() {
        assertEquals(
            at(2026, 11, 1, 21).toEpochMilli(),
            Reminders.nextTriggerAt(at(2026, 10, 31, 23), cairo),
        )
    }

    // ---- whether there is anything to say ----------------------------------

    @Test fun anemptyReviewListIsSilent() {
        assertFalse(Reminders.shouldPost(0, enabled = true))
    }

    @Test fun switchedOffIsSilentHoweverMuchIsWaiting() {
        assertFalse(Reminders.shouldPost(12, enabled = false))
    }

    @Test fun oneWaitingIsWorthSaying() {
        assertTrue(Reminders.shouldPost(1, enabled = true))
    }

    // ---- what it says ------------------------------------------------------

    @Test fun oneIsNotPluralised() {
        assertEquals("1 transaction needs a category", Reminders.title(1))
        assertTrue(Reminders.body(1).contains(" it "))
    }

    @Test fun severalAreCountedRatherThanListed() {
        // One notification however many there are. One per transaction is how a channel gets muted,
        // and the per-transaction alert already exists as the triage one.
        assertEquals("4 transactions need a category", Reminders.title(4))
        assertTrue(Reminders.body(4).contains(" they "))
    }

    @Test fun thereminderIsOnForAnewLedger() {
        assertTrue(LedgerData().pendingReminderEnabled)
    }
}

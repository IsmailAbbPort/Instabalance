package com.instabalance

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One evening nudge for transactions still waiting to be categorised.
 *
 * Deliberately not "N hours after it landed". [TriageAlerts] already fires the instant a
 * transaction arrives and carries the buttons to file it, so a second alert per transaction says
 * nothing new and is how a channel gets muted. What this adds is the sweep: whatever is still
 * sitting in the inbox at the end of the day, counted once.
 *
 * A fixed hour rather than an offset, because an offset floats. Buy something at ten at night and
 * "six hours later" is four in the morning.
 *
 * Pure: no clock of its own, no NotificationManager, no AlarmManager. [ReminderAlarm] does all
 * three and decides nothing.
 */
object Reminders {

    /** Local time the digest goes out. */
    val HOUR: LocalTime = LocalTime.of(21, 0)

    /**
     * The next [HOUR] strictly after [now], as epoch millis.
     *
     * Strictly after, so a receiver that fires at 21:00:00 and immediately asks for its next slot
     * gets tomorrow rather than the instant it is standing on, which would be an alarm loop.
     */
    fun nextTriggerAt(now: Instant, zone: ZoneId): Long {
        val local = now.atZone(zone)
        val today = local.toLocalDate().atTime(HOUR).atZone(zone)
        val next = if (today.toInstant() > now) today else today.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    /** Nothing to say and nothing to post. Off, or an empty inbox, are the same answer. */
    fun shouldPost(pendingCount: Int, enabled: Boolean): Boolean = enabled && pendingCount > 0

    fun title(pendingCount: Int): String =
        if (pendingCount == 1) "1 transaction needs a category"
        else "$pendingCount transactions need a category"

    fun body(pendingCount: Int): String =
        if (pendingCount == 1) "Open your review list while you still remember what it was."
        else "Open your review list while you still remember what they were."
}

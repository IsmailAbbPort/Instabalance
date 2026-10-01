package com.instabalance

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId

/**
 * The clock and the notification for [Reminders]. Everything worth testing is in that object; this
 * file schedules an alarm, posts what it is told to, and schedules the next one.
 *
 * Inexact on purpose. `setAndAllowWhileIdle` needs no permission and survives doze, where an exact
 * alarm would need SCHEDULE_EXACT_ALARM, which is a permission the user has to grant by hand and is
 * meant for alarm clocks and calendar events. A nudge about filing receipts is neither, and it does
 * not matter whether it lands at 21:00 or 21:11.
 */
object ReminderAlarm {

    private const val CHANNEL_ID = "pending_reminder"
    private const val NOTIFICATION_ID = 4401
    private const val REQUEST_CODE = 4402

    const val ACTION = "com.instabalance.PENDING_REMINDER"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Evening reminder",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "One reminder in the evening when transactions are still uncategorised."
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Arms the next evening, or cancels if the setting is off. Safe to call as often as you like:
     * the PendingIntent is reused, so re-arming replaces the pending alarm rather than stacking a
     * second one. Called on every launch, which is also what re-arms it after a force-stop.
     */
    fun sync(context: Context, enabled: Boolean, now: Instant = Instant.now()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        if (!enabled) {
            manager.cancel(pendingIntent(context))
            return
        }
        val at = Reminders.nextTriggerAt(now, ZoneId.systemDefault())
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }
    }

    /** Silently does nothing when the inbox is empty, the setting is off, or alerts are denied. */
    fun post(context: Context, pendingCount: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(Reminders.title(pendingCount))
            .setContentText(Reminders.body(pendingCount))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

/**
 * Fires the digest, then arms tomorrow. Also re-arms after a reboot, which otherwise clears every
 * alarm the app had set and would leave the reminder silent until the next time the app was opened.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Matched positively. "Anything that is not BOOT_COMPLETED" treats an unrecognised action
        // as a reason to do the work, which is the wrong way round for a receiver.
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != ReminderAlarm.ACTION) return

        val app = context.applicationContext
        LedgerRepository.init(app)
        val data = LedgerRepository.data.value

        if (!boot) {
            val pending = Insights.uncategorisedCount(data.entries)
            if (Reminders.shouldPost(pending, data.pendingReminderEnabled)) {
                ReminderAlarm.post(app, pending)
            }
        }
        // Re-armed either way: a day with an empty inbox still has to schedule the next one, or the
        // reminder stops for good the first evening you were up to date.
        ReminderAlarm.sync(app, data.pendingReminderEnabled)
    }
}

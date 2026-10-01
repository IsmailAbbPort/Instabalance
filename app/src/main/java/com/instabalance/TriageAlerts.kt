package com.instabalance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Offers to file a transaction from the notification shade, the moment it is captured.
 *
 * Built to be ignorable. Your bank already buzzed the phone one second before this appears, so this
 * channel is IMPORTANCE_LOW: it shows in the shade, silently, with no heads-up and no vibration.
 * It only ever appears for a transaction nobody could file for you, it cancels itself the moment
 * the entry is categorised anywhere, and there is a switch in Settings to stop it entirely.
 */
object TriageAlerts {

    private const val CHANNEL_ID = "triage"

    const val ACTION_FILE = "com.instabalance.action.FILE"
    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_CATEGORY_ID = "category_id"

    /**
     * One notification per entry, so several arriving together do not overwrite each other. The
     * id has to be stable across processes, and the entry id is the only thing that is.
     */
    fun notificationId(entryId: String): Int = entryId.hashCode()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Categorise as it happens",
            // LOW on purpose: visible in the shade, but never a sound, a vibration or a heads-up.
            // The bank's own SMS already made the noise.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Offers to file a transaction while you still remember what it was."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun post(context: Context, entry: Entry, data: LedgerData) {
        if (!data.triageAlertsEnabled) return
        if (!worthTriaging(entry)) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val choices = triageChoices(data.entries, data.categories, entry)
        if (choices.isEmpty()) return

        val open = PendingIntent.getActivity(
            context,
            notificationId(entry.id),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(triageTitle(entry))
            .setContentText(triageBody(entry))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        choices.forEach { category ->
            val intent = Intent(context, TriageActionReceiver::class.java).apply {
                action = ACTION_FILE
                putExtra(EXTRA_ENTRY_ID, entry.id)
                putExtra(EXTRA_CATEGORY_ID, category.id)
                // Distinct data, or the system reuses one PendingIntent for all three actions and
                // every button files into whichever category was registered first. setData rather
                // than the property, which here would resolve to this function's own `data`.
                setData(android.net.Uri.parse("instabalance://file/${entry.id}/${category.id}"))
            }
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, category.name, pending)
        }

        runCatching { manager.notify(notificationId(entry.id), builder.build()) }
    }

    /** Called when an entry gets a category from anywhere, so a stale offer cannot sit in the shade. */
    fun cancel(context: Context, entryId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(entryId))
        }
    }
}

/** Files the entry from the notification action, without opening the app. */
class TriageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TriageAlerts.ACTION_FILE) return
        val entryId = intent.getStringExtra(TriageAlerts.EXTRA_ENTRY_ID) ?: return
        val categoryId = intent.getStringExtra(TriageAlerts.EXTRA_CATEGORY_ID) ?: return

        LedgerRepository.init(context.applicationContext)
        LedgerRepository.setCategory(setOf(entryId), categoryId)
        TriageAlerts.cancel(context, entryId)
    }
}

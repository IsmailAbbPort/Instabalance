package com.instabalance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The only file that talks to NotificationManager. Everything that decides WHETHER to alert is in
 * [Budget] and is pure; this just posts what it is told to.
 */
object BudgetAlerts {

    private const val CHANNEL_ID = "budget_alerts"
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Budget alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when your spending passes a share of your monthly budget."
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Silently does nothing if the user never granted POST_NOTIFICATIONS. The budget card on Home
     * keeps working either way, so a denied permission costs the alert and not the feature.
     */
    fun post(context: Context, milestone: Int, spentMinor: Long, limitMinor: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(Budget.title(milestone))
            .setContentText(Budget.body(spentMinor, limitMinor))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Throws only if the runtime permission is missing, which areNotificationsEnabled already
        // covers; catching keeps a failed alert from ever reaching a background receiver.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

package com.instabalance

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications, but ONLY from the watched apps (InstaPay). It never inspects any other
 * app's notifications. Requires the user to grant "Notification access" in system settings.
 */
class InstaPayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        LedgerRepository.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        LedgerRepository.init(applicationContext)
        val data = LedgerRepository.data.value

        val pkg = sbn.packageName ?: return
        // Hard scope: we only ever look at the watched packages, nothing else on the phone.
        if (pkg !in data.watchedPackages) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val body = listOf(title, big.ifEmpty { text }).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (body.isEmpty()) return

        val now = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        // Learning mode records the raw wording of the watched app so the parser can be tuned.
        if (data.learningMode) {
            LedgerRepository.addCapture("NOTIFICATION", pkg, body, now)
        }

        val parsed = BalanceParser.parse(body, data.smsConfig) ?: return
        LedgerRepository.addAuto(parsed, Source.NOTIFICATION, body, now)
    }
}

package com.instabalance

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise early so a background SMS / notification can be recorded even if the
        // user has never opened the app this boot. Re-locking is handled in MainActivity.onStop.
        LedgerRepository.init(this)

        BudgetAlerts.ensureChannel(this)
        TriageAlerts.ensureChannel(this)
        ReminderAlarm.ensureChannel(this)
        // Re-arms the evening digest on every launch, which is also how it comes back after a
        // force-stop (Android clears an app's alarms when you force-stop it).
        ReminderAlarm.sync(this, LedgerRepository.data.value.pendingReminderEnabled)

        // Catches up a backup the phone was off for, then arms the next one. Checked on every
        // launch as well as on the alarm, so a dropped alarm costs a late backup and not a missing
        // one.
        //
        // Off the main thread: a run is 210k rounds of PBKDF2 plus encrypting the whole ledger,
        // and this is onCreate, so doing it here stalled the cold start by that much. Wrapped
        // because nothing about scheduling a backup is worth failing to start the app over.
        BackupPassphrase.init(this)
        Thread {
            runCatching {
                AutoBackupAlarm.runIfDue(this)
                AutoBackupAlarm.sync(this, LedgerRepository.data.value)
            }
        }.start()
        // Wired here so the ledger itself never imports NotificationManager and stays testable.
        LedgerRepository.onBudgetMilestone = { milestone, spent, limit ->
            BudgetAlerts.post(this, milestone, spent, limit)
        }
        LedgerRepository.onCategoryBudget = { status ->
            BudgetAlerts.postCategory(this, status)
        }
        LedgerRepository.onUncategorised = { entry ->
            TriageAlerts.post(this, entry, LedgerRepository.data.value)
        }
        LedgerRepository.onCategorised = { ids ->
            ids.forEach { TriageAlerts.cancel(this, it) }
        }
    }
}

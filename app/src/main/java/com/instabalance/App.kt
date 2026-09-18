package com.instabalance

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise early so a background SMS / notification can be recorded even if the
        // user has never opened the app this boot. Re-locking is handled in MainActivity.onStop.
        LedgerRepository.init(this)

        BudgetAlerts.ensureChannel(this)
        // Wired here so the ledger itself never imports NotificationManager and stays testable.
        LedgerRepository.onBudgetMilestone = { milestone, spent, limit ->
            BudgetAlerts.post(this, milestone, spent, limit)
        }
    }
}

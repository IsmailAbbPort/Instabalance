package com.instabalance

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark the window secure: the OS won't snapshot it for the app switcher or the resume
        // animation (which is what briefly flashed the balance before the lock), and it blocks
        // screenshots of your balance too.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        LedgerRepository.init(applicationContext)
        setContent {
            InstaBalanceTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Gate()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock the moment the app leaves the screen, so reopening shows the passcode first.
        // Guarded so a rotation/config change (or a settings screen we opened) doesn't lock.
        if (!isChangingConfigurations && !LedgerRepository.consumeSkipLock()) {
            LedgerRepository.lockSession()
        }
    }
}

@Composable
private fun Gate() {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    val unlocked by LedgerRepository.sessionUnlocked.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    if (!data.hasPasscode || unlocked) {
        val nav = rememberNavStack()
        when (nav.current) {
            Route.HOME -> HomeScreen(onSettings = { nav.go(Route.SETTINGS) })
            Route.SETTINGS -> SettingsScreen(onBack = { nav.back() })
        }
    } else {
        LockScreen(activity, data.biometricEnabled) { LedgerRepository.markUnlocked() }
    }
}

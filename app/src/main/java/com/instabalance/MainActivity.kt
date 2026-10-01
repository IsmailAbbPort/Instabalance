package com.instabalance

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark the window secure: the OS won't snapshot it for the app switcher or the resume
        // animation (which is what briefly flashed the balance before the lock), and it blocks
        // screenshots of your balance too.
        //
        // Off in debug builds only, so the UI can be screenshotted during review. The flag exists
        // to protect real money on a real phone, and a release build always has it.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        LedgerRepository.init(applicationContext)
        SessionResume.init(applicationContext)

        // Emulator-only hook so sample data can be loaded without tapping through Settings:
        //   adb shell am start -n com.instabalance/.MainActivity --ez seed_sample_data true
        // Handy for screenshots, and for a device whose input service is being unreliable.
        //
        // Gated the same way as the Settings buttons rather than on BuildConfig.DEBUG, because it
        // destroys the ledger and the phone runs the debug APK: anyone with USB debugging could
        // wipe a real ledger with one adb command.
        if (isDevSandbox && intent?.getBooleanExtra("seed_sample_data", false) == true) {
            LedgerRepository.loadSampleData()
        }

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

    // Above the lock, deliberately. Leaving the app re-locks it, which swaps this whole branch for
    // the passcode screen and takes everything remembered inside it along: the back stack and the
    // transaction filter both died there, so every unlock landed on an unfiltered Home no matter
    // what you had been reading a moment earlier.
    val showContent = !data.hasPasscode || unlocked
    val resumed = remember { SessionResume.load(System.currentTimeMillis()) }
    val nav = rememberNavStack(
        initial = resumed?.routes ?: listOf(Route.HOME),
        backEnabled = showContent,
    )
    var filter by remember { mutableStateOf(resumed?.filter ?: TransactionFilter()) }

    // Written when the app leaves the screen, which is the only moment the answer can change and
    // the only one worth a write. Read back on the next launch, and honoured for fifteen minutes.
    val latestFilter by rememberUpdatedState(filter)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                SessionResume.save(nav.snapshot(), latestFilter, System.currentTimeMillis())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showContent) {
        when (nav.current) {
            Route.HOME -> HomeScreen(
                onSettings = { nav.go(Route.SETTINGS) },
                onInbox = { nav.go(Route.INBOX) },
                onAllTransactions = { nav.go(Route.TRANSACTIONS) },
            )
            Route.INBOX -> InboxScreen(onBack = { nav.back() })
            Route.TRANSACTIONS -> TransactionsScreen(
                filter = filter,
                onFilterChange = { filter = it },
                onBack = { nav.back() },
            )
            Route.SETTINGS -> SettingsScreen(
                onBack = { nav.back() },
                onCategories = { nav.go(Route.CATEGORIES) },
                onRules = { nav.go(Route.RULES) },
                onSmsSetup = { nav.go(Route.SMS_SETUP) },
            )
            Route.CATEGORIES -> CategoriesScreen(onBack = { nav.back() })
            Route.RULES -> RulesScreen(onBack = { nav.back() })
            Route.SMS_SETUP -> SmsSetupScreen(onBack = { nav.back() })
        }
    } else {
        LockScreen(activity, data.biometricEnabled) { LedgerRepository.markUnlocked() }
    }
}

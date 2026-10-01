package com.instabalance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onCategories: () -> Unit,
    onRules: () -> Unit,
    onSmsSetup: () -> Unit,
) {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BudgetSetting(data)
            NavRow(
                "Reading your bank's SMS",
                if (data.smsConfig == SmsConfig()) "Set up for Egyptian banks. Tap if yours differs."
                else "Customised for your bank",
                onSmsSetup,
            )
            NavRow("Categories", "${data.categories.count { !it.hidden }} in use", onCategories)
            NavRow(
                "Merchant rules",
                if (data.merchantRules.isEmpty()) "File a shop's transactions automatically"
                else "${data.merchantRules.size} saved",
                onRules,
            )
            SettingsPanel(data)
            BackupSection(data)
            if (isDevSandbox) DeveloperTools()
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The notification permission is asked for here, at the moment a budget is first set, rather than
 * at app start where it has no context and gets refused out of hand.
 */
@Composable
private fun BudgetSetting(data: LedgerData) {
    val context = LocalContext.current
    var amount by remember(data.monthlyBudgetMinor) {
        mutableStateOf(data.monthlyBudgetMinor?.let { Money.formatMinor(it) } ?: "")
    }

    // Re-read on every resume, so granting the permission in system settings and coming back
    // updates this without a restart. Reading it once at composition left the warning stale.
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsOn = granted }

    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below 13 there is no runtime permission; the only way back is the system screen.
            LedgerRepository.suppressNextLock()
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Monthly budget", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Alerts you at 25%, 50%, 75%, 90%, 100% and 120% of your limit. If one transaction " +
                    "passes several at once, only the highest is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = sanitizeAmount(it) },
                label = { Text("Limit (EGP, blank = off)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            // sanitizeAmount permits a lone ".", which parses to null. Treating null as "no budget"
            // would silently delete the budget and its milestone state when the user meant to set
            // one, so only a genuinely blank field turns it off.
            val blank = amount.isBlank()
            val parsed = if (blank) null else Money.parseToMinor(amount)
            val usable = blank || parsed != null

            Row(verticalAlignment = Alignment.CenterVertically) {
                ConfirmingTextButton(
                    label = if (blank) "Turn off budget" else "Save budget",
                    confirmedLabel = if (blank) "Turned off!" else "Saved!",
                    enabled = usable,
                    onClick = {
                        LedgerRepository.setBudget(parsed)
                        if (parsed != null && !notificationsOn) askForNotifications()
                    },
                )
            }
            if (!usable) {
                Text(
                    "That is not an amount. Enter a number, or clear the field to turn the budget off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // A budget with notifications off is silently useless. Say so, and give the way back:
            // the permission can be set outside this screen (or a budget can arrive without ever
            // passing through it), so the fix cannot live only on the Save button.
            if (data.monthlyBudgetMinor != null && !notificationsOn) {
                Text(
                    "Alerts are off. Notifications are not allowed for this app, so the card on " +
                        "Home will keep updating but nothing will be sent when you pass a milestone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { askForNotifications() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on budget alerts")
                }
            } else if (data.monthlyBudgetMinor != null) {
                Text(
                    "Alerts are on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Categorise as it happens", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(
                "When a transfer arrives that no rule can file, a silent notification offers three " +
                    "categories so you can file it while you still remember what it was. It never " +
                    "makes a sound, never appears for anything a rule already filed, and goes away " +
                    "the moment the transaction is categorised.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Offer to file new transactions", Modifier.weight(1f))
                Switch(
                    checked = data.triageAlertsEnabled,
                    onCheckedChange = {
                        LedgerRepository.setTriageAlertsEnabled(it)
                        if (it && !notificationsOn) askForNotifications()
                    },
                )
            }
            if (data.triageAlertsEnabled && !notificationsOn) {
                Text(
                    "Notifications are not allowed for this app, so nothing will be offered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                "One reminder at ${Reminders.HOUR} if anything is still waiting at the end of the " +
                    "day, counted in a single notification however many there are. Nothing is sent " +
                    "on a day your review list is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Evening reminder", Modifier.weight(1f))
                Switch(
                    checked = data.pendingReminderEnabled,
                    onCheckedChange = {
                        LedgerRepository.setPendingReminderEnabled(it)
                        ReminderAlarm.sync(context, it)
                        if (it && !notificationsOn) askForNotifications()
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(data: LedgerData) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var showPasscodeSetup by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ---- App lock ------------------------------------------------------
        Text("App lock", style = MaterialTheme.typography.titleSmall)
        Text(
            if (data.hasPasscode) "Passcode is set. Required every time you reopen the app."
            else "No passcode. Anyone who opens the app sees your balance.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { showPasscodeSetup = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (data.hasPasscode) "Change passcode" else "Set passcode")
        }
        if (data.hasPasscode) {
            val bioAvailable = activity != null && BiometricAuth.isAvailable(activity)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unlock with fingerprint / face", style = MaterialTheme.typography.bodyMedium)
                    if (!bioAvailable) {
                        Text("No fingerprint/face enrolled on this device yet. Enroll one in the phone's settings and this will start working.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = data.biometricEnabled,
                    onCheckedChange = { LedgerRepository.setBiometricEnabled(it) }
                )
            }
            TextButton(onClick = { LedgerRepository.clearPasscode() }) { Text("Remove passcode") }
        }

        HorizontalDivider()

        // ---- Automation ----------------------------------------------------
        Text("Automation", style = MaterialTheme.typography.titleSmall)
        val notifOn = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        Text("Notification access: ${if (notifOn) "granted" else "not granted"}",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            LedgerRepository.suppressNextLock()
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, modifier = Modifier.fillMaxWidth()) { Text("Open notification access settings") }

        val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        Text("SMS access: ${if (smsGranted) "granted" else "not granted"}",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = {
                activity?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECEIVE_SMS), 1001)
                }
            },
            enabled = !smsGranted && activity != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (smsGranted) "SMS permission granted" else "Grant SMS permission") }

        OutlinedButton(onClick = {
            LedgerRepository.suppressNextLock()
            openBatterySettings(context)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Disable battery optimisation (keeps the reader alive)")
        }

        HorizontalDivider()

        // ---- InstaPay transfer fee ----
        Text("InstaPay transfer fee", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-add fee on sends", style = MaterialTheme.typography.bodyMedium)
                Text("Sends report only the amount; this adds the fee so your balance stays right.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = data.instapaySendFeeEnabled,
                onCheckedChange = {
                    LedgerRepository.setFeeConfig(it, data.feePercentBps, data.feeMinMinor, data.feeCapMinor)
                }
            )
        }
        if (data.instapaySendFeeEnabled) {
            var pct by remember(data.feePercentBps) { mutableStateOf((data.feePercentBps / 100.0).toString()) }
            var minv by remember(data.feeMinMinor) { mutableStateOf(Money.formatMinor(data.feeMinMinor)) }
            var capv by remember(data.feeCapMinor) {
                mutableStateOf(data.feeCapMinor?.let { Money.formatMinor(it) } ?: "")
            }
            OutlinedTextField(pct, { pct = sanitizeAmount(it) }, label = { Text("Percent (%)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(minv, { minv = sanitizeAmount(it) }, label = { Text("Minimum fee (EGP)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(capv, { capv = sanitizeAmount(it) },
                label = { Text("Max fee cap (EGP, blank = none)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            ConfirmingTextButton(label = "Save fee settings", confirmedLabel = "Saved!") {
                val bps = Money.percentToBasisPoints(pct) ?: 0
                val minMinor = Money.parseToMinor(minv) ?: 0L
                val capMinor = if (capv.isBlank()) null else Money.parseToMinor(capv)
                LedgerRepository.setFeeConfig(true, bps, minMinor, capMinor)
            }
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learning mode", style = MaterialTheme.typography.bodyMedium)
                Text("Records the raw text of InstaPay notifications and money SMS below, so their wording can be tuned. It never reads other apps. Turn off once set up.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = data.learningMode, onCheckedChange = { LedgerRepository.setLearningMode(it) })
        }

        Text("Watched apps", style = MaterialTheme.typography.bodyMedium)
        Text("The app package name(s) whose notifications are read (only these, nothing else). InstaPay's package is the id in its Play Store link: play.google.com/store/apps/details?id=THIS_PART. Comma-separated.",
            style = MaterialTheme.typography.bodySmall)
        var pkgs by remember(data.watchedPackages) {
            mutableStateOf(data.watchedPackages.joinToString(", "))
        }
        OutlinedTextField(
            value = pkgs,
            onValueChange = { pkgs = it },
            label = { Text("Watched packages") },
            modifier = Modifier.fillMaxWidth()
        )
        ConfirmingTextButton(label = "Save watched apps", confirmedLabel = "Saved!") {
            LedgerRepository.setWatchedPackages(pkgs.split(",").map { it.trim() })
        }

        if (data.captures.isNotEmpty()) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Captured messages", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { LedgerRepository.clearCaptures() }) { Text("Clear") }
            }
            data.captures.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("${c.channel} • ${c.packageOrSender}",
                            style = MaterialTheme.typography.labelMedium)
                        Text(c.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showPasscodeSetup) {
        PasscodeSetupDialog(onDismiss = { showPasscodeSetup = false })
    }
}

/**
 * Export and import, which is the only way data leaves this app.
 *
 * The ledger is encrypted with an Android Keystore key that is destroyed when the app is
 * uninstalled, so `ledger.enc` cannot be restored from any phone backup: the file survives and the
 * key does not. That makes this the real backup, and the reason it writes plain JSON to a location
 * the user picks rather than somewhere of our choosing.
 */
@Composable
private fun BackupSection(data: LedgerData) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<BackupFile?>(null) }
    /** An encrypted backup we have read but cannot open until a passphrase is typed. */
    var locked by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = Backup.encode(data, System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        status = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("no stream")
            "Saved ${data.entries.size} transactions."
        }.getOrElse { "Could not write to that location. Try somewhere else." }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            failure = "That file could not be read."
            return@rememberLauncherForActivityResult
        }
        // An automatic backup is encrypted, so it needs the passphrase before it is even a backup
        // we can describe. Detected by reading the file rather than by its extension.
        if (BackupCrypto.looksEncrypted(text)) {
            locked = text
            return@rememberLauncherForActivityResult
        }
        when (val parsed = Backup.decode(text)) {
            is BackupParse.Ok -> pending = parsed.file
            is BackupParse.Failed -> failure = parsed.reason
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Uninstalling the app destroys the key your data is encrypted with, and no phone " +
                    "backup can bring it back. Save a copy somewhere you keep things, and do it " +
                    "before you reinstall or change phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    // The picker is another app, so the re-lock on leaving would otherwise bounce
                    // the user to the passcode on the way back.
                    LedgerRepository.suppressNextLock()
                    status = null
                    exporter.launch(Backup.suggestedFileName(System.currentTimeMillis()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export a backup") }

            OutlinedButton(
                onClick = {
                    LedgerRepository.suppressNextLock()
                    status = null
                    importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from a backup") }

            Text(
                "The export holds your transactions, categories, rules and budget, and the full " +
                    "text of every bank message they came from. It is not encrypted, so keep it " +
                    "somewhere you would keep a bank statement. It does not hold your passcode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    AutoBackupSection(data)

    locked?.let { text ->
        var passphrase by remember(text) { mutableStateOf("") }
        var wrong by remember(text) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { locked = null },
            title = { Text("This backup is encrypted") },
            text = {
                Column {
                    Text(
                        "Enter the backup passphrase you set on the phone that wrote it. It is not " +
                            "your app passcode, and the app cannot recover it for you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; wrong = null },
                        label = { Text("Backup passphrase") },
                        singleLine = true,
                        isError = wrong != null,
                        supportingText = wrong?.let { { Text(it) } },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        when (val opened = BackupCrypto.decrypt(text, passphrase)) {
                            is BackupCrypto.Opened.Failed -> wrong = opened.reason
                            is BackupCrypto.Opened.Ok ->
                                when (val parsed = Backup.decode(opened.plaintext)) {
                                    is BackupParse.Ok -> { locked = null; pending = parsed.file }
                                    is BackupParse.Failed -> wrong = parsed.reason
                                }
                        }
                    },
                ) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { locked = null }) { Text("Cancel") } },
        )
    }

    pending?.let { file ->
        val summary = Backup.summarise(file.data)
        val range = Backup.describeRange(summary)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace everything with this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${summary.entryCount} transactions, ${summary.categoryCount} categories, " +
                            "${summary.ruleCount} merchant rules." +
                            (range?.let { "\n$it." } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Balance in the backup: ${Money.formatMinor(summary.balanceMinor)} EGP",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Your ${data.entries.size} current transactions will be replaced. Your " +
                            "passcode stays as it is. This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.importBackup(file.data)
                    status = "Restored ${summary.entryCount} transactions."
                    pending = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    failure?.let { reason ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Cannot restore that file") },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

/**
 * Emulator only, gated by [isDevSandbox] at the call site. Exists so the charts, the review inbox
 * and the budget can be seen on a machine that has no real messages, without waiting weeks for
 * them. Both buttons destroy the ledger, which is why a debug-build check was not enough: the
 * phone runs the debug APK too.
 */
/**
 * The backup that writes itself.
 *
 * Gated on a passphrase rather than offered first and secured later: everything this writes goes
 * into a public folder, and the only version of that worth shipping is the encrypted one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoBackupSection(data: LedgerData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingPassphrase by remember { mutableStateOf(false) }
    val hasPassphrase = remember(settingPassphrase) { BackupPassphrase.isSet() }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Back up automatically", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Writes an encrypted copy into Download/${AutoBackup.FOLDER} on its own. It survives " +
                    "uninstalling the app and opens on any phone, as long as you remember the " +
                    "passphrase. Nothing else on the phone can read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!hasPassphrase) {
                Text(
                    "Set a passphrase to turn this on. It is not your app passcode, and if you " +
                        "lose it the backups are gone with it: nothing can open them, including us.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { settingPassphrase = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set a backup passphrase") }
            } else {
                Text("How often", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackupFrequency.entries.forEach { f ->
                        FilterChip(
                            selected = data.autoBackupFrequency == f,
                            onClick = {
                                LedgerRepository.setAutoBackupFrequency(f)
                                AutoBackupAlarm.sync(context, LedgerRepository.data.value)
                            },
                            label = { Text(f.label) },
                        )
                    }
                }

                Text("Old backups", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoBackup.KEEP_CHOICES.forEach { keep ->
                        FilterChip(
                            selected = data.autoBackupKeep == keep,
                            onClick = { LedgerRepository.setAutoBackupKeep(keep) },
                            label = {
                                Text(
                                    if (keep == AutoBackup.KEEP_EVERYTHING) "Keep all"
                                    else "Newest $keep"
                                )
                            },
                        )
                    }
                }
                Text(
                    if (data.autoBackupKeep == AutoBackup.KEEP_EVERYTHING) {
                        "Nothing is ever deleted. The folder grows until you tidy it yourself."
                    } else {
                        "Once there are more than ${data.autoBackupKeep}, the oldest is deleted as a " +
                            "new one is written. Only backups this setting wrote are ever deleted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    // Off the UI thread: forcing a run always does the key derivation, which
                    // froze the screen for the length of it.
                    onClick = { scope.launch(Dispatchers.IO) { AutoBackupAlarm.runIfDue(context, force = true) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back up now") }

                TextButton(onClick = { settingPassphrase = true }) { Text("Change the passphrase") }

                if (data.autoBackupLastRunAt > 0L) {
                    Text(
                        "Last run ${Backup.describeMoment(data.autoBackupLastRunAt)}: " +
                            data.autoBackupLastResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (settingPassphrase) {
        PassphraseDialog(
            onDismiss = { settingPassphrase = false },
            onSet = {
                BackupPassphrase.init(context)
                BackupPassphrase.set(it)
                settingPassphrase = false
            },
        )
    }
}

@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tooShort = first.isNotEmpty() && first.length < 8
    val mismatch = second.isNotEmpty() && first != second
    val usable = first.length >= 8 && first == second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup passphrase") },
        text = {
            Column {
                Text(
                    "Everything written to Download/${AutoBackup.FOLDER} is encrypted with this. " +
                        "Write it down somewhere that is not this phone: if you lose the phone and " +
                        "the passphrase, the backups are unreadable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text("At least 8 characters.") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text("Type it again") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("These do not match.") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = usable, onClick = { onSet(first) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeveloperTools() {
    var confirmLoad by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer tools", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(
                "Emulator only. Never shown on a phone, whichever build is installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmLoad = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Load sample data")
            }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear all transactions")
            }
        }
    }

    if (confirmLoad) {
        AlertDialog(
            onDismissRequest = { confirmLoad = false },
            title = { Text("Replace everything with sample data?") },
            text = {
                Text(
                    "Four months of generated transactions, a few merchant rules and a monthly " +
                        "budget. Your existing transactions are deleted. Your passcode is kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.loadSampleData()
                    confirmLoad = false
                }) { Text("Load") }
            },
            dismissButton = { TextButton(onClick = { confirmLoad = false }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete every transaction?") },
            text = { Text("Categories, rules and your passcode are kept. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    LedgerRepository.clearAllEntries()
                    confirmClear = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

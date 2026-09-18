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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onCategories: () -> Unit,
    onRules: () -> Unit,
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
            NavRow("Categories", "${data.categories.count { !it.hidden }} in use", onCategories)
            NavRow("Merchant rules", "${data.merchantRules.size} saved", onRules)
            SettingsPanel(data)
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
    val activity = context as? FragmentActivity
    var amount by remember(data.monthlyBudgetMinor) {
        mutableStateOf(data.monthlyBudgetMinor?.let { Money.formatMinor(it) } ?: "")
    }
    val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val minor = if (amount.isBlank()) null else Money.parseToMinor(amount)
                    LedgerRepository.setBudget(minor)
                    if (minor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !notificationsOn && activity != null
                    ) {
                        ActivityCompat.requestPermissions(
                            activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
                        )
                    }
                }) { Text("Save budget") }
            }
            if (data.monthlyBudgetMinor != null && !notificationsOn) {
                // Say so rather than letting the user believe alerts are armed when they are not.
                Text(
                    "Notifications are turned off for this app, so the budget card on Home will " +
                        "update but no alerts will be sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
            TextButton(onClick = {
                val bps = ((pct.toDoubleOrNull() ?: 0.0) * 100).toInt()
                val minMinor = Money.parseToMinor(minv) ?: 0L
                val capMinor = if (capv.isBlank()) null else Money.parseToMinor(capv)
                LedgerRepository.setFeeConfig(true, bps, minMinor, capMinor)
            }) { Text("Save fee settings") }
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
        TextButton(onClick = {
            LedgerRepository.setWatchedPackages(pkgs.split(",").map { it.trim() })
        }) { Text("Save watched apps") }

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

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

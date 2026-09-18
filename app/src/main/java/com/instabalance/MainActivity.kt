package com.instabalance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            MaterialTheme(colorScheme = lightColorScheme()) {
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

private const val PIN_LENGTH = 4

private enum class Dialog { NONE, CREDIT, DEBIT, ANCHOR }

@Composable
private fun Gate() {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    val unlocked by LedgerRepository.sessionUnlocked.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    if (!data.hasPasscode || unlocked) {
        AppScreen()
    } else {
        LockScreen(activity, data.biometricEnabled) { LedgerRepository.markUnlocked() }
    }
}

// ---------------------------------------------------------------------------
// Lock screen (keypad, dots, enter, biometric) — item 8 + 10
// ---------------------------------------------------------------------------

@Composable
private fun LockScreen(activity: FragmentActivity?, biometricEnabled: Boolean, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bioAvailable = activity != null && BiometricAuth.isAvailable(activity)

    fun launchBiometric() {
        if (bioAvailable) {
            activity?.let { BiometricAuth.prompt(it, onSuccess = onUnlocked, onFallback = {}) }
        }
    }

    fun submit() {
        if (pin.length == PIN_LENGTH) {
            if (LedgerRepository.verifyPasscode(pin)) {
                onUnlocked()
            } else {
                error = true
                pin = ""
            }
        }
    }

    // Auto-offer biometric when the lock appears, if the user turned it on.
    LaunchedEffect(Unit) { if (biometricEnabled) launchBiometric() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text("Fuck Instapay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Enter passcode to unlock", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        PinDots(filled = pin.length, error = error)
        Spacer(Modifier.height(12.dp))
        Text(
            if (error) "Wrong passcode" else " ",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.weight(1f))
        KeyPad(
            onDigit = { d -> if (pin.length < PIN_LENGTH) { pin += d; error = false } },
            onBackspace = { if (pin.isNotEmpty()) { pin = pin.dropLast(1); error = false } },
            onEnter = { submit() }
        )
        if (biometricEnabled && bioAvailable) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { launchBiometric() }) {
                Icon(Icons.Default.Fingerprint, null)
                Spacer(Modifier.width(6.dp))
                Text("Use fingerprint / face")
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PinDots(filled: Int, error: Boolean) {
    val active = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(PIN_LENGTH) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(if (i < filled) active else inactive)
            )
        }
    }
}

@Composable
private fun KeyPad(onDigit: (String) -> Unit, onBackspace: () -> Unit, onEnter: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("back", "0", "enter"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { key ->
                    when (key) {
                        "back" -> KeyButton(onClick = onBackspace) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                        }
                        "enter" -> KeyButton(onClick = onEnter) {
                            Icon(Icons.Default.Check, "Enter", tint = MaterialTheme.colorScheme.primary)
                        }
                        else -> KeyButton(onClick = { onDigit(key) }) {
                            Text(key, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------------------------------------------------------------------
// Main app
// ---------------------------------------------------------------------------

@Composable
private fun AppScreen() {
    val data by LedgerRepository.data.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(Dialog.NONE) }

    val balance = LedgerRepository.balanceMinor(data)
    val lastAnchor = LedgerRepository.lastAnchorTimestamp(data)

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BalanceCard(balance, lastAnchor)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = Dialog.CREDIT }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Received")
            }
            Button(onClick = { dialog = Dialog.DEBIT }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Remove, null); Spacer(Modifier.width(4.dp)); Text("Sent")
            }
        }
        OutlinedButton(onClick = { dialog = Dialog.ANCHOR }, modifier = Modifier.fillMaxWidth()) {
            Text("Set balance (re-sync from the real app)")
        }

        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        if (data.entries.isEmpty()) {
            Text("Nothing yet. Add a transaction or set your balance.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            EntryList(data.entries)
        }

        HorizontalDivider()
        SettingsPanel(data)
    }

    if (dialog != Dialog.NONE) {
        AmountDialog(
            kind = dialog,
            onDismiss = { dialog = Dialog.NONE },
            onConfirm = { minor, note ->
                val now = System.currentTimeMillis()
                when (dialog) {
                    Dialog.CREDIT -> LedgerRepository.addManual(EntryType.CREDIT, minor, note, now)
                    Dialog.DEBIT -> LedgerRepository.addManual(EntryType.DEBIT, minor, note, now)
                    Dialog.ANCHOR -> LedgerRepository.setBalance(minor, note, now)
                    Dialog.NONE -> {}
                }
                dialog = Dialog.NONE
            }
        )
    }
}

@Composable
private fun BalanceCard(balanceMinor: Long, lastAnchor: Long?) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("EGP", style = MaterialTheme.typography.titleMedium)
            Text(
                Money.formatMinor(balanceMinor),
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(syncedAgoText(lastAnchor), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun syncedAgoText(lastAnchor: Long?): String {
    if (lastAnchor == null) return "Never synced — set your balance from the real app once."
    val days = ((System.currentTimeMillis() - lastAnchor) / (24 * 60 * 60 * 1000L)).toInt()
    val trust = when {
        days <= 1 -> "high confidence"
        days <= 7 -> "ok"
        else -> "getting stale, consider re-syncing"
    }
    val whenStr = when (days) {
        0 -> "today"
        1 -> "1 day ago"
        else -> "$days days ago"
    }
    return "Last synced $whenStr • $trust"
}

@Composable
private fun EntryList(entries: List<Entry>) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(entries.sortedByDescending { it.timestamp }, key = { it.id }) { e ->
            EntryRow(e)
        }
    }
}

@Composable
private fun EntryRow(e: Entry) {
    val (label, sign) = when (e.type) {
        EntryType.CREDIT -> "Received" to "+"
        EntryType.DEBIT -> (if (e.source == Source.FEE) "Fee" else "Sent") to "-"
        EntryType.ANCHOR -> "Set balance" to "="
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("$label  •  ${e.source.name.lowercase()}", style = MaterialTheme.typography.labelMedium)
                Text(dateFmt.format(Date(e.timestamp)), style = MaterialTheme.typography.bodySmall)
                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
            }
            Text("$sign ${Money.formatMinor(e.amountMinor)}", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { LedgerRepository.deleteEntry(e.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

/** Keeps only digits and a single decimal point, so money fields accept numbers only. */
private fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot == -1) filtered
    else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

@Composable
private fun AmountDialog(kind: Dialog, onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val title = when (kind) {
        Dialog.CREDIT -> "Money received"
        Dialog.DEBIT -> "Money sent"
        Dialog.ANCHOR -> "Set current balance"
        Dialog.NONE -> ""
    }
    val minor = Money.parseToMinor(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = sanitizeAmount(it) },
                    label = { Text("Amount (EGP)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = minor != null && minor > 0,
                onClick = { minor?.let { onConfirm(it, note.trim()) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PasscodeSetupDialog(onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length == PIN_LENGTH && pin.all { it.isDigit() } && pin == confirm

    fun digits(s: String) = s.filter { it.isDigit() }.take(PIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a $PIN_LENGTH-digit passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digits(it) },
                    label = { Text("New passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = digits(it) },
                    label = { Text("Confirm passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (confirm.length == PIN_LENGTH && pin != confirm) {
                    Text("Passcodes don't match", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { LedgerRepository.setPasscode(pin); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsPanel(data: LedgerData) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var expanded by remember { mutableStateOf(false) }
    var showPasscodeSetup by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide settings" else "Settings (security + automation)")
    }
    if (!expanded) return

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

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

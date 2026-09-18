package com.instabalance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

// ---------------------------------------------------------------------------
// Lock screen (keypad, dots, enter, biometric)
// ---------------------------------------------------------------------------

@Composable
internal fun LockScreen(activity: FragmentActivity?, biometricEnabled: Boolean, onUnlocked: () -> Unit) {
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

package com.instabalance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Reads incoming SMS. A single message can arrive in several PDUs, and long texts are split,
 * so we group parts by sender and concatenate before parsing.
 *
 * Note: RECEIVE_SMS is a runtime permission; the UI must request it before this fires.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        LedgerRepository.init(context.applicationContext)
        val data = LedgerRepository.data.value

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (m in messages) {
            val sender = m.displayOriginatingAddress ?: m.originatingAddress ?: "unknown"
            bySender.getOrPut(sender) { StringBuilder() }.append(m.displayMessageBody ?: m.messageBody ?: "")
        }

        val now = System.currentTimeMillis()
        val config = data.smsConfig
        for ((sender, sb) in bySender) {
            val body = sb.toString().trim()
            if (body.isEmpty()) continue

            // Learning mode records regardless of the sender allowlist: you need to SEE a sender's
            // messages before you can decide to allow it, and an allowlist that hides the thing you
            // are trying to configure is a trap.
            if (data.learningMode && looksFinancial(body, config)) {
                LedgerRepository.addCapture("SMS", sender, body, now)
            }

            if (!config.acceptsSender(sender)) continue
            val parsed = BalanceParser.parse(body, config) ?: continue
            LedgerRepository.addAuto(parsed, Source.SMS, "[$sender] $body", now)
        }
    }

    /** A cheap gate so we don't capture unrelated personal SMS in Learning mode. */
    private fun looksFinancial(body: String, config: SmsConfig): Boolean {
        val low = body.lowercase()
        return config.currencyWords.any { it.isNotBlank() && low.contains(it.lowercase()) } ||
            config.creditWords.any { it.isNotBlank() && low.contains(it.lowercase()) } ||
            config.debitWords.any { it.isNotBlank() && low.contains(it.lowercase()) } ||
            config.balanceLabels.any { it.isNotBlank() && low.contains(it.lowercase()) }
    }
}

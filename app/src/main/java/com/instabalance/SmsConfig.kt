package com.instabalance

import kotlinx.serialization.Serializable

/**
 * How to read your bank's messages.
 *
 * The parser used to hardcode EGBANK's exact phrasing, which meant anyone on another bank got an
 * app whose automatic capture silently did nothing: no error, no empty state, just a ledger that
 * never moved on its own. These lists are those hardcoded words turned into data, so the same
 * parser can be pointed at any bank by someone who can read their own SMS.
 *
 * Every default is what the parser did before, so an existing ledger behaves identically until
 * somebody changes something.
 */
@Serializable
data class SmsConfig(
    /** Senders to read. Empty means read every sender, which is the old behaviour. */
    val senders: List<String> = emptyList(),
    /** Words meaning money arrived. */
    val creditWords: List<String> = DEFAULT_CREDIT,
    /** Words meaning money left. */
    val debitWords: List<String> = DEFAULT_DEBIT,
    /** Words that flip the direction: a cancelled purchase is money coming back. */
    val reversalWords: List<String> = DEFAULT_REVERSAL,
    /** Currency markers, used to find the amount. */
    val currencyWords: List<String> = DEFAULT_CURRENCY,
    /** Phrases introducing the amount, which win over a bare number plus currency. */
    val amountLabels: List<String> = DEFAULT_AMOUNT_LABELS,
    /**
     * Phrases introducing your remaining balance. The number after one of these is NOT the
     * transaction, and reading it as one is the single most damaging mistake the parser can make:
     * it would record your whole balance as a purchase.
     */
    val balanceLabels: List<String> = DEFAULT_BALANCE_LABELS,
    /** Words introducing the other party, used to learn merchant rules. */
    val merchantLabels: List<String> = DEFAULT_MERCHANT_LABELS,
    /** A message containing any of these is never a transaction, whatever else it says. */
    val ignoreWords: List<String> = DEFAULT_IGNORE,
) {
    companion object {
        val DEFAULT_CREDIT = listOf("credited", "received", "ايداع", "إيداع", "اضافة", "إضافة")
        val DEFAULT_DEBIT = listOf(
            "charged", "debited", "sent", "paid", "شراء", "سحب", "خصم",
        )
        val DEFAULT_REVERSAL = listOf("الغاء", "إلغاء", "reversed", "refund of", "cancelled")
        val DEFAULT_CURRENCY = listOf("EGP", "جم", "ج.م", "ج", "LE")
        val DEFAULT_AMOUNT_LABELS = listOf("بمبلغ", "مبلغ", "by", "amount of")
        val DEFAULT_BALANCE_LABELS = listOf(
            "الرصيد المتاح", "الرصيد", "available balance", "avail bal", "balance is",
        )
        val DEFAULT_MERCHANT_LABELS = listOf("من", "from", "to", "at")
        val DEFAULT_IGNORE = listOf("otp", "one time password", "رمز التحقق", "كود التحقق")
    }

    /** True when this sender should be read at all. An empty allowlist reads everyone. */
    fun acceptsSender(sender: String): Boolean {
        if (senders.isEmpty()) return true
        val s = sender.uppercase()
        return senders.any { it.isNotBlank() && s.contains(it.uppercase()) }
    }
}

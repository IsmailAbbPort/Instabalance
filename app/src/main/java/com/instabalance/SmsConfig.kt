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
    /**
     * Senders to read. Empty means read every sender, which is what a fresh install used to do and
     * is still a real choice for anyone on a bank these defaults do not know.
     *
     * Defaulting to the bank rather than to everyone, because "read everyone" means every shortcode
     * in the country gets parsed, and an ad for a sandwich at 175 EGP has a number, a currency and
     * the word خصم in it. That reads as a purchase, and a wrong purchase is worse than a missed one.
     */
    val senders: List<String> = DEFAULT_SENDERS,
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
        /** EGBANK's sender ID. One entry, because this is the bank the defaults below describe. */
        val DEFAULT_SENDERS = listOf("EGBANK")

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
        /**
         * A link is the tell. Every real EGBANK transaction message is a statement of fact with a
         * reference number; the ones carrying a short link are selling something, and an ad with a
         * price in it parses as cleanly as a purchase does. Kept here rather than as a rule in the
         * parser so it is visible and removable, like every other word the parser keys on.
         */
        val DEFAULT_LINK_WORDS = listOf("http", "www.")

        val DEFAULT_IGNORE =
            listOf("otp", "one time password", "رمز التحقق", "كود التحقق") + DEFAULT_LINK_WORDS
    }

    /** True when this sender should be read at all. An empty allowlist reads everyone. */
    fun acceptsSender(sender: String): Boolean {
        if (senders.isEmpty()) return true
        val s = sender.uppercase()
        return senders.any { it.isNotBlank() && s.contains(it.uppercase()) }
    }
}

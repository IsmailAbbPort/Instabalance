package com.instabalance

/**
 * Turns a bank SMS or a payment notification into a transaction, or null if it isn't one.
 *
 * Every word this keys on comes from [SmsConfig], whose defaults are EGBANK's wording. That is the
 * whole point: the shapes below are common to bank SMS everywhere (a direction word, an amount
 * next to a currency, a remaining balance that must not be mistaken for the amount, a counterparty
 * after "from"), while the exact words are not. Someone on another bank can read their own messages
 * and fill in their own words, rather than needing this file edited.
 *
 * Real EGBANK formats the defaults handle:
 *   AR debit:    "تم سحب 100جم من حساب 0057*100 من ATM  الرصيد المتاح 2091.36جم"
 *   AR debit:    "تم الشراء بمبلغ 165جم على الكارت رقم +++0954 من PAYMOB ..."
 *   AR reversal: "تم الغاء الشراء بمبلغ 23.8جم على الكارت رقم +++0954 من ..."   (money back -> credit)
 *   EN debit:    "Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# ..."
 *   EN credit:   "Your account was credited by EGP 100 on 22-07 22:51 IPN REF# ... from NAME"
 */
object BalanceParser {

    private const val NUMBER = """[0-9][0-9,]*(?:\.[0-9]{1,2})?"""

    fun parse(rawText: String, config: SmsConfig = SmsConfig()): ParsedTxn? {
        if (rawText.isBlank()) return null
        val text = normalizeArabicDigits(rawText)
        val low = text.lowercase()

        // An OTP contains a number and often the word "account". Gate it out first, because
        // recording a verification code as a transaction is both wrong and alarming.
        if (config.ignoreWords.any { it.isNotBlank() && low.contains(it.lowercase()) }) return null

        val type = direction(low, text, config) ?: return null

        // Strip "available balance 2091.36 EGP" BEFORE looking for the amount. Without this the
        // parser reads your remaining balance as the size of the purchase.
        val withoutBalance = stripBalanceClauses(text, config)

        val minor = findAmount(withoutBalance, config) ?: return null
        if (minor <= 0) return null

        return ParsedTxn(
            type = type,
            amountMinor = minor,
            merchant = findMerchant(text, withoutBalance, config),
            reportedBalanceMinor = findReportedBalance(text, config),
        )
    }

    /**
     * Credit or debit, with reversals flipping the sign: a cancelled purchase is money coming back.
     * Null when the message says both or neither, because guessing the direction of money is worse
     * than not recording it.
     */
    private fun direction(low: String, text: String, config: SmsConfig): EntryType? {
        fun mentions(words: List<String>) = words.any { w ->
            w.isNotBlank() && (low.contains(w.lowercase()) || text.contains(w))
        }

        val credit = mentions(config.creditWords)
        val debit = mentions(config.debitWords)
        if (credit == debit) return null

        val reversed = mentions(config.reversalWords)
        val base = if (credit) EntryType.CREDIT else EntryType.DEBIT
        return if (!reversed) base else when (base) {
            EntryType.CREDIT -> EntryType.DEBIT
            else -> EntryType.CREDIT
        }
    }

    private fun balanceClauseRegex(label: String, config: SmsConfig): Regex {
        val currency = currencyAlternation(config)
        return Regex(
            """${Regex.escape(label)}\s*:?\s*(?:$currency)?\s*($NUMBER)\s*(?:$currency)?""",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun stripBalanceClauses(text: String, config: SmsConfig): String {
        var out = text
        config.balanceLabels.filter { it.isNotBlank() }.forEach { label ->
            out = out.replace(balanceClauseRegex(label, config), " ")
        }
        return out
    }

    /**
     * The balance the message itself reports, where it carries one.
     *
     * The clause is already being found, to keep the remaining balance from being read as the size
     * of the purchase. This only stops throwing the number away: the bank is telling you what the
     * account holds, which is the exact figure the manual re-sync exists to go and fetch by hand.
     *
     * The longest label is tried first, because the defaults include labels that are prefixes of
     * other labels ("الرصيد" and "الرصيد المتاح"). Whichever matches, it must be the same number
     * [stripBalanceClauses] removed, or the amount and the balance would come from different
     * readings of one sentence.
     */
    private fun findReportedBalance(text: String, config: SmsConfig): Long? {
        config.balanceLabels
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
            .forEach { label ->
                balanceClauseRegex(label, config).find(text)?.let { m ->
                    val minor = Money.parseToMinor(m.groupValues[1])
                    if (minor != null && minor > 0) return minor
                }
            }
        return null
    }

    /**
     * A labelled amount ("by EGP 100", "بمبلغ 165") wins over a bare number beside a currency,
     * because a message often carries both a card number and a date that a looser match would grab.
     */
    private fun findAmount(text: String, config: SmsConfig): Long? {
        val currency = currencyAlternation(config)

        config.amountLabels.filter { it.isNotBlank() }.forEach { label ->
            val re = Regex(
                """${Regex.escape(label)}\s*:?\s*(?:$currency)?\s*($NUMBER)""",
                RegexOption.IGNORE_CASE,
            )
            re.find(text)?.let { return Money.parseToMinor(it.groupValues[1]) }
        }

        if (currency.isNotEmpty()) {
            // Currency on either side: "EGP 100" and "100 EGP" are both common.
            Regex("""(?:$currency)\s*($NUMBER)""", RegexOption.IGNORE_CASE).find(text)
                ?.let { return Money.parseToMinor(it.groupValues[1]) }
            Regex("""($NUMBER)\s*(?:$currency)""", RegexOption.IGNORE_CASE).find(text)
                ?.let { return Money.parseToMinor(it.groupValues[1]) }
        }
        return null
    }

    /** Words that follow a merchant label without naming anybody. */
    private val structural = setOf("حساب", "الكارت", "رقم", "account", "card", "no", "ref")

    /**
     * The bank's own tag, "[EGBANK]", which it inserts wherever the message happens to be assembled,
     * including between the masked card number and its last four digits. Removed before the shop is
     * looked for, or the card-purchase shape below never matches a real message.
     */
    private val BANK_TAG = Regex("""\[[^\[\]\n]{1,24}\]""")

    /**
     * The other party, where the message names one. Card purchases do; an InstaPay transfer seen as
     * a bank SMS does not, and that is a fact about the message rather than a gap here.
     *
     * Never able to fail a transaction: the amount and direction are the money, this is a
     * convenience for the category rules.
     */
    private fun findMerchant(fullRaw: String, withoutBalanceRaw: String, config: SmsConfig): String? {
        val full = fullRaw.replace(BANK_TAG, " ")
        val withoutBalance = withoutBalanceRaw.replace(BANK_TAG, " ")

        // An address wins outright: it is unambiguous and it is the whole counterparty.
        Regex("""\b(?:from|to)\s+(\S+@\S+)""", RegexOption.IGNORE_CASE).find(full)
            ?.let { return clean(it.groupValues[1]) }

        // A card purchase names the shop as the rest of the line after the card number.
        //
        // The card number is matched as several runs, because the bank breaks it across a line
        // ("++" then "0954"). The shop is then everything to the end of ITS line: `\s*` reaches
        // across the blank lines to find it, and `.` not matching a newline is what stops the
        // capture before a balance sentence printed underneath.
        Regex(
            """(?:الكارت|card)\s*(?:رقم|no\.?|#)?\s*[+*x0-9]+(?:\s+[+*x0-9]+)*\s*(?:من|from|at)\s*(.+)""",
            RegexOption.IGNORE_CASE,
        ).find(full)?.let { m -> clean(m.groupValues[1])?.let { return it } }

        Regex("""\bfrom\s+(.+?)(?:\s+for\s+details\b|\s+IPN\b|$)""", RegexOption.IGNORE_CASE)
            .find(full)?.let { m -> clean(m.groupValues[1])?.let { return it } }

        // Otherwise the last "from X" that names something, which is what turns an ATM withdrawal
        // into one rule covering every cash withdrawal.
        config.merchantLabels.filter { it.isNotBlank() }.forEach { label ->
            val candidates = Regex("""${Regex.escape(label)}\s+([^\s]+)""", RegexOption.IGNORE_CASE)
                .findAll(withoutBalance)
                .map { it.groupValues[1] }
                .filterNot { it.lowercase() in structural || it in structural }
                .toList()
            candidates.lastOrNull()?.let { clean(it)?.let { c -> return c } }
        }
        return null
    }

    private fun currencyAlternation(config: SmsConfig): String =
        config.currencyWords.filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }

    /** Collapses whitespace and drops anything too short to be a name. */
    private fun clean(raw: String?): String? {
        val s = raw?.replace(Regex("""\s+"""), " ")?.trim()?.trimEnd('.', ',', '-') ?: return null
        return s.takeIf { it.length >= 2 }
    }

    /** Arabic-Indic digits (٠-٩ and ۰-۹) -> ASCII 0-9 so the regex and Money can read them. */
    internal fun normalizeArabicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> ('0' + (ch - '٠'))
                    in '۰'..'۹' -> ('0' + (ch - '۰'))
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}

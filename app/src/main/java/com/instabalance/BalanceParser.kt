package com.instabalance

/**
 * Turns an EGBANK SMS or an InstaPay notification into a transaction, or null if it isn't one.
 *
 * Real EGBANK formats this handles (from live messages):
 *   AR debit:    "تم سحب 100جم من حساب 0057*100 من ATM  الرصيد المتاح 2091.36جم"
 *   AR debit:    "تم الشراء بمبلغ 165جم على الكارت رقم +++0954 من PAYMOB ..."
 *   AR reversal: "تم الغاء الشراء بمبلغ 23.8جم على الكارت رقم +++0954 من ..."   (money back -> credit)
 *   AR reversal: "تم إلغاء خصم مبلغ 100جم من ATM على حساب رقم 0057*100"          (money back -> credit)
 *   EN debit:    "Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# ..."
 *   EN credit:   "Your account was credited by EGP 100 on 22-07 22:51 IPN REF# ..."
 *
 * InstaPay notification wording is not confirmed yet, so the English branch below is a best-effort
 * guess (received/sent/credited/charged). Use Learning mode to capture the real text, then tighten.
 */
object BalanceParser {

    // "EGP 1105" or "100 EGP" (either order).
    private val englishAmount = Regex(
        """egp\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*egp""",
        RegexOption.IGNORE_CASE
    )

    // Amount stated after مبلغ / بمبلغ, e.g. "بمبلغ 23.8جم".
    private val arabicAmountLabelled = Regex("""(?:بمبلغ|مبلغ)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    // Any "<number> ج" (covers جم / ج.م). Used only after the balance clause is stripped out.
    private val arabicAmountBeforeCurrency = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*ج""")
    // The available-balance clause, e.g. "الرصيد المتاح 2091.36جم" — must NOT be read as the amount.
    private val arabicBalanceClause = Regex("""الرصيد\s*المتاح\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*ج""")

    // ---- Merchant / counterparty extraction -------------------------------
    // Only some messages carry one. A card purchase names the shop; an InstaPay send (IPN REF#)
    // names nobody at all, which is why roughly half of debits can never be auto-categorised.

    /** "على الكارت رقم +++0954 من PAYMOB RAF SPECIALIT CAIRO" -> the shop. */
    private val arabicCardMerchant = Regex("""الكارت\s*رقم\s*[+*0-9]+\s*من\s+(.+)$""")
    /**
     * "من ATM" on a withdrawal. Every "من X" is considered and the last wins, because the message
     * says "from account 0057*100 from ATM" and the ATM is the part worth learning. It cannot be
     * anchored to the end of the string: stripping the balance clause stops at "ج" and leaves the
     * "م" of "جم" behind.
     */
    private val arabicFromToken = Regex("""من\s+([^\s]+)""")

    /** Structural words that follow "من" without naming anybody. */
    private val arabicStructural = setOf("حساب", "الكارت", "رقم")
    /** "was credited by EGP 100 ... from NANICE AHMED for details ..." */
    private val englishFrom = Regex("""\bfrom\s+(.+?)(?:\s+for\s+details\b|\s+IPN\b|$)""", RegexOption.IGNORE_CASE)
    /** An InstaPay address, e.g. "to naniiceeabbas@instapay". */
    private val instapayAddress = Regex("""\b(?:from|to)\s+(\S+@\S+)""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedTxn? {
        if (rawText.isBlank()) return null
        val t = normalizeArabicDigits(rawText)
        val low = t.lowercase()

        // ---- English (EGBANK IPN + likely InstaPay) ----
        val engCredit = low.contains("credited") || low.contains("received")
        val engDebit = low.contains("charged") || low.contains("debited") ||
            low.contains("sent") || low.contains("paid")
        if (engCredit != engDebit) {
            val minor = firstEnglishAmount(t) ?: return null
            if (minor <= 0) return null
            return ParsedTxn(
                if (engCredit) EntryType.CREDIT else EntryType.DEBIT,
                minor,
                englishMerchant(t),
            )
        }

        // ---- Arabic (EGBANK) ----
        val hasCancel = t.contains("الغاء") || t.contains("إلغاء")
        val isPurchase = t.contains("شراء")
        val isWithdraw = t.contains("سحب")
        val isDeduction = t.contains("خصم")
        val isDeposit = t.contains("ايداع") || t.contains("إيداع") ||
            t.contains("اضافة") || t.contains("إضافة")

        val type = when {
            // A cancelled purchase/withdrawal/deduction returns money -> credit.
            hasCancel && (isPurchase || isWithdraw || isDeduction) -> EntryType.CREDIT
            isDeposit -> EntryType.CREDIT
            isPurchase || isWithdraw || isDeduction -> EntryType.DEBIT
            else -> return null
        }

        val withoutBalance = t.replace(arabicBalanceClause, " ")
        val amountStr = arabicAmountLabelled.find(withoutBalance)?.groupValues?.get(1)
            ?: arabicAmountBeforeCurrency.find(withoutBalance)?.groupValues?.get(1)
            ?: return null
        val minor = Money.parseToMinor(amountStr) ?: return null
        if (minor <= 0) return null
        return ParsedTxn(type, minor, arabicMerchant(t, withoutBalance))
    }

    private fun firstEnglishAmount(text: String): Long? {
        val m = englishAmount.find(text) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return Money.parseToMinor(raw)
    }

    /**
     * An InstaPay address if there is one, else a plain "from NAME". A send seen only as an EGBANK
     * IPN SMS has neither, and returns null: there is genuinely nothing in the text to learn from.
     */
    private fun englishMerchant(text: String): String? =
        clean(instapayAddress.find(text)?.groupValues?.get(1))
            ?: clean(englishFrom.find(text)?.groupValues?.get(1))

    /** The shop on a card purchase, else the "من X" token (typically ATM) on a withdrawal. */
    private fun arabicMerchant(full: String, withoutBalance: String): String? =
        clean(arabicCardMerchant.find(full)?.groupValues?.get(1))
            ?: clean(
                arabicFromToken.findAll(withoutBalance)
                    .map { it.groupValues[1] }
                    .filterNot { it in arabicStructural }
                    .lastOrNull()
            )

    /**
     * Collapses whitespace and drops anything too short to be a name. Merchant extraction is a
     * convenience: it must never be able to fail a transaction, so every path here returns null
     * rather than throwing.
     */
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

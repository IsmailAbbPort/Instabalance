package com.instabalance

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * All money is stored as an integer number of piastres (1 EGP = 100 piastres).
 * Never use Double for money: 0.1 + 0.2 != 0.3 in floating point, and that error
 * compounds over a month of transactions.
 */
object Money {

    /** "1,234.50" -> 123450 piastres. Returns null if it can't be parsed. */
    fun parseToMinor(raw: String): Long? {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            // longValueExact, not toLong: toLong narrows silently, so a number too big for a Long
            // comes back as some unrelated small figure rather than as a refusal. A bank message
            // reading 184,467,440,737,095,526.16 was being recorded as 10.00 EGP, and the same
            // function reads the balance the automatic re-sync pins the ledger to.
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * 123450 -> "1.2k". For chart axes, where the full "1,234.50" is wider than the space and the
     * reader only needs the order of magnitude. Never used for a figure someone might act on.
     */
    /**
     * "0.29" -> 29 basis points, or null if it is not a percentage. Rounded rather than truncated:
     * 0.29 * 100 is 28.999999 in binary floating point, so a plain toInt() silently stores the fee
     * as 0.28%, and every send is then charged slightly wrong forever.
     */
    fun percentToBasisPoints(raw: String): Int? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        if (v < 0) return null
        return Math.round(v * 100).toInt()
    }

    /**
     * Whole pounds above ten, because that is all a chart axis has room for and all it needs to
     * say. Below ten, the piastres are the figure: a day where you spent 0.50 drew an axis of five
     * zeros, which is not a scale, it is a wall. Trailing ".00" is still dropped, so a scale that
     * happens to land on whole pounds does not grow two digits for nothing.
     */
    fun formatCompactMinor(minor: Long): String {
        val abs = kotlin.math.abs(minor)
        val pounds = abs / 100
        val sign = if (minor < 0) "-" else ""
        return when {
            pounds >= 1_000_000 -> "$sign${pounds / 1_000_000}.${(pounds % 1_000_000) / 100_000}m"
            pounds >= 1_000 -> "$sign${pounds / 1_000}.${(pounds % 1_000) / 100}k"
            pounds >= 10 || abs % 100 == 0L -> "$sign$pounds"
            else -> sign + "%.2f".format(abs / 100.0)
        }
    }

    /** 123450 -> "1,234.50". */
    fun formatMinor(minor: Long): String {
        val negative = minor < 0
        val abs = kotlin.math.abs(minor)
        val pounds = abs / 100
        val piastres = abs % 100
        val grouped = "%,d".format(pounds)
        val sign = if (negative) "-" else ""
        return "$sign$grouped.%02d".format(piastres)
    }
}

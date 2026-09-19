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
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (e: NumberFormatException) {
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

    fun formatCompactMinor(minor: Long): String {
        val pounds = kotlin.math.abs(minor) / 100
        val sign = if (minor < 0) "-" else ""
        return when {
            pounds >= 1_000_000 -> "$sign${pounds / 1_000_000}.${(pounds % 1_000_000) / 100_000}m"
            pounds >= 1_000 -> "$sign${pounds / 1_000}.${(pounds % 1_000) / 100}k"
            else -> "$sign$pounds"
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

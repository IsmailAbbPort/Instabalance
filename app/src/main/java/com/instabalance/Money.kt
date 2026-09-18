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

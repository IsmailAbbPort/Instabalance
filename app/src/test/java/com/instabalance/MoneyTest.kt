package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    // ---- Fee percentage. Every send is charged with this, so a rounding slip is permanent ----

    @Test fun percentRoundsRatherThanTruncating() {
        // 0.29 * 100 is 28.999999 in binary floating point. toInt() would store 0.28%.
        assertEquals(29, Money.percentToBasisPoints("0.29"))
        assertEquals(10, Money.percentToBasisPoints("0.1"))
        assertEquals(250, Money.percentToBasisPoints("2.5"))
        assertEquals(0, Money.percentToBasisPoints("0"))
    }

    @Test fun percentRejectsNonsenseRatherThanReadingItAsZero() {
        assertNull(Money.percentToBasisPoints(""))
        assertNull(Money.percentToBasisPoints("."))
        assertNull(Money.percentToBasisPoints("abc"))
        assertNull(Money.percentToBasisPoints("-1"))
    }

    // ---- A lone "." passes the amount field's filter, so it has to parse to null ----

    @Test fun aLoneDotIsNotAnAmount() {
        // sanitizeAmount permits it, and the budget screen treated a null parse as "no budget",
        // which silently deleted the budget when the user meant to set one.
        assertNull(Money.parseToMinor("."))
        assertNull(Money.parseToMinor(""))
        assertNull(Money.parseToMinor("   "))
    }

    // ---- Compact form, used only on chart axes where the full figure will not fit ----

    @Test fun compactShowsWholePoundsUnderAThousand() {
        assertEquals("0", Money.formatCompactMinor(0))
        assertEquals("7", Money.formatCompactMinor(750))          // 7.50 EGP
        assertEquals("999", Money.formatCompactMinor(99_999))     // 999.99 EGP
    }

    @Test fun compactSwitchesToThousands() {
        assertEquals("1.0k", Money.formatCompactMinor(100_000))   // 1,000 EGP
        assertEquals("1.5k", Money.formatCompactMinor(150_000))
        assertEquals("12.3k", Money.formatCompactMinor(1_234_500))
    }

    @Test fun compactSwitchesToMillions() {
        assertEquals("1.0m", Money.formatCompactMinor(100_000_000))
        assertEquals("2.5m", Money.formatCompactMinor(250_000_000))
    }

    @Test fun compactKeepsTheSign() {
        assertEquals("-1.5k", Money.formatCompactMinor(-150_000))
    }

    @Test fun parsesPlainInteger() {
        assertEquals(10000L, Money.parseToMinor("100"))
    }

    @Test fun parsesDecimals() {
        assertEquals(10050L, Money.parseToMinor("100.5"))
        assertEquals(10099L, Money.parseToMinor("100.99"))
    }

    @Test fun parsesThousandsSeparators() {
        assertEquals(123456L, Money.parseToMinor("1,234.56"))
        assertEquals(200000000L, Money.parseToMinor("2,000,000"))
    }

    @Test fun rejectsGarbage() {
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor(""))
    }

    @Test fun formatsWithGroupingAndTwoDecimals() {
        assertEquals("100.00", Money.formatMinor(10000))
        assertEquals("1,234.56", Money.formatMinor(123456))
        assertEquals("0.50", Money.formatMinor(50))
    }

    @Test fun formatsNegatives() {
        assertEquals("-50.00", Money.formatMinor(-5000))
    }

    @Test fun roundTrips() {
        val samples = listOf("0.50", "1.00", "999.99", "12,345.67")
        for (s in samples) {
            val minor = Money.parseToMinor(s)!!
            assertEquals(s, Money.formatMinor(minor))
        }
    }
}

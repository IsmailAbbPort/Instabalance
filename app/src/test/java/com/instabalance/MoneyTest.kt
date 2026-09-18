package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

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

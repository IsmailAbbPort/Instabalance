package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test fun morningRunsFromFiveToNoon() {
        assertEquals("Good Morning", greetingForHour(5))
        assertEquals("Good Morning", greetingForHour(9))
        assertEquals("Good Morning", greetingForHour(11))
    }

    @Test fun afternoonRunsFromNoonToFive() {
        assertEquals("Good Afternoon", greetingForHour(12))
        assertEquals("Good Afternoon", greetingForHour(16))
    }

    @Test fun eveningCoversTheEveningAndTheSmallHours() {
        assertEquals("Good Evening", greetingForHour(17))
        assertEquals("Good Evening", greetingForHour(23))
        assertEquals("Good Evening", greetingForHour(0))
        assertEquals("Good Evening", greetingForHour(4))
    }

    @Test fun everyHourOfTheDayIsCovered() {
        // A gap would show an empty greeting rather than throwing, which is the kind of thing
        // nobody notices until someone opens the app at that hour.
        (0..23).forEach { h ->
            assertEquals("hour $h", true, greetingForHour(h).startsWith("Good "))
        }
    }
}

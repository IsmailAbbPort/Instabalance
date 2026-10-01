package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reopening where you left off is right for a few minutes and wrong by the next morning. By then
 * the app should open on your balance like any other day, rather than on a filtered list with rows
 * missing for a reason you have long forgotten setting.
 */
class SessionResumeTest {

    private val now = 1_790_000_000_000L
    private val window = SessionResume.WINDOW_MS

    private val filter = TransactionFilter(
        direction = Direction.EXPENSE,
        categoryIds = setOf("groceries", null),
        query = "seoudi",
        sortBy = SortBy.LARGEST,
    )

    private val routes = listOf(Route.HOME, Route.TRANSACTIONS)

    // ---- the window ---------------------------------------------------------

    @Test fun fifteenMinutesIsTheWindow() {
        assertEquals(15 * 60 * 1000L, SessionResume.WINDOW_MS)
    }

    @Test fun amomentAgoIsFresh() {
        assertTrue(SessionResume.isFresh(now - 1_000, now))
    }

    @Test fun exactlyAtTheWindowIsStillFresh() {
        assertTrue(SessionResume.isFresh(now - window, now))
    }

    @Test fun oneMillisecondPastTheWindowIsStale() {
        assertFalse(SessionResume.isFresh(now - window - 1, now))
    }

    @Test fun thisMorningIsStale() {
        assertFalse(SessionResume.isFresh(now - 8 * 60 * 60 * 1000L, now))
    }

    @Test fun aTimeInTheFutureIsStale() {
        // A clock that moved backwards, not a session from later today. Trusting it would pin the
        // app to one screen for as long as the clock stayed wrong.
        assertFalse(SessionResume.isFresh(now + 60_000, now))
    }

    // ---- round trip ---------------------------------------------------------

    @Test fun theScreenAndTheFilterBothComeBack() {
        val text = SessionResume.encode(routes, filter, now)

        val out = SessionResume.decode(text, now + 60_000)

        assertNotNull(out)
        assertEquals(routes, out!!.routes)
        assertEquals(filter, out.filter)
    }

    @Test fun theWholeBackStackComesBackSoBackStillWorks() {
        val deep = listOf(Route.HOME, Route.SETTINGS, Route.RULES)

        val out = SessionResume.decode(SessionResume.encode(deep, TransactionFilter(), now), now)

        assertEquals(deep, out?.routes)
    }

    @Test fun anOldSessionIsRefusedEvenThoughItDecodes() {
        val text = SessionResume.encode(routes, filter, now)

        assertNull(SessionResume.decode(text, now + window + 1))
    }

    // ---- never crash on the way in ------------------------------------------

    @Test fun rubbishIsIgnoredRatherThanThrown() {
        assertNull(SessionResume.decode("not json", now))
        assertNull(SessionResume.decode("", now))
        assertNull(SessionResume.decode("{}", now))
    }

    @Test fun aScreenThisBuildNoLongerHasIsIgnored() {
        // A route removed in a later build would otherwise throw on valueOf during startup, which
        // is the worst possible moment.
        val text = """{"routes":["HOME","GHOST_SCREEN"],"filter":[],"savedAt":$now}"""

        assertNull(SessionResume.decode(text, now))
    }

    @Test fun aStackThatDoesNotStartAtHomeIsIgnored() {
        // Back has to have somewhere to land. A stack rooted anywhere else is corrupt.
        val text = """{"routes":["SETTINGS"],"filter":[],"savedAt":$now}"""

        assertNull(SessionResume.decode(text, now))
    }

    @Test fun anEmptyStackIsIgnored() {
        assertNull(SessionResume.decode("""{"routes":[],"filter":[],"savedAt":$now}""", now))
    }

    @Test fun aSessionWithAnUnreadableFilterStillRestoresTheScreen() {
        // The screen is the valuable half. Losing the filter is a small annoyance; refusing to
        // open where you were because one field went bad is a larger one.
        val text = """{"routes":["HOME","TRANSACTIONS"],"filter":["NONSENSE"],"savedAt":$now}"""

        val out = SessionResume.decode(text, now)

        assertEquals(listOf(Route.HOME, Route.TRANSACTIONS), out?.routes)
        assertEquals(TransactionFilter(), out?.filter)
    }
}

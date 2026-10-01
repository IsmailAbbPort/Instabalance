package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moment anybody knows what a nameless 350 EGP transfer was is the moment it lands. By the time
 * the inbox is opened it is just a number.
 *
 * Built to be ignorable, so most of this is about when it must stay quiet.
 */
class TriageTest {

    private var n = 0

    private fun entry(
        type: EntryType = EntryType.DEBIT,
        minor: Long = 35_000,
        ts: Long = 1_000,
        merchant: String? = null,
        categoryId: String? = null,
        fromRule: Boolean = false,
        source: Source = Source.SMS,
    ) = Entry(
        id = "e${n++}", type = type, amountMinor = minor, timestamp = ts, source = source,
        merchant = merchant, categoryId = categoryId, categoryFromRule = fromRule,
    )

    // ---- when it stays quiet ------------------------------------------------

    @Test fun somethingAruleAlreadyFiledIsNeverOffered() {
        // The better your rules get, the quieter this becomes. That is the design.
        assertFalse(worthTriaging(entry(categoryId = "groceries", fromRule = true)))
    }

    @Test fun somethingYouAlreadyFiledIsNeverOffered() {
        assertFalse(worthTriaging(entry(categoryId = "rent")))
    }

    @Test fun theInstapayFeeLineIsNeverOffered() {
        // It arrives pre-categorised, and a notification per fee would double the noise per send.
        assertFalse(worthTriaging(entry(source = Source.FEE, categoryId = Categories.FEES)))
        assertFalse(worthTriaging(entry(source = Source.FEE)))
    }

    @Test fun ananchorIsNotMoneyMoving() {
        assertFalse(worthTriaging(entry(type = EntryType.ANCHOR)))
    }

    @Test fun anuncategorisedTransferIsExactlyWhatThisIsFor() {
        assertTrue(worthTriaging(entry()))
    }

    // ---- what it offers -----------------------------------------------------

    @Test fun threeChoicesAtMostBecauseAndroidShowsThree() {
        val history = (1..8).map { entry(categoryId = "c$it", ts = it * 1_000L) }
        val categories = (1..8).map { Category("c$it", "C$it", CategoryKind.EXPENSE, it) }

        val out = triageChoices(history, categories + Categories.PRESETS, entry())

        assertEquals(3, out.size)
    }

    @Test fun whereThisExactAmountWentLastTimeComesFirst() {
        val twin = entry(minor = 35_000, categoryId = "rent", ts = 1_000)
        val recent = entry(minor = 99_999, categoryId = "groceries", ts = 9_000)
        val now = entry(minor = 35_000, ts = 10_000)

        val out = triageChoices(listOf(twin, recent, now), Categories.PRESETS, now)

        assertEquals("rent", out.first().id)
    }

    @Test fun thereIsAlwaysAwayToFinish() {
        // With no history at all there is still Other, or the notification would be three buttons
        // short of useful and you would have to open the app anyway.
        val e = entry()

        val out = triageChoices(listOf(e), Categories.PRESETS, e)

        assertEquals(listOf(Categories.OTHER_EXPENSE), out.map { it.id })
    }

    @Test fun moneyInIsOfferedIncomeCategories() {
        val e = entry(type = EntryType.CREDIT)

        val out = triageChoices(listOf(e), Categories.PRESETS, e)

        assertEquals(listOf(Categories.OTHER_INCOME), out.map { it.id })
        assertTrue(out.none { it.kind == CategoryKind.EXPENSE })
    }

    @Test fun ahiddenCategoryIsNotOffered() {
        val past = entry(categoryId = "rent", ts = 1_000)
        val now = entry(ts = 2_000)
        val hiddenRent = Categories.PRESETS.map {
            if (it.id == "rent") it.copy(hidden = true) else it
        }

        val out = triageChoices(listOf(past, now), hiddenRent, now)

        assertTrue(out.none { it.id == "rent" })
    }

    @Test fun ananchorIsOfferedNothingAtAll() {
        val anchor = entry(type = EntryType.ANCHOR)

        assertTrue(triageChoices(listOf(anchor), Categories.PRESETS, anchor).isEmpty())
    }

    // ---- what it says -------------------------------------------------------

    @Test fun thetitleIsTheAmountAndItsDirection() {
        assertEquals("- 350.00 EGP", triageTitle(entry()))
        assertEquals("+ 350.00 EGP", triageTitle(entry(type = EntryType.CREDIT)))
    }

    @Test fun ashopIsNamedWhenTheMessageNamedOne() {
        assertTrue(triageBody(entry(merchant = "SEOUDI-ROXY")).contains("SEOUDI-ROXY"))
    }

    @Test fun thebanksMaskIsNotAname() {
        // "from **. File it?" would be nonsense.
        val body = triageBody(entry(type = EntryType.CREDIT, merchant = "**"))

        assertFalse(body, body.contains("**"))
        assertTrue(body, body.contains("Money in"))
    }

    // ---- the notification id ------------------------------------------------

    @Test fun eachEntryGetsItsOwnNotification() {
        // Two transfers arriving together must not overwrite one another in the shade.
        assertTrue(TriageAlerts.notificationId("a") != TriageAlerts.notificationId("b"))
    }

    @Test fun theidIsStableAcrossProcesses() {
        // It has to be recomputable by the receiver, which runs in a different process invocation
        // from the one that posted.
        assertEquals(TriageAlerts.notificationId("entry-1"), TriageAlerts.notificationId("entry-1"))
    }
}

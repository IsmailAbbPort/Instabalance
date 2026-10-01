package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An InstaPay transfer seen as a bank SMS names nobody, so no merchant rule can ever reach it and
 * every one has to be filed by hand. The amount is the only thing left to recognise it by.
 */
class AmountTwinTest {

    private var n = 0

    private fun entry(
        type: EntryType = EntryType.DEBIT,
        minor: Long = 350_000,
        ts: Long = 1_000,
        merchant: String? = null,
        categoryId: String? = null,
        fromRule: Boolean = false,
    ) = Entry(
        id = "e${n++}",
        type = type,
        amountMinor = minor,
        timestamp = ts,
        source = Source.SMS,
        merchant = merchant,
        categoryId = categoryId,
        categoryFromRule = fromRule,
    )

    @Test fun theCategoryTheSameAmountWentToLastTime() {
        val past = entry(ts = 1_000, categoryId = "rent")
        val now = entry(ts = 9_000)

        assertEquals(listOf("rent"), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun newestFirst() {
        val older = entry(ts = 1_000, categoryId = "friends")
        val newer = entry(ts = 5_000, categoryId = "rent")
        val now = entry(ts = 9_000)

        assertEquals(listOf("rent", "friends"), amountTwinCategoryIds(listOf(older, newer, now), now))
    }

    @Test fun theSameCategoryTwiceIsStillOneChip() {
        val a = entry(ts = 1_000, categoryId = "rent")
        val b = entry(ts = 5_000, categoryId = "rent")
        val now = entry(ts = 9_000)

        assertEquals(listOf("rent"), amountTwinCategoryIds(listOf(a, b, now), now))
    }

    @Test fun cappedSoItCannotCrowdOutTheRecents() {
        val entries = (1..5).map { entry(ts = it * 1_000L, categoryId = "c$it") }
        val now = entry(ts = 9_000)

        assertEquals(2, amountTwinCategoryIds(entries + now, now).size)
    }

    // ---- what it refuses to look at -----------------------------------------

    @Test fun adifferentAmountSaysNothing() {
        val past = entry(minor = 350_001, categoryId = "rent")
        val now = entry(minor = 350_000)

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun moneyGoingTheOtherWaySaysNothing() {
        // 3,500 received and 3,500 sent are not the same event, however alike the numbers look.
        val past = entry(type = EntryType.CREDIT, categoryId = "salary")
        val now = entry(type = EntryType.DEBIT)

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun apurchaseAtAnamedShopSaysNothing() {
        // 500 at Seoudi tells you nothing about 500 sent to a person. Named entries have rules.
        val past = entry(merchant = "SEOUDI MARKET", categoryId = "groceries")
        val now = entry()

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun thebanksMaskStillCountsAsNamingNobody() {
        // "from **" is what a real InstaPay transfer looks like as a bank SMS, and those are the
        // entries this exists for. Reading "**" as a name would switch the feature off entirely.
        val past = entry(merchant = "**", categoryId = "rent")
        val now = entry(merchant = "**")

        assertEquals(listOf("rent"), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun anentryThatAlreadyHasAnameIsLeftToItsRules() {
        val past = entry(categoryId = "rent")
        val now = entry(merchant = "family.transfer@instapay")

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun aruleGuessIsNotEvidence() {
        // Suggesting from a rule's own guess would let one wrong rule teach itself to the inbox.
        val past = entry(categoryId = "rent", fromRule = true)
        val now = entry()

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun anuncategorisedTwinSaysNothing() {
        val past = entry(categoryId = null)
        val now = entry()

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, now), now))
    }

    @Test fun anentryNeverSuggestsItself() {
        val only = entry(categoryId = "rent")

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(only), only))
    }

    @Test fun ananchorIsNeverFiled() {
        val past = entry(categoryId = "rent")
        val anchor = entry(type = EntryType.ANCHOR)

        assertEquals(emptyList<String>(), amountTwinCategoryIds(listOf(past, anchor), anchor))
    }
}

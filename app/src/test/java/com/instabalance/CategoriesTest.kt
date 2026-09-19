package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    private fun entry(id: String, type: EntryType, categoryId: String?, fromRule: Boolean = false) =
        Entry(id = id, type = type, amountMinor = 100, timestamp = 1, categoryId = categoryId,
            categoryFromRule = fromRule)

    @Test fun presetIdsAreUnique() {
        val ids = Categories.PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyPresetHasItsOwnColour() {
        // Two categories sharing a colour makes the ring unreadable. There are 19 presets and 20
        // ramp colours, so there is no excuse for a collision.
        val colours = Categories.PRESETS.map { it.colorIndex }
        assertEquals(colours.size, colours.toSet().size)
    }

    @Test fun addRefusesATakenColourAndFindsAFreeOne() {
        val taken = Categories.byId(Categories.PRESETS, "rent")!!.colorIndex
        val out = Categories.add(Categories.PRESETS, "Gym", CategoryKind.EXPENSE, taken)

        val added = out.last()
        assertTrue("picked $taken, which rent already holds", added.colorIndex != taken)
        assertTrue(added.colorIndex !in Categories.takenColours(Categories.PRESETS))
    }

    @Test fun addKeepsAFreeColourWhenAsked() {
        val free = Categories.firstFreeColour(Categories.PRESETS)!!
        val out = Categories.add(Categories.PRESETS, "Gym", CategoryKind.EXPENSE, free)
        assertEquals(free, out.last().colorIndex)
    }

    @Test fun recolourRefusesAColourAnotherCategoryHolds() {
        val rentColour = Categories.byId(Categories.PRESETS, "rent")!!.colorIndex
        val out = Categories.recolour(Categories.PRESETS, "groceries", rentColour)

        // Unchanged, so the greyed-out swatch in the UI and this agree.
        assertEquals(Categories.PRESETS, out)
    }

    @Test fun recolourAllowsACategoryToKeepItsOwnColour() {
        val own = Categories.byId(Categories.PRESETS, "rent")!!.colorIndex
        val out = Categories.recolour(Categories.PRESETS, "rent", own)
        assertEquals(own, Categories.byId(out, "rent")?.colorIndex)
    }

    @Test fun firstFreeColourRunsOutRatherThanWrapping() {
        // Twenty colours, so the twenty-first category has none. Null is the honest answer and the
        // UI says so, instead of silently handing out a duplicate.
        var cats = Categories.PRESETS
        while (Categories.firstFreeColour(cats) != null) {
            cats = Categories.add(cats, "x", CategoryKind.EXPENSE, Categories.firstFreeColour(cats)!!)
        }
        assertEquals(ChartPalette.RAMP.size, Categories.takenColours(cats).size)
        assertNull(Categories.firstFreeColour(cats))
    }

    @Test fun takenColoursCanExcludeTheCategoryBeingEdited() {
        // A category's own colour is not taken as far as it is concerned.
        val own = Categories.byId(Categories.PRESETS, "rent")!!.colorIndex
        assertTrue(own in Categories.takenColours(Categories.PRESETS))
        assertTrue(own !in Categories.takenColours(Categories.PRESETS, excludingId = "rent"))
    }

    @Test fun presetColourIndicesAreInsideTheRamp() {
        // A stored index out of range would blow up at draw time, not here, so pin it here.
        Categories.PRESETS.forEach {
            assertTrue("${it.id} has colorIndex ${it.colorIndex}",
                it.colorIndex in ChartPalette.RAMP.indices)
        }
    }

    @Test fun feeAndOtherPresetsExist() {
        // Code references these by constant, so their absence is a crash waiting to happen.
        listOf(Categories.FEES, Categories.CASH, Categories.REFUND,
            Categories.OTHER_EXPENSE, Categories.OTHER_INCOME).forEach {
            assertTrue(it, Categories.PRESETS.any { p -> p.id == it })
        }
    }

    @Test fun visibleForFiltersByDirection() {
        val expense = Categories.visibleFor(Categories.PRESETS, EntryType.DEBIT).map { it.id }
        val income = Categories.visibleFor(Categories.PRESETS, EntryType.CREDIT).map { it.id }
        assertTrue(expense.contains("groceries"))
        assertTrue(!expense.contains("salary"))
        assertTrue(income.contains("salary"))
        assertTrue(!income.contains("groceries"))
    }

    @Test fun bothKindAppearsInEitherDirection() {
        assertTrue(Categories.visibleFor(Categories.PRESETS, EntryType.DEBIT).any { it.id == "family" })
        assertTrue(Categories.visibleFor(Categories.PRESETS, EntryType.CREDIT).any { it.id == "family" })
    }

    @Test fun anchorIsOfferedNothing() {
        // An anchor is a re-sync, not spending, so it must never be categorisable.
        assertTrue(Categories.visibleFor(Categories.PRESETS, EntryType.ANCHOR).isEmpty())
    }

    @Test fun hiddenCategoriesDisappearFromPickers() {
        val hidden = Categories.setHidden(Categories.PRESETS, "groceries", true)
        assertTrue(Categories.visibleFor(hidden, EntryType.DEBIT).none { it.id == "groceries" })
        // But the category itself still exists, so entries filed under it keep their name.
        assertEquals("Groceries", Categories.byId(hidden, "groceries")?.name)
    }

    @Test fun renamingYourOwnCategoryKeepsTheId() {
        // The id is what every entry points at, so a rename must never orphan anything.
        val withCustom = Categories.add(Categories.PRESETS, "Gym", CategoryKind.EXPENSE, 2)
        val id = withCustom.last().id

        val renamed = Categories.rename(withCustom, id, "Fitness")

        assertEquals("Fitness", Categories.byId(renamed, id)?.name)
    }

    @Test fun presetsCannotBeRenamed() {
        // Code files things under "fees" and "cash" by id and describes them by name, so a
        // built-in whose name no longer matches what the app puts in it would be a lie.
        val out = Categories.rename(Categories.PRESETS, "groceries", "Supermarket")
        assertEquals("Groceries", Categories.byId(out, "groceries")?.name)
    }

    @Test fun presetsCanStillBeRecoloured() {
        // Onto a free colour: 19 presets hold 19 of the 20, so exactly one is spare.
        val free = Categories.firstFreeColour(Categories.PRESETS)!!
        val out = Categories.recolour(Categories.PRESETS, "groceries", free)
        assertEquals(free, Categories.byId(out, "groceries")?.colorIndex)
    }

    @Test fun recolourClampsIntoTheRamp() {
        // Two categories only, so the clamp target is not blocked by the uniqueness rule and this
        // tests the clamp rather than the collision.
        val two = listOf(
            Category("a", "A", CategoryKind.EXPENSE, 0),
            Category("b", "B", CategoryKind.EXPENSE, 1),
        )
        val out = Categories.recolour(two, "a", 999)
        assertEquals(ChartPalette.RAMP.lastIndex, Categories.byId(out, "a")?.colorIndex)
    }

    @Test fun ensurePresetsAppendsOnlyWhatIsMissing() {
        val trimmed = Categories.PRESETS.filterNot { it.id == "rent" }
        val restored = Categories.ensurePresets(trimmed)
        assertEquals(Categories.PRESETS.size, restored.size)
        assertTrue(restored.any { it.id == "rent" })
    }

    @Test fun ensurePresetsLeavesRecolouredPresetsAlone() {
        // An upgrade must not undo the user's colour choice.
        val free = Categories.firstFreeColour(Categories.PRESETS)!!
        val recoloured = Categories.recolour(Categories.PRESETS, "rent", free)
        assertEquals(free, Categories.byId(Categories.ensurePresets(recoloured), "rent")?.colorIndex)
    }

    @Test fun byIdReturnsNullForUnknownAndForNull() {
        // A dangling id from a hand-edited file resolves, it does not throw.
        assertNull(Categories.byId(Categories.PRESETS, "nope"))
        assertNull(Categories.byId(Categories.PRESETS, null))
    }

    @Test fun deleteRefusesPresets() {
        val data = LedgerData()
        assertEquals(data, Categories.delete(data, "groceries"))
    }

    @Test fun deleteRemovesCustomAndSendsItsEntriesBackToTheInbox() {
        val withCustom = Categories.add(Categories.PRESETS, "Gym", CategoryKind.EXPENSE, 2)
        val id = withCustom.last().id
        val data = LedgerData(
            entries = listOf(entry("a", EntryType.DEBIT, id, fromRule = true),
                entry("b", EntryType.DEBIT, "groceries")),
            categories = withCustom,
            merchantRules = listOf(MerchantRule(pattern = "GYM", categoryId = id, createdAt = 1)),
        )

        val after = Categories.delete(data, id)

        assertNull(Categories.byId(after.categories, id))
        assertNull(after.entries.first { it.id == "a" }.categoryId)
        // The rule flag has to clear too, or the entry claims a rule filed it when none exists.
        assertEquals(false, after.entries.first { it.id == "a" }.categoryFromRule)
        assertEquals("groceries", after.entries.first { it.id == "b" }.categoryId)
        assertTrue(after.merchantRules.isEmpty())
    }

    @Test fun entryCountIsWhatTheConfirmDialogShows() {
        val entries = listOf(entry("a", EntryType.DEBIT, "rent"), entry("b", EntryType.DEBIT, "rent"),
            entry("c", EntryType.DEBIT, null))
        assertEquals(2, Categories.entryCount(entries, "rent"))
    }
}

package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two categories with the same name are indistinguishable everywhere they meet: the ring's legend,
 * a rule's target, the picker. Nothing in any of those says which direction a name came from, so
 * the rule is across every kind rather than within one.
 */
class CategoryNameTest {

    private val presets = Categories.PRESETS

    // ---- the two that shipped as a clash ------------------------------------

    @Test fun theshippedPresetsNoLongerCollide() {
        val names = presets.map { it.name.lowercase() }

        assertEquals(names.size, names.distinct().size)
    }

    @Test fun theescapeHatchesSayWhichTheyAre() {
        assertEquals("Other expense", Categories.byId(presets, Categories.OTHER_EXPENSE)?.name)
        assertEquals("Other income", Categories.byId(presets, Categories.OTHER_INCOME)?.name)
    }

    // ---- nameTaken ----------------------------------------------------------

    @Test fun anunusedNameIsFree() {
        assertFalse(Categories.nameTaken(presets, "Barber"))
    }

    @Test fun caseAndSpaceDoNotMakeAnameDifferent() {
        assertTrue(Categories.nameTaken(presets, "groceries"))
        assertTrue(Categories.nameTaken(presets, "  GROCERIES  "))
    }

    @Test fun anexpenseNameCollidesWithAnincomeOne() {
        // The whole point of the rule being across kinds rather than within one.
        assertTrue(Categories.nameTaken(presets, "Salary"))   // income
        assertTrue(Categories.nameTaken(presets, "Rent"))     // expense
    }

    @Test fun acategoryIsNeverAclashWithItself() {
        val rent = Categories.byId(presets, "rent")!!

        assertTrue(Categories.nameTaken(presets, "Rent"))
        assertFalse(Categories.nameTaken(presets, "Rent", excludingId = rent.id))
    }

    @Test fun ablankNameIsNotAclash() {
        // It is refused for being blank, which is a different message and a different reason.
        assertFalse(Categories.nameTaken(presets, ""))
        assertFalse(Categories.nameTaken(presets, "   "))
    }

    // ---- add and rename both refuse -----------------------------------------

    @Test fun addingAduplicateChangesNothing() {
        assertEquals(presets, Categories.add(presets, "Groceries", CategoryKind.EXPENSE, 5))
        assertEquals(presets, Categories.add(presets, "  groceries ", CategoryKind.INCOME, 5))
    }

    @Test fun addingAblankNameChangesNothing() {
        assertEquals(presets, Categories.add(presets, "   ", CategoryKind.EXPENSE, 5))
    }

    @Test fun addingAfreshNameStillWorks() {
        val out = Categories.add(presets, "Barber", CategoryKind.EXPENSE, 5)

        assertEquals(presets.size + 1, out.size)
        assertEquals("Barber", out.last().name)
    }

    @Test fun renamingOntoAnotherNameChangesNothing() {
        val mine = Categories.add(presets, "Barber", CategoryKind.EXPENSE, 5)
        val id = mine.last().id

        assertEquals(mine, Categories.rename(mine, id, "Groceries"))
    }

    @Test fun renamingToAdifferentCaseOfItsOwnNameIsAllowed() {
        val mine = Categories.add(presets, "Barber", CategoryKind.EXPENSE, 5)
        val id = mine.last().id

        val out = Categories.rename(mine, id, "barber")

        assertEquals("barber", Categories.byId(out, id)?.name)
    }

    @Test fun renamingToAfreshNameStillWorks() {
        val mine = Categories.add(presets, "Barber", CategoryKind.EXPENSE, 5)
        val id = mine.last().id

        assertEquals("Haircuts", Categories.byId(Categories.rename(mine, id, "Haircuts"), id)?.name)
    }

    // ---- what the user is told ----------------------------------------------

    @Test fun themessageNamesTheCategoryAndWhichSideItIsOn() {
        assertEquals(
            "You already have an income category called Salary.",
            duplicateNameMessage(presets, "salary"),
        )
        assertEquals(
            "You already have an expense category called Rent.",
            duplicateNameMessage(presets, "RENT"),
        )
        assertEquals(
            "You already have a category used for both called Friends.",
            duplicateNameMessage(presets, "friends"),
        )
    }
}

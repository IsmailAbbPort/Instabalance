package com.instabalance

import kotlinx.serialization.Serializable
import java.util.UUID

/** BOTH exists because a family transfer is income one month and an expense the next. */
enum class CategoryKind { EXPENSE, INCOME, BOTH }

/**
 * Ids are stable slugs and are never shown. Renaming a category keeps every entry filed under it,
 * which is the whole reason the id is not the name.
 *
 * The colour is an index into [ChartPalette.RAMP], not an ARGB value: the palette stays in code
 * where its contrast is unit-tested, and the user picks from swatches that are all known-legible.
 */
@Serializable
data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val colorIndex: Int,
    val hidden: Boolean = false,
    val preset: Boolean = false,
)

object Categories {

    // Referenced from code, so they are constants rather than loose strings.
    const val FEES = "fees"
    const val CASH = "cash"
    const val REFUND = "refund"
    const val OTHER_EXPENSE = "other_expense"
    const val OTHER_INCOME = "other_income"

    /**
     * Egypt-relevant defaults. `cash` is separate from `shopping` because the ATM withdrawal SMS is
     * its own common message shape. The two `other_*` entries are the "nothing fits, stop asking me"
     * answer the review inbox needs to let someone finish triaging.
     */
    /** Every colour index here is unique, which [uniqueColours] pins. */
    val PRESETS: List<Category> = listOf(
        Category("groceries", "Groceries", CategoryKind.EXPENSE, 7, preset = true),
        Category("transport", "Transport", CategoryKind.EXPENSE, 13, preset = true),
        Category("eating_out", "Eating out", CategoryKind.EXPENSE, 1, preset = true),
        Category("bills", "Bills and utilities", CategoryKind.EXPENSE, 11, preset = true),
        Category("rent", "Rent", CategoryKind.EXPENSE, 17, preset = true),
        Category("health", "Health and pharmacy", CategoryKind.EXPENSE, 0, preset = true),
        Category("shopping", "Shopping", CategoryKind.EXPENSE, 16, preset = true),
        Category("mobile", "Mobile and internet", CategoryKind.EXPENSE, 12, preset = true),
        Category("education", "Education", CategoryKind.EXPENSE, 14, preset = true),
        Category("entertainment", "Entertainment", CategoryKind.EXPENSE, 18, preset = true),
        Category(CASH, "Cash withdrawal", CategoryKind.EXPENSE, 4, preset = true),
        Category(FEES, "Fees", CategoryKind.EXPENSE, 2, preset = true),
        Category(OTHER_EXPENSE, "Other", CategoryKind.EXPENSE, 10, preset = true),
        Category("family", "Family transfer", CategoryKind.BOTH, 19, preset = true),
        Category("friends", "Friends", CategoryKind.BOTH, 9, preset = true),
        Category("salary", "Salary", CategoryKind.INCOME, 6, preset = true),
        Category("freelance", "Freelance", CategoryKind.INCOME, 3, preset = true),
        Category(REFUND, "Refund", CategoryKind.INCOME, 15, preset = true),
        Category(OTHER_INCOME, "Other", CategoryKind.INCOME, 5, preset = true),
    )

    /** Null rather than throwing: a dangling id from a hand-edited file must not crash the app. */
    fun byId(categories: List<Category>, id: String?): Category? =
        if (id == null) null else categories.firstOrNull { it.id == id }

    /** What a picker offers for a direction. Hidden categories keep their entries but stop appearing. */
    fun visibleFor(categories: List<Category>, type: EntryType): List<Category> {
        val wanted = when (type) {
            EntryType.CREDIT -> CategoryKind.INCOME
            EntryType.DEBIT -> CategoryKind.EXPENSE
            // An anchor is a re-sync, not money moving, so nothing is ever offered for it.
            EntryType.ANCHOR -> return emptyList()
        }
        return categories.filter { !it.hidden && (it.kind == wanted || it.kind == CategoryKind.BOTH) }
    }

    /**
     * Appends presets the stored list is missing, so a preset added in a later build reaches
     * existing users. Presets already present are left exactly as they are, so a recolour or a
     * hide the user made survives every upgrade.
     */
    fun ensurePresets(categories: List<Category>): List<Category> {
        val known = categories.mapTo(mutableSetOf()) { it.id }
        val missing = PRESETS.filterNot { it.id in known }
        return if (missing.isEmpty()) categories else categories + missing
    }

    /**
     * Colour indices already spoken for. Two categories sharing a colour makes a pie chart
     * unreadable, so this is what the pickers grey out and what [add] and [recolour] respect.
     */
    fun takenColours(categories: List<Category>, excludingId: String? = null): Set<Int> =
        categories.filterNot { it.id == excludingId }.mapTo(mutableSetOf()) { it.colorIndex }

    /**
     * The lowest colour nothing is using, or null once all twenty are spoken for. Null is a real
     * case (twenty-one categories), and the UI says so rather than silently duplicating.
     */
    fun firstFreeColour(categories: List<Category>, excludingId: String? = null): Int? {
        val taken = takenColours(categories, excludingId)
        return ChartPalette.RAMP.indices.firstOrNull { it !in taken }
    }

    /**
     * Ignores the requested colour if it is taken and falls back to a free one, so a caller can
     * never create two categories that look identical in a chart. Once the palette is exhausted
     * the requested colour is used as-is: refusing to create the category would be worse.
     */
    fun add(categories: List<Category>, name: String, kind: CategoryKind, colorIndex: Int): List<Category> {
        val colour = if (colorIndex in takenColours(categories)) {
            firstFreeColour(categories) ?: colorIndex
        } else {
            colorIndex
        }
        return categories + Category(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            kind = kind,
            colorIndex = colour.coerceIn(0, ChartPalette.RAMP.lastIndex),
        )
    }

    /**
     * Only your own categories can be renamed. A built-in keeps its name so that the code that
     * references one by id ([FEES], [CASH], the two [OTHER_EXPENSE]/[OTHER_INCOME] escape hatches)
     * keeps describing what it actually does. Returns the list unchanged for a preset.
     */
    fun rename(categories: List<Category>, id: String, name: String): List<Category> =
        categories.map {
            if (it.id == id && !it.preset) it.copy(name = name.trim()) else it
        }

    /**
     * Every category can be recoloured, presets included, but never onto a colour another category
     * already holds: the whole point of the colour is telling one slice from another. Returns the
     * list unchanged when the colour is taken, so the UI's greyed-out swatch and this agree.
     * Coerced into the ramp so a bad index can never reach a list access at draw time.
     */
    fun recolour(categories: List<Category>, id: String, colorIndex: Int): List<Category> {
        val wanted = colorIndex.coerceIn(0, ChartPalette.RAMP.lastIndex)
        if (wanted in takenColours(categories, excludingId = id)) return categories
        return categories.map { if (it.id == id) it.copy(colorIndex = wanted) else it }
    }

    fun setHidden(categories: List<Category>, id: String, hidden: Boolean): List<Category> =
        categories.map { if (it.id == id) it.copy(hidden = hidden) else it }

    /** How many entries a delete would send back to the inbox. Shown before confirming. */
    fun entryCount(entries: List<Entry>, id: String): Int = entries.count { it.categoryId == id }

    /**
     * Presets are never deleted, only hidden: deleting one would need a tombstone list to stop
     * [ensurePresets] resurrecting it on the next launch. Returns the data unchanged for a preset
     * or an unknown id.
     */
    fun delete(data: LedgerData, id: String): LedgerData {
        val target = byId(data.categories, id) ?: return data
        if (target.preset) return data
        return data.copy(
            categories = data.categories.filterNot { it.id == id },
            // Back to uncategorised, visibly, rather than left pointing at something gone.
            entries = data.entries.map {
                if (it.categoryId == id) it.copy(categoryId = null, categoryFromRule = false) else it
            },
            merchantRules = data.merchantRules.filterNot { it.categoryId == id },
        )
    }
}

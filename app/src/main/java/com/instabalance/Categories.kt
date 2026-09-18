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
    val PRESETS: List<Category> = listOf(
        Category("groceries", "Groceries", CategoryKind.EXPENSE, 0, preset = true),
        Category("transport", "Transport", CategoryKind.EXPENSE, 1, preset = true),
        Category("eating_out", "Eating out", CategoryKind.EXPENSE, 2, preset = true),
        Category("bills", "Bills and utilities", CategoryKind.EXPENSE, 3, preset = true),
        Category("rent", "Rent", CategoryKind.EXPENSE, 4, preset = true),
        Category("health", "Health and pharmacy", CategoryKind.EXPENSE, 5, preset = true),
        Category("shopping", "Shopping", CategoryKind.EXPENSE, 6, preset = true),
        Category("mobile", "Mobile and internet", CategoryKind.EXPENSE, 7, preset = true),
        Category("education", "Education", CategoryKind.EXPENSE, 8, preset = true),
        Category("entertainment", "Entertainment", CategoryKind.EXPENSE, 9, preset = true),
        Category(CASH, "Cash withdrawal", CategoryKind.EXPENSE, 8, preset = true),
        Category(FEES, "Fees", CategoryKind.EXPENSE, 9, preset = true),
        Category(OTHER_EXPENSE, "Other", CategoryKind.EXPENSE, 7, preset = true),
        Category("family", "Family transfer", CategoryKind.BOTH, 3, preset = true),
        Category("friends", "Friends", CategoryKind.BOTH, 5, preset = true),
        Category("salary", "Salary", CategoryKind.INCOME, 2, preset = true),
        Category("freelance", "Freelance", CategoryKind.INCOME, 0, preset = true),
        Category(REFUND, "Refund", CategoryKind.INCOME, 4, preset = true),
        Category(OTHER_INCOME, "Other", CategoryKind.INCOME, 6, preset = true),
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

    fun add(categories: List<Category>, name: String, kind: CategoryKind, colorIndex: Int): List<Category> =
        categories + Category(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            kind = kind,
            colorIndex = colorIndex,
        )

    /**
     * Only your own categories can be renamed. A built-in keeps its name so that the code that
     * references one by id ([FEES], [CASH], the two [OTHER_EXPENSE]/[OTHER_INCOME] escape hatches)
     * keeps describing what it actually does. Returns the list unchanged for a preset.
     */
    fun rename(categories: List<Category>, id: String, name: String): List<Category> =
        categories.map {
            if (it.id == id && !it.preset) it.copy(name = name.trim()) else it
        }

    /** Every category can be recoloured, presets included. Coerced into the ramp so a bad index
     * can never reach a list access at draw time. */
    fun recolour(categories: List<Category>, id: String, colorIndex: Int): List<Category> =
        categories.map {
            if (it.id == id) it.copy(colorIndex = colorIndex.coerceIn(0, ChartPalette.RAMP.lastIndex)) else it
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

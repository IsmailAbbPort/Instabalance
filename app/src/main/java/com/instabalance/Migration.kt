package com.instabalance

/**
 * Brings a ledger loaded from disk up to what the current build expects.
 *
 * Deliberately keyed off the data rather than a version number: every new field carries a default,
 * so a file written before those fields existed decodes with the same values a current file would,
 * and no stored number can tell the two apart.
 *
 * Pure and idempotent. Returns null when there is nothing to do, so a launch that changes nothing
 * does not rewrite (and re-encrypt) the file.
 */
object Migration {

    fun apply(data: LedgerData): LedgerData? {
        var next = data
        var changed = false

        // Fees were their own Source long before they were their own category.
        val entries = next.entries.map {
            if (it.source == Source.FEE && it.categoryId == null) {
                it.copy(categoryId = Categories.FEES)
            } else {
                it
            }
        }
        if (entries != next.entries) {
            next = next.copy(entries = entries)
            changed = true
        }

        // Presets added in a later build reach existing users, without disturbing any the user has
        // already renamed or hidden.
        val categories = Categories.ensurePresets(next.categories)
        if (categories.size != next.categories.size) {
            next = next.copy(categories = categories)
            changed = true
        }

        return if (changed) next else null
    }
}

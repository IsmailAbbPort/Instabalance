package com.instabalance

/**
 * Brings a ledger loaded from disk up to what the current build expects.
 *
 * Mostly keyed off the data rather than a version number: every new field carries a default, so a
 * file written before those fields existed decodes with the same values a current file would, and
 * no stored number can tell the two apart.
 *
 * Seeded rules are the exception, and [LedgerData.seededRuleVersion] is why. A rule the app ships
 * is still the user's to delete, and without a stored watermark "is this rule missing because we
 * have not added it yet, or because you threw it away?" has no answer, so every launch would
 * resurrect it.
 *
 * Pure and idempotent. Returns null when there is nothing to do, so a launch that changes nothing
 * does not rewrite (and re-encrypt) the file.
 */
object Migration {

    /** Bump when [SEEDED_RULES] gains entries, so existing installs pick up the new ones once. */
    const val SEEDED_RULE_VERSION = 1

    /**
     * Bump when the SMS defaults get stricter in a way an existing install should inherit.
     *
     * 1: an empty sender allowlist, which read every shortcode in the country, becomes EGBANK, and
     *    messages carrying a link stop being transactions. Both came from a Dushka ad for a
     *    sandwich landing in the ledger as a 175 EGP purchase.
     */
    const val SMS_DEFAULTS_VERSION = 1

    private data class Seed(val pattern: String, val categoryId: String)

    /**
     * Rules for the shops that turn up on an EGBANK card statement in Cairo often enough to be
     * worth filing without being asked. Patterns are matched with `contains` against a normalised
     * merchant, so each is as short as it can be without catching something else:
     *
     *  - MISR PETROLEUM, not MISR, which is the word for Egypt and opens half the country's
     *    company names.
     *  - CHILL OUT and CHILLOUT both, because the bank prints it both ways and a `contains` match
     *    cannot bridge the space. A single "CHILL" would have covered both and also filed a chilli
     *    restaurant under petrol.
     */
    private val SEEDED_RULES = listOf(
        Seed("TALABAT", "eating_out"),
        Seed("SEOUDI", "groceries"),
        Seed("MISR PETROLEUM", "transport"),
        Seed("CHILL OUT", "transport"),
        Seed("CHILLOUT", "transport"),
    )

    fun apply(data: LedgerData, now: Long = System.currentTimeMillis()): LedgerData? {
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

        val backfilled = backfillMerchants(next)
        if (backfilled != next.entries) {
            next = next.copy(entries = backfilled)
            changed = true
        }

        // Presets added in a later build reach existing users, without disturbing any the user has
        // already renamed or hidden. Before the rules below, which file into them.
        val categories = Categories.ensurePresets(next.categories)
        if (categories.size != next.categories.size) {
            next = next.copy(categories = categories)
            changed = true
        }

        val renamed = renameOtherPresets(next.categories)
        if (renamed != next.categories) {
            next = next.copy(categories = renamed)
            changed = true
        }

        if (next.smsDefaultsVersion < SMS_DEFAULTS_VERSION) {
            next = next.copy(
                smsConfig = tightenSmsDefaults(next.smsConfig),
                smsDefaultsVersion = SMS_DEFAULTS_VERSION,
            )
            changed = true
        }

        if (next.seededRuleVersion < SEEDED_RULE_VERSION) {
            val existing = next.merchantRules.mapTo(mutableSetOf()) { it.pattern }
            val added = SEEDED_RULES
                .filterNot { it.pattern in existing }
                .map { MerchantRule(pattern = it.pattern, categoryId = it.categoryId, createdAt = now) }
            next = next.copy(
                merchantRules = next.merchantRules + added,
                seededRuleVersion = SEEDED_RULE_VERSION,
            )
            changed = true
        }

        return if (changed) next else null
    }

    /**
     * The two escape-hatch presets both shipped as "Other", one for expenses and one for income.
     * Two chips reading "Other" side by side in a picker is a coin toss, and category names are now
     * required to be unique, which these two were the only thing breaking.
     *
     * Only touches a category still called exactly "Other", so this can never rename one twice and
     * never fights a name somebody chose. Ids are untouched, so every entry stays filed.
     */
    private fun renameOtherPresets(categories: List<Category>): List<Category> =
        categories.map {
            val wanted = when (it.id) {
                Categories.OTHER_EXPENSE -> "Other expense"
                Categories.OTHER_INCOME -> "Other income"
                else -> return@map it
            }
            // Skip when the user got there first and already has a category of that name. Creating
            // the clash would leave two identical chips in every picker, and the preset's own edit
            // sheet has no name field to resolve it with, so its Done button would be dead.
            // Leaving it as "Other" is still unique against "Other expense".
            if (it.name != "Other" || Categories.nameTaken(categories, wanted, excludingId = it.id)) it
            else it.copy(name = wanted)
        }

    /**
     * Narrows an install that was reading every sender down to the bank, and teaches it that a
     * message with a link in it is an advert. Additive on the ignore list and only fills a sender
     * allowlist that is empty, so nothing the user typed is ever removed.
     */
    private fun tightenSmsDefaults(config: SmsConfig): SmsConfig {
        val senders =
            if (config.senders.isEmpty()) SmsConfig.DEFAULT_SENDERS else config.senders
        val missing = SmsConfig.DEFAULT_LINK_WORDS.filterNot { w ->
            config.ignoreWords.any { it.equals(w, ignoreCase = true) }
        }
        return config.copy(senders = senders, ignoreWords = config.ignoreWords + missing)
    }

    /**
     * Re-reads the shop out of the original message for entries the parser could not name at the
     * time. The card-purchase shape it now understands (the bank's tag wedged into the card number,
     * the shop on its own line) used to miss entirely, which left "MISR PETROLEUM" stored as "MISR"
     * and "CHILL OUT" stored as nothing, and no rule can match what was never recorded.
     *
     * Only fills a blank or completes a truncation: a merchant that is genuinely different from
     * what the message now yields is left exactly as it is. Nothing else about the entry is
     * touched, least of all the amount or the direction, which are the money.
     */
    private fun backfillMerchants(data: LedgerData): List<Entry> =
        data.entries.map { e ->
            if (e.source != Source.SMS && e.source != Source.NOTIFICATION) return@map e
            val raw = e.rawText ?: return@map e
            val parsed = BalanceParser.parse(raw, data.smsConfig)?.merchant ?: return@map e
            val current = e.merchant
            val completes = current != null &&
                parsed.length > current.length &&
                parsed.startsWith(current, ignoreCase = true)
            if (current == null || completes) e.copy(merchant = parsed) else e
        }
}

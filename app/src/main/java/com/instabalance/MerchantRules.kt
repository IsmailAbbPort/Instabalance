package com.instabalance

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * A learned "file anything from this merchant here" rule. The pattern is stored already normalised,
 * so matching is a plain `contains` against a normalised merchant and needs no regex at capture
 * time (which runs on a binder thread while the app is closed).
 */
@Serializable
data class MerchantRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    val categoryId: String,
    val createdAt: Long,
)

object MerchantRules {

    /** Tokens too generic to be a rule on their own; they would match half the ledger. */
    private val STOP_WORDS = setOf("NEW", "THE", "FOR", "AND", "EL", "AL", "ABU", "MR", "CO")

    private val PUNCTUATION = Regex("""[^A-Z0-9@. ]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Uppercase ASCII, no punctuation except @ and dot (both meaningful in an InstaPay address). */
    fun normalise(merchant: String): String =
        BalanceParser.normalizeArabicDigits(merchant)
            .uppercase(Locale.ROOT)
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Longest pattern wins, so a specific rule beats a general one that also matches. Ties break on
     * createdAt then id so the result never depends on list order.
     */
    fun match(rules: List<MerchantRule>, merchant: String?): MerchantRule? {
        if (merchant.isNullOrBlank()) return null
        val n = normalise(merchant)
        if (n.isEmpty()) return null
        return rules
            .filter { it.pattern.isNotEmpty() && n.contains(it.pattern) }
            .minWithOrNull(
                compareByDescending<MerchantRule> { it.pattern.length }
                    .thenBy { it.createdAt }
                    .thenBy { it.id }
            )
    }

    /**
     * What the picker pre-fills when offering to learn a merchant. The first token alone is usually
     * the brand ("PAYMOB RAF SPECIALIT CAIRO" -> "PAYMOB"), but a short or generic first token
     * would over-match, so those take two tokens instead.
     */
    fun proposePattern(merchant: String): String {
        val tokens = normalise(merchant).split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val first = tokens[0]
        val tooWeak = first.length < 4 || first in STOP_WORDS
        return if (tooWeak && tokens.size > 1) "${first} ${tokens[1]}" else first
    }

    /** Applied to a freshly captured entry. Never touches one that already has a category. */
    fun categoriseNew(entry: Entry, rules: List<MerchantRule>): Entry {
        if (entry.categoryId != null || entry.type == EntryType.ANCHOR) return entry
        val rule = match(rules, entry.merchant) ?: return entry
        return entry.copy(categoryId = rule.categoryId, categoryFromRule = true)
    }

    /**
     * Retro-applies a rule to entries the user has not categorised. The count is returned so the UI
     * can say "apply to 4 more" and let the user decide: this is never run automatically, because
     * silently rewriting history is how an expense tracker loses someone's trust.
     */
    fun applyToUncategorised(entries: List<Entry>, rule: MerchantRule): Pair<List<Entry>, Int> {
        var changed = 0
        val next = entries.map { e ->
            val eligible = e.categoryId == null && e.type != EntryType.ANCHOR
            if (eligible && match(listOf(rule), e.merchant) != null) {
                changed++
                e.copy(categoryId = rule.categoryId, categoryFromRule = true)
            } else {
                e
            }
        }
        return next to changed
    }

    /** How many rule-filed entries a rule's category change would move. Shown before confirming. */
    fun ruleOwnedCount(entries: List<Entry>, rule: MerchantRule): Int =
        entries.count { e ->
            e.categoryFromRule && e.categoryId == rule.categoryId &&
                match(listOf(rule), e.merchant) != null
        }
}

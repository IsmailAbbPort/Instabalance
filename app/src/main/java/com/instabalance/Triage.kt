package com.instabalance

/**
 * Filing a transaction at the moment it happens, instead of days later from memory.
 *
 * The reason every transaction needs human triage is that an InstaPay transfer seen as a bank SMS
 * names nobody, so no rule can ever reach it. By the time the inbox is opened, the 350 EGP is just
 * a number. The one moment anybody knows what it was is the moment the message lands.
 *
 * The decision of what to offer is here, pure and tested; posting it is [TriageAlerts].
 */

/** Android allows three actions on a notification, and a fourth would be invisible anyway. */
const val TRIAGE_CHOICE_LIMIT = 3

/**
 * The categories worth offering for [entry], best guess first.
 *
 * The same order the inbox row uses, for the same reasons: where this exact amount went last time,
 * then what you have been reaching for lately, then Other so there is always a way to finish. A
 * merchant rule never appears, because anything a rule could file is already filed and never gets
 * here at all.
 */
fun triageChoices(
    entries: List<Entry>,
    categories: List<Category>,
    entry: Entry,
    limit: Int = TRIAGE_CHOICE_LIMIT,
): List<Category> {
    if (entry.type == EntryType.ANCHOR) return emptyList()
    val visible = Categories.visibleFor(categories, entry.type)
    return buildList {
        addAll(amountTwinCategoryIds(entries, entry))
        addAll(recentCategoryIds(entries))
        add(if (entry.type == EntryType.CREDIT) Categories.OTHER_INCOME else Categories.OTHER_EXPENSE)
    }
        .distinct()
        .mapNotNull { id -> visible.firstOrNull { it.id == id } }
        .take(limit)
}

/**
 * Whether this entry is worth interrupting for.
 *
 * Only the ones nobody can file for you. A transaction a rule already filed is silent, which means
 * the better your rules get the quieter this becomes, and the fee line on an InstaPay send arrives
 * pre-categorised so it never fires either. Anchors are not money moving.
 */
fun worthTriaging(entry: Entry): Boolean =
    entry.type != EntryType.ANCHOR &&
        entry.categoryId == null &&
        entry.source != Source.FEE

/** What the notification says. Short: the whole thing has to read at a glance in the shade. */
fun triageTitle(entry: Entry): String {
    val sign = if (entry.type == EntryType.CREDIT) "+" else "-"
    return "$sign ${Money.formatMinor(entry.amountMinor)} EGP"
}

fun triageBody(entry: Entry): String = when {
    !namesNobody(entry.merchant) -> "${entry.merchant}. File it?"
    entry.type == EntryType.CREDIT -> "Money in, from nobody named. File it?"
    else -> "Transfer out, to nobody named. File it?"
}

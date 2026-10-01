package com.instabalance

/** One message as it sits in the phone's own SMS store. */
data class StoredSms(val sender: String, val body: String, val sentAt: Long)

/** A message that parsed, with what it parsed to, ready to be written. */
data class BackfillItem(val message: StoredSms, val parsed: ParsedTxn, val rawText: String)

/** What a scan found, so the screen can say what happened rather than just a number. */
data class BackfillPlan(
    val items: List<BackfillItem>,
    val alreadyKnown: Int,
    val unreadable: Int,
)

/**
 * Recovering transactions from messages the app never saw live.
 *
 * The reader only runs while the receiver is alive, so every gap is a hole in the ledger: the phone
 * dozing, a reboot before first unlock, a reinstall, and above all the weeks before the app existed
 * on this phone at all. Today each gap is either typed back in by hand or quietly swallowed by the
 * next re-sync, which makes the charts wrong about a period rather than merely missing it.
 *
 * The messages are still sitting in the phone's SMS store. This is the same parser, the same sender
 * allowlist and the same dedupe as the live path; the only difference is where the text comes from
 * and that each entry is written at the time the message actually arrived.
 */
object SmsBackfill {

    /** Matches what [SmsReceiver] stores, so a message imported twice is recognisably the same. */
    fun rawTextFor(message: StoredSms): String = "[${message.sender}] ${message.body.trim()}"

    /**
     * Which of [messages] are worth writing.
     *
     * Skipped for three separate reasons, counted separately because they mean different things:
     * a sender you never allowed is not read at all, a message that does not parse is reported so
     * you know the words need work, and one already in the ledger is the normal case on a rescan.
     *
     * "Already in the ledger" is decided by the same twenty minute rule the live path uses, and
     * deliberately not by the message text. Identical text is not identical money: the same basket
     * at the same shop next week produces a byte-for-byte identical SMS, and matching on text would
     * quietly drop the second one forever.
     */
    fun plan(
        entries: List<Entry>,
        messages: List<StoredSms>,
        config: SmsConfig,
    ): BackfillPlan {
        // Grows as we go, so a message is also tested against what this same scan already accepted.
        val accepted = mutableListOf<BackfillItem>()
        var known = 0
        var unreadable = 0

        messages.sortedBy { it.sentAt }.forEach { m ->
            if (m.body.isBlank() || !config.acceptsSender(m.sender)) return@forEach
            val parsed = BalanceParser.parse(m.body, config)
            if (parsed == null) {
                unreadable++
                return@forEach
            }
            val against = entries + accepted.map { it.toEntry() }
            if (LedgerRepository.isDuplicateAuto(against, parsed.type, parsed.amountMinor, m.sentAt)) {
                known++
                return@forEach
            }
            accepted += BackfillItem(m, parsed, rawTextFor(m))
        }

        return BackfillPlan(accepted, known, unreadable)
    }

    /** Only ever used to test the running batch against itself; never written as-is. */
    private fun BackfillItem.toEntry() = Entry(
        type = parsed.type,
        amountMinor = parsed.amountMinor,
        timestamp = message.sentAt,
        source = Source.SMS,
        rawText = rawText,
        merchant = parsed.merchant,
    )

    /** How far back a scan reaches, and what the buttons offer. */
    val WINDOWS_DAYS = listOf(30, 90, 365)

    fun cutoff(days: Int, now: Long): Long = now - days * 24L * 60 * 60 * 1000
}

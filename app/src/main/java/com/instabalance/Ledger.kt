package com.instabalance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class EntryType { ANCHOR, CREDIT, DEBIT }
enum class Source { MANUAL, NOTIFICATION, SMS, FEE }

/**
 * One line in the ledger.
 * - ANCHOR: "I checked the real app, balance is exactly X." amountMinor is the absolute balance.
 * - CREDIT / DEBIT: a received / sent transaction. amountMinor is the (positive) size of the move.
 */
@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val type: EntryType,
    val amountMinor: Long,
    val timestamp: Long,
    val source: Source = Source.MANUAL,
    val note: String = "",
    val rawText: String? = null,
    /** Counterparty as printed in the message, if the message named one at all. */
    val merchant: String? = null,
    /** null = uncategorised, which is what the review inbox lists. ANCHOR entries stay null. */
    val categoryId: String? = null,
    /**
     * True only while the category came from a merchant rule rather than a person. It is what lets
     * a rule change offer to update the entries it filed without ever touching a hand-made choice.
     */
    val categoryFromRule: Boolean = false,
)

/** A captured notification/SMS shown in Learning mode so you can identify InstaPay's package + wording. */
@Serializable
data class DebugCapture(
    val timestamp: Long,
    val channel: String,   // "NOTIFICATION" or "SMS"
    val packageOrSender: String,
    val text: String,
)

/** Everything we persist, in one JSON file. */
@Serializable
data class LedgerData(
    val entries: List<Entry> = emptyList(),
    val watchedPackages: List<String> = DEFAULT_WATCHED_PACKAGES,
    val learningMode: Boolean = false,
    val captures: List<DebugCapture> = emptyList(),
    val passcodeHash: String? = null,
    val passcodeSalt: String? = null,
    val biometricEnabled: Boolean = false,
    val instapaySendFeeEnabled: Boolean = true,
    val feePercentBps: Int = 10,        // basis points: 10 = 0.10%
    val feeMinMinor: Long = 50,         // 0.50 EGP
    val feeCapMinor: Long? = 2000,      // 20 EGP cap on the InstaPay send fee
    val categories: List<Category> = Categories.PRESETS,
    val merchantRules: List<MerchantRule> = emptyList(),
    val smsConfig: SmsConfig = SmsConfig(),
    val monthlyBudgetMinor: Long? = null,   // null = no budget, no alerts
    val budgetMonth: String = "",           // "2026-09": the month highestMilestoneFired belongs to
    val highestMilestoneFired: Int = 0,     // 0, 25, 50, 75, 90, 100 or 120
    /**
     * Per-category equivalent of [highestMilestoneFired], keyed by category id, and reset by the
     * same [budgetMonth]. Absent means nothing has fired for that category this month.
     */
    val categoryMilestones: Map<String, Int> = emptyMap(),
    /**
     * Highest [Migration.SEEDED_RULE_VERSION] whose shipped merchant rules have been offered to
     * this ledger. Zero on a file written before they existed. Without it, deleting a rule the app
     * ships would only last until the next launch.
     */
    val seededRuleVersion: Int = 0,
    /**
     * Whether a captured transaction nobody could file for you offers to be filed from the
     * notification shade. On by default: the channel is silent, and the moment you know what a
     * nameless transfer was is the moment it lands.
     */
    val triageAlertsEnabled: Boolean = true,
    /**
     * Highest [Migration.SMS_DEFAULTS_VERSION] whose tightened SMS defaults have been offered to
     * this ledger. Same watermark shape as [seededRuleVersion] and for the same reason: both the
     * sender allowlist and the ignore list are the user's to edit, so without this, clearing either
     * one would last until the next launch.
     */
    val smsDefaultsVersion: Int = 0,
    /**
     * Whether an evening reminder goes out when transactions are still waiting to be categorised.
     * Separate from [triageAlertsEnabled]: that one fires the instant a transaction lands and is
     * per-transaction, this is one digest at a fixed hour for whatever is still sitting there.
     */
    val pendingReminderEnabled: Boolean = true,
    /**
     * How often the app writes an encrypted backup of itself into Download/InstaBalance. Off until
     * a passphrase is set, because a backup nobody can open is not a backup and one written in
     * plaintext into a public folder is the opposite of what the rest of this app is for.
     */
    val autoBackupFrequency: BackupFrequency = BackupFrequency.OFF,
    /** Newest N auto-backups to keep, or [AutoBackup.KEEP_EVERYTHING] to never delete any. */
    val autoBackupKeep: Int = 12,
    val autoBackupLastRunAt: Long = 0L,
    /** [AutoBackup.fingerprint] as of the last backup written, so an unchanged ledger is skipped. */
    val autoBackupLastFingerprint: String = "",
    /** Last outcome, shown in Settings. A backup that silently stopped working is worse than none. */
    val autoBackupLastResult: String = "",
) {
    val hasPasscode: Boolean get() = passcodeHash != null && passcodeSalt != null

    /** Fee InstaPay takes on a send of [amountMinor]: max(min, percent * amount), optionally capped. */
    fun instapaySendFeeMinor(amountMinor: Long): Long {
        var fee = (amountMinor * feePercentBps + 5000) / 10000  // rounded to nearest piastre
        if (fee < feeMinMinor) fee = feeMinMinor
        feeCapMinor?.let { if (fee > it) fee = it }
        return fee
    }

    companion object {
        // TODO: confirm the real InstaPay package on YOUR phone with Learning mode, then trim this list.
        val DEFAULT_WATCHED_PACKAGES = listOf(
            "com.egyptianbanks.instapay",
        )
    }
}

/**
 * What the parser hands back for an auto-captured transaction.
 *
 * [reportedBalanceMinor] is the bank's own "الرصيد المتاح 6542.73جم", where the message carries one.
 * It is the balance AFTER this transaction, which is the same figure the manual re-sync asks you to
 * read off the real app, arriving for free.
 */
data class ParsedTxn(
    val type: EntryType,
    val amountMinor: Long,
    val merchant: String? = null,
    val reportedBalanceMinor: Long? = null,
)

/**
 * Pure category assignment, kept out of the repository so it can be unit-tested without the
 * Keystore. ANCHOR entries and unknown ids are silently left alone: an anchor is a re-sync, not
 * spending, so it must never carry a category.
 */
fun applyCategory(entries: List<Entry>, ids: Set<String>, categoryId: String?): List<Entry> {
    // A blank id is not a category. It reads as filed everywhere (the inbox and the triage check
    // both test for null), so an entry carrying one leaves the review list while still showing as
    // "Uncategorised" and can never be found again. Normalised here rather than at each caller so
    // no future caller can reintroduce it.
    val id = categoryId?.takeIf { it.isNotBlank() }
    return entries.map { e ->
        if (e.id in ids && e.type != EntryType.ANCHOR) {
            e.copy(categoryId = id, categoryFromRule = false)
        } else {
            e
        }
    }
}

/**
 * Pure note assignment, kept beside [applyCategory] and out of the repository for the same reason:
 * it can then be tested without the Keystore. Unlike a category, a note is allowed on an ANCHOR,
 * because "checked the real app after the ATM" is exactly the kind of thing worth writing down.
 */
fun applyNote(entries: List<Entry>, id: String, note: String): List<Entry> =
    entries.map { if (it.id == id) it.copy(note = note.trim()) else it }

/**
 * Same window as the cross-source dedupe, and for the same reason: two channels describing one
 * transfer do not arrive at the same instant.
 */
const val CAPTURE_MATCH_WINDOW_MS = 20 * 60 * 1000L

/**
 * The Learning mode capture that recorded the same transaction as [entry], or null.
 *
 * One InstaPay transfer is announced twice, by the app and by the bank, and the dedupe keeps
 * whichever landed first. That is usually the bank SMS, which is the worse of the two to read:
 *
 *   bank:   "...credited by EGP 1070 on 27-07 17:07 IPN REF# 47006796428 from ** for details
 *            please call عمرو احمد فوزى مرعى 19342"
 *   InstaPay: "You have received 1070.00 EGP from marrynbe@instapay"
 *
 * The bank names the counterparty "**" and splices their name into the middle of its own support
 * line. The notification simply says who sent it. When Learning mode happens to have kept the
 * other one, the detail sheet can show that instead.
 *
 * Matched on what the message MEANS, not on time alone: it must parse to the same direction and
 * the same amount. A capture that merely arrived nearby is somebody else's transaction, and
 * showing it under this one would be a lie about where the money went.
 */
fun captureFor(
    entry: Entry,
    captures: List<DebugCapture>,
    config: SmsConfig,
    windowMs: Long = CAPTURE_MATCH_WINDOW_MS,
): DebugCapture? {
    if (entry.type == EntryType.ANCHOR) return null
    return captures
        .filter { kotlin.math.abs(it.timestamp - entry.timestamp) <= windowMs }
        .filter {
            val parsed = BalanceParser.parse(it.text, config)
            parsed != null && parsed.type == entry.type && parsed.amountMinor == entry.amountMinor
        }
        // The notification first: it is the one written for a person to read. Then the closest in
        // time, so the result never depends on the order the list happens to be in.
        .minWithOrNull(
            compareBy<DebugCapture> { it.channel != "NOTIFICATION" }
                .thenBy { kotlin.math.abs(it.timestamp - entry.timestamp) }
                .thenBy { it.timestamp }
        )
}

/**
 * Whether a counterparty is a name at all.
 *
 * Blank is the easy half. The other half is the bank's mask: an InstaPay transfer seen as a bank
 * SMS says the money came "from **", and the parser faithfully records "**", because it cannot know
 * that this particular pair of asterisks means "we are not telling you". Anything with no letter
 * and no digit in it names nobody, whatever characters it is made of.
 */
internal fun namesNobody(merchant: String?): Boolean =
    merchant.isNullOrBlank() || merchant.none { it.isLetterOrDigit() }

/**
 * Folds what the dropped twin knew into the entry that was kept.
 *
 * One InstaPay transfer arrives twice, and the dedupe keeps whichever landed first. That is usually
 * the bank SMS, which names the counterparty "**", so the useful message, the one saying
 * "marrynbe@instapay", was thrown away whole. Everything downstream suffered for it: the list read
 * as anonymous, search could not find the person, and a merchant rule had no name to match on.
 *
 * Only ever fills a blank. A counterparty already on the entry is the one the parser found in the
 * message it kept, and a second message is no reason to overwrite it.
 *
 * Returns null when there is nothing to add, so the caller can skip the write entirely: this runs
 * on every duplicate, and most duplicates teach us nothing.
 */
fun mergeTwinInto(
    entries: List<Entry>,
    type: EntryType,
    amountMinor: Long,
    timestamp: Long,
    merchant: String?,
    rules: List<MerchantRule>,
    windowMs: Long = CAPTURE_MATCH_WINDOW_MS,
): List<Entry>? {
    if (namesNobody(merchant)) return null
    val target = entries
        .filter { it.type == type && it.amountMinor == amountMinor }
        .filter { it.source == Source.NOTIFICATION || it.source == Source.SMS }
        .filter { namesNobody(it.merchant) }
        .filter { kotlin.math.abs(it.timestamp - timestamp) <= windowMs }
        .minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }
        ?: return null

    // Named at last, so the rules get their first real chance at it. Only while it is still
    // uncategorised: a category chosen by hand is never overruled by a rule.
    val named = target.copy(merchant = merchant)
    val filed = if (named.categoryId == null) MerchantRules.categoriseNew(named, rules) else named
    return entries.map { if (it.id == target.id) filed else it }
}

/**
 * The anchor to write when the bank's own balance disagrees with ours, or null when it agrees or
 * the message never carried one.
 *
 * Placed one millisecond after the transaction so it lands after the transaction itself and after
 * any fee line sharing its timestamp, both of which are already counted in the figure the bank just
 * reported. An anchor on the same millisecond would have them counted twice.
 *
 * Recorded as an ordinary visible ANCHOR rather than a silent correction, so it sits in the list
 * saying what it did and can be deleted like anything else if the bank was talking about a
 * different account.
 */
fun autoResyncAnchor(
    reportedBalanceMinor: Long?,
    currentBalanceMinor: Long,
    timestamp: Long,
): Entry? {
    if (reportedBalanceMinor == null) return null
    if (reportedBalanceMinor == currentBalanceMinor) return null
    val drift = reportedBalanceMinor - currentBalanceMinor
    val direction = if (drift > 0) "higher" else "lower"
    return Entry(
        type = EntryType.ANCHOR,
        amountMinor = reportedBalanceMinor,
        timestamp = timestamp + 1,
        source = Source.SMS,
        note = "Auto re-synced from your bank: ${Money.formatMinor(kotlin.math.abs(drift))} EGP $direction " +
            "than recorded",
    )
}

/**
 * Single source of truth for the ledger. It is an `object` (singleton) so the UI, the
 * notification listener, and the SMS receiver all read/write the same in-memory state.
 * All of them run in the same app process, so this is safe; writes are synchronized.
 */
object LedgerRepository {

    /** Two auto-captures with the same amount + direction inside this window are the same txn. */
    private const val DEDUPE_WINDOW_MS = 20 * 60 * 1000L
    private const val MAX_CAPTURES = 100

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private lateinit var file: File
    private val lock = Any()

    private val _data = MutableStateFlow(LedgerData())
    val data: StateFlow<LedgerData> = _data.asStateFlow()

    /** In-memory only: whether the user has passed the app lock this process. Resets on cold start. */
    private val _sessionUnlocked = MutableStateFlow(false)
    val sessionUnlocked: StateFlow<Boolean> = _sessionUnlocked.asStateFlow()

    fun markUnlocked() { _sessionUnlocked.value = true }
    fun lockSession() { _sessionUnlocked.value = false }

    // When we deliberately send the user to a system screen (permissions, settings), we don't
    // want the onStop re-lock to fire and bounce them to the passcode. This one-shot flag skips it.
    @Volatile private var skipNextLock = false
    fun suppressNextLock() { skipNextLock = true }
    fun consumeSkipLock(): Boolean { val s = skipNextLock; skipNextLock = false; return s }

    fun init(context: Context) {
        synchronized(lock) {
            if (::file.isInitialized) return
            file = File(context.applicationContext.filesDir, "ledger.enc")
            // A file that exists but will not decrypt must never be mistaken for a first launch.
            // The else branch below persists an empty ledger immediately, which would overwrite the
            // only copy of everything the user has ever recorded, silently and unrecoverably. Copy
            // it aside first, exactly as the unreadable-JSON path does.
            if (SecureStore.existsButUnreadable(file)) {
                runCatching { file.copyTo(File(file.parentFile, "ledger.enc.bak"), overwrite = true) }
                return
            }
            val decrypted = SecureStore.readString(file)
            if (decrypted != null) {
                runCatching { json.decodeFromString<LedgerData>(decrypted) }
                    .onSuccess { loaded ->
                        _data.value = loaded
                        Migration.apply(loaded)?.let { migrated ->
                            _data.value = migrated
                            persist(migrated)
                        }
                    }
                    .onFailure {
                        // We keep the empty in-memory ledger, and the next write would overwrite
                        // the file with it. Copy the unreadable original first: it is encrypted,
                        // it is the only copy, and a schema bug must not be able to destroy it.
                        runCatching { file.copyTo(File(file.parentFile, "ledger.enc.bak"), overwrite = true) }
                    }
            } else {
                // A first launch has no file, but it still needs the migration: the rules the app
                // ships are added by it, and a brand new install skipping it would be the only
                // install that never got them.
                val fresh = Migration.apply(_data.value) ?: _data.value
                _data.value = fresh
                persist(fresh)
            }
        }
    }

    // ---- balance math -------------------------------------------------------

    /** balance = latest anchor + signed sum of every transaction recorded after it. */
    fun balanceMinor(d: LedgerData = _data.value): Long {
        val anchor = d.entries.filter { it.type == EntryType.ANCHOR }.maxByOrNull { it.timestamp }
        val anchorTs = anchor?.timestamp ?: Long.MIN_VALUE
        val base = anchor?.amountMinor ?: 0L
        val delta = d.entries
            .filter { it.type != EntryType.ANCHOR && it.timestamp > anchorTs }
            .sumOf { if (it.type == EntryType.CREDIT) it.amountMinor else -it.amountMinor }
        return base + delta
    }

    /** The most recent re-sync, however it got there, or null if never anchored. */
    fun lastAnchor(d: LedgerData = _data.value): Entry? =
        d.entries.filter { it.type == EntryType.ANCHOR }.maxByOrNull { it.timestamp }

    /** Millis since the last "Set balance" anchor, or null if never anchored. */
    fun lastAnchorTimestamp(d: LedgerData = _data.value): Long? = lastAnchor(d)?.timestamp

    /** Pure dedupe rule (extracted for tests): same direction + amount from an auto channel within the window. */
    fun isDuplicateAuto(entries: List<Entry>, type: EntryType, amountMinor: Long, timestamp: Long): Boolean =
        entries.any { e ->
            e.type == type && e.amountMinor == amountMinor &&
                (e.source == Source.NOTIFICATION || e.source == Source.SMS) &&
                kotlin.math.abs(e.timestamp - timestamp) <= DEDUPE_WINDOW_MS
        }

    // ---- mutations ----------------------------------------------------------

    fun addManual(
        type: EntryType,
        amountMinor: Long,
        note: String,
        timestamp: Long,
        categoryId: String? = null,
    ) {
        require(type != EntryType.ANCHOR)
        var overCategories: List<CategoryBudgetStatus> = emptyList()
        val milestone = synchronized(lock) {
            addEntryLocked(Entry(type = type, amountMinor = amountMinor, timestamp = timestamp,
                source = Source.MANUAL, note = note, categoryId = categoryId))
            val m = evaluateBudgetLocked(timestamp)
            overCategories = evaluateCategoryBudgetsLocked(timestamp)
            m
        }
        postMilestone(milestone)
        overCategories.forEach { onCategoryBudget?.invoke(it) }
    }

    fun setBalance(amountMinor: Long, note: String, timestamp: Long) {
        addEntry(Entry(type = EntryType.ANCHOR, amountMinor = amountMinor, timestamp = timestamp,
            source = Source.MANUAL, note = note))
    }

    /**
     * Saved on an explicit tap rather than on every keystroke: a write re-encrypts and rewrites the
     * whole ledger, which is not something to do per character typed.
     */
    fun setNote(id: String, note: String) = mutate { it.copy(entries = applyNote(it.entries, id, note)) }

    /**
     * Returns the entry it removed, so the caller can offer Undo without snapshotting the ledger
     * itself. Null when the id is already gone, which is what a double tap looks like.
     */
    fun deleteEntry(id: String): Entry? = synchronized(lock) {
        val removed = _data.value.entries.firstOrNull { it.id == id } ?: return@synchronized null
        val next = _data.value.copy(entries = _data.value.entries.filterNot { it.id == id })
        _data.value = next
        persist(next)
        removed
    }

    /**
     * Puts back exactly what [deleteEntry] returned, id included, so anything else holding that id
     * still points at the same entry. Re-sorted on the way in, because an entry restored minutes
     * later still belongs at its own moment in the ledger, not at the end of it.
     */
    fun restoreEntry(entry: Entry) = synchronized(lock) {
        if (_data.value.entries.any { it.id == entry.id }) return@synchronized
        addEntryLocked(entry)
    }

    /**
     * Called by the notification listener and the SMS receiver. Applies the 20-minute
     * cross-source dedupe: if an auto-entry with the same direction + amount already exists
     * within the window, this one is dropped (so a txn seen on BOTH channels counts once).
     * Returns true if it was actually added.
     */
    fun addAuto(
        parsed: ParsedTxn,
        source: Source,
        rawText: String,
        timestamp: Long,
        allowAutoResync: Boolean = true,
        offerTriage: Boolean = true,
    ): Boolean {
        var milestone: Int? = null
        var uncategorised: Entry? = null
        var overCategories: List<CategoryBudgetStatus> = emptyList()
        val added = synchronized(lock) {
            if (isDuplicateAuto(_data.value.entries, parsed.type, parsed.amountMinor, timestamp)) {
                // Dropped as a duplicate, but not before taking the one thing it might know that
                // the entry we kept does not: who the money was actually from.
                mergeTwinInto(
                    _data.value.entries, parsed.type, parsed.amountMinor, timestamp,
                    parsed.merchant, _data.value.merchantRules,
                )?.let { merged ->
                    val next = _data.value.copy(entries = merged)
                    _data.value = next
                    persist(next)
                }
                return@synchronized false
            }
            // Rules run here, inside the lock, which is what lets a transaction captured while the
            // app is closed arrive already filed.
            val entry = MerchantRules.categoriseNew(
                Entry(type = parsed.type, amountMinor = parsed.amountMinor,
                    timestamp = timestamp, source = source, rawText = rawText,
                    merchant = parsed.merchant),
                _data.value.merchantRules,
            )
            addEntryLocked(entry)
            if (offerTriage && worthTriaging(entry)) uncategorised = entry

            // InstaPay sends arrive as an EGBANK SMS marked "IPN REF#" (card/ATM SMS aren't).
            // The SMS reports the transfer amount; InstaPay's fee is separate, so add it as its own
            // line to keep the balance accurate. If your bank instead bundles the fee into the
            // charged amount, or sends a separate fee SMS, turn this off in settings.
            val d = _data.value
            val isInstapaySend = parsed.type == EntryType.DEBIT &&
                (source == Source.NOTIFICATION ||
                    (source == Source.SMS && rawText.contains("IPN", ignoreCase = true)))
            if (d.instapaySendFeeEnabled && isInstapaySend) {
                val fee = d.instapaySendFeeMinor(parsed.amountMinor)
                if (fee > 0) {
                    // A fee has no merchant and no rule; it is always its own category.
                    addEntryLocked(Entry(type = EntryType.DEBIT, amountMinor = fee, timestamp = timestamp,
                        source = Source.FEE, note = "InstaPay transfer fee",
                        categoryId = Categories.FEES))
                }
            }
            // Last, so it compares against the balance with this transaction and its fee already
            // in. The bank's figure is what the account actually holds; anything we are missing
            // (a message that never arrived, cash the app cannot see) is the gap it closes.
            //
            // Off for a backfill. The comparison is between a balance the bank reported weeks ago
            // and the balance as it stands today, which are answers to different questions, and
            // acting on it would move the anchor to a figure that was true a fortnight back.
            if (allowAutoResync) {
                autoResyncAnchor(parsed.reportedBalanceMinor, balanceMinor(_data.value), timestamp)
                    ?.let { addEntryLocked(it) }
            }

            milestone = evaluateBudgetLocked(timestamp)
            overCategories = evaluateCategoryBudgetsLocked(timestamp)
            true
        }
        // Outside the lock on purpose: posting a notification must not be able to hold the ledger
        // lock, and this runs on a binder thread while the app is closed.
        if (added) {
            postMilestone(milestone)
            overCategories.forEach { onCategoryBudget?.invoke(it) }
            uncategorised?.let { onUncategorised?.invoke(it) }
        }
        return added
    }

    /**
     * Writes what a scan of the phone's own SMS store found, at the times the messages arrived.
     *
     * Each one goes through [addAuto], so a backfilled message is filed by the merchant rules and
     * deduped exactly like a live one. The auto re-sync is off: see the note there.
     */
    fun importBackfill(items: List<BackfillItem>): Int =
        items.count {
            addAuto(
                it.parsed, Source.SMS, it.rawText, it.message.sentAt,
                allowAutoResync = false,
                // A scan of a year of history would post a notification per transaction. The
                // triage offer is about the moment something happens, and none of these are that.
                offerTriage = false,
            )
        }

    private fun addEntry(entry: Entry) = synchronized(lock) { addEntryLocked(entry) }

    private fun addEntryLocked(entry: Entry) {
        val next = _data.value.copy(entries = (_data.value.entries + entry).sortedBy { it.timestamp })
        _data.value = next
        persist(next)
    }

    // ---- budget -------------------------------------------------------------

    /**
     * Posted when a spend crosses a milestone. Set once by [App]; kept as a callback so the ledger
     * never imports NotificationManager and stays unit-testable.
     */
    @Volatile var onBudgetMilestone: ((milestone: Int, spentMinor: Long, limitMinor: Long) -> Unit)? = null

    /**
     * Fired for a captured entry nobody could file automatically, and again with the id when any
     * entry gets a category, so an offer already in the shade can be taken down. Callbacks rather
     * than direct calls for the same reason as the milestone above: the ledger never imports
     * NotificationManager and stays unit-testable.
     */
    @Volatile var onUncategorised: ((Entry) -> Unit)? = null
    @Volatile var onCategorised: ((ids: Set<String>) -> Unit)? = null

    /** Posted when a category passes its own limit. One per category per month, see [Budget]. */
    @Volatile var onCategoryBudget: ((CategoryBudgetStatus) -> Unit)? = null

    /**
     * Recomputes the fired-milestone state at the moment of the change rather than leaving it, so
     * lowering a limit below what you have already spent does not immediately fire every milestone
     * underneath it.
     */
    fun setBudget(limitMinor: Long?, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val d = _data.value
        val zone = ZoneId.systemDefault()
        val spent = Insights.spentInMonth(d.entries, Instant.ofEpochMilli(now), zone, Categories.excludedIds(d.categories))
        val next = d.copy(
            monthlyBudgetMinor = limitMinor,
            budgetMonth = Budget.monthKey(Instant.ofEpochMilli(now), zone),
            highestMilestoneFired = if (limitMinor == null) 0
            else Budget.reachedMilestone(spent, limitMinor),
        )
        _data.value = next
        persist(next)
    }

    /**
     * Must be called holding [lock], on the already-updated ledger. Returns the milestone to post,
     * which the caller does outside the lock: a notification failure must not be able to hold the
     * ledger lock or strand an entry that is already persisted.
     */
    private fun evaluateBudgetLocked(timestamp: Long): Int? {
        val d = _data.value
        val limit = d.monthlyBudgetMinor ?: return null
        if (limit <= 0L) return null

        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestamp)
        val month = Budget.monthKey(instant, zone)

        // The reset happens on the next transaction, which is the only moment it can matter. No
        // alarm, no scheduled job, nothing running while the app is closed.
        val fired = if (d.budgetMonth == month) d.highestMilestoneFired else 0

        val spent = Insights.spentInMonth(d.entries, instant, zone, Categories.excludedIds(d.categories))
        val toFire = Budget.milestoneToFire(spent, limit, fired)

        if (toFire != null || d.budgetMonth != month) {
            val next = d.copy(
                budgetMonth = month,
                highestMilestoneFired = toFire ?: fired,
                // Both live off this one month stamp, and this runs first. See the note on
                // carryOverCategoryMilestones for what goes wrong without this line.
                categoryMilestones = Budget.carryOverCategoryMilestones(d.budgetMonth, month, d.categoryMilestones),
            )
            _data.value = next
            persist(next)
        }
        return toFire
    }

    /**
     * Must be called holding [lock], on the already-updated ledger. Returns the categories that
     * have just gone over, which the caller posts outside the lock.
     *
     * Runs on filing as well as on capture, and that is the important half: almost everything
     * arrives uncategorised, so the moment a category goes over its limit is usually the moment you
     * file something into it, not the moment the money moved.
     */
    private fun evaluateCategoryBudgetsLocked(timestamp: Long): List<CategoryBudgetStatus> {
        val d = _data.value
        if (d.categories.none { (it.budgetMinor ?: 0L) > 0L }) return emptyList()

        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestamp)
        val month = Budget.monthKey(instant, zone)
        // Shares budgetMonth with the monthly budget, so both reset on the same transaction.
        val fired = Budget.carryOverCategoryMilestones(d.budgetMonth, month, d.categoryMilestones)

        val toFire = Budget.categoryStatuses(d.entries, d.categories, instant, zone)
            .filter { Budget.categoryMilestoneToFire(it.spentMinor, it.limitMinor, fired[it.category.id] ?: 0) != null }

        val sameMonth = d.budgetMonth == month
        if (toFire.isEmpty() && sameMonth) return emptyList()

        val next = d.copy(
            budgetMonth = month,
            // This pass can be the first write of a new month (filing from the inbox on the 1st
            // before any SMS lands), and stamping the month without clearing the monthly ladder
            // would make the stale high-water mark look like this month's.
            highestMilestoneFired = Budget.carryOverMilestone(d.budgetMonth, month, d.highestMilestoneFired),
            categoryMilestones = fired + toFire.associate {
                it.category.id to Budget.categoryReached(it.spentMinor, it.limitMinor)
            },
        )
        _data.value = next
        persist(next)
        return toFire
    }

    private fun postMilestone(milestone: Int?) {
        val d = _data.value
        val limit = d.monthlyBudgetMinor ?: return
        if (milestone == null) return
        val spent = Insights.spentInMonth(d.entries, Instant.now(), ZoneId.systemDefault(), Categories.excludedIds(d.categories))
        onBudgetMilestone?.invoke(milestone, spent, limit)
    }

    // ---- categories + merchant rules ----------------------------------------

    /**
     * Files [ids] under [categoryId]. Returns what they were, so the Snackbar can offer Undo
     * without the UI having to snapshot the ledger itself.
     *
     * Entries are addressed by id, never by handing a list back, so an entry captured in the
     * background between the UI reading the flow and the user tapping cannot be dropped.
     */
    fun setCategory(ids: Set<String>, categoryId: String?): Map<String, Pair<String?, Boolean>> {
        var overCategories: List<CategoryBudgetStatus> = emptyList()
        val previous = synchronized(lock) {
            val was = _data.value.entries
                .filter { it.id in ids }
                .associate { it.id to (it.categoryId to it.categoryFromRule) }
            val next = _data.value.copy(entries = applyCategory(_data.value.entries, ids, categoryId))
            _data.value = next
            persist(next)
            // Filing is when a per-category limit is usually crossed: the money moved days ago,
            // uncategorised, and counted against nothing until now.
            overCategories = evaluateCategoryBudgetsLocked(System.currentTimeMillis())
            was
        }
        // Outside the lock, like the other callbacks. Takes down any triage offer still sitting in
        // the shade for these entries, however they came to be filed.
        if (categoryId != null) onCategorised?.invoke(ids)
        overCategories.forEach { onCategoryBudget?.invoke(it) }
        return previous
    }

    /** Restores exactly what [setCategory] returned, rule flag included. */
    fun restoreCategories(previous: Map<String, Pair<String?, Boolean>>) = synchronized(lock) {
        val next = _data.value.copy(
            entries = _data.value.entries.map { e ->
                previous[e.id]?.let { (cat, fromRule) -> e.copy(categoryId = cat, categoryFromRule = fromRule) } ?: e
            }
        )
        _data.value = next
        persist(next)
    }

    /**
     * Null when the name was blank or already taken, which [Categories.add] refuses.
     *
     * Nullable rather than an empty string so a caller cannot treat a refusal as an id: filing an
     * entry under "" hides it from the review inbox forever, and the picker also writes a merchant
     * rule pointing at the same nothing, which then swallows every later transaction from that shop.
     */
    fun addCategory(name: String, kind: CategoryKind, colorIndex: Int): String? {
        var newId: String? = null
        synchronized(lock) {
            val categories = Categories.add(_data.value.categories, name, kind, colorIndex)
            if (categories.size == _data.value.categories.size) return@synchronized
            val next = _data.value.copy(categories = categories)
            newId = categories.last().id
            _data.value = next
            persist(next)
        }
        return newId
    }

    fun renameCategory(id: String, name: String) = mutate {
        it.copy(categories = Categories.rename(it.categories, id, name))
    }

    fun recolourCategory(id: String, colorIndex: Int) = mutate {
        it.copy(categories = Categories.recolour(it.categories, id, colorIndex))
    }

    fun setCategoryHidden(id: String, hidden: Boolean) = mutate {
        it.copy(categories = Categories.setHidden(it.categories, id, hidden))
    }

    /**
     * Recomputes the fired-milestone state, the same way [setBudget] does. Excluding a category
     * mid-month lowers what counts as spent, and leaving the old milestone standing would mean the
     * alert you already got blocks the one you should get when you cross the line for real.
     */
    fun setCategoryExcludedFromBudget(id: String, excluded: Boolean, now: Long = System.currentTimeMillis()) =
        mutate { d ->
            val categories = Categories.setExcludedFromBudget(d.categories, id, excluded)
            val limit = d.monthlyBudgetMinor
            if (limit == null || limit <= 0L) {
                d.copy(categories = categories)
            } else {
                val zone = ZoneId.systemDefault()
                val spent = Insights.spentInMonth(
                    d.entries, Instant.ofEpochMilli(now), zone, Categories.excludedIds(categories),
                )
                d.copy(
                    categories = categories,
                    budgetMonth = Budget.monthKey(Instant.ofEpochMilli(now), zone),
                    highestMilestoneFired = Budget.reachedMilestone(spent, limit),
                )
            }
        }

    /**
     * Clears this category's fired state along with the limit, so lowering a limit below what you
     * have already spent alerts you once for the new limit rather than staying silent because the
     * old one had already fired.
     */
    fun setCategoryBudget(id: String, limitMinor: Long?) = mutate { d ->
        d.copy(
            categories = d.categories.map {
                if (it.id == id) it.copy(budgetMinor = limitMinor?.takeIf { l -> l > 0L }) else it
            },
            categoryMilestones = d.categoryMilestones - id,
        )
    }

    fun deleteCategory(id: String) = mutate { Categories.delete(it, id) }

    /**
     * Returns the rule it created, or null when the pattern normalises to nothing. Callers need the
     * rule itself: reaching for `merchantRules.last()` afterwards throws on the first rule and picks
     * an unrelated one after that, because this can legitimately add nothing.
     */
    fun addRule(
        pattern: String,
        categoryId: String,
        now: Long,
        label: String? = null,
    ): MerchantRule? {
        val clean = MerchantRules.normalise(pattern)
        if (clean.isEmpty()) return null
        val rule = MerchantRule(
            pattern = clean, categoryId = categoryId, createdAt = now,
            label = label?.trim()?.takeIf { it.isNotEmpty() },
        )
        mutate { it.copy(merchantRules = it.merchantRules + rule) }
        return rule
    }

    fun updateRule(id: String, pattern: String, categoryId: String, label: String? = null) = mutate { d ->
        d.copy(merchantRules = d.merchantRules.map {
            if (it.id == id) {
                it.copy(
                    pattern = MerchantRules.normalise(pattern),
                    categoryId = categoryId,
                    label = label?.trim()?.takeIf { l -> l.isNotEmpty() },
                )
            } else {
                it
            }
        })
    }

    fun deleteRule(id: String) = mutate { d ->
        d.copy(merchantRules = d.merchantRules.filterNot { it.id == id })
    }

    /**
     * Moves the entries [rule] filed itself to [categoryId], leaving hand-filed ones alone. Without
     * this, changing a rule's category leaves everything it previously filed pointing at the old
     * one, disagreeing with the rule that put them there. Opt-in and counted in the UI first.
     */
    fun recategoriseRuleOwned(rule: MerchantRule, categoryId: String): Int {
        var moved = 0
        synchronized(lock) {
            val next = _data.value.entries.map { e ->
                val ownedByRule = e.categoryFromRule && e.categoryId == rule.categoryId &&
                    MerchantRules.match(listOf(rule), e.merchant) != null
                if (ownedByRule) {
                    moved++
                    e.copy(categoryId = categoryId, categoryFromRule = true)
                } else {
                    e
                }
            }
            if (moved > 0) {
                val d = _data.value.copy(entries = next)
                _data.value = d
                persist(d)
            }
        }
        return moved
    }

    /** Opt-in and counted in the UI first; a rule never rewrites history on its own. */
    fun applyRuleToUncategorised(rule: MerchantRule): Int {
        var count = 0
        synchronized(lock) {
            val (entries, changed) = MerchantRules.applyToUncategorised(_data.value.entries, rule)
            count = changed
            if (changed > 0) {
                val next = _data.value.copy(entries = entries)
                _data.value = next
                persist(next)
            }
        }
        return count
    }

    /**
     * Replaces the ledger with the contents of a backup file, keeping this device's own app lock.
     * Runs the same migration a file read from disk gets, so a backup written by an older build
     * lands in the shape the current one expects rather than one step behind it.
     */
    fun importBackup(imported: LedgerData) = mutate { current ->
        val merged = Backup.forImport(current, imported)
        Migration.apply(merged) ?: merged
    }

    /**
     * Replaces the ledger with generated sample data. Only ever called from a debug build: it
     * destroys whatever is there, which is the point on a machine that has nothing worth keeping.
     */
    fun loadSampleData(now: Long = System.currentTimeMillis()) = mutate {
        LedgerData(
            entries = SampleData.generate(now),
            merchantRules = SampleData.rules(now),
            // Deliberately just under 75 percent of a typical month, so the next manual expense
            // demonstrates the milestone alert.
            monthlyBudgetMinor = 1_200_000,
            // Keep whatever lock the user has set up; wiping it mid-session would lock them out.
            passcodeHash = it.passcodeHash,
            passcodeSalt = it.passcodeSalt,
            biometricEnabled = it.biometricEnabled,
        )
    }

    /** Debug-only companion to [loadSampleData], for getting back to an empty app. */
    fun clearAllEntries() = mutate {
        it.copy(entries = emptyList(), merchantRules = emptyList(), highestMilestoneFired = 0)
    }

    /** One lock, one persist, for the many small settings mutations that all look the same. */
    private inline fun mutate(block: (LedgerData) -> LedgerData) = synchronized(lock) {
        val next = block(_data.value)
        _data.value = next
        persist(next)
    }

    // ---- settings + learning mode ------------------------------------------

    fun setLearningMode(on: Boolean) = synchronized(lock) {
        val next = _data.value.copy(learningMode = on)
        _data.value = next
        persist(next)
    }

    fun setSmsConfig(config: SmsConfig) = mutate { it.copy(smsConfig = config) }

    fun setTriageAlertsEnabled(on: Boolean) = mutate { it.copy(triageAlertsEnabled = on) }

    fun setPendingReminderEnabled(on: Boolean) = mutate { it.copy(pendingReminderEnabled = on) }

    fun setAutoBackupFrequency(f: BackupFrequency) = mutate { it.copy(autoBackupFrequency = f) }

    fun setAutoBackupKeep(keep: Int) = mutate { it.copy(autoBackupKeep = keep) }

    /** Recorded after a run, successful or not, so Settings can say what actually happened. */
    fun recordAutoBackup(at: Long, fingerprint: String, result: String) = mutate {
        it.copy(
            autoBackupLastRunAt = at,
            autoBackupLastFingerprint = fingerprint,
            autoBackupLastResult = result,
        )
    }

    fun setWatchedPackages(packages: List<String>) = synchronized(lock) {
        val next = _data.value.copy(watchedPackages = packages.map { it.trim() }.filter { it.isNotEmpty() })
        _data.value = next
        persist(next)
    }

    fun addCapture(channel: String, packageOrSender: String, text: String, timestamp: Long) =
        synchronized(lock) {
            val capture = DebugCapture(timestamp, channel, packageOrSender, text)
            val next = _data.value.copy(
                captures = (listOf(capture) + _data.value.captures).take(MAX_CAPTURES)
            )
            _data.value = next
            persist(next)
        }

    fun clearCaptures() = synchronized(lock) {
        val next = _data.value.copy(captures = emptyList())
        _data.value = next
        persist(next)
    }

    // ---- app lock (passcode + biometric) -----------------------------------

    fun setPasscode(pin: String) = synchronized(lock) {
        val salt = PasscodeCrypto.newSalt()
        val next = _data.value.copy(
            passcodeSalt = salt,
            passcodeHash = PasscodeCrypto.hash(pin, salt),
        )
        _data.value = next
        persist(next)
        _sessionUnlocked.value = true  // they set it while already inside the app; don't lock them out
    }

    fun verifyPasscode(pin: String): Boolean {
        val d = _data.value
        val hash = d.passcodeHash ?: return false
        val salt = d.passcodeSalt ?: return false
        return PasscodeCrypto.verify(pin, salt, hash)
    }

    fun clearPasscode() = synchronized(lock) {
        val next = _data.value.copy(passcodeHash = null, passcodeSalt = null, biometricEnabled = false)
        _data.value = next
        persist(next)
    }

    fun setBiometricEnabled(on: Boolean) = synchronized(lock) {
        val next = _data.value.copy(biometricEnabled = on)
        _data.value = next
        persist(next)
    }

    fun setFeeConfig(enabled: Boolean, percentBps: Int, minMinor: Long, capMinor: Long?) = synchronized(lock) {
        val next = _data.value.copy(
            instapaySendFeeEnabled = enabled,
            feePercentBps = percentBps.coerceAtLeast(0),
            feeMinMinor = minMinor.coerceAtLeast(0),
            feeCapMinor = capMinor,
        )
        _data.value = next
        persist(next)
    }

    private fun persist(d: LedgerData) {
        runCatching { SecureStore.writeString(file, json.encodeToString(d)) }
    }
}

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

/** What the parser hands back for an auto-captured transaction. */
data class ParsedTxn(val type: EntryType, val amountMinor: Long, val merchant: String? = null)

/**
 * Pure category assignment, kept out of the repository so it can be unit-tested without the
 * Keystore. ANCHOR entries and unknown ids are silently left alone: an anchor is a re-sync, not
 * spending, so it must never carry a category.
 */
fun applyCategory(entries: List<Entry>, ids: Set<String>, categoryId: String?): List<Entry> =
    entries.map { e ->
        if (e.id in ids && e.type != EntryType.ANCHOR) {
            e.copy(categoryId = categoryId, categoryFromRule = false)
        } else {
            e
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
                persist(_data.value)
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

    /** Millis since the last "Set balance" anchor, or null if never anchored. */
    fun lastAnchorTimestamp(d: LedgerData = _data.value): Long? =
        d.entries.filter { it.type == EntryType.ANCHOR }.maxOfOrNull { it.timestamp }

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
        val milestone = synchronized(lock) {
            addEntryLocked(Entry(type = type, amountMinor = amountMinor, timestamp = timestamp,
                source = Source.MANUAL, note = note, categoryId = categoryId))
            evaluateBudgetLocked(timestamp)
        }
        postMilestone(milestone)
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

    fun deleteEntry(id: String) = synchronized(lock) {
        val next = _data.value.copy(entries = _data.value.entries.filterNot { it.id == id })
        _data.value = next
        persist(next)
    }

    /**
     * Called by the notification listener and the SMS receiver. Applies the 20-minute
     * cross-source dedupe: if an auto-entry with the same direction + amount already exists
     * within the window, this one is dropped (so a txn seen on BOTH channels counts once).
     * Returns true if it was actually added.
     */
    fun addAuto(parsed: ParsedTxn, source: Source, rawText: String, timestamp: Long): Boolean {
        var milestone: Int? = null
        val added = synchronized(lock) {
            if (isDuplicateAuto(_data.value.entries, parsed.type, parsed.amountMinor, timestamp)) {
                return@synchronized false
            }
            // Rules run here, inside the lock, which is what lets a transaction captured while the
            // app is closed arrive already filed.
            addEntryLocked(
                MerchantRules.categoriseNew(
                    Entry(type = parsed.type, amountMinor = parsed.amountMinor,
                        timestamp = timestamp, source = source, rawText = rawText,
                        merchant = parsed.merchant),
                    _data.value.merchantRules,
                )
            )

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
            milestone = evaluateBudgetLocked(timestamp)
            true
        }
        // Outside the lock on purpose: posting a notification must not be able to hold the ledger
        // lock, and this runs on a binder thread while the app is closed.
        if (added) postMilestone(milestone)
        return added
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
            )
            _data.value = next
            persist(next)
        }
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
    fun setCategory(ids: Set<String>, categoryId: String?): Map<String, Pair<String?, Boolean>> =
        synchronized(lock) {
            val previous = _data.value.entries
                .filter { it.id in ids }
                .associate { it.id to (it.categoryId to it.categoryFromRule) }
            val next = _data.value.copy(entries = applyCategory(_data.value.entries, ids, categoryId))
            _data.value = next
            persist(next)
            previous
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

    fun addCategory(name: String, kind: CategoryKind, colorIndex: Int): String {
        var newId = ""
        synchronized(lock) {
            val next = _data.value.copy(
                categories = Categories.add(_data.value.categories, name, kind, colorIndex)
            )
            newId = next.categories.last().id
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

    fun deleteCategory(id: String) = mutate { Categories.delete(it, id) }

    /**
     * Returns the rule it created, or null when the pattern normalises to nothing. Callers need the
     * rule itself: reaching for `merchantRules.last()` afterwards throws on the first rule and picks
     * an unrelated one after that, because this can legitimately add nothing.
     */
    fun addRule(pattern: String, categoryId: String, now: Long): MerchantRule? {
        val clean = MerchantRules.normalise(pattern)
        if (clean.isEmpty()) return null
        val rule = MerchantRule(pattern = clean, categoryId = categoryId, createdAt = now)
        mutate { it.copy(merchantRules = it.merchantRules + rule) }
        return rule
    }

    fun updateRule(id: String, pattern: String, categoryId: String) = mutate { d ->
        d.copy(merchantRules = d.merchantRules.map {
            if (it.id == id) it.copy(pattern = MerchantRules.normalise(pattern), categoryId = categoryId) else it
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

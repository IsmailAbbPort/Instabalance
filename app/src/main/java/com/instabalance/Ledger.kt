package com.instabalance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
data class ParsedTxn(val type: EntryType, val amountMinor: Long)

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
                    .onSuccess { _data.value = it }
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

    fun addManual(type: EntryType, amountMinor: Long, note: String, timestamp: Long) {
        require(type != EntryType.ANCHOR)
        addEntry(Entry(type = type, amountMinor = amountMinor, timestamp = timestamp,
            source = Source.MANUAL, note = note))
    }

    fun setBalance(amountMinor: Long, note: String, timestamp: Long) {
        addEntry(Entry(type = EntryType.ANCHOR, amountMinor = amountMinor, timestamp = timestamp,
            source = Source.MANUAL, note = note))
    }

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
    fun addAuto(parsed: ParsedTxn, source: Source, rawText: String, timestamp: Long): Boolean =
        synchronized(lock) {
            if (isDuplicateAuto(_data.value.entries, parsed.type, parsed.amountMinor, timestamp)) return false
            addEntryLocked(Entry(type = parsed.type, amountMinor = parsed.amountMinor,
                timestamp = timestamp, source = source, rawText = rawText))

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
                    addEntryLocked(Entry(type = EntryType.DEBIT, amountMinor = fee, timestamp = timestamp,
                        source = Source.FEE, note = "InstaPay transfer fee"))
                }
            }
            true
        }

    private fun addEntry(entry: Entry) = synchronized(lock) { addEntryLocked(entry) }

    private fun addEntryLocked(entry: Entry) {
        val next = _data.value.copy(entries = (_data.value.entries + entry).sortedBy { it.timestamp })
        _data.value = next
        persist(next)
    }

    // ---- settings + learning mode ------------------------------------------

    fun setLearningMode(on: Boolean) = synchronized(lock) {
        val next = _data.value.copy(learningMode = on)
        _data.value = next
        persist(next)
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

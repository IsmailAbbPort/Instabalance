package com.instabalance

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The on-disk shape of an exported backup.
 *
 * The ledger itself is encrypted with a key held in the Android Keystore, and that key dies with
 * the app: uninstall, a factory reset or a lost phone leaves `ledger.enc` as undecryptable bytes
 * that no backup can rescue. This file is the only way data gets out, so it is deliberately plain
 * JSON. Encrypting it would recreate the exact problem it exists to solve.
 *
 * [format] and [schema] are carried so a file can be recognised (and refused) rather than fed to
 * the decoder and half-read. [exportedAt] and [appVersion] are for the human reading it later.
 */
@Serializable
data class BackupFile(
    val format: String = Backup.FORMAT,
    val schema: Int = Backup.SCHEMA,
    val exportedAt: Long,
    val appVersion: String,
    val data: LedgerData,
)

/** What an import is about to do, shown to the user before anything is written. */
data class BackupSummary(
    val entryCount: Int,
    val categoryCount: Int,
    val ruleCount: Int,
    val balanceMinor: Long,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
)

/** Why a file could not be imported, in words worth showing on screen. */
sealed interface BackupParse {
    data class Ok(val file: BackupFile) : BackupParse
    data class Failed(val reason: String) : BackupParse
}

object Backup {

    const val FORMAT = "instabalance-backup"
    const val SCHEMA = 1

    // Lenient on read so a file written by an older build (fewer fields) still imports, and strict
    // about writing every field so the export never depends on the reader's defaults matching.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val fileNameFmt = DateTimeFormatter
        .ofPattern("yyyy-MM-dd-HHmm", Locale.ENGLISH)
    private val readableFmt = DateTimeFormatter
        .ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * Everything worth keeping, minus everything that should not travel.
     *
     * The passcode hash and its salt are left behind on purpose: restoring them into a fresh
     * install means a lock whose PIN you may no longer remember, guarding data you just restored.
     * Captures are raw notification and SMS text recorded in Learning mode, which is the most
     * sensitive content the app ever holds and has no value in a restore.
     */
    fun forExport(d: LedgerData): LedgerData = d.copy(
        passcodeHash = null,
        passcodeSalt = null,
        biometricEnabled = false,
        learningMode = false,
        captures = emptyList(),
    )

    /**
     * The ledger to persist after an import: the file's content, but with this device's own lock
     * left exactly as it is. An import must never be able to change the passcode that guards it.
     */
    fun forImport(current: LedgerData, imported: LedgerData): LedgerData = imported.copy(
        passcodeHash = current.passcodeHash,
        passcodeSalt = current.passcodeSalt,
        biometricEnabled = current.biometricEnabled,
        learningMode = current.learningMode,
        captures = current.captures,
    )

    fun encode(d: LedgerData, now: Long, appVersion: String): String =
        json.encodeToString(
            BackupFile(exportedAt = now, appVersion = appVersion, data = forExport(d))
        )

    /**
     * Parses a file the user picked. Every failure is a message rather than an exception: the user
     * chose this file from a picker showing every document on the phone, so picking the wrong one
     * is the expected case, not an error.
     */
    fun decode(text: String): BackupParse {
        val file = runCatching { json.decodeFromString<BackupFile>(text) }.getOrElse {
            return BackupParse.Failed("This file is not an InstaBalance backup.")
        }
        if (file.format != FORMAT) {
            return BackupParse.Failed("This file is not an InstaBalance backup.")
        }
        if (file.schema > SCHEMA) {
            return BackupParse.Failed(
                "This backup was made by a newer version of the app. Update the app, then import it."
            )
        }
        return BackupParse.Ok(file)
    }

    fun summarise(d: LedgerData): BackupSummary {
        val stamps = d.entries.map { it.timestamp }
        return BackupSummary(
            entryCount = d.entries.size,
            categoryCount = d.categories.size,
            ruleCount = d.merchantRules.size,
            balanceMinor = LedgerRepository.balanceMinor(d),
            firstTimestamp = stamps.minOrNull(),
            lastTimestamp = stamps.maxOrNull(),
        )
    }

    fun suggestedFileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "instabalance-${Instant.ofEpochMilli(now).atZone(zone).format(fileNameFmt)}.json"

    /** "12 Mar 2026 to 19 Sep 2026", or null when the backup holds no transactions at all. */
    fun describeRange(s: BackupSummary, zone: ZoneId = ZoneId.systemDefault()): String? {
        val first = s.firstTimestamp ?: return null
        val last = s.lastTimestamp ?: return null
        val from = Instant.ofEpochMilli(first).atZone(zone).format(readableFmt)
        val to = Instant.ofEpochMilli(last).atZone(zone).format(readableFmt)
        return if (from == to) from else "$from to $to"
    }
}

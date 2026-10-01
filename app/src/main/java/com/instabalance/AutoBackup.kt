package com.instabalance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How often a backup writes itself. [OFF] is the default: nothing leaves the app unasked. */
enum class BackupFrequency(val label: String) {
    OFF("Off"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
}

/**
 * Deciding when a backup is due, what to call it, and which old ones to let go.
 *
 * Pure: no clock of its own, no MediaStore, no AlarmManager. [AutoBackupAlarm] does all three and
 * decides nothing, which is the same split [Reminders] and [ReminderAlarm] use.
 */
object AutoBackup {

    /** Created inside the phone's Downloads. Public on purpose: a backup that dies with the app is not one. */
    const val FOLDER = "InstaBalance"

    const val PREFIX = "instabalance-auto-"

    /** Keep every backup ever written. The user prunes by hand, or not at all. */
    const val KEEP_EVERYTHING = 0

    val KEEP_CHOICES = listOf(3, 6, 12, 24, KEEP_EVERYTHING)

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ENGLISH)

    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "$PREFIX${Instant.ofEpochMilli(now).atZone(zone).format(stamp)}.json"

    fun keepLabel(keep: Int): String =
        if (keep == KEEP_EVERYTHING) "Keep everything" else "Keep the newest $keep"

    /**
     * When the next backup is owed, or null when the feature is off.
     *
     * Months are counted as months rather than as thirty days, so a monthly backup taken on the
     * 31st does not walk backwards through the calendar. [java.time] clamps a short month for us,
     * which is the behaviour anybody would expect and nobody would write correctly by hand.
     */
    fun dueAt(lastRunAt: Long, frequency: BackupFrequency, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (frequency == BackupFrequency.OFF) return null
        // Never run: owed immediately, so turning it on gives you a backup now rather than in a month.
        if (lastRunAt <= 0L) return 0L
        // Total, deliberately. This runs from Application.onCreate, and a stored timestamp near
        // Long.MAX_VALUE makes the arithmetic below overflow: an exception here does not fail a
        // backup, it stops the app starting at all, for good, because the value is already on disk.
        return runCatching {
            val last = Instant.ofEpochMilli(lastRunAt).atZone(zone)
            when (frequency) {
                BackupFrequency.DAILY -> last.plusDays(1)
                BackupFrequency.WEEKLY -> last.plusWeeks(1)
                BackupFrequency.MONTHLY -> last.plusMonths(1)
                BackupFrequency.OFF -> return null
            }.toInstant().toEpochMilli()
        }.getOrElse {
            // A timestamp we cannot do arithmetic on is not a schedule. Treat it as owed now, which
            // writes a backup and replaces the nonsense with a real time.
            0L
        }
    }

    /**
     * True when one is owed now. Checked on every launch as well as on the alarm, so a phone that
     * was off, or an alarm the system dropped under Doze, costs a late backup rather than no backup.
     */
    fun isDue(
        lastRunAt: Long,
        frequency: BackupFrequency,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = dueAt(lastRunAt, frequency, zone)?.let { now >= it } ?: false

    /** Ours, newest last. Names sort by date because the stamp runs widest unit first. */
    fun ours(names: List<String>): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted()

    /**
     * Which files to delete once [keep] is in place, oldest first. Only ever considers files this
     * feature wrote: a backup you exported by hand, or anything else in the folder, is not ours to
     * tidy away.
     */
    fun toDelete(names: List<String>, keep: Int): List<String> {
        if (keep == KEEP_EVERYTHING) return emptyList()
        return ours(names).dropLast(keep)
    }

    /**
     * Whether the ledger has changed since the last backup, as a cheap fingerprint of the content
     * that actually matters. Without it a daily backup writes a byte-identical file every day for a
     * week you did not spend anything, and the retention window then throws away the week you did.
     */
    fun fingerprint(d: LedgerData): String {
        // Fingerprint what the FILE would contain, not a hand-picked subset of it. Listing fields
        // by hand missed categories, rules, limits and the SMS config, so a week spent curating
        // rules with no new transactions looked like "nothing changed" and produced no backup,
        // which is precisely the hand-made state a backup exists to protect.
        //
        // The three fields excluded below are written by the backup run itself, so leaving them in
        // would make every ledger differ from its own last fingerprint and defeat the check.
        val content = Backup.forExport(d).copy(
            entries = d.entries.sortedBy { it.id },
            autoBackupLastRunAt = 0L,
            autoBackupLastFingerprint = "",
            autoBackupLastResult = "",
        )
        return (content.hashCode().toLong() and 0xFFFFFFFFL).toString(16) + "-" + d.entries.size
    }
}

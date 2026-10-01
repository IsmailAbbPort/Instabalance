package com.instabalance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.ZoneId

/**
 * Where the passphrase lives between backups.
 *
 * The alarm fires while nobody is holding the phone, so the passphrase has to be reachable without
 * being typed. It is kept in its own small file encrypted under the same Android Keystore key the
 * ledger uses, which means it is never at rest in plaintext and never leaves this device. The
 * backups themselves stay passphrase-encrypted, so they still open on a phone that has never seen
 * this Keystore, which is the entire point of them.
 */
object BackupPassphrase {

    private lateinit var file: File

    fun init(context: Context) {
        if (!::file.isInitialized) {
            file = File(context.applicationContext.filesDir, "backup-key.enc")
        }
    }

    fun isSet(): Boolean = ::file.isInitialized && file.exists()

    fun get(): String? = if (::file.isInitialized) SecureStore.readString(file) else null

    fun set(passphrase: String) {
        if (::file.isInitialized) runCatching { SecureStore.writeString(file, passphrase) }
    }

    /** Clearing it turns automatic backups off: there is nothing left to encrypt with. */
    fun clear() {
        if (::file.isInitialized) runCatching { file.delete() }
    }
}

/**
 * Writes the backup, schedules the next one, and tidies old ones. Every decision about WHEN and
 * WHICH is in [AutoBackup] and is pure; this file only knows how to talk to AlarmManager and the
 * phone's Downloads folder.
 *
 * Inexact alarms, the same as [ReminderAlarm]: a monthly backup does not need SCHEDULE_EXACT_ALARM,
 * and every launch re-checks whether one is owed, so a dropped alarm costs a late backup and not a
 * missing one.
 */
object AutoBackupAlarm {

    private const val REQUEST_CODE = 4501
    const val ACTION = "com.instabalance.AUTO_BACKUP"

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoBackupReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun sync(context: Context, data: LedgerData, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val due = AutoBackup.dueAt(data.autoBackupLastRunAt, data.autoBackupFrequency)
        if (due == null) {
            manager.cancel(pendingIntent(context))
            return
        }
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                maxOf(due, now + 1_000L),
                pendingIntent(context),
            )
        }
    }

    /**
     * Runs one if it is owed. [force] is the "Back up now" button, which ignores both the schedule
     * and the unchanged-ledger skip, because a button that can decide to do nothing is a bad button.
     */
    fun runIfDue(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        BackupPassphrase.init(context)
        val data = LedgerRepository.data.value

        if (!force && !AutoBackup.isDue(data.autoBackupLastRunAt, data.autoBackupFrequency, now)) return
        if (data.autoBackupFrequency == BackupFrequency.OFF && !force) return

        val passphrase = BackupPassphrase.get()
        if (passphrase.isNullOrEmpty()) {
            LedgerRepository.recordAutoBackup(now, data.autoBackupLastFingerprint, "No passphrase set.")
            return
        }

        val fingerprint = AutoBackup.fingerprint(data)
        if (!force && fingerprint == data.autoBackupLastFingerprint) {
            // Nothing changed. Move the clock on so the next one is a full period away, and leave
            // the fingerprint and the folder alone.
            LedgerRepository.recordAutoBackup(now, fingerprint, "Nothing changed since the last backup.")
            sync(context, LedgerRepository.data.value, now)
            return
        }

        val plain = Backup.encode(data, now, BuildConfig.VERSION_NAME)
        val result = runCatching {
            val name = AutoBackup.fileName(now)
            write(context, name, BackupCrypto.encrypt(plain, passphrase))
            prune(context, data.autoBackupKeep)
            "Saved ${data.entries.size} transactions to Download/${AutoBackup.FOLDER}."
        }.getOrElse { "Could not write the backup: ${it.message ?: "unknown error"}." }

        LedgerRepository.recordAutoBackup(now, fingerprint, result)
        sync(context, LedgerRepository.data.value, now)
    }

    // ---- the folder ---------------------------------------------------------
    //
    // MediaStore on 29 and up, where an app may write into Downloads with no permission at all and
    // the file survives uninstall. Below that there is no MediaStore Downloads collection, so it is
    // the plain File API behind WRITE_EXTERNAL_STORAGE, which the manifest asks for only up to 28.

    private fun relativePath() = "${Environment.DIRECTORY_DOWNLOADS}/${AutoBackup.FOLDER}"

    private fun legacyFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AutoBackup.FOLDER)

    private fun write(context: Context, name: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath())
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads folder refused the file")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("could not open the file for writing")
        } else {
            val folder = legacyFolder()
            if (!folder.exists() && !folder.mkdirs()) error("could not create Download/${AutoBackup.FOLDER}")
            File(folder, name).writeText(text)
        }
    }

    /** Names of our own backups in the folder, newest last. Empty when the folder is not there yet. */
    fun existing(context: Context): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val names = mutableListOf<String>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%${AutoBackup.FOLDER}%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
            AutoBackup.ours(names)
        } else {
            AutoBackup.ours(legacyFolder().list()?.toList() ?: emptyList())
        }
    }.getOrDefault(emptyList())

    private fun prune(context: Context, keep: Int) {
        val doomed = AutoBackup.toDelete(existing(context), keep)
        if (doomed.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            doomed.forEach { name ->
                runCatching {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(name),
                    )
                }
            }
        } else {
            doomed.forEach { runCatching { File(legacyFolder(), it).delete() } }
        }
    }
}

/** Fires the scheduled backup, and re-arms after a reboot. */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        LedgerRepository.init(app)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoBackupAlarm.sync(app, LedgerRepository.data.value)
        } else {
            AutoBackupAlarm.runIfDue(app)
        }
    }
}

package com.instabalance

import android.content.Context
import android.provider.Telephony

/**
 * Reads the phone's own SMS store, which is the only place a message the app missed still exists.
 *
 * Deliberately the whole of the Android dependency for the backfill: what to do with the messages
 * is [SmsBackfill], which is pure and tested. This just fetches rows.
 *
 * Needs READ_SMS, which is a heavier permission than the RECEIVE_SMS the live reader uses. Nothing
 * calls this until the user taps Scan and grants it.
 */
object SmsInbox {

    /** Newest first, as the provider returns them. Empty rather than throwing if the read fails. */
    fun since(context: Context, cutoffMillis: Long, limit: Int = 2_000): List<StoredSms> =
        runCatching {
            val out = mutableListOf<StoredSms>()
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(cutoffMillis.toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (c.moveToNext() && out.size < limit) {
                    out += StoredSms(
                        sender = c.getString(address).orEmpty(),
                        body = c.getString(body).orEmpty(),
                        sentAt = c.getLong(date),
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
}

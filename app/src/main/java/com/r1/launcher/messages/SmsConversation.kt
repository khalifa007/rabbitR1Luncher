package com.r1.launcher.messages

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager

/**
 * One thread = one sender, with the most-recent body and timestamp shown
 * as the row subtitle. The full message list is loaded lazily when the user
 * drills in (kept simple: re-query inbox filtered by address).
 */
data class SmsConversation(
    val address: String,
    val displayName: String,
    val latestBody: String,
    val latestTimestampMs: Long,
    val unreadCount: Int,
    val totalCount: Int,
)

data class SmsItem(
    val id: Long,
    val address: String,
    val body: String,
    val timestampMs: Long,
    val read: Boolean,
    val incoming: Boolean,
)

object SmsLoader {

    /** Group by address; one row per sender. Most-recent thread first. */
    fun loadConversations(ctx: Context): List<SmsConversation> {
        val cr = ctx.contentResolver
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
        )
        val grouped = LinkedHashMap<String, SmsConversation>()

        fun mergeRow(rawAddr: String?, body: String, ts: Long, read: Boolean) {
            val key = (rawAddr?.takeUnless { it.isBlank() } ?: "unknown").trim()
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = SmsConversation(
                    address = key,
                    displayName = resolveDisplayName(cr, key),
                    latestBody = body,
                    latestTimestampMs = ts,
                    unreadCount = if (!read) 1 else 0,
                    totalCount = 1,
                )
            } else {
                val newer = ts > existing.latestTimestampMs
                grouped[key] = existing.copy(
                    latestBody = if (newer) body else existing.latestBody,
                    latestTimestampMs = if (newer) ts else existing.latestTimestampMs,
                    unreadCount = existing.unreadCount + if (!read) 1 else 0,
                    totalCount = existing.totalCount + 1,
                )
            }
        }

        runCatching {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                cols,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (c.moveToNext()) {
                    mergeRow(
                        rawAddr = c.getString(addrIdx),
                        body = c.getString(bodyIdx).orEmpty(),
                        ts = c.getLong(dateIdx),
                        read = c.getInt(readIdx) == 1,
                    )
                }
            }
        }

        // Merge in ICC (SIM-card) SMS — most carriers no longer store messages
        // there, but it's the only path that works when the device-side inbox
        // is empty (e.g. fresh install, no default SMS app to receive writes).
        loadIccMessagesRaw(ctx).forEach { item ->
            mergeRow(
                rawAddr = item.address,
                body = item.body,
                ts = item.timestampMs,
                read = item.read,
            )
        }

        // Local cache populated by SmsReceiver — required on this build because
        // there is no default SMS app, so incoming messages never reach the
        // system's content://sms provider. Captured directly from the legacy
        // SMS_RECEIVED broadcast and persisted in our own JSON file.
        SmsCache.all(ctx).forEach { entry ->
            mergeRow(
                rawAddr = entry.address,
                body = entry.body,
                ts = entry.timestampMs,
                read = entry.read,
            )
        }

        return grouped.values.sortedByDescending { it.latestTimestampMs }
    }

    private fun loadIccMessagesRaw(ctx: Context): List<SmsItem> {
        val out = mutableListOf<SmsItem>()
        runCatching {
            iccSmsManagers(ctx).forEach { sm ->
                val msgs: List<SmsMessage?> = invokeAllMessagesFromIcc(sm) ?: return@forEach
                msgs.forEachIndexed { i, raw ->
                    val m: SmsMessage = raw ?: return@forEachIndexed
                    val body = (m.messageBody ?: m.displayMessageBody).orEmpty()
                    if (body.isBlank()) return@forEachIndexed
                    out.add(
                        SmsItem(
                            id = -(i + 1).toLong(), // negative IDs to avoid collision with provider rows
                            address = (m.originatingAddress ?: m.displayOriginatingAddress).orEmpty(),
                            body = body,
                            timestampMs = m.timestampMillis,
                            read = m.statusOnIcc == SmsManager.STATUS_ON_ICC_READ,
                            incoming = true,
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * Reflection bridge: getAllMessagesFromIcc() is @SystemApi (hidden) in AOSP 13/14
     * but the implementation is reachable on signed system installs because the
     * launcher ships in /system/app. On non-system installs the method returns null
     * and the caller falls back to the empty list.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeAllMessagesFromIcc(sm: SmsManager): List<SmsMessage?>? {
        return runCatching {
            val method = SmsManager::class.java.getMethod("getAllMessagesFromIcc")
            method.invoke(sm) as? List<SmsMessage?>
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun iccSmsManagers(ctx: Context): List<SmsManager> {
        val list = mutableListOf<SmsManager>()
        runCatching {
            val sm = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            if (sm != null) list.add(sm)
        }
        // Multi-SIM: also pull each active subscription's manager.
        runCatching {
            val subs = ctx.getSystemService(SubscriptionManager::class.java)
            subs?.activeSubscriptionInfoList?.forEach { info ->
                val sub = info.subscriptionId
                val sm = if (Build.VERSION.SDK_INT >= 31) {
                    ctx.getSystemService(SmsManager::class.java)?.createForSubscriptionId(sub)
                } else {
                    SmsManager.getSmsManagerForSubscriptionId(sub)
                }
                if (sm != null) list.add(sm)
            }
        }
        return list
    }

    /** Full message list for a single sender, oldest first (chat-style). */
    fun loadMessagesFor(ctx: Context, address: String): List<SmsItem> {
        val cr = ctx.contentResolver
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
        )
        val out = mutableListOf<SmsItem>()
        runCatching {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                cols,
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    val type = c.getInt(typeIdx)
                    out.add(
                        SmsItem(
                            id = c.getLong(idIdx),
                            address = c.getString(addrIdx).orEmpty(),
                            body = c.getString(bodyIdx).orEmpty(),
                            timestampMs = c.getLong(dateIdx),
                            read = c.getInt(readIdx) == 1,
                            incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        ),
                    )
                }
            }
        }
        // Append ICC entries for the same address (same loose match the
        // conversation list uses — trim only).
        val needle = address.trim()
        loadIccMessagesRaw(ctx)
            .filter { it.address.trim() == needle }
            .forEach { out.add(it) }
        // Local SMS_RECEIVED cache.
        SmsCache.all(ctx)
            .filter { it.address.trim() == needle }
            .forEach { entry ->
                out.add(
                    SmsItem(
                        id = -2_000_000L - entry.timestampMs, // unique negative
                        address = entry.address,
                        body = entry.body,
                        timestampMs = entry.timestampMs,
                        read = entry.read,
                        incoming = true,
                    ),
                )
            }
        return out.sortedBy { it.timestampMs }
    }

    private fun resolveDisplayName(cr: ContentResolver, phone: String): String {
        if (phone.isBlank()) return phone
        // Letters in address (e.g. service names like "GOOGLE") — show as-is.
        if (phone.any { it.isLetter() }) return phone
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone),
            )
            cr.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: phone
    }
}

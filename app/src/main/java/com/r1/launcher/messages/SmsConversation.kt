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
import android.util.Log

private const val TAG = "SmsLoader"

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

    private val iccCacheLock = Any()
    private var iccCache: List<SmsItem>? = null

    /** Group by address; one row per sender. Most-recent thread first. */
    fun loadConversations(ctx: Context): List<SmsConversation> {
        return loadConversationsInternal(
            ctx = ctx,
            iccItems = refreshIccMessages(ctx),
            logName = "loadConversations",
        )
    }

    /** Fast path for panel-open: provider + local receiver cache only. */
    fun loadConversationsFast(ctx: Context): List<SmsConversation> {
        return loadConversationsInternal(
            ctx = ctx,
            iccItems = null,
            logName = "loadConversationsFast",
        )
    }

    /** Merge the last ICC scan without touching the slow radio/SIM API. */
    fun loadConversationsWithCachedIcc(ctx: Context): List<SmsConversation> {
        return loadConversationsInternal(
            ctx = ctx,
            iccItems = cachedIccMessages(),
            logName = "loadConversationsCachedIcc",
        )
    }

    fun refreshIccMessages(ctx: Context): List<SmsItem> {
        val items = loadIccMessagesRaw(ctx)
        synchronized(iccCacheLock) { iccCache = items }
        return items
    }

    private fun cachedIccMessages(): List<SmsItem> =
        synchronized(iccCacheLock) { iccCache.orEmpty() }

    private fun loadConversationsInternal(
        ctx: Context,
        iccItems: List<SmsItem>?,
        logName: String,
    ): List<SmsConversation> {
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

        var providerRows = 0
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
                    providerRows++
                    mergeRow(
                        rawAddr = c.getString(addrIdx),
                        body = c.getString(bodyIdx).orEmpty(),
                        ts = c.getLong(dateIdx),
                        read = c.getInt(readIdx) == 1,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "content://sms query failed", it) }

        iccItems.orEmpty().forEach { item ->
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
        val cacheEntries = SmsCache.all(ctx)
        cacheEntries.forEach { entry ->
            mergeRow(
                rawAddr = entry.address,
                body = entry.body,
                ts = entry.timestampMs,
                read = entry.read,
            )
        }

        Log.d(
            TAG,
            "$logName: provider=$providerRows icc=${iccItems?.size ?: 0} cache=${cacheEntries.size} threads=${grouped.size}",
        )
        return grouped.values.sortedByDescending { it.latestTimestampMs }
    }

    private fun loadIccMessagesRaw(ctx: Context): List<SmsItem> {
        // Dedup across SmsManagers: the default SmsManager and createForSubscriptionId(sub)
        // return the same SIM records on a single-SIM device, so the same message appears
        // twice without this. Key by (address, body, timestampMs) — the actual content of
        // the message, since the SIM doesn't expose stable record IDs across managers.
        val seen = HashSet<Triple<String, String, Long>>()
        val out = mutableListOf<SmsItem>()
        val managers = iccSmsManagers(ctx)
        Log.d(TAG, "loadIccMessagesRaw: ${managers.size} SmsManager(s)")
        var nextId = -1L // monotonic across all managers; never collides
        managers.forEachIndexed { idx, sm ->
            val msgs = invokeAllMessagesFromIcc(sm, idx)
            if (msgs == null) {
                Log.w(TAG, "icc[$idx]: getAllMessagesFromIcc returned null")
                return@forEachIndexed
            }
            Log.d(TAG, "icc[$idx]: ${msgs.size} raw record(s)")
            msgs.forEach { raw ->
                val m: SmsMessage = raw ?: return@forEach
                val body = (m.messageBody ?: m.displayMessageBody).orEmpty()
                if (body.isBlank()) return@forEach
                val address = (m.originatingAddress ?: m.displayOriginatingAddress).orEmpty()
                val ts = m.timestampMillis
                if (!seen.add(Triple(address, body, ts))) return@forEach
                out.add(
                    SmsItem(
                        id = nextId--,
                        address = address,
                        body = body,
                        timestampMs = ts,
                        read = m.statusOnIcc == SmsManager.STATUS_ON_ICC_READ,
                        incoming = true,
                    ),
                )
            }
        }
        Log.d(TAG, "loadIccMessagesRaw: emitted ${out.size} item(s) after dedup")
        return out
    }

    /**
     * Reflection bridge: getAllMessagesFromIcc() is @SystemApi (hidden) in AOSP 13/14
     * but the implementation is reachable on signed system installs because the
     * launcher ships in /system/app. On non-system installs the method returns null
     * and the caller falls back to the empty list.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeAllMessagesFromIcc(sm: SmsManager, idx: Int): List<SmsMessage?>? {
        return runCatching {
            val method = SmsManager::class.java.getMethod("getAllMessagesFromIcc")
            method.invoke(sm) as? List<SmsMessage?>
        }.onFailure { e ->
            // Unwrap reflection's InvocationTargetException so the underlying
            // SecurityException / NoSuchMethodError actually shows up.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "icc[$idx]: getAllMessagesFromIcc threw ${cause.javaClass.simpleName}: ${cause.message}")
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun iccSmsManagers(ctx: Context): List<SmsManager> {
        val list = mutableListOf<SmsManager>()
        // Prefer active subscription managers. On the R1, the default manager
        // and the single active-sub manager hit the same SIM record, but each
        // hidden API call costs several seconds.
        runCatching {
            val subs = ctx.getSystemService(SubscriptionManager::class.java)
            val infos = subs?.activeSubscriptionInfoList
            Log.d(TAG, "active subscriptions: ${infos?.size ?: -1}")
            infos?.forEach { info ->
                val sub = info.subscriptionId
                val sm = if (Build.VERSION.SDK_INT >= 31) {
                    ctx.getSystemService(SmsManager::class.java)?.createForSubscriptionId(sub)
                } else {
                    SmsManager.getSmsManagerForSubscriptionId(sub)
                }
                if (sm != null) list.add(sm)
            }
        }.onFailure { Log.w(TAG, "per-sub SmsManager lookup failed", it) }
        if (list.isNotEmpty()) return list

        runCatching {
            val sm = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            if (sm != null) list.add(sm)
        }.onFailure { Log.w(TAG, "default SmsManager lookup failed", it) }
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
        // Append cached ICC entries for the same address. Do not touch the
        // slow SIM API here; conversation refresh keeps the memory cache warm.
        val needle = address.trim()
        val iccAll = cachedIccMessages()
        val iccMatched = iccAll.filter { it.address.trim() == needle }
        Log.d(
            TAG,
            "loadMessagesFor: needle=[$needle] (len=${needle.length}) icc.all=${iccAll.size} icc.cached=${iccAll.size} icc.matched=${iccMatched.size} icc.addrs=${iccAll.map { "[${it.address}](len=${it.address.length})" }}",
        )
        iccMatched.forEach { out.add(it) }
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

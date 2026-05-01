package com.r1.launcher.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Manifest-registered receiver for the legacy `SMS_RECEIVED` broadcast.
 * Fires on every incoming SMS regardless of who the default SMS app is
 * (or whether one exists at all) — we only need `RECEIVE_SMS`.
 *
 * We do NOT abort the broadcast; if a default SMS app ever gets installed
 * later it can still write the message to `content://sms` normally.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NEW_SMS_LOCAL = "com.r1.launcher.NEW_SMS_LOCAL"
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val msgs = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return
        if (msgs.isEmpty()) return

        // Multi-part SMS arrives as multiple SmsMessage entries that share an
        // originating address. Concatenate per-sender so the cached body is
        // the full text the user actually saw on a normal phone.
        val byAddr = LinkedHashMap<String, StringBuilder>()
        var ts = 0L
        for (m in msgs) {
            if (m == null) continue
            val addr = (m.originatingAddress ?: m.displayOriginatingAddress).orEmpty()
            val body = (m.displayMessageBody ?: m.messageBody).orEmpty()
            if (body.isEmpty()) continue
            byAddr.getOrPut(addr) { StringBuilder() }.append(body)
            if (m.timestampMillis > ts) ts = m.timestampMillis
        }
        if (byAddr.isEmpty()) return
        if (ts == 0L) ts = System.currentTimeMillis()

        for ((addr, sb) in byAddr) {
            val entry = SmsCache.Entry(
                address = addr.ifBlank { "unknown" },
                body = sb.toString(),
                timestampMs = ts,
                read = false,
            )
            SmsCache.append(ctx, entry)
            Log.i(TAG, "captured SMS from ${entry.address} (${entry.body.length} chars)")
        }

        // Notify any running launcher instance so the panel refreshes live.
        ctx.sendBroadcast(Intent(ACTION_NEW_SMS_LOCAL).setPackage(ctx.packageName))
    }
}

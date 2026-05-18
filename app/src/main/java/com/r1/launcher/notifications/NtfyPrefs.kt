package com.r1.launcher.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the user-chosen ntfy.sh topic + a master enable flag for the
 * outbound subscriber. Public ntfy.sh only for v1 — no custom server URL,
 * no bearer token. The "auth" model is "use an unguessable topic name".
 *
 * Plain prefs (no encryption) — the topic is effectively a shared URL,
 * not a credential to a remote service.
 */
class NtfyPrefs private constructor(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ntfy.plain", Context.MODE_PRIVATE)

    /** ntfy.sh topic name. Subscriber connects to `https://ntfy.sh/<topic>/json`.
     *  Empty / blank = not configured; subscriber refuses to start. */
    var topic: String
        get() = plain.getString(KEY_TOPIC, "").orEmpty().trim()
        set(value) = plain.edit(commit = true) { putString(KEY_TOPIC, value.trim()) }

    /** Master toggle for the subscriber. When false, the subscriber stays
     *  stopped regardless of whether a topic is configured. */
    var enabled: Boolean
        get() = plain.getBoolean(KEY_ENABLED, false)
        set(value) = plain.edit(commit = true) { putBoolean(KEY_ENABLED, value) }

    /** Last message id we successfully received. Used as `?since=<id>` on
     *  reconnect to replay missed messages from ntfy.sh's 12h retention
     *  window. Empty string = first connect / no resume token.
     *
     *  Persisted synchronously (commit) so a process death between receiving
     *  a frame and the async apply() flushing can't leave the cursor pointing
     *  at an older id — that would cause ntfy.sh to replay the missed window
     *  on the next reconnect, repopulating any list the user had cleared. */
    var lastMessageId: String
        get() = plain.getString(KEY_LAST_ID, "").orEmpty()
        set(value) { setLastMessageId(value) }

    /** Synchronous setter that surfaces the commit result. Returns false on
     *  IO error / full disk — caller can log and decide whether to retry.
     *  The property setter delegates here so existing call sites stay terse;
     *  code that cares about durability can call this directly. */
    fun setLastMessageId(value: String): Boolean =
        plain.edit().putString(KEY_LAST_ID, value).commit().also {
            if (!it) android.util.Log.w("NtfyPrefs", "lastMessageId commit failed")
        }

    /** Wall-clock ms of the most recent user-initiated clear. Used by
     *  [NtfySubscriber] as a time fence: any frame whose server `time`
     *  predates this is dropped. Belt-and-suspenders against ntfy.sh
     *  replaying cached messages despite our empty `?since=` cursor.
     *  Persisted synchronously so a process death between clear and the
     *  next reconnect can't lose the fence. */
    var clearedAtMs: Long
        get() = plain.getLong(KEY_CLEARED_AT_MS, 0L)
        set(value) {
            plain.edit().putLong(KEY_CLEARED_AT_MS, value).commit().also {
                if (!it) android.util.Log.w("NtfyPrefs", "clearedAtMs commit failed")
            }
        }

    fun isConfigured(): Boolean = topic.isNotBlank()

    companion object {
        private const val KEY_TOPIC = "topic"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_ID = "last.id"
        private const val KEY_CLEARED_AT_MS = "cleared.at.ms"

        @Volatile private var instance: NtfyPrefs? = null
        fun get(ctx: Context): NtfyPrefs =
            instance ?: synchronized(this) {
                instance ?: NtfyPrefs(ctx).also { instance = it }
            }
    }
}

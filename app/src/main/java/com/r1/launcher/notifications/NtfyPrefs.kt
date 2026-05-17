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
        set(value) = plain.edit(commit = true) { putString(KEY_LAST_ID, value) }

    fun isConfigured(): Boolean = topic.isNotBlank()

    companion object {
        private const val KEY_TOPIC = "topic"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_ID = "last.id"

        @Volatile private var instance: NtfyPrefs? = null
        fun get(ctx: Context): NtfyPrefs =
            instance ?: synchronized(this) {
                instance ?: NtfyPrefs(ctx).also { instance = it }
            }
    }
}

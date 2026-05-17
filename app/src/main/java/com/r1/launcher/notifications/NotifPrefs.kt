package com.r1.launcher.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.SecureRandom

/**
 * Notification settings + the webhook bearer token used by `POST /api/notify`.
 * Stored in plain SharedPreferences — the token isn't a credential to a remote
 * service, just an unguessable secret that gates "anyone on the LAN" → "anyone
 * who knows the token". 16 random bytes hex (32 chars) is plenty.
 */
class NotifPrefs private constructor(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("notif.plain", Context.MODE_PRIVATE)

    /** Master toggle for the chime that fires when a new notification lands.
     *  When off, the visual badge / panel still update — only the audio cue is
     *  suppressed. Surfaced in Settings → Sound → "notifications". */
    var soundEnabled: Boolean
        get() = plain.getBoolean(KEY_SOUND, true)
        set(value) = plain.edit { putBoolean(KEY_SOUND, value) }

    /** Bearer token required on `POST /api/notify`. Generated lazily on first
     *  read so existing devices get a token without needing migration code.
     *  Synchronized so two concurrent first-time reads can't generate two
     *  competing tokens (which would briefly invalidate one client's auth). */
    val webhookToken: String
        @Synchronized
        get() {
            val existing = plain.getString(KEY_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = generateToken()
            plain.edit(commit = true) { putString(KEY_TOKEN, fresh) }
            return fresh
        }

    @Synchronized
    fun regenerateWebhookToken(): String {
        val fresh = generateToken()
        plain.edit(commit = true) { putString(KEY_TOKEN, fresh) }
        return fresh
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_SOUND = "sound.enabled"
        private const val KEY_TOKEN = "webhook.token"

        @Volatile private var instance: NotifPrefs? = null
        fun get(ctx: Context): NotifPrefs =
            instance ?: synchronized(this) {
                instance ?: NotifPrefs(ctx).also { instance = it }
            }
    }
}

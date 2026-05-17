package com.r1.launcher.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Notification settings + the webhook bearer token used by `POST /api/notify`.
 *
 * The token is stored in EncryptedSharedPreferences (`notif.secure`) because
 * it authorizes notification ingress — anyone holding it can post arbitrary
 * notifications, deeplinks included. `MODE_PRIVATE` would prevent other apps
 * on the device from reading it, but encrypted-at-rest also protects against
 * an offline backup / image dump.
 *
 * Pre-1.1.5 builds stored the token in plain prefs (`notif.plain` key
 * `webhook.token`). On first read we migrate it into the secure store and
 * clear the plain copy so existing tokens stay valid across the upgrade.
 *
 * The audio toggle stays in plain prefs — it's not sensitive.
 */
class NotifPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "notif.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Same fallback pattern as VoicePrefs — keystore corruption is rare on
        // AOSP but better to keep notifications working than to hard-crash.
        app.getSharedPreferences("notif.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("notif.plain", Context.MODE_PRIVATE)

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
            val existing = secure.getString(KEY_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            // Migration: pre-1.1.5 stored the token in plain prefs. Promote
            // into the secure store and only wipe the plain copy after the
            // secure write is confirmed — otherwise a keystore hiccup during
            // commit would lose the token entirely (active webhook clients
            // would 401 with no way to recover the same value). If commit
            // fails, leave the plain copy in place so the next read retries
            // the migration cleanly.
            val legacy = plain.getString(KEY_TOKEN, null)
            if (!legacy.isNullOrBlank()) {
                if (commitToken(secure, legacy)) {
                    plain.edit(commit = true) { remove(KEY_TOKEN) }
                }
                return legacy
            }
            val fresh = generateToken()
            commitToken(secure, fresh)
            return fresh
        }

    @Synchronized
    fun regenerateWebhookToken(): String {
        val fresh = generateToken()
        commitToken(secure, fresh)
        // Defensive: if a legacy plain entry survived (e.g. user rotated before
        // the secure path was ever read), drop it now so it can't be recovered.
        plain.edit(commit = true) { remove(KEY_TOKEN) }
        return fresh
    }

    /** Synchronous write that returns the underlying `Editor.commit()` boolean
     *  so callers can branch on success. The kotlin `edit { }` extension
     *  swallows this value, which is dangerous for the migration path where
     *  we use it to decide whether the legacy copy can be safely removed. */
    private fun commitToken(prefs: SharedPreferences, token: String): Boolean {
        val editor = prefs.edit()
        editor.putString(KEY_TOKEN, token)
        return editor.commit()
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

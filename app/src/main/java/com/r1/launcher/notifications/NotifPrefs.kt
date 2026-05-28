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

    /** Pre-shared token gating the embedded web panel. Separate from
     *  [webhookToken] on purpose — webhooks are inbound from third parties
     *  (GitHub, ntfy.sh, the user's own scripts); the panel token is the
     *  user's own browser session credential. Mixing them would mean rotating
     *  the panel kicks out webhooks and vice versa. Same lazy-generate +
     *  synchronized pattern; bytes are persisted in the encrypted store. */
    val panelToken: String
        @Synchronized
        get() {
            val existing = secure.getString(KEY_PANEL_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = generateToken()
            secure.edit().putString(KEY_PANEL_TOKEN, fresh).commit().also {
                if (!it) android.util.Log.w("NotifPrefs", "panel token commit failed")
            }
            return fresh
        }

    @Synchronized
    fun regeneratePanelToken(): String {
        val fresh = generateToken()
        secure.edit().putString(KEY_PANEL_TOKEN, fresh).commit()
        return fresh
    }

    /** Human-friendly 4-digit passcode the user types on their phone to unlock
     *  the embedded web panel. Exchanged for [panelToken] via POST /api/auth.
     *  On a fresh device this is *generated at random* on first read and
     *  persisted — never the well-known "0000", which any LAN peer would guess
     *  on the first try. The user reads/changes it from Settings → Network.
     *  The 4-digit keyspace is only safe behind the per-IP rate limiter in
     *  R1WebServer; do not skip that. */
    var panelPasscode: String
        @Synchronized
        get() {
            val stored = secure.getString(KEY_PANEL_PASSCODE, null)?.takeIf { isValidPasscode(it) }
            if (stored != null) return stored
            val fresh = generateRandomPasscode()
            secure.edit().putString(KEY_PANEL_PASSCODE, fresh).commit()
            return fresh
        }
        @Synchronized
        set(value) {
            require(isValidPasscode(value)) { "passcode must be exactly 4 digits" }
            secure.edit().putString(KEY_PANEL_PASSCODE, value).commit()
        }

    /** Per-device secret gating the exported control broadcasts
     *  (SET_ELEVENLABS_KEY / SET_HERMES_CONFIG / TOGGLE_WEB_SERVER). Senders
     *  must pass `--es secret <this>`; without it ANY installed app could set
     *  the user's API keys, repoint the AI backend, or switch on the LAN web
     *  server. Lazy-generated like the tokens; surfaced read-only in
     *  Settings → Credentials so the user can copy it into their adb snippets. */
    val controlSecret: String
        @Synchronized
        get() {
            val existing = secure.getString(KEY_CONTROL_SECRET, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = generateShortSecret()
            secure.edit().putString(KEY_CONTROL_SECRET, fresh).commit()
            return fresh
        }

    @Synchronized
    fun regenerateControlSecret(): String {
        val fresh = generateShortSecret()
        secure.edit().putString(KEY_CONTROL_SECRET, fresh).commit()
        return fresh
    }

    private fun isValidPasscode(s: String): Boolean =
        s.length == 4 && s.all { it.isDigit() }

    /** Random 4-digit passcode, never the well-known [DEFAULT_PASSCODE]. */
    private fun generateRandomPasscode(): String {
        val n = SecureRandom().nextInt(10_000)
        val code = "%04d".format(n)
        return if (code == DEFAULT_PASSCODE) "%04d".format((n + 1) % 10_000) else code
    }

    /** Short (8 hex char) secret the user can realistically type into an adb
     *  command. Not high-entropy by token standards, but a broadcast carries no
     *  timing feedback so brute force isn't practical, and it keeps a malicious
     *  app that doesn't know it out. */
    private fun generateShortSecret(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
        private const val KEY_PANEL_TOKEN = "panel.token"
        private const val KEY_PANEL_PASSCODE = "panel.passcode"
        private const val KEY_CONTROL_SECRET = "control.secret"
        /** Reserved as the one passcode we refuse to auto-generate — no longer
         *  used as a fallback default (fresh devices get a random code). */
        const val DEFAULT_PASSCODE = "0000"

        @Volatile private var instance: NotifPrefs? = null
        fun get(ctx: Context): NotifPrefs =
            instance ?: synchronized(this) {
                instance ?: NotifPrefs(ctx).also { instance = it }
            }
    }
}

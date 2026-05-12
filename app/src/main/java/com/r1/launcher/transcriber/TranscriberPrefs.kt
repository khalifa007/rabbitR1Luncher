package com.r1.launcher.transcriber

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SMTP credentials + default email recipient for the Meetings app. Mirrors
 * [com.r1.launcher.voice.VoicePrefs]:
 *   - secrets (host, port, user, password) live in EncryptedSharedPreferences
 *   - the default recipient lives in plain prefs (it's a routing target, not
 *     a credential)
 *
 * Defaults to Gmail's submission endpoint (`smtp.gmail.com:587`). Users
 * generate a 16-character "app password" in their Google account and paste
 * that as the password — Gmail rejects raw account passwords from non-OAuth
 * SMTP clients.
 */
class TranscriberPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "transcriber.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("transcriber.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("transcriber.plain", Context.MODE_PRIVATE)

    var smtpHost: String
        get() = secure.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = secure.edit { putString(KEY_HOST, value.trim()) }

    var smtpPort: Int
        get() = secure.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = secure.edit { putInt(KEY_PORT, value) }

    var smtpUser: String?
        get() = secure.getString(KEY_USER, null)?.takeIf { it.isNotBlank() }
        set(value) = secure.edit {
            if (value.isNullOrBlank()) remove(KEY_USER)
            else putString(KEY_USER, value.trim())
        }

    /** Gmail "app password" (16 chars, no spaces) or any provider's SMTP secret. */
    var smtpPassword: String?
        get() = secure.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }
        set(value) = secure.edit {
            if (value.isNullOrBlank()) remove(KEY_PASSWORD)
            else putString(KEY_PASSWORD, value.trim())
        }

    /** Comma-separated default recipients. Empty = user must type one each send. */
    var defaultRecipient: String
        get() = plain.getString(KEY_RECIPIENT, "") ?: ""
        set(value) = plain.edit { putString(KEY_RECIPIENT, value.trim()) }

    fun hasSmtp(): Boolean =
        !smtpUser.isNullOrBlank() && !smtpPassword.isNullOrBlank() &&
            smtpHost.isNotBlank() && smtpPort in 1..65535

    fun clear() {
        secure.edit {
            remove(KEY_HOST); remove(KEY_PORT)
            remove(KEY_USER); remove(KEY_PASSWORD)
        }
        plain.edit { remove(KEY_RECIPIENT) }
    }

    companion object {
        const val DEFAULT_HOST = "smtp.gmail.com"
        const val DEFAULT_PORT = 587

        private const val KEY_HOST = "smtp.host"
        private const val KEY_PORT = "smtp.port"
        private const val KEY_USER = "smtp.user"
        private const val KEY_PASSWORD = "smtp.password"
        private const val KEY_RECIPIENT = "default.recipient"

        @Volatile private var instance: TranscriberPrefs? = null
        fun get(ctx: Context): TranscriberPrefs =
            instance ?: synchronized(this) {
                instance ?: TranscriberPrefs(ctx).also { instance = it }
            }
    }
}

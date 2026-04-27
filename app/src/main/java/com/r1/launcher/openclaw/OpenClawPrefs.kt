package com.r1.launcher.openclaw

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class OpenClawPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "openclaw.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fall back to plain prefs if EncryptedSharedPreferences setup fails
        // (rare on AOSP — happens when keystore corrupted). Caveat: tokens then
        // unencrypted on disk; better than crashing the launcher.
        app.getSharedPreferences("openclaw.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("openclaw.plain", Context.MODE_PRIVATE)

    var gatewayUrl: String?
        get() = secure.getString(KEY_URL, null)
        set(value) = secure.edit { if (value == null) remove(KEY_URL) else putString(KEY_URL, value) }

    var bootstrapToken: String?
        get() = secure.getString(KEY_BOOTSTRAP, null)
        set(value) = secure.edit { if (value == null) remove(KEY_BOOTSTRAP) else putString(KEY_BOOTSTRAP, value) }

    var deviceToken: String?
        get() = secure.getString(KEY_DEVICE_TOKEN, null)
        set(value) = secure.edit { if (value == null) remove(KEY_DEVICE_TOKEN) else putString(KEY_DEVICE_TOKEN, value) }

    var sharedToken: String?
        get() = secure.getString(KEY_SHARED_TOKEN, null)
        set(value) = secure.edit { if (value == null) remove(KEY_SHARED_TOKEN) else putString(KEY_SHARED_TOKEN, value) }

    var openaiKey: String?
        get() = secure.getString(KEY_OPENAI_KEY, null)
        set(value) = secure.edit { if (value == null) remove(KEY_OPENAI_KEY) else putString(KEY_OPENAI_KEY, value) }

    val instanceId: String
        get() = plain.getString(KEY_INSTANCE_ID, null) ?: run {
            val fresh = UUID.randomUUID().toString()
            plain.edit { putString(KEY_INSTANCE_ID, fresh) }
            fresh
        }

    var hideChat: Boolean
        get() = plain.getBoolean(KEY_HIDE_CHAT, false)
        set(value) = plain.edit { putBoolean(KEY_HIDE_CHAT, value) }

    var chatFontSize: Int
        get() = plain.getInt(KEY_CHAT_FONT_SIZE, 14)
        set(value) = plain.edit { putInt(KEY_CHAT_FONT_SIZE, value) }

    var ttsEnabled: Boolean
        get() = plain.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = plain.edit { putBoolean(KEY_TTS_ENABLED, value) }



    var selectedSessionKey: String?
        get() = plain.getString(KEY_SELECTED_SESSION, null)
        set(value) = plain.edit {
            if (value.isNullOrBlank()) remove(KEY_SELECTED_SESSION) else putString(KEY_SELECTED_SESSION, value)
        }

    var lastMainSessionKey: String?
        get() = plain.getString(KEY_LAST_MAIN_SESSION, null)
        set(value) = plain.edit {
            if (value.isNullOrBlank()) remove(KEY_LAST_MAIN_SESSION) else putString(KEY_LAST_MAIN_SESSION, value)
        }

    fun hasPairing(): Boolean = !gatewayUrl.isNullOrBlank() &&
        (!deviceToken.isNullOrBlank() || !bootstrapToken.isNullOrBlank() || !sharedToken.isNullOrBlank())

    fun clear() {
        secure.edit {
            remove(KEY_URL); remove(KEY_BOOTSTRAP); remove(KEY_DEVICE_TOKEN); remove(KEY_SHARED_TOKEN)
        }
        plain.edit {
            remove(KEY_SELECTED_SESSION); remove(KEY_LAST_MAIN_SESSION)
        }
    }

    companion object {
        private const val KEY_URL = "gateway.url"
        private const val KEY_BOOTSTRAP = "gateway.bootstrap"
        private const val KEY_DEVICE_TOKEN = "gateway.deviceToken"
        private const val KEY_SHARED_TOKEN = "gateway.token"
        private const val KEY_INSTANCE_ID = "node.instanceId"
        private const val KEY_OPENAI_KEY = "openai.key"
        private const val KEY_HIDE_CHAT = "chat.hide"
        private const val KEY_CHAT_FONT_SIZE = "chat.fontSize"
        private const val KEY_TTS_ENABLED = "chat.ttsEnabled"

        private const val KEY_SELECTED_SESSION = "chat.selectedSessionKey"
        private const val KEY_LAST_MAIN_SESSION = "chat.lastMainSessionKey"

        @Volatile private var instance: OpenClawPrefs? = null
        fun get(ctx: Context): OpenClawPrefs =
            instance ?: synchronized(this) {
                instance ?: OpenClawPrefs(ctx).also { instance = it }
            }
    }
}

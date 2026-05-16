package com.r1.launcher.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Persistent config for the Hermes Agent app — server URL, bearer token, model id,
 * and an opt-in session id for continuity across restarts. Same shape as
 * [com.r1.launcher.openclaw.OpenClawPrefs] (encrypted secure prefs + plain prefs).
 */
class HermesPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "hermes.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("hermes.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("hermes.plain", Context.MODE_PRIVATE)

    /** Base URL of the Hermes gateway, including the `/v1` suffix.
     *  Example: `http://hermes.lan:8642/v1`. We append `/chat/completions` ourselves. */
    var serverUrl: String
        get() = secure.getString(KEY_URL, "").orEmpty()
        set(value) = secure.edit { putString(KEY_URL, value.trim()) }

    /** Bearer token sent in `Authorization: Bearer <token>`. Empty = no auth header
     *  (acceptable for LAN-only Hermes instances bound to localhost). */
    var apiKey: String
        get() = secure.getString(KEY_KEY, "").orEmpty()
        set(value) = secure.edit { putString(KEY_KEY, value.trim()) }

    /** Model id sent in the chat-completions request body. Hermes advertises
     *  `hermes-agent` by default; users may also forward an LLM-provider id. */
    var model: String
        get() = plain.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = plain.edit { putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }) }

    /** Session id sent in `X-Hermes-Session-Id` header. Generated lazily on first
     *  read so all turns in the chat list share one continuity scope.
     *  `clearHistory` rotates the id so the next turn starts a fresh server-side
     *  thread without forcing the user to re-enter URL/key. */
    val sessionId: String
        get() = plain.getString(KEY_SESSION, null) ?: run {
            val fresh = UUID.randomUUID().toString()
            plain.edit { putString(KEY_SESSION, fresh) }
            fresh
        }

    fun rotateSessionId() {
        plain.edit { putString(KEY_SESSION, UUID.randomUUID().toString()) }
    }

    var fontSize: Int
        get() = plain.getInt(KEY_FONT_SIZE, 14)
        set(value) = plain.edit { putInt(KEY_FONT_SIZE, value) }

    var hideChat: Boolean
        get() = plain.getBoolean(KEY_HIDE_CHAT, false)
        set(value) = plain.edit { putBoolean(KEY_HIDE_CHAT, value) }

    fun hasConfig(): Boolean = serverUrl.isNotBlank()

    /** Strip trailing slash + optional `/v1` suffix so we can build sub-endpoints
     *  consistently (e.g. `${baseRoot}/health` for the test probe). */
    fun baseRoot(): String {
        val u = serverUrl.trimEnd('/')
        return if (u.endsWith("/v1")) u.removeSuffix("/v1") else u
    }

    /** Full URL for `POST /v1/chat/completions`. */
    fun chatCompletionsUrl(): String = baseRoot() + "/v1/chat/completions"

    /** Full URL for `GET /health` (the API server mounts this at the root, not under /v1). */
    fun healthUrl(): String = baseRoot() + "/health"

    companion object {
        const val DEFAULT_MODEL = "hermes-agent"

        private const val KEY_URL = "hermes.serverUrl"
        private const val KEY_KEY = "hermes.apiKey"
        private const val KEY_MODEL = "hermes.model"
        private const val KEY_SESSION = "hermes.sessionId"
        private const val KEY_FONT_SIZE = "hermes.fontSize"
        private const val KEY_HIDE_CHAT = "hermes.hideChat"

        @Volatile private var instance: HermesPrefs? = null
        fun get(ctx: Context): HermesPrefs =
            instance ?: synchronized(this) {
                instance ?: HermesPrefs(ctx).also { instance = it }
            }
    }
}

package com.r1.launcher.survey

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent config for the AI Survey Call Bot.
 *
 * `survey.secure` (EncryptedSharedPreferences): API keys + SIP credentials.
 * `survey.plain` (regular SharedPreferences): non-sensitive defaults — consent
 * disclosure text, summarizer model pick, email recipient, etc.
 */
class SurveyPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "survey.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("survey.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("survey.plain", Context.MODE_PRIVATE)

    // ---- Realtime conversation engine: OpenAI gpt-realtime-2 ----

    var openAiKey: String?
        get() = secure.getString(KEY_OPENAI_KEY, null)
        set(value) = secure.edit {
            if (value == null) remove(KEY_OPENAI_KEY) else putString(KEY_OPENAI_KEY, value)
        }

    fun hasOpenAiKey(): Boolean = !openAiKey.isNullOrBlank()

    /** Voice for gpt-realtime-2 output. OpenAI catalog: alloy, echo, shimmer, etc. */
    var realtimeVoice: String
        get() = plain.getString(KEY_REALTIME_VOICE, DEFAULT_REALTIME_VOICE) ?: DEFAULT_REALTIME_VOICE
        set(value) = plain.edit { putString(KEY_REALTIME_VOICE, value) }

    // ---- SIP credentials for Mode B (outbound call path) ----

    var sipHost: String?
        get() = secure.getString(KEY_SIP_HOST, null)
        set(value) = secure.edit {
            if (value.isNullOrBlank()) remove(KEY_SIP_HOST) else putString(KEY_SIP_HOST, value.trim())
        }

    var sipUser: String?
        get() = secure.getString(KEY_SIP_USER, null)
        set(value) = secure.edit {
            if (value.isNullOrBlank()) remove(KEY_SIP_USER) else putString(KEY_SIP_USER, value.trim())
        }

    var sipPassword: String?
        get() = secure.getString(KEY_SIP_PASSWORD, null)
        set(value) = secure.edit {
            if (value == null) remove(KEY_SIP_PASSWORD) else putString(KEY_SIP_PASSWORD, value)
        }

    /** Caller-ID number on outbound calls. Typically the SIP provider's DID. */
    var sipFromNumber: String?
        get() = secure.getString(KEY_SIP_FROM, null)
        set(value) = secure.edit {
            if (value.isNullOrBlank()) remove(KEY_SIP_FROM) else putString(KEY_SIP_FROM, value.trim())
        }

    fun hasSipCreds(): Boolean =
        !sipHost.isNullOrBlank() && !sipUser.isNullOrBlank() && !sipPassword.isNullOrBlank()

    // ---- Summarizer (Claude / GPT one-shot after the call ends) ----

    /** Optional override. When unset, the post-call summarizer reads the
     *  Anthropic key from `/data/local/tmp/.anthropic_key` via carroot. */
    var claudeKey: String?
        get() = secure.getString(KEY_CLAUDE_KEY, null)
        set(value) = secure.edit {
            if (value == null) remove(KEY_CLAUDE_KEY) else putString(KEY_CLAUDE_KEY, value)
        }

    /** Which model to use for the post-call summary. */
    var summarizerModel: String
        get() = plain.getString(KEY_SUMMARIZER_MODEL, DEFAULT_SUMMARIZER_MODEL) ?: DEFAULT_SUMMARIZER_MODEL
        set(value) = plain.edit { putString(KEY_SUMMARIZER_MODEL, value) }

    // ---- Consent ----

    /** Disclosure read by the bot at the start of every call. The orchestrator
     *  enforces this as the literal first message via the system prompt. */
    var consentText: String
        get() = plain.getString(KEY_CONSENT_TEXT, DEFAULT_CONSENT_TEXT) ?: DEFAULT_CONSENT_TEXT
        set(value) = plain.edit { putString(KEY_CONSENT_TEXT, value) }

    // ---- Email delivery (after-call summary) ----

    /** Inbox where call summaries are mailed. Shares SMTP creds with the
     *  Transcriber feature (see [com.r1.launcher.transcriber.TranscriberPrefs]). */
    var emailRecipient: String?
        get() = plain.getString(KEY_EMAIL_RECIPIENT, null)
        set(value) = plain.edit {
            if (value.isNullOrBlank()) remove(KEY_EMAIL_RECIPIENT) else putString(KEY_EMAIL_RECIPIENT, value.trim())
        }

    /** Delay (ms) between consecutive calls in a campaign. */
    var betweenCallsDelayMs: Long
        get() = plain.getLong(KEY_BETWEEN_DELAY_MS, DEFAULT_BETWEEN_DELAY_MS)
        set(value) = plain.edit { putLong(KEY_BETWEEN_DELAY_MS, value.coerceAtLeast(0L)) }

    fun clearAll() {
        secure.edit {
            remove(KEY_OPENAI_KEY); remove(KEY_CLAUDE_KEY)
            remove(KEY_SIP_HOST); remove(KEY_SIP_USER); remove(KEY_SIP_PASSWORD); remove(KEY_SIP_FROM)
        }
        plain.edit {
            remove(KEY_REALTIME_VOICE)
            remove(KEY_CONSENT_TEXT)
            remove(KEY_SUMMARIZER_MODEL)
            remove(KEY_EMAIL_RECIPIENT)
            remove(KEY_BETWEEN_DELAY_MS)
        }
    }

    companion object {
        const val DEFAULT_REALTIME_VOICE = "alloy"

        /** OpenAI gpt-realtime-2 voice catalog (cycle in Settings). */
        val REALTIME_VOICES: List<String> =
            listOf("alloy", "echo", "shimmer", "verse", "ballad", "ash", "sage")

        const val DEFAULT_SUMMARIZER_MODEL = "claude-haiku-4-5-20251001"

        /** Summarizer-model catalog (label → model_id). */
        val SUMMARIZER_MODELS: List<Pair<String, String>> = listOf(
            "claude haiku 4.5" to "claude-haiku-4-5-20251001",
            "claude sonnet 4.6" to "claude-sonnet-4-6",
            "openai gpt-4o-mini" to "gpt-4o-mini",
        )

        const val DEFAULT_CONSENT_TEXT =
            "Hi, this is an AI assistant calling on behalf of the survey team. " +
            "This call is being recorded and a transcript will be saved for analysis. " +
            "May I continue with a few questions?"

        const val DEFAULT_BETWEEN_DELAY_MS = 30_000L

        private const val KEY_OPENAI_KEY = "openai.key"
        private const val KEY_CLAUDE_KEY = "claude.key"
        private const val KEY_REALTIME_VOICE = "realtime.voice"
        private const val KEY_SIP_HOST = "sip.host"
        private const val KEY_SIP_USER = "sip.user"
        private const val KEY_SIP_PASSWORD = "sip.password"
        private const val KEY_SIP_FROM = "sip.from"
        private const val KEY_SUMMARIZER_MODEL = "summarizer.model"
        private const val KEY_CONSENT_TEXT = "consent.text"
        private const val KEY_EMAIL_RECIPIENT = "email.recipient"
        private const val KEY_BETWEEN_DELAY_MS = "campaign.between_ms"

        @Volatile private var instance: SurveyPrefs? = null
        fun get(ctx: Context): SurveyPrefs =
            instance ?: synchronized(this) {
                instance ?: SurveyPrefs(ctx).also { instance = it }
            }
    }
}

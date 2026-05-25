package com.r1.launcher.translator

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Translator config — three API keys (Gemini / OpenAI / Anthropic), the active
 * provider, default source/target languages, auto-detect-source toggle, and
 * auto-speak-target toggle.
 *
 * Keys live in `translator.secure` (EncryptedSharedPreferences); UI prefs live
 * in `translator.plain`. Same idiom as VoicePrefs / HermesPrefs.
 *
 * The translator app intentionally stores its own keys — separate from
 * Hermes / OpenClaw — because:
 *   - users may want to use a free Gemini key here while paying for a
 *     different provider in Hermes
 *   - clearing a translator key shouldn't affect any other app
 *   - the UX is cleaner: "set translator key" lives in Translator's settings,
 *     not buried in a global credentials page
 */
class TranslatorPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "translator.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("translator.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("translator.plain", Context.MODE_PRIVATE)

    // ---- API keys (per-provider) ----

    fun keyFor(provider: ProviderId): String? = secure.getString(provider.keyName, null)
        ?.takeIf { it.isNotBlank() }

    fun setKey(provider: ProviderId, value: String?) = secure.edit {
        val trimmed = value?.trim()
        if (trimmed.isNullOrBlank()) remove(provider.keyName)
        else putString(provider.keyName, trimmed)
    }

    fun hasKey(provider: ProviderId): Boolean = !keyFor(provider).isNullOrBlank()

    /** Last 4 chars of the key, prefixed with `…`. Empty when unset. Used to
     *  show a non-secret confirmation that something is saved. */
    fun keyTail(provider: ProviderId): String {
        val k = keyFor(provider) ?: return ""
        return if (k.length > 6) "…" + k.takeLast(4) else "set"
    }

    // ---- active provider ----

    var provider: ProviderId
        get() {
            val raw = plain.getString(KEY_PROVIDER, ProviderId.GEMINI.name) ?: ProviderId.GEMINI.name
            return runCatching { ProviderId.valueOf(raw) }.getOrDefault(ProviderId.GEMINI)
        }
        set(value) = plain.edit { putString(KEY_PROVIDER, value.name) }

    // ---- language defaults ----

    var sourceLang: String
        get() = plain.getString(KEY_SOURCE_LANG, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
        set(value) = plain.edit { putString(KEY_SOURCE_LANG, value) }

    var targetLang: String
        get() = plain.getString(KEY_TARGET_LANG, DEFAULT_TARGET) ?: DEFAULT_TARGET
        set(value) = plain.edit { putString(KEY_TARGET_LANG, value) }

    /** When true, STT runs without `language_code`, ElevenLabs auto-detects,
     *  and the translator infers source from the committed transcript instead
     *  of trusting [sourceLang]. */
    var autoDetectSource: Boolean
        get() = plain.getBoolean(KEY_AUTO_DETECT, true)
        set(value) = plain.edit { putBoolean(KEY_AUTO_DETECT, value) }

    /** Auto-play TTS for every new target translation. Reuses the global
     *  ElevenLabs key + voice from VoicePrefs (no separate key here). */
    var autoSpeak: Boolean
        get() = plain.getBoolean(KEY_AUTO_SPEAK, true)
        set(value) = plain.edit { putBoolean(KEY_AUTO_SPEAK, value) }

    /** First-run wizard completed? Gates [com.r1.launcher.Panel.TRANSLATOR_ONBOARDING].
     *  Set true when the user finishes (or skips) the source→target→key flow. */
    var onboarded: Boolean
        get() = plain.getBoolean(KEY_ONBOARDED, false)
        set(value) = plain.edit { putBoolean(KEY_ONBOARDED, value) }

    /** Hide the text input row → voice-first mode. The translation gets the
     *  whole screen and input happens via the side-button PTT (plus a compact
     *  hold-to-talk pill). */
    var hideInput: Boolean
        get() = plain.getBoolean(KEY_HIDE_INPUT, false)
        set(value) = plain.edit { putBoolean(KEY_HIDE_INPUT, value) }

    fun clear() {
        secure.edit {
            ProviderId.values().forEach { remove(it.keyName) }
        }
        plain.edit {
            remove(KEY_PROVIDER)
            remove(KEY_SOURCE_LANG)
            remove(KEY_TARGET_LANG)
            remove(KEY_AUTO_DETECT)
            remove(KEY_AUTO_SPEAK)
            remove(KEY_ONBOARDED)
            remove(KEY_HIDE_INPUT)
        }
    }

    fun hasAnyKey(): Boolean = ProviderId.values().any { hasKey(it) }

    companion object {
        const val DEFAULT_SOURCE = "en"
        const val DEFAULT_TARGET = "ar"

        private const val KEY_PROVIDER = "translator.provider"
        private const val KEY_SOURCE_LANG = "translator.source"
        private const val KEY_TARGET_LANG = "translator.target"
        private const val KEY_AUTO_DETECT = "translator.auto_detect"
        private const val KEY_AUTO_SPEAK = "translator.auto_speak"
        private const val KEY_ONBOARDED = "translator.onboarded"
        private const val KEY_HIDE_INPUT = "translator.hide_input"

        @Volatile private var instance: TranslatorPrefs? = null
        fun get(ctx: Context): TranslatorPrefs =
            instance ?: synchronized(this) {
                instance ?: TranslatorPrefs(ctx).also { instance = it }
            }
    }
}

/** The three supported LLM backends. Each declares its on-disk pref key (so a
 *  rename here doesn't lose user data) and its picker label. */
enum class ProviderId(val keyName: String, val label: String) {
    GEMINI("translator.key.gemini",       "gemini"),
    OPENAI("translator.key.openai",       "openai"),
    ANTHROPIC("translator.key.anthropic", "claude"),
}

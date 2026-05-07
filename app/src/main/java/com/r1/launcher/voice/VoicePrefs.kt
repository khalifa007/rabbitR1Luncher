package com.r1.launcher.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Global voice config — used by OpenClaw chat, Terminal, Claude (STT) and
 * OpenClaw TTS readback. Persists ElevenLabs API key (encrypted), the auto-
 * speak toggle, the chosen voice id, the model, and per-voice tuning knobs
 * (stability / similarity / style / speed / speaker_boost). The tuning values
 * map straight into the ElevenLabs `voice_settings` payload on every TTS call.
 *
 * Lives outside the OpenClaw subpackage because STT is now used by 3 apps.
 */
class VoicePrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "voice.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fall back to plain prefs if EncryptedSharedPreferences setup fails
        // (rare on AOSP — happens when keystore corrupted). Caveat: key then
        // unencrypted on disk; better than crashing the launcher.
        app.getSharedPreferences("voice.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("voice.plain", Context.MODE_PRIVATE)

    var elevenlabsKey: String?
        get() = secure.getString(KEY_ELEVENLABS_KEY, null)
        set(value) = secure.edit {
            if (value == null) remove(KEY_ELEVENLABS_KEY) else putString(KEY_ELEVENLABS_KEY, value)
        }

    var enabled: Boolean
        get() = plain.getBoolean(KEY_ENABLED, false)
        set(value) = plain.edit { putBoolean(KEY_ENABLED, value) }

    /** ElevenLabs voice ID for TTS readback. Defaults to Rachel (free-tier-friendly). */
    var voiceId: String
        get() = plain.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
        set(value) = plain.edit { putString(KEY_VOICE_ID, value) }

    /** Optional user-supplied voice ID (cloned, professional, or shared). When
     *  set + non-blank, it's preferred over [voiceId] for synthesis. Saved
     *  raw — ElevenLabs voice IDs are 20-char alphanumeric tokens, no
     *  validation enforced beyond non-blank. */
    var customVoiceId: String?
        get() = plain.getString(KEY_CUSTOM_VOICE_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = plain.edit {
            if (value.isNullOrBlank()) remove(KEY_CUSTOM_VOICE_ID)
            else putString(KEY_CUSTOM_VOICE_ID, value.trim())
        }

    /** Effective voice ID: custom override if set, else the catalog pick. */
    fun effectiveVoiceId(): String = customVoiceId ?: voiceId

    /** Model ID for /v1/text-to-speech. See [MODELS] for the catalog. */
    var model: String
        get() = plain.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = plain.edit { putString(KEY_MODEL, value) }

    /** voice_settings.stability — 0 (expressive) to 1 (monotone). Default 0.5. */
    var stability: Float
        get() = plain.getFloat(KEY_STABILITY, DEFAULT_STABILITY)
        set(value) = plain.edit { putFloat(KEY_STABILITY, value.coerceIn(0f, 1f)) }

    /** voice_settings.similarity_boost — 0 (loose) to 1 (cloned-voice match). */
    var similarity: Float
        get() = plain.getFloat(KEY_SIMILARITY, DEFAULT_SIMILARITY)
        set(value) = plain.edit { putFloat(KEY_SIMILARITY, value.coerceIn(0f, 1f)) }

    /** voice_settings.style — 0 (neutral) to 1 (heavy emotional cues). Costs
     *  more compute; ElevenLabs warns it can degrade stability. */
    var style: Float
        get() = plain.getFloat(KEY_STYLE, DEFAULT_STYLE)
        set(value) = plain.edit { putFloat(KEY_STYLE, value.coerceIn(0f, 1f)) }

    /** voice_settings.speed — 0.7 (slow) to 1.2 (fast). 1.0 = native rate. */
    var speed: Float
        get() = plain.getFloat(KEY_SPEED, DEFAULT_SPEED)
        set(value) = plain.edit { putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)) }

    /** voice_settings.use_speaker_boost — clarity bump for small speakers. */
    var speakerBoost: Boolean
        get() = plain.getBoolean(KEY_SPEAKER_BOOST, DEFAULT_SPEAKER_BOOST)
        set(value) = plain.edit { putBoolean(KEY_SPEAKER_BOOST, value) }

    /** Snapshot of current tuning knobs — used as a single arg to the TTS client. */
    fun tuning(): VoiceTuning = VoiceTuning(
        stability = stability,
        similarity = similarity,
        style = style,
        speed = speed,
        speakerBoost = speakerBoost,
    )

    fun resetTuning() {
        plain.edit {
            putFloat(KEY_STABILITY, DEFAULT_STABILITY)
            putFloat(KEY_SIMILARITY, DEFAULT_SIMILARITY)
            putFloat(KEY_STYLE, DEFAULT_STYLE)
            putFloat(KEY_SPEED, DEFAULT_SPEED)
            putBoolean(KEY_SPEAKER_BOOST, DEFAULT_SPEAKER_BOOST)
            putString(KEY_MODEL, DEFAULT_MODEL)
        }
    }

    fun hasKey(): Boolean = !elevenlabsKey.isNullOrBlank()

    fun clear() {
        secure.edit { remove(KEY_ELEVENLABS_KEY) }
        plain.edit {
            remove(KEY_VOICE_ID); remove(KEY_ENABLED); remove(KEY_CUSTOM_VOICE_ID)
            remove(KEY_MODEL)
            remove(KEY_STABILITY); remove(KEY_SIMILARITY); remove(KEY_STYLE)
            remove(KEY_SPEED); remove(KEY_SPEAKER_BOOST)
        }
    }

    companion object {
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel

        /** Catalog the picker cycles through. (label → voice_id). */
        val VOICES: List<Pair<String, String>> = listOf(
            "rachel" to "21m00Tcm4TlvDq8ikWAM",
            "adam"   to "pNInz6obpgDQGcFmaJgB",
            "aria"   to "9BWtsMINqrJLrRacOk9x",
            "sarah"  to "EXAVITQu4vr4xnSDxMaL",
        )

        /** TTS model catalog. (label → model_id). Flash = fastest, English-leaning;
         *  Multilingual = better non-English (Arabic, Spanish, etc.) at higher
         *  latency; Turbo = middle ground. */
        const val DEFAULT_MODEL = "eleven_flash_v2_5"
        val MODELS: List<Pair<String, String>> = listOf(
            "flash"        to "eleven_flash_v2_5",
            "turbo"        to "eleven_turbo_v2_5",
            "multilingual" to "eleven_multilingual_v2",
        )

        const val DEFAULT_STABILITY = 0.5f
        const val DEFAULT_SIMILARITY = 0.75f
        const val DEFAULT_STYLE = 0.0f
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_SPEAKER_BOOST = true
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.2f

        private const val KEY_ELEVENLABS_KEY = "elevenlabs.key"
        private const val KEY_ENABLED = "voice.enabled"
        private const val KEY_VOICE_ID = "voice.id"
        private const val KEY_CUSTOM_VOICE_ID = "voice.custom_id"
        private const val KEY_MODEL = "voice.model"
        private const val KEY_STABILITY = "voice.stability"
        private const val KEY_SIMILARITY = "voice.similarity"
        private const val KEY_STYLE = "voice.style"
        private const val KEY_SPEED = "voice.speed"
        private const val KEY_SPEAKER_BOOST = "voice.speaker_boost"

        @Volatile private var instance: VoicePrefs? = null
        fun get(ctx: Context): VoicePrefs =
            instance ?: synchronized(this) {
                instance ?: VoicePrefs(ctx).also { instance = it }
            }
    }
}

/** Snapshot of the five `voice_settings` knobs ElevenLabs accepts on every
 *  TTS call. Pass this to [ElevenLabsTtsClient.synthesize]. */
data class VoiceTuning(
    val stability: Float,
    val similarity: Float,
    val style: Float,
    val speed: Float,
    val speakerBoost: Boolean,
)

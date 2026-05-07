package com.r1.launcher.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Global voice config — used by OpenClaw chat, Terminal, Claude (STT) and
 * OpenClaw TTS readback. Persists ElevenLabs API key (encrypted), the auto-
 * speak toggle, and the chosen voice id.
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

    fun hasKey(): Boolean = !elevenlabsKey.isNullOrBlank()

    fun clear() {
        secure.edit { remove(KEY_ELEVENLABS_KEY) }
        plain.edit { remove(KEY_VOICE_ID); remove(KEY_ENABLED) }
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

        private const val KEY_ELEVENLABS_KEY = "elevenlabs.key"
        private const val KEY_ENABLED = "voice.enabled"
        private const val KEY_VOICE_ID = "voice.id"

        @Volatile private var instance: VoicePrefs? = null
        fun get(ctx: Context): VoicePrefs =
            instance ?: synchronized(this) {
                instance ?: VoicePrefs(ctx).also { instance = it }
            }
    }
}

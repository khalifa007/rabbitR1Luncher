package com.r1.launcher.sound

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the UI-click feedback volume (0..MAX). Speaker / media volume is
 * managed by the framework via STREAM_MUSIC, so it doesn't need its own pref.
 */
class SoundPrefs private constructor(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("sound.plain", Context.MODE_PRIVATE)

    var uiVolumeLevel: Int
        get() = plain.getInt(KEY_UI_VOL, DEFAULT_UI_LEVEL).coerceIn(0, MAX_UI_LEVEL)
        set(value) = plain.edit { putInt(KEY_UI_VOL, value.coerceIn(0, MAX_UI_LEVEL)) }

    companion object {
        const val MAX_UI_LEVEL = 15
        // Default 5/15 ≈ 0.33, matches the previous hardcoded UI_SOUND_VOLUME = 0.35f
        // so existing users feel no change until they crank it up or down.
        const val DEFAULT_UI_LEVEL = 5

        private const val KEY_UI_VOL = "ui.volume"

        @Volatile private var instance: SoundPrefs? = null
        fun get(ctx: Context): SoundPrefs =
            instance ?: synchronized(this) {
                instance ?: SoundPrefs(ctx).also { instance = it }
            }
    }
}

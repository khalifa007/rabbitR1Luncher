package com.r1.launcher.haptic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class HapticPrefs private constructor(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("haptic.plain", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = plain.getBoolean(KEY_ENABLED, true)
        set(value) = plain.edit { putBoolean(KEY_ENABLED, value) }

    companion object {
        private const val KEY_ENABLED = "haptic.enabled"

        @Volatile private var instance: HapticPrefs? = null
        fun get(ctx: Context): HapticPrefs =
            instance ?: synchronized(this) {
                instance ?: HapticPrefs(ctx).also { instance = it }
            }
    }
}

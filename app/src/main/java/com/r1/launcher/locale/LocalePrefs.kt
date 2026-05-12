package com.r1.launcher.locale

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Catalog entry for the language picker. `displayName` is rendered in the
 * language's own script so a fresh-boot user can pick without reading any
 * other text on the device.
 */
data class Language(
    val code: String,
    val displayName: String,
    val isRtl: Boolean,
)

/**
 * Per-app locale preference. Read on every Activity attachBaseContext to
 * apply a Configuration override; written by Settings → Language and the
 * onboarding language step. Plain (unencrypted) — locale code is not a secret.
 *
 * Adding a new language is three steps:
 *   1. Drop res/values-XX/strings.xml.
 *   2. Append a Language entry to [SUPPORTED] below.
 *   3. If the script needs a different font, edit ui/Theme.kt.
 */
class LocalePrefs private constructor(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("locale.plain", Context.MODE_PRIVATE)

    /** BCP-47 language tag (e.g. "en", "ar"). Defaults to English. */
    var language: String
        get() = plain.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = plain.edit { putString(KEY_LANGUAGE, value) }

    /** True iff the user has explicitly picked a language at least once.
     *  Used to skip the onboarding language step on subsequent boots. */
    var picked: Boolean
        get() = plain.getBoolean(KEY_PICKED, false)
        set(value) = plain.edit { putBoolean(KEY_PICKED, value) }

    companion object {
        const val DEFAULT_LANGUAGE = "en"

        /** All supported languages. Keep first-class English first. */
        val SUPPORTED: List<Language> = listOf(
            Language(code = "en", displayName = "English",  isRtl = false),
            Language(code = "ar", displayName = "العربية", isRtl = true),
            Language(code = "fr", displayName = "Français", isRtl = false),
        )

        fun isRtl(code: String): Boolean =
            SUPPORTED.firstOrNull { it.code == code }?.isRtl ?: false

        private const val KEY_LANGUAGE = "language.code"
        private const val KEY_PICKED = "language.picked"

        @Volatile private var instance: LocalePrefs? = null
        fun get(ctx: Context): LocalePrefs =
            instance ?: synchronized(this) {
                instance ?: LocalePrefs(ctx).also { instance = it }
            }
    }
}

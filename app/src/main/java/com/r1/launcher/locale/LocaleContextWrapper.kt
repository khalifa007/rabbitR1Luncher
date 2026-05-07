package com.r1.launcher.locale

import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.util.Locale

/**
 * Wraps [base] with a Configuration whose locale + layout direction are
 * forced to [code]. Called from [com.r1.launcher.LauncherActivity.attachBaseContext]
 * so every Resources.getString / stringResource lookup honours the user's
 * picked language without needing androidx.appcompat.
 *
 * Also updates [Locale.setDefault] so non-Resources consumers (SimpleDateFormat,
 * NumberFormat, java.util.Calendar) pick up the locale.
 */
fun applyLocale(base: Context, code: String): Context {
    val locale = Locale.forLanguageTag(code)
    Locale.setDefault(locale)

    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    val isRtl = LocalePrefs.isRtl(code)
    config.setLayoutDirection(locale)
    // setLayoutDirection from a locale may not be reliable for all locales on
    // every API; force explicitly so RTL languages we ship behave consistently.
    @Suppress("DEPRECATION")
    run {
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        config.screenLayout = (config.screenLayout and Configuration.SCREENLAYOUT_LAYOUTDIR_MASK.inv()) or
            (dir shl Configuration.SCREENLAYOUT_LAYOUTDIR_SHIFT)
    }
    return base.createConfigurationContext(config)
}

/**
 * The current default locale wrapped to force Latin numerals (0-9) regardless
 * of the script. Use this when constructing [java.text.SimpleDateFormat] or
 * [java.text.NumberFormat] so the device clock + date strings + message
 * timestamps + percentages all stay readable as ASCII digits even in Arabic
 * or Persian locale (which would otherwise render `٢٠:٤٢` instead of `20:42`).
 *
 * Locale.getDefault() must already be set to the user's pick — call this from
 * a `by lazy { ... }` block so the resolution happens after attachBaseContext.
 */
fun digitFriendlyLocale(): Locale = Locale.Builder()
    .setLocale(Locale.getDefault())
    .setUnicodeLocaleKeyword("nu", "latn")
    .build()


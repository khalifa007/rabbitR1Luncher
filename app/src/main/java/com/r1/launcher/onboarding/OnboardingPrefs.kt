package com.r1.launcher.onboarding

import android.content.Context
import androidx.core.content.edit

object OnboardingPrefs {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    fun isDone(ctx: Context): Boolean =
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(ctx: Context) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DONE, true) }
    }

    fun reset(ctx: Context) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DONE, false) }
    }
}

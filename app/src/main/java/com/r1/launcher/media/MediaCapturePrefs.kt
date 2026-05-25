package com.r1.launcher.media

import android.content.Context

/**
 * Persistent toggles for the audio-capture leg of screen recording. Read by
 * [MediaCaptureManager] at the start of each recording — changing a toggle
 * mid-recording has no effect on the in-flight session.
 *
 * **Defaults:** mic ON, playback OFF. The playback path uses
 * AudioSource.REMOTE_SUBMIX, and this MTK build's audio HAL configures that
 * source to *redirect* the audio mix away from the speakers (legacy AOSP
 * behavior, pre-MediaProjection era). Activating REMOTE_SUBMIX therefore
 * mutes the device's speakers for the duration of the recording — a bug
 * report-worthy surprise. Default OFF so a fresh install records cleanly
 * via the mic without muting; the user can opt in to playback capture and
 * accept the mute trade-off if they explicitly want music/app audio in
 * their recordings.
 */
object MediaCapturePrefs {
    private const val PREFS = "media.capture"
    private const val KEY_MIC = "capture.mic"
    private const val KEY_PLAYBACK = "capture.playback"
    private const val KEY_SCHEMA = "schema.version"
    /** Bump when changing default semantics. Each install advances through
     *  the migration steps in [migrate] exactly once. */
    private const val CURRENT_SCHEMA = 2

    /**
     * Run on app start (from [MediaCaptureManager.init]). Resets prefs that
     * shipped under old defaults to the new defaults. Safe to call every
     * boot — only the first call per schema bump does any work.
     */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        val v = p.getInt(KEY_SCHEMA, 0)
        if (v >= CURRENT_SCHEMA) return
        val ed = p.edit()
        if (v < 2) {
            // v1 → v2: playback default flipped from true to false because
            // REMOTE_SUBMIX redirects the audio mix on this MTK build, muting
            // device speakers while recording. Reset any previously-saved
            // "true" so existing installs match the new fresh-install
            // behavior. Users who actually want playback capture can flip it
            // back on in Settings → Remote Panel.
            ed.putBoolean(KEY_PLAYBACK, false)
        }
        ed.putInt(KEY_SCHEMA, CURRENT_SCHEMA).apply()
    }

    fun micEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MIC, true)

    fun setMicEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MIC, enabled).apply()
    }

    fun playbackEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PLAYBACK, false)

    fun setPlaybackEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PLAYBACK, enabled).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

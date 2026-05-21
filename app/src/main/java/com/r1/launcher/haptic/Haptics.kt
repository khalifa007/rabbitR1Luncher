package com.r1.launcher.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class Haptics private constructor(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val prefs = HapticPrefs.get(appCtx)

    // R1's MTK vibrator advertises CLICK, DOUBLE_CLICK, TICK, HEAVY_CLICK,
    // TEXTURE_TICK as supported predefined effects; capabilities/primitives
    // lists are empty so we don't try amplitude control or composition.
    private val vibrator: Vibrator? =
        (appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator

    fun click() = fire(VibrationEffect.EFFECT_CLICK)
    fun heavyClick() = fire(VibrationEffect.EFFECT_HEAVY_CLICK)
    fun doubleClick() = fire(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun fire(effect: Int) {
        if (!prefs.enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createPredefined(effect))
    }

    companion object {
        @Volatile private var instance: Haptics? = null
        @JvmStatic
        fun get(ctx: Context): Haptics =
            instance ?: synchronized(this) {
                instance ?: Haptics(ctx).also { instance = it }
            }
    }
}

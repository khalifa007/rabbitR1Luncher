package com.r1.launcher.wifishare

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

class WifiSharePrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "wifishare.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("wifishare.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("wifishare.plain", Context.MODE_PRIVATE)

    var ssid: String
        get() = plain.getString(KEY_SSID, null) ?: defaultSsid().also { ssid = it }
        set(value) = plain.edit { putString(KEY_SSID, value) }

    var password: String
        get() = secure.getString(KEY_PASS, null) ?: defaultPassword().also { password = it }
        set(value) = secure.edit { putString(KEY_PASS, value) }

    var timerMinutes: Int
        get() = plain.getInt(KEY_TIMER, 0)
        set(value) = plain.edit { putInt(KEY_TIMER, value) }

    private fun defaultSsid(): String {
        val tail = runCatching {
            @Suppress("DEPRECATION")
            (Build.SERIAL ?: "").takeLast(4).filter { it.isLetterOrDigit() }
        }.getOrNull().orEmpty()
        val suffix = if (tail.length == 4) tail else randomAlphanumeric(4)
        return "R1-$suffix"
    }

    private fun defaultPassword(): String = randomAlphanumeric(10)

    private fun randomAlphanumeric(len: Int): String {
        val pool = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(pool[rng.nextInt(pool.length)]) }
        return sb.toString()
    }

    companion object {
        private const val KEY_SSID = "share.ssid"
        private const val KEY_PASS = "share.pass"
        private const val KEY_TIMER = "share.timerMinutes"

        @Volatile private var instance: WifiSharePrefs? = null
        fun get(ctx: Context): WifiSharePrefs =
            instance ?: synchronized(this) {
                instance ?: WifiSharePrefs(ctx).also { instance = it }
            }
    }
}

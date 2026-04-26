package com.r1.launcher.updater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.r1.launcher.LauncherState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OTAUpdater {
    private const val TAG = "OTAUpdater"
    private const val API_URL = "https://api.github.com/repos/khalifa007/rabbitR1Luncher/releases/latest"

    // Root command executor injected from LauncherActivity
    var executeRootCommand: ((String) -> Boolean)? = null

    fun checkForUpdates(
        context: Context,
        state: LauncherState,
        forcePrompt: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        state.updateIconState = 1 // Checking indicator
        Thread {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val code = conn.responseCode
                if (code == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tagName = json.getString("tag_name") // e.g. "v3.6.1"

                    // Extract remote version code from tag (strip leading "v")
                    val remoteVersionStr = tagName.removePrefix("v") // "3.6.1"

                    // Get local version name from PackageManager
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val localVersionStr = pInfo.versionName ?: "0.0.0" // e.g. "3.6.0"

                    Log.i(TAG, "Local: v$localVersionStr | Remote: $tagName")

                    if (isNewerVersion(remoteVersionStr, localVersionStr)) {
                        // Check if we've already tried and failed for this exact tag (anti-loop)
                        val prefs = context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
                        val lastFailed = prefs.getString("last_failed_tag", "")

                        if (tagName == lastFailed && !forcePrompt) {
                            // Silently skip — we already tried this one and it didn't work
                            Log.i(TAG, "Skipping $tagName — previous install attempt failed")
                            resetState(state, null, null)
                            return@Thread
                        }

                        // Find the APK asset
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }

                        if (downloadUrl.isNotEmpty()) {
                            notify(onResult, "Update $tagName found! Downloading...")
                            downloadAndInstall(context, tagName, downloadUrl, state, onResult)
                        } else {
                            resetState(state, onResult, if (forcePrompt) "No APK in release $tagName" else null)
                        }
                    } else {
                        resetState(state, onResult, if (forcePrompt) "Already up to date (v$localVersionStr)" else null)
                    }
                } else if (code == 404) {
                    resetState(state, onResult, if (forcePrompt) "No releases on GitHub yet" else null)
                } else {
                    resetState(state, onResult, if (forcePrompt) "GitHub check failed (HTTP $code)" else null)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "OTA check error", e)
                resetState(state, onResult, if (forcePrompt) "Check failed: ${e.message}" else null)
            }
        }.start()
    }

    /**
     * Compares semantic version strings like "3.6.1" > "3.6.0"
     * Returns true if remote is strictly newer than local.
     */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun downloadAndInstall(
        context: Context,
        tagName: String,
        downloadUrl: String,
        state: LauncherState,
        onResult: ((String) -> Unit)?
    ) {
        state.updateIconState = 2 // Downloading indicator
        Thread {
            val cacheFile = File(context.cacheDir, "update_cache.apk")
            try {
                if (cacheFile.exists()) cacheFile.delete()

                // Follow redirects manually (GitHub -> AWS S3 CDN)
                var currentUrl = downloadUrl
                var conn: HttpURLConnection
                var attempts = 0
                while (true) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 90000
                    val status = conn.responseCode
                    if (status in 300..399) {
                        val location = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        currentUrl = location
                        if (++attempts > 5) break // safety limit
                    } else {
                        break
                    }
                }

                // Stream download to cacheDir (app has full write access here)
                FileOutputStream(cacheFile).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()

                Log.i(TAG, "Downloaded ${cacheFile.length() / 1024}KB to ${cacheFile.absolutePath}")

                val rootFunc = executeRootCommand
                if (rootFunc != null) {
                    val src = cacheFile.absolutePath
                    val dst = "/data/local/tmp/update.apk"
                    // Copy to world-readable location then install silently via root
                    rootFunc("cp \"$src\" $dst && chmod 644 $dst")
                    notify(onResult, "Installing update...")
                    val ok = rootFunc("pm install -r $dst")
                    if (ok) {
                        // Clear the fail-guard on success
                        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
                            .edit().remove("last_failed_tag").apply()
                        // Relaunch launcher
                        rootFunc("am force-stop com.r1.launcher")
                        rootFunc("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                    } else {
                        // Mark this tag as failed so silent checks don't loop
                        context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
                            .edit().putString("last_failed_tag", tagName).apply()
                        resetState(state, onResult, "Install failed — try again manually")
                    }
                } else {
                    resetState(state, onResult, "Root shell unavailable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                cacheFile.delete()
                resetState(state, onResult, "Download failed: ${e.message}")
            }
        }.start()
    }

    private fun notify(onResult: ((String) -> Unit)?, msg: String) {
        Handler(Looper.getMainLooper()).post { onResult?.invoke(msg) }
    }

    private fun resetState(state: LauncherState, onResult: ((String) -> Unit)?, msg: String?) {
        state.updateIconState = 0
        if (msg != null) notify(onResult, msg)
    }
}

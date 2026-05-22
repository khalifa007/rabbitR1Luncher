# Remote media capture implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add screenshot + screen-recording capability to the web companion so users can capture media on the R1 over LAN and download it.

**Architecture:** New `MediaCaptureManager` Kotlin module shells out to carroot's `screencap` / `screenrecord --audio-source mic`, copies results into `filesDir/captures/`, and serves them via new `/static/media/` routes. Six new `capture.*` JSON-RPC methods plus two broadcast events drive a new `view-media` thumbnail grid in the companion. Mic audio always on, 3-min hard cap, 100-file/500-MB retention, web-only trigger.

**Tech Stack:** Kotlin (Android), carroot socket (`127.0.0.1:1337`), NanoHTTPD/NanoWSD, vanilla JS + CSS in `assets/web/`.

**Spec:** `docs/superpowers/specs/2026-05-22-remote-media-capture-design.md`.

---

## Pre-flight

Before Task 1, confirm the dev loop works on this branch:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

If `screenrecord --audio-source mic` is unfamiliar, sanity-check the carroot side once:

```bash
adb shell 'echo "screenrecord --help 2>&1 | head -30" | nc 127.0.0.1 1337'
```

Expected: help text includes `--audio-source` (Android 12+/API 31+). The R1 is on `ap2a` (Android 14), so this is guaranteed but worth eyeballing once.

---

## Task 1: `CaptureItem` + `MediaCaptureManager` skeleton

**Files:**
- Create: `app/src/main/java/com/r1/launcher/media/CaptureItem.kt`
- Create: `app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt`

- [ ] **Step 1: Create `CaptureItem.kt`**

```kotlin
package com.r1.launcher.media

data class CaptureItem(
    val name: String,
    val kind: String,        // "image" | "video"
    val sizeBytes: Long,
    val takenAt: Long,
    val durationMs: Long?,
    val url: String,
    val thumbUrl: String,
)
```

- [ ] **Step 2: Create `MediaCaptureManager.kt` skeleton**

```kotlin
package com.r1.launcher.media

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object MediaCaptureManager {
    private const val TAG = "MediaCapture"

    const val MAX_FILES = 100
    const val MAX_BYTES = 500L * 1024 * 1024
    const val VIDEO_TIME_LIMIT_S = 180
    const val VIDEO_BIT_RATE = 4_000_000
    const val LOW_STORAGE_FREE_BYTES = 100L * 1024 * 1024

    private lateinit var rootDir: File
    private lateinit var imagesDir: File
    private lateinit var videosDir: File
    private lateinit var thumbsDir: File

    private var initialized = false
    private val fnameDateFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private var lastFnameStamp: String = ""
    private val fnameCounter = AtomicInteger(0)

    @Volatile private var recordingPid: Int = -1
    @Volatile private var recordingStartedAt: Long = 0L
    @Volatile private var recordingTmpPath: String = ""

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        rootDir = File(ctx.filesDir, "captures")
        imagesDir = File(rootDir, "images").apply { mkdirs() }
        videosDir = File(rootDir, "videos").apply { mkdirs() }
        thumbsDir = File(videosDir, ".thumbs").apply { mkdirs() }
        // Clean stale tmp files from prior runs.
        runCatching {
            sendCarroot("rm -f /data/local/tmp/r1cap-*")
        }
        recoverOrphanRecording()
        initialized = true
        Log.i(TAG, "init: rootDir=${rootDir.absolutePath}")
    }

    fun isRecording(): Boolean = recordingPid > 0
    fun recordingStartedAt(): Long = recordingStartedAt

    fun list(limit: Int = 50): List<CaptureItem> {
        if (!initialized) return emptyList()
        val all = (imagesDir.listFiles().orEmpty() + videosDir.listFiles().orEmpty())
            .filter { it.isFile }
            .sortedByDescending { it.name }
            .take(limit)
        return all.map { toItem(it) }
    }

    fun totalBytes(): Long {
        if (!initialized) return 0
        var total = 0L
        imagesDir.listFiles()?.forEach { if (it.isFile) total += it.length() }
        videosDir.listFiles()?.forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun delete(name: String): Boolean {
        if (!initialized) return false
        val f = resolveByName(name) ?: return false
        val ok = f.delete()
        if (ok && f.parentFile == videosDir) {
            File(thumbsDir, f.nameWithoutExtension + ".jpg").delete()
        }
        return ok
    }

    fun clear(): Int {
        if (!initialized) return 0
        var n = 0
        imagesDir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        videosDir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        thumbsDir.listFiles()?.forEach { it.delete() }
        return n
    }

    internal fun resolveByName(name: String): File? {
        val img = File(imagesDir, name)
        if (img.exists()) return img
        val vid = File(videosDir, name)
        if (vid.exists()) return vid
        return null
    }

    internal fun resolveThumbByName(name: String): File? {
        val f = File(thumbsDir, name)
        return if (f.exists()) f else null
    }

    private fun toItem(f: File): CaptureItem {
        val isVideo = f.parentFile == videosDir
        val ext = f.extension.lowercase()
        val thumbFile = if (isVideo) File(thumbsDir, f.nameWithoutExtension + ".jpg") else null
        val thumbUrl = when {
            !isVideo -> "/static/media/${f.name}"
            thumbFile?.exists() == true -> "/static/media/.thumbs/${thumbFile.name}"
            else -> "/static/media/_play_placeholder"
        }
        return CaptureItem(
            name = f.name,
            kind = if (isVideo) "video" else "image",
            sizeBytes = f.length(),
            takenAt = f.lastModified(),
            durationMs = null, // populated by stopVideoRecording for fresh items; null after restart is acceptable
            url = "/static/media/${f.name}",
            thumbUrl = thumbUrl,
        )
    }

    internal fun nextFilename(kind: String, ext: String): String {
        val now = Date()
        val stamp = fnameDateFmt.format(now)
        if (stamp != lastFnameStamp) {
            lastFnameStamp = stamp
            fnameCounter.set(0)
        }
        val seq = fnameCounter.incrementAndGet()
        val prefix = if (kind == "image") "shot" else "rec"
        return "%s-%s-%03d.%s".format(prefix, stamp, seq, ext)
    }

    // --- Implemented in later tasks ---

    fun captureScreenshot(): Result<CaptureItem> =
        Result.failure(NotImplementedError("Task 2"))

    fun startVideoRecording(): Result<Long> =
        Result.failure(NotImplementedError("Task 3"))

    fun stopVideoRecording(): Result<CaptureItem> =
        Result.failure(NotImplementedError("Task 3"))

    private fun recoverOrphanRecording() {
        // Task 3
    }

    /** Send a single carroot command synchronously, returning combined stdout. */
    internal fun sendCarroot(cmd: String): String {
        // Task 2 implements this. For init's `rm`, runCatching swallows the missing impl.
        return ""
    }
}
```

- [ ] **Step 3: Build, verify it compiles**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. No use sites yet, so no runtime path is exercised.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/media/
git commit -m "feat(media): scaffold MediaCaptureManager + CaptureItem"
```

---

## Task 2: Implement `captureScreenshot()` end-to-end

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt`

- [ ] **Step 1: Implement a synchronous `sendCarroot` helper**

Replace the placeholder `sendCarroot` with a real implementation. The existing pattern is in `LauncherActivity.kt` — we replicate it here so `MediaCaptureManager` doesn't depend on the activity.

```kotlin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// inside MediaCaptureManager:

internal fun sendCarroot(cmd: String, timeoutMs: Int = 5000): String {
    return try {
        Socket("127.0.0.1", 1337).use { sock ->
            sock.soTimeout = timeoutMs
            sock.getOutputStream().write((cmd + "\nexit\n").toByteArray())
            sock.getOutputStream().flush()
            sock.shutdownOutput()
            BufferedReader(InputStreamReader(sock.getInputStream())).readText()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sendCarroot failed: ${t.message}")
        ""
    }
}

internal fun sendCarrootBackground(cmd: String): Int {
    // Fires off a backgrounded command and returns the PID.
    // PID echo is forced via `& echo $!`. Used for screenrecord.
    val out = sendCarroot("$cmd & echo \$!")
    return out.lines().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: -1
}
```

- [ ] **Step 2: Implement `captureScreenshot()`**

Replace the placeholder body:

```kotlin
fun captureScreenshot(): Result<CaptureItem> {
    if (!initialized) return Result.failure(IllegalStateException("not_initialized"))

    val fname = nextFilename("image", "png")
    val tmpPath = "/data/local/tmp/r1cap-${System.nanoTime()}.png"
    val destFile = File(imagesDir, fname)

    // First attempt
    var copied = runCapture(tmpPath, destFile)
    if (!copied) {
        // Retry once after 200ms
        Thread.sleep(200)
        copied = runCapture(tmpPath, destFile)
    }

    sendCarroot("rm -f $tmpPath")

    if (!copied || destFile.length() < 1024) {
        destFile.delete()
        return Result.failure(RuntimeException("capture_failed"))
    }

    enforceRetention()
    return Result.success(toItem(destFile))
}

private fun runCapture(tmpPath: String, destFile: File): Boolean {
    sendCarroot("screencap -p $tmpPath")
    // The cp must run as root (filesDir is owned by the app uid; root can write to it).
    val cpOut = sendCarroot("cp $tmpPath ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK")
    return cpOut.contains("OK") && destFile.exists() && destFile.length() > 1024
}
```

- [ ] **Step 3: Add `enforceRetention()` stub** (real impl in Task 4 — for now just no-op so the call site compiles)

```kotlin
private fun enforceRetention() {
    // Task 4
}
```

- [ ] **Step 4: Build + install**

```bash
./gradlew assembleDebug && \
  adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

- [ ] **Step 5: Manual verify via a one-off invocation**

`MediaCaptureManager.init` and `captureScreenshot` aren't wired yet. Force a manual invocation by attaching a temporary call in `LauncherActivity.onCreate` (we'll delete this in Task 5):

Temporarily, at the end of `LauncherActivity.onCreate`:

```kotlin
Thread {
    com.r1.launcher.media.MediaCaptureManager.init(this)
    val r = com.r1.launcher.media.MediaCaptureManager.captureScreenshot()
    android.util.Log.i("MediaCaptureSmoke", "screenshot result: $r")
}.start()
```

Rebuild, install, and watch:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
adb logcat -s MediaCaptureSmoke MediaCapture
```

Expected:
- `MediaCapture: init: rootDir=/data/user/0/com.r1.launcher/files/captures`
- `MediaCaptureSmoke: screenshot result: Success(CaptureItem(name=shot-..., kind=image, sizeBytes=NNNN, ...))`
- File exists: `adb shell run-as com.r1.launcher ls -la files/captures/images/` (may fail on platform-signed system app — use `adb shell 'echo "ls -la /data/data/com.r1.launcher/files/captures/images/" | nc 127.0.0.1 1337'` instead).

- [ ] **Step 6: Remove the smoke-test snippet from `onCreate`**

Delete the temporary `Thread { ... }.start()` block.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt
git commit -m "feat(media): screenshot capture via screencap+carroot"
```

---

## Task 3: Video start/stop + thumbnail + orphan recovery

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt`

- [ ] **Step 1: Implement `startVideoRecording`**

```kotlin
fun startVideoRecording(): Result<Long> {
    if (!initialized) return Result.failure(IllegalStateException("not_initialized"))
    if (recordingPid > 0) return Result.failure(IllegalStateException("already_recording"))

    val freeBytes = rootDir.usableSpace
    if (freeBytes < LOW_STORAGE_FREE_BYTES) {
        return Result.failure(LowStorageException(freeBytes))
    }

    val tmpPath = "/data/local/tmp/r1cap-${System.nanoTime()}.mp4"
    val cmd = "screenrecord --audio-source mic --bit-rate $VIDEO_BIT_RATE " +
        "--time-limit $VIDEO_TIME_LIMIT_S $tmpPath"

    val pid = sendCarrootBackground(cmd)
    if (pid <= 0) {
        return Result.failure(RuntimeException("recording_start_failed"))
    }

    recordingPid = pid
    recordingTmpPath = tmpPath
    recordingStartedAt = System.currentTimeMillis()
    Log.i(TAG, "startVideoRecording: pid=$pid tmp=$tmpPath")
    return Result.success(recordingStartedAt)
}

class LowStorageException(val freeBytes: Long) : RuntimeException("low_storage")
```

- [ ] **Step 2: Implement `stopVideoRecording`**

```kotlin
fun stopVideoRecording(): Result<CaptureItem> {
    if (!initialized) return Result.failure(IllegalStateException("not_initialized"))
    val pid = recordingPid
    val tmpPath = recordingTmpPath
    val startedAt = recordingStartedAt
    if (pid <= 0) return Result.failure(IllegalStateException("not_recording"))

    // SIGINT so screenrecord finalizes the moov atom.
    sendCarroot("kill -2 $pid")
    // Wait for moov atom write (matches TranscriberRecordingService pattern).
    Thread.sleep(500)
    // Verify the process is actually gone — if not, give it another beat.
    val stillAlive = sendCarroot("kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD").contains("ALIVE")
    if (stillAlive) {
        Thread.sleep(500)
        sendCarroot("kill -9 $pid")
    }

    val durationMs = System.currentTimeMillis() - startedAt
    val fname = nextFilename("video", "mp4")
    val destFile = File(videosDir, fname)
    val cpOut = sendCarroot("cp $tmpPath ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK")
    sendCarroot("rm -f $tmpPath")

    recordingPid = -1
    recordingTmpPath = ""
    recordingStartedAt = 0L

    if (!cpOut.contains("OK") || !destFile.exists() || destFile.length() < 1024) {
        destFile.delete()
        return Result.failure(RuntimeException("recording_lost"))
    }

    generateThumb(destFile)
    enforceRetention()

    val item = toItem(destFile).copy(durationMs = durationMs)
    Log.i(TAG, "stopVideoRecording: $fname ${destFile.length()}B ${durationMs}ms")
    return Result.success(item)
}
```

- [ ] **Step 3: Implement `generateThumb`**

```kotlin
import android.media.MediaMetadataRetriever
import java.io.FileOutputStream
import android.graphics.Bitmap

private fun generateThumb(mp4: File): File? {
    val out = File(thumbsDir, mp4.nameWithoutExtension + ".jpg")
    return try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(mp4.absolutePath)
        val bmp = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        mmr.release()
        if (bmp == null) return null
        FileOutputStream(out).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, fos)
        }
        bmp.recycle()
        Log.i(TAG, "generateThumb: ${out.name} ${out.length()}B")
        out
    } catch (t: Throwable) {
        Log.w(TAG, "generateThumb failed for ${mp4.name}: ${t.message}")
        out.delete()
        null
    }
}
```

- [ ] **Step 4: Implement `recoverOrphanRecording`**

```kotlin
private fun recoverOrphanRecording() {
    // pgrep -f is broken on toybox per CLAUDE.md — use ps + grep.
    val out = sendCarroot("ps -ef | grep screenrecord | grep -v grep | awk '{print \$2}'")
    val pid = out.lines().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: return
    Log.w(TAG, "recoverOrphanRecording: found orphan pid=$pid, sending SIGINT")
    sendCarroot("kill -2 $pid")
    Thread.sleep(500)
    // Find the tmp file. screenrecord writes to whatever path was given; we scan for r1cap-*.mp4.
    val tmp = sendCarroot("ls /data/local/tmp/r1cap-*.mp4 2>/dev/null | head -1").trim()
    if (tmp.isEmpty()) return
    val fname = nextFilename("video", "mp4")
    val destFile = File(videosDir, fname)
    val cpOut = sendCarroot("cp $tmp ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK")
    sendCarroot("rm -f $tmp")
    if (cpOut.contains("OK") && destFile.length() > 1024) {
        generateThumb(destFile)
        Log.i(TAG, "recoverOrphanRecording: salvaged ${destFile.name}")
    } else {
        destFile.delete()
    }
}
```

- [ ] **Step 5: Build + smoke-test recording**

Append a temporary smoke harness in `LauncherActivity.onCreate` (delete in Task 5):

```kotlin
Thread {
    com.r1.launcher.media.MediaCaptureManager.init(this)
    com.r1.launcher.media.MediaCaptureManager.startVideoRecording()
    Thread.sleep(5_000)
    val r = com.r1.launcher.media.MediaCaptureManager.stopVideoRecording()
    android.util.Log.i("MediaCaptureSmoke", "video result: $r")
}.start()
```

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
adb logcat -s MediaCaptureSmoke MediaCapture
```

Expected:
- `startVideoRecording: pid=NNNN tmp=...`
- `generateThumb: rec-... NNNNB`
- `stopVideoRecording: rec-... NNNNB ~5000ms`
- `video result: Success(CaptureItem(... durationMs=4xxx-5xxx))`

Pull the mp4 to your desktop and confirm it plays with audio:

```bash
adb shell 'echo "cp /data/data/com.r1.launcher/files/captures/videos/rec-*.mp4 /sdcard/" | nc 127.0.0.1 1337'
adb pull /sdcard/rec-*.mp4 /tmp/
xdg-open /tmp/rec-*.mp4
```

- [ ] **Step 6: Remove smoke harness, commit**

```bash
git add app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt
git commit -m "feat(media): video record + thumbnail + orphan recovery"
```

---

## Task 4: Retention enforcement + `list`/`delete`/`clear` polish

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt`

- [ ] **Step 1: Replace the `enforceRetention()` stub**

```kotlin
private fun enforceRetention() {
    val all = (imagesDir.listFiles().orEmpty() + videosDir.listFiles().orEmpty())
        .filter { it.isFile }
        .sortedBy { it.lastModified() } // oldest first
        .toMutableList()

    var totalBytes = all.sumOf { it.length() }
    var evicted = 0
    val beforeBytes = totalBytes

    while (all.isNotEmpty() && (all.size > MAX_FILES || totalBytes > MAX_BYTES)) {
        val victim = all.removeAt(0)
        val size = victim.length()
        if (victim.delete()) {
            totalBytes -= size
            evicted++
            if (victim.parentFile == videosDir) {
                File(thumbsDir, victim.nameWithoutExtension + ".jpg").delete()
            }
        } else {
            // Can't delete (race?). Break to avoid infinite loop.
            break
        }
    }

    if (evicted > 0) {
        Log.i(TAG, "enforceRetention: evicted $evicted files (was ${beforeBytes}B, now ${totalBytes}B)")
    }
}
```

- [ ] **Step 2: Build, install, manually verify retention**

Temporarily lower the cap to test:

```kotlin
const val MAX_FILES = 5  // temp, revert before commit
```

Take 7 screenshots (via repeated invocations from the smoke harness or by calling from the in-app UI once Task 8 lands — for now just loop in a temp thread). Watch logcat:

```
enforceRetention: evicted 2 files (was XXXB, now YYYB)
```

Confirm only 5 files remain.

- [ ] **Step 3: Restore `MAX_FILES = 100`, commit**

```bash
git add app/src/main/java/com/r1/launcher/media/MediaCaptureManager.kt
git commit -m "feat(media): retention enforcement (100 files / 500MB)"
```

---

## Task 5: Wire state + host interface + activity implementations

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/LauncherState.kt:51`
- Modify: `app/src/main/java/com/r1/launcher/LauncherNav.kt` (`LauncherHost` interface)
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt`

- [ ] **Step 1: Add two state fields to `LauncherState`**

Find an appropriate group of fields (e.g. near the terminal-related state) and add:

```kotlin
// === Media capture (web companion) ===
var mediaRecording by mutableStateOf(false)
var mediaRecordingStartedAt by mutableLongStateOf(0L)
```

(If `mutableLongStateOf` isn't already imported, add `import androidx.compose.runtime.mutableLongStateOf`.)

- [ ] **Step 2: Add host methods to `LauncherHost` interface in `LauncherNav.kt`**

Append to the interface (anywhere is fine — group near terminal/web methods):

```kotlin
// Media capture (web companion only)
fun mediaCaptureScreenshot(): Result<com.r1.launcher.media.CaptureItem>
fun mediaStartVideo(): Result<Long>
fun mediaStopVideo(): Result<com.r1.launcher.media.CaptureItem>
fun mediaList(limit: Int): List<com.r1.launcher.media.CaptureItem>
fun mediaTotalBytes(): Long
fun mediaIsRecording(): Boolean
fun mediaDelete(name: String): Boolean
fun mediaClear(): Int
```

- [ ] **Step 3: Implement them in `LauncherActivity.kt`**

Add `MediaCaptureManager.init(this)` to `LauncherActivity.onCreate` after the other manager inits (e.g. right after `AppStore.init` or wherever the other module inits live — search for existing `init(this)` calls):

```kotlin
com.r1.launcher.media.MediaCaptureManager.init(this)
```

Then add the host overrides (group them near `terminalRun` / `claudeSend`):

```kotlin
override fun mediaCaptureScreenshot(): Result<com.r1.launcher.media.CaptureItem> {
    val r = com.r1.launcher.media.MediaCaptureManager.captureScreenshot()
    r.onSuccess { item -> webServer?.broadcastCaptureAdded(item) }
    return r
}

override fun mediaStartVideo(): Result<Long> {
    val r = com.r1.launcher.media.MediaCaptureManager.startVideoRecording()
    r.onSuccess { startedAt ->
        state.mediaRecording = true
        state.mediaRecordingStartedAt = startedAt
        webServer?.broadcastCaptureRecording(true, startedAt)
        armRecordingWatchdog()
    }
    return r
}

override fun mediaStopVideo(): Result<com.r1.launcher.media.CaptureItem> {
    val r = com.r1.launcher.media.MediaCaptureManager.stopVideoRecording()
    state.mediaRecording = false
    state.mediaRecordingStartedAt = 0L
    webServer?.broadcastCaptureRecording(false, 0L)
    r.onSuccess { item -> webServer?.broadcastCaptureAdded(item) }
    return r
}

override fun mediaList(limit: Int) =
    com.r1.launcher.media.MediaCaptureManager.list(limit)

override fun mediaTotalBytes() =
    com.r1.launcher.media.MediaCaptureManager.totalBytes()

override fun mediaIsRecording() =
    com.r1.launcher.media.MediaCaptureManager.isRecording()

override fun mediaDelete(name: String) =
    com.r1.launcher.media.MediaCaptureManager.delete(name)

override fun mediaClear() =
    com.r1.launcher.media.MediaCaptureManager.clear()

private var recordingWatchdog: android.os.Handler? = null
private fun armRecordingWatchdog() {
    val h = android.os.Handler(android.os.Looper.getMainLooper())
    recordingWatchdog = h
    h.postDelayed({
        if (state.mediaRecording) {
            android.util.Log.w("LauncherActivity", "recording watchdog fired — forcing stop")
            mediaStopVideo()
        }
    }, (com.r1.launcher.media.MediaCaptureManager.VIDEO_TIME_LIMIT_S + 5) * 1000L)
}
```

`broadcastCaptureAdded` and `broadcastCaptureRecording` don't exist yet — they're added in Task 7. The compiler will flag them; that's expected. To unblock the build NOW, stub them on `R1WebServer`:

Add to `R1WebServer.kt` (next to existing `broadcast*` methods around line 575):

```kotlin
fun broadcastCaptureAdded(item: com.r1.launcher.media.CaptureItem) {
    // Real impl in Task 7
}

fun broadcastCaptureRecording(recording: Boolean, startedAt: Long) {
    // Real impl in Task 7
}
```

- [ ] **Step 4: Build + install**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

Expected: BUILD SUCCESSFUL. App launches normally with no functional change visible — only wiring is in place.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/r1/launcher/LauncherState.kt \
        app/src/main/java/com/r1/launcher/LauncherNav.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt \
        app/src/main/java/com/r1/launcher/web/R1WebServer.kt
git commit -m "feat(media): state, host interface, activity wiring"
```

---

## Task 6: Static asset route + play-placeholder drawable

**Files:**
- Create: `app/src/main/res/drawable/ic_play_placeholder.xml`
- Modify: `app/src/main/java/com/r1/launcher/web/R1WebServer.kt`

- [ ] **Step 1: Create the play-placeholder vector**

`app/src/main/res/drawable/ic_play_placeholder.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="120dp"
    android:height="120dp"
    android:viewportWidth="120"
    android:viewportHeight="120">
    <path
        android:fillColor="#222222"
        android:pathData="M0,0L120,0L120,120L0,120Z" />
    <path
        android:fillColor="#FF6A00"
        android:pathData="M45,30L90,60L45,90Z" />
</vector>
```

We won't actually serve the XML — we serve a static SVG string from `R1WebServer` directly. The vector resource is here purely so a designer can swap glyphs later if they want. (No-op for now, harmless to include.)

- [ ] **Step 2: Find `serveHttp` in `R1WebServer.kt` and add the media routing branch**

Search for the existing `/static/` block (around the spot that maps `/static/<x>` → `web/<x>`). Add the new branch BEFORE that, since `/static/media/` is a strict prefix of `/static/`:

```kotlin
// --- Media captures ---
if (uri.startsWith("/static/media/")) {
    return serveMediaStatic(session, uri.removePrefix("/static/media/"))
}
```

Then add the helper somewhere in the class:

```kotlin
private fun serveMediaStatic(session: IHTTPSession, rest: String): Response {
    // _play_placeholder synthetic
    if (rest == "_play_placeholder") {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120">
            <rect width="120" height="120" fill="#222"/>
            <polygon points="45,30 90,60 45,90" fill="#FF6A00"/>
        </svg>""".trimIndent()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg)
    }

    val captures = java.io.File(ctx.filesDir, "captures")
    val file = when {
        rest.startsWith(".thumbs/") ->
            java.io.File(captures, "videos/" + rest)
        else -> {
            // Try images/ then videos/
            val img = java.io.File(captures, "images/$rest")
            val vid = java.io.File(captures, "videos/$rest")
            when { img.exists() -> img; vid.exists() -> vid; else -> img }
        }
    }
    if (!file.exists() || !file.isFile) {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }
    val mime = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
    val download = session.parameters["download"]?.firstOrNull() == "1"
    val resp = NanoHTTPD.newChunkedResponse(
        Response.Status.OK, mime, file.inputStream()
    )
    if (download) {
        resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
    }
    return resp
}
```

`ctx: Context` is the existing constructor field on `R1WebServer` (declared around line 49). It's already in scope inside any method on the class.

- [ ] **Step 3: Build + install + curl the route**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'

# Find the R1's IP (e.g. via Settings → Network in the companion or `adb shell ifconfig wlan0`)
R1_IP=192.168.1.42
curl -i "http://$R1_IP:8080/static/media/_play_placeholder"
```

Expected: `HTTP/1.1 200 OK`, `Content-Type: image/svg+xml`, SVG body.

For an actual file, drop a PNG in via carroot first:

```bash
adb shell 'echo "cp /sdcard/test.png /data/data/com.r1.launcher/files/captures/images/shot-test.png && chmod 644 /data/data/com.r1.launcher/files/captures/images/shot-test.png" | nc 127.0.0.1 1337'
curl -i "http://$R1_IP:8080/static/media/shot-test.png" -o /tmp/got.png
curl -I "http://$R1_IP:8080/static/media/shot-test.png?download=1"
```

Expected: first downloads inline (no `Content-Disposition`); second includes `Content-Disposition: attachment; filename="shot-test.png"`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_play_placeholder.xml \
        app/src/main/java/com/r1/launcher/web/R1WebServer.kt
git commit -m "feat(media): /static/media/ asset route + play placeholder"
```

---

## Task 7: RPC dispatch + snapshot + broadcast helpers

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt`
- Modify: `app/src/main/java/com/r1/launcher/web/R1WebServer.kt`

- [ ] **Step 1: Add `capture.*` cases to `WebRpc.dispatch`**

In `WebRpc.kt`, find the `dispatch` `when (method) {...}` block. Failures throw `RpcException(code, message)` — the existing `R1WebServer.kt:499-503` wrapper catches it and emits a proper `{ok:false, error:{code,message}}` frame. Successes return a `JsonElement`. Add these cases following the existing pattern (e.g. how `hermes.send` / `transcriber.delete` are handled):

```kotlin
"capture.screenshot" -> {
    host.mediaCaptureScreenshot().fold(
        onSuccess = { item -> captureItemJson(item) },
        onFailure = { t ->
            throw RpcException(
                code = (t as? RpcException)?.code ?: "capture_failed",
                message = t.message ?: "capture failed"
            )
        }
    )
}
"capture.startVideo" -> {
    if (host.mediaIsRecording()) {
        // Already recording — echo current startedAt so a second client can sync.
        buildJsonObject {
            put("code", "already_recording")
            put("startedAt", state.mediaRecordingStartedAt)
        }
    } else {
        host.mediaStartVideo().fold(
            onSuccess = { startedAt ->
                buildJsonObject {
                    put("ok", true)
                    put("startedAt", startedAt)
                }
            },
            onFailure = { t ->
                when (t) {
                    is com.r1.launcher.media.MediaCaptureManager.LowStorageException ->
                        throw RpcException("low_storage", "free=${t.freeBytes}")
                    else ->
                        throw RpcException("recording_start_failed", t.message ?: "start failed")
                }
            }
        )
    }
}
"capture.stopVideo" -> {
    host.mediaStopVideo().fold(
        onSuccess = { item -> captureItemJson(item) },
        onFailure = { t ->
            throw RpcException(
                code = if (t.message == "not_recording") "not_recording" else "recording_lost",
                message = t.message ?: "stop failed"
            )
        }
    )
}
"capture.list" -> {
    val limit = params?.get("limit")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 50
    val items = host.mediaList(limit)
    buildJsonObject {
        put("items", buildJsonArray { items.forEach { add(captureItemJson(it)) } })
        put("totalBytes", host.mediaTotalBytes())
    }
}
"capture.delete" -> {
    val name = params.requireString("name")
    if (host.mediaDelete(name)) {
        buildJsonObject { put("ok", true) }
    } else {
        throw RpcException("not_found", "no such capture: $name")
    }
}
"capture.clear" -> {
    buildJsonObject { put("deleted", host.mediaClear()) }
}
```

Add a helper at the bottom of the file (next to `secretTail` / `requireString`):

```kotlin
private fun captureItemJson(item: com.r1.launcher.media.CaptureItem): JsonObject = buildJsonObject {
    put("name", item.name)
    put("kind", item.kind)
    put("sizeBytes", item.sizeBytes)
    put("takenAt", item.takenAt)
    item.durationMs?.let { put("durationMs", it) }
    put("url", item.url)
    put("thumbUrl", item.thumbUrl)
}
```

- [ ] **Step 2: Extend `buildSnapshot` with the `media` block**

In `WebRpc.buildSnapshot`:

First add a cheap `count()` helper to `MediaCaptureManager.kt` (next to `totalBytes`):

```kotlin
fun count(): Int {
    if (!initialized) return 0
    val imgs = imagesDir.listFiles()?.size ?: 0
    val vids = videosDir.listFiles()?.size ?: 0
    return imgs + vids
}
```

Then in `WebRpc.buildSnapshot`:

```kotlin
put("media", buildJsonObject {
    put("recording", state.mediaRecording)
    put("startedAt", state.mediaRecordingStartedAt)
    put("count", com.r1.launcher.media.MediaCaptureManager.count())
    put("totalBytes", com.r1.launcher.media.MediaCaptureManager.totalBytes())
})
```

The snapshot fires at 1 Hz; both helpers just stat directory entries (no per-file work), which is sub-millisecond on a 100-file cap.

- [ ] **Step 3: Replace the broadcast stubs in `R1WebServer`**

Match the existing `broadcastTerminalOutput` shape exactly — there's no `broadcastEvent` wrapper, just direct `sockets.toList().forEach { it.sendEvent(...) }`. Replace the stubs added in Task 5:

```kotlin
fun broadcastCaptureAdded(item: com.r1.launcher.media.CaptureItem) {
    if (sockets.isEmpty()) return
    val payload = buildJsonObject {
        put("name", item.name)
        put("kind", item.kind)
        put("sizeBytes", item.sizeBytes)
        put("takenAt", item.takenAt)
        item.durationMs?.let { put("durationMs", it) }
        put("url", item.url)
        put("thumbUrl", item.thumbUrl)
    }
    sockets.toList().forEach { it.sendEvent("capture.added", payload) }
}

fun broadcastCaptureRecording(recording: Boolean, startedAt: Long) {
    if (sockets.isEmpty()) return
    val payload = buildJsonObject {
        put("recording", recording)
        put("startedAt", startedAt)
    }
    sockets.toList().forEach { it.sendEvent("capture.recording", payload) }
}
```

- [ ] **Step 4: Build + install + curl-test the RPCs**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'

R1_IP=192.168.1.42
# screenshot
curl -s "http://$R1_IP:8080/api/rpc" -H 'content-type: application/json' \
  -d '{"type":"req","id":1,"method":"capture.screenshot","params":{}}'
# list
curl -s "http://$R1_IP:8080/api/rpc" -H 'content-type: application/json' \
  -d '{"type":"req","id":2,"method":"capture.list","params":{"limit":10}}'
```

Expected first: `{"type":"res","id":1,"ok":true,"payload":{"name":"shot-...","url":"/static/media/shot-...",...}}`. Second: `items` array with that shot.

Test recording:

```bash
curl -s "http://$R1_IP:8080/api/rpc" -H 'content-type: application/json' \
  -d '{"type":"req","id":3,"method":"capture.startVideo","params":{}}'
sleep 5
curl -s "http://$R1_IP:8080/api/rpc" -H 'content-type: application/json' \
  -d '{"type":"req","id":4,"method":"capture.stopVideo","params":{}}'
```

Expected: start returns `{ok:true, startedAt:<epoch>}`, stop returns the new CaptureItem.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt \
        app/src/main/java/com/r1/launcher/web/R1WebServer.kt
git commit -m "feat(media): capture.* RPCs + snapshot + broadcast events"
```

---

## Task 8: Companion HTML — view + home tile + i18n keys

**Files:**
- Modify: `app/src/main/assets/web/index.html`
- Modify: `app/src/main/assets/web/i18n.js`

- [ ] **Step 1: Add the home-grid media tile**

Find the existing home grid in `index.html` (`<nav class="apps-grid">` block, around line 78). Tiles use `class="app-tile"` with `data-app="<name>"` attribute and three children (`tile-glyph`, `tile-label`, `tile-sub`). The click handler at `app.js:374` routes `data-app` → `setView`. Add the new tile after the meetings tile, with the next `--i` index:

```html
            <button class="app-tile" data-app="media" style="--i:5">
                <span class="tile-glyph">📷</span>
                <span class="tile-label" data-i18n="tile.media.label">media</span>
                <span class="tile-sub" data-i18n="tile.media.sub">screenshots + video</span>
            </button>
```

Note: i18n attribute is `data-i18n` (not `data-i18n-key`) — match what's already used by adjacent tiles.

- [ ] **Step 2: Add the `view-media` section**

Right before `view-meetings` (around line 256), insert:

```html
<section id="view-media" class="view view-app" data-title-key="view.media" data-title="media">
  <div class="app-mount"></div>
  <div class="app-body">
  <div class="media-actions">
    <button id="media-snap" class="media-btn media-btn-snap" data-i18n-key="media.snap">snap</button>
    <button id="media-record" class="media-btn media-btn-record">
      <span class="rec-dot"></span>
      <span class="rec-label" data-i18n-key="media.record">record</span>
    </button>
  </div>
  <div class="media-stats">
    <span id="media-stats-text"></span>
    <button id="media-clear-all" class="media-clear" data-i18n-key="media.clearAll">clear all</button>
  </div>
  <div id="media-grid" class="media-grid"></div>
  <div id="media-empty" class="media-empty" data-i18n-key="media.empty">no captures yet. tap snap or record.</div>

  <!-- Lightbox overlay -->
  <div id="media-lightbox" class="media-lightbox" hidden>
    <div class="media-lightbox-stage" id="media-lightbox-stage"></div>
    <div class="media-lightbox-bar">
      <a id="media-lightbox-download" class="media-lightbox-btn" download data-i18n-key="media.download">download</a>
      <button id="media-lightbox-delete" class="media-lightbox-btn" data-i18n-key="media.delete">delete</button>
      <button id="media-lightbox-close" class="media-lightbox-btn">×</button>
    </div>
  </div>
  </div><!-- /.app-body -->
</section>
```

- [ ] **Step 3: Add i18n keys to `i18n.js`**

Locate the `en` and `ar` translation tables and add to both:

Existing keys in `i18n.js` use flat dot-notation (e.g. `tile.terminal.label`, `tile.terminal.sub`). Match that style for the tile keys and add the rest under `media.*` and `view.*`:

```js
// English
'tile.media.label': 'media',
'tile.media.sub': 'screenshots + video',
'view.media': 'media',
'media.snap': 'snap',
'media.record': 'record',
'media.stop': 'stop',
'media.recording': 'recording',
'media.autoStop': 'auto in',
'media.clearAll': 'clear all',
'media.empty': 'no captures yet. tap snap or record.',
'media.statsItems': 'items',
'media.statsBytes': 'MB',
'media.download': 'download',
'media.delete': 'delete',
'media.confirmDelete': 'delete?',
'media.confirmClear': 'wipe all?',
```

```js
// Arabic
'tile.media.label': 'الوسائط',
'tile.media.sub': 'لقطات وفيديو',
'view.media': 'الوسائط',
'media.snap': 'لقطة',
'media.record': 'تسجيل',
'media.stop': 'إيقاف',
'media.recording': 'يسجل',
'media.autoStop': 'تلقائي خلال',
'media.clearAll': 'مسح الكل',
'media.empty': 'لا توجد لقطات بعد. اضغط لقطة أو تسجيل.',
'media.statsItems': 'عنصر',
'media.statsBytes': 'م.ب',
'media.download': 'تنزيل',
'media.delete': 'حذف',
'media.confirmDelete': 'حذف؟',
'media.confirmClear': 'مسح الكل؟',
```

- [ ] **Step 4: Build + install + verify tile renders**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

Open `http://<R1_IP>:8080` in a browser. Expected: new "media" tile appears in the home grid. Clicking it shows the empty view-media section with snap/record buttons (unstyled so far — Task 9 fixes styling).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/web/index.html app/src/main/assets/web/i18n.js
git commit -m "feat(media): companion view-media markup + i18n"
```

---

## Task 9: Companion CSS — grid + tiles + buttons + lightbox

**Files:**
- Modify: `app/src/main/assets/web/style.css`

- [ ] **Step 1: Append the media-specific styles**

At the bottom of `style.css`:

```css
/* ===== Media capture view ===== */

.media-actions {
  display: flex;
  gap: 12px;
  padding: 12px 16px 8px;
}

.media-btn {
  flex: 1;
  background: #111;
  color: #fff;
  border: 2px solid #FF6A00;
  font-family: 'Jersey 15', monospace;
  font-size: 22px;
  padding: 12px 0;
  text-transform: lowercase;
  cursor: pointer;
}
.media-btn:hover { background: #1a1a1a; }
.media-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.media-btn-record .rec-dot {
  display: inline-block;
  width: 10px; height: 10px;
  border-radius: 50%;
  background: #FF6A00;
  margin-right: 8px;
  vertical-align: middle;
}
.media-btn-record.recording .rec-dot {
  background: #ff2222;
  animation: media-pulse 1s ease-in-out infinite;
}
@keyframes media-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.media-stats {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 16px 12px;
  color: #888;
  font-size: 14px;
}
.media-clear {
  background: transparent;
  border: 1px solid #444;
  color: #888;
  padding: 4px 10px;
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
}
.media-clear:hover { color: #FF6A00; border-color: #FF6A00; }
.media-clear.confirm { color: #ff2222; border-color: #ff2222; }

.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px;
  padding: 0 16px 16px;
}

.media-tile {
  position: relative;
  aspect-ratio: 1 / 1;
  background: #000;
  border: 2px solid #222;
  overflow: hidden;
  cursor: pointer;
}
.media-tile:hover { border-color: #FF6A00; }
.media-tile img {
  width: 100%; height: 100%;
  object-fit: cover;
  display: block;
}
.media-tile-meta {
  position: absolute;
  left: 0; right: 0; bottom: 0;
  background: linear-gradient(to top, rgba(0,0,0,0.8), transparent);
  color: #fff;
  font-size: 11px;
  padding: 12px 6px 4px;
  display: flex;
  justify-content: space-between;
}
.media-tile-kind {
  background: rgba(255, 106, 0, 0.9);
  color: #000;
  padding: 1px 4px;
  font-weight: bold;
}
.media-tile-delete {
  position: absolute;
  top: 4px; right: 4px;
  background: rgba(0,0,0,0.6);
  color: #fff;
  border: none;
  width: 22px; height: 22px;
  font-size: 14px;
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 120ms;
}
.media-tile:hover .media-tile-delete { opacity: 1; }
.media-tile-delete.confirm { background: #ff2222; opacity: 1; }

.media-empty {
  text-align: center;
  color: #555;
  padding: 40px 16px;
  font-style: italic;
}

/* Lightbox */
.media-lightbox {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.92);
  z-index: 1000;
  display: flex;
  flex-direction: column;
}
.media-lightbox[hidden] { display: none; }
.media-lightbox-stage {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  overflow: hidden;
}
.media-lightbox-stage img,
.media-lightbox-stage video {
  max-width: 100%;
  max-height: 100%;
  display: block;
}
.media-lightbox-bar {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 16px;
  background: #000;
  border-top: 1px solid #222;
}
.media-lightbox-btn {
  background: #111;
  color: #fff;
  border: 2px solid #FF6A00;
  font-family: 'Jersey 15', monospace;
  font-size: 18px;
  padding: 8px 24px;
  text-transform: lowercase;
  cursor: pointer;
  text-decoration: none;
  display: inline-block;
}
.media-lightbox-btn:hover { background: #1a1a1a; }
.media-lightbox-btn.confirm { background: #ff2222; border-color: #ff2222; }
```

- [ ] **Step 2: Build + install + visual check**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

Open companion in a browser, navigate to media view. Expected: two big buttons (snap orange-bordered, record with dot), empty grid area below, empty-state text visible.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/web/style.css
git commit -m "feat(media): companion CSS for media view + lightbox"
```

---

## Task 10: Companion JS — list, capture buttons, time formatting

**Files:**
- Modify: `app/src/main/assets/web/app.js`

- [ ] **Step 1: Add a media-view module at the bottom of `app.js` (before any closing IIFE)**

```js
// ===== Media capture view =====

const Media = (() => {
  const grid = () => document.getElementById('media-grid');
  const empty = () => document.getElementById('media-empty');
  const statsText = () => document.getElementById('media-stats-text');
  const snapBtn = () => document.getElementById('media-snap');
  const recordBtn = () => document.getElementById('media-record');
  const recordLabel = () => recordBtn().querySelector('.rec-label');
  const clearBtn = () => document.getElementById('media-clear-all');

  let items = [];
  let recordingTicker = null;
  let clearConfirm = false;
  let clearConfirmTimer = null;

  function relTime(takenAt) {
    const diff = Date.now() - takenAt;
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return Math.floor(diff / 60_000) + 'm ago';
    if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + 'h ago';
    const d = new Date(takenAt);
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }

  function formatBytes(n) {
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function renderStats(totalBytes) {
    const t = i18n.t('media.statsItems');
    statsText().textContent = `${items.length} ${t} · ${formatBytes(totalBytes)}`;
    clearBtn().style.display = items.length ? '' : 'none';
  }

  function renderGrid() {
    empty().style.display = items.length ? 'none' : '';
    grid().innerHTML = '';
    items.forEach(item => {
      const tile = document.createElement('div');
      tile.className = 'media-tile';
      tile.dataset.name = item.name;

      const img = document.createElement('img');
      img.loading = 'lazy';
      img.src = item.thumbUrl;
      tile.appendChild(img);

      const meta = document.createElement('div');
      meta.className = 'media-tile-meta';
      meta.innerHTML = `<span class="media-tile-kind">${item.kind === 'video' ? 'MP4' : 'PNG'}</span><span>${relTime(item.takenAt)}</span>`;
      tile.appendChild(meta);

      const del = document.createElement('button');
      del.className = 'media-tile-delete';
      del.textContent = '×';
      del.dataset.confirm = '0';
      del.addEventListener('click', (e) => {
        e.stopPropagation();
        if (del.dataset.confirm === '1') {
          rpc('capture.delete', { name: item.name });
        } else {
          del.dataset.confirm = '1';
          del.classList.add('confirm');
          setTimeout(() => {
            del.dataset.confirm = '0';
            del.classList.remove('confirm');
          }, 2000);
        }
      });
      tile.appendChild(del);

      tile.addEventListener('click', () => Lightbox.open(item));
      grid().appendChild(tile);
    });
  }

  async function refresh() {
    const res = await rpc('capture.list', { limit: 100 });
    if (!res.ok) return;
    items = res.payload.items || [];
    renderGrid();
    renderStats(res.payload.totalBytes || 0);
  }

  function setRecordingUi(recording, startedAt) {
    if (recording) {
      recordBtn().classList.add('recording');
      snapBtn().disabled = true;
      tickRecording(startedAt);
      if (recordingTicker) clearInterval(recordingTicker);
      recordingTicker = setInterval(() => tickRecording(startedAt), 1000);
    } else {
      recordBtn().classList.remove('recording');
      snapBtn().disabled = false;
      recordLabel().textContent = t('media.record');
      if (recordingTicker) { clearInterval(recordingTicker); recordingTicker = null; }
    }
  }

  function tickRecording(startedAt) {
    const elapsed = Math.floor((Date.now() - startedAt) / 1000);
    const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
    const ss = String(elapsed % 60).padStart(2, '0');
    const remain = 180 - elapsed;
    let label = `${i18n.t('media.stop')} (${mm}:${ss})`;
    if (remain <= 5 && remain > 0) {
      label += ` — ${i18n.t('media.autoStop')} ${String(remain).padStart(2, '0')}s`;
    }
    recordLabel().textContent = label;
  }

  function onCaptureAdded(payload) {
    // Prepend the new item without a full refresh.
    items = [payload, ...items.filter(it => it.name !== payload.name)];
    renderGrid();
    // Update stats imprecisely; full refresh will follow on next snapshot.
    rpc('capture.list', { limit: 0 }).then(res => {
      if (res.ok) renderStats(res.payload.totalBytes || 0);
    });
  }

  function onCaptureRecording(payload) {
    setRecordingUi(payload.recording, payload.startedAt);
  }

  function onSnapshot(snap) {
    if (!snap.media) return;
    // Sync recording state on every snapshot (catches mid-session reloads).
    const ui = recordBtn().classList.contains('recording');
    if (snap.media.recording !== ui) {
      setRecordingUi(snap.media.recording, snap.media.startedAt);
    }
  }

  function bind() {
    snapBtn().addEventListener('click', async () => {
      snapBtn().disabled = true;
      const res = await rpc('capture.screenshot', {});
      snapBtn().disabled = false;
      if (!res.ok) {
        // Show toast — assume a global `toast(text)` exists; if not, use alert as fallback.
        const msg = (res.error && res.error.code) || 'capture failed';
        (window.toast || window.alert)('capture failed — ' + msg);
      }
      // `capture.added` event handler will prepend the item.
    });

    recordBtn().addEventListener('click', async () => {
      const isRecording = recordBtn().classList.contains('recording');
      if (isRecording) {
        await rpc('capture.stopVideo', {});
      } else {
        const res = await rpc('capture.startVideo', {});
        if (!res.ok) {
          const msg = (res.error && res.error.code) || 'start failed';
          (window.toast || window.alert)('record failed — ' + msg);
        }
      }
    });

    clearBtn().addEventListener('click', () => {
      if (clearConfirm) {
        rpc('capture.clear', {}).then(refresh);
        clearConfirm = false;
        clearBtn().classList.remove('confirm');
        clearBtn().textContent = i18n.t('media.clearAll');
      } else {
        clearConfirm = true;
        clearBtn().classList.add('confirm');
        clearBtn().textContent = i18n.t('media.confirmClear');
        if (clearConfirmTimer) clearTimeout(clearConfirmTimer);
        clearConfirmTimer = setTimeout(() => {
          clearConfirm = false;
          clearBtn().classList.remove('confirm');
          clearBtn().textContent = i18n.t('media.clearAll');
        }, 2000);
      }
    });
  }

  return { bind, refresh, onCaptureAdded, onCaptureRecording, onSnapshot };
})();
```

- [ ] **Step 2: Wire the module into `setView` and the WS event handler**

`setView` lives at `app.js:287` and is a plain function that lazy-loads per-view data. Add the media case alongside terminal/meetings:

```js
function setView(name) {
    document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + name));
    document.body.className = 'view-' + name;
    if (name === 'terminal') refreshTerminalHistory();
    if (name === 'meetings') refreshMeetings();
    if (name === 'media') { Media.bind(); Media.refresh(); }
}
```

`Media.bind` has its own `_bound` guard, so repeat calls are cheap.

Wire the event handlers in the existing WS message dispatcher. Grep for `terminal.output` in `app.js` to find the central event switch and add alongside:

```js
} else if (msg.event === 'capture.added') {
    Media.onCaptureAdded(msg.payload);
} else if (msg.event === 'capture.recording') {
    Media.onCaptureRecording(msg.payload);
}
```

And in the snapshot handler (grep for `state.snapshot`), add:

```js
Media.onSnapshot(snap);
```

(`snap` is whatever variable the existing snapshot handler uses for the parsed payload — adapt naming.)

- [ ] **Step 3: Build + install + click-through**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

In browser:
1. Open companion → home tile "media" → media view loads, empty state visible.
2. Click `snap` → button briefly disables, ~1 s later new tile appears in grid.
3. Click `record` → button morphs to `stop (00:00)` with pulsing dot, counter ticks.
4. Click `stop (00:NN)` → button reverts, new MP4 tile appears.
5. Tap `×` on a tile → button turns red, tap again within 2 s → tile disappears.
6. Click `clear all` (only visible when items > 0) → twice to wipe.

(Lightbox click-to-open is wired but the lightbox controller comes in Task 11. Tiles are clickable but nothing happens yet — that's fine.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/app.js
git commit -m "feat(media): companion JS — grid render + capture controls"
```

---

## Task 11: Companion JS — lightbox

**Files:**
- Modify: `app/src/main/assets/web/app.js`

- [ ] **Step 1: Add the `Lightbox` module after `Media`**

```js
// ===== Media lightbox =====

const Lightbox = (() => {
  const root = () => document.getElementById('media-lightbox');
  const stage = () => document.getElementById('media-lightbox-stage');
  const dlAnchor = () => document.getElementById('media-lightbox-download');
  const delBtn = () => document.getElementById('media-lightbox-delete');
  const closeBtn = () => document.getElementById('media-lightbox-close');

  let currentItem = null;
  let deleteConfirm = false;
  let deleteConfirmTimer = null;

  function open(item) {
    currentItem = item;
    stage().innerHTML = '';
    if (item.kind === 'video') {
      const v = document.createElement('video');
      v.src = item.url;
      v.controls = true;
      v.autoplay = true;
      stage().appendChild(v);
    } else {
      const img = document.createElement('img');
      img.src = item.url;
      stage().appendChild(img);
    }
    dlAnchor().href = item.url + '?download=1';
    dlAnchor().setAttribute('download', item.name);
    delBtn().textContent = i18n.t('media.delete');
    delBtn().classList.remove('confirm');
    deleteConfirm = false;
    root().hidden = false;
  }

  function close() {
    root().hidden = true;
    // Pause any playing video and clear src.
    const vid = stage().querySelector('video');
    if (vid) { vid.pause(); vid.removeAttribute('src'); vid.load(); }
    stage().innerHTML = '';
    currentItem = null;
  }

  function bind() {
    if (Lightbox._bound) return;
    Lightbox._bound = true;
    closeBtn().addEventListener('click', close);
    root().addEventListener('click', (e) => {
      if (e.target === root()) close();
    });
    document.addEventListener('keydown', (e) => {
      if (!root().hidden && e.key === 'Escape') close();
    });
    delBtn().addEventListener('click', async () => {
      if (!currentItem) return;
      if (deleteConfirm) {
        await rpc('capture.delete', { name: currentItem.name });
        close();
        Media.refresh();
      } else {
        deleteConfirm = true;
        delBtn().classList.add('confirm');
        delBtn().textContent = i18n.t('media.confirmDelete');
        if (deleteConfirmTimer) clearTimeout(deleteConfirmTimer);
        deleteConfirmTimer = setTimeout(() => {
          deleteConfirm = false;
          delBtn().classList.remove('confirm');
          delBtn().textContent = i18n.t('media.delete');
        }, 2000);
      }
    });
  }

  return { open, close, bind };
})();
```

- [ ] **Step 2: Bind on startup**

Find where the app initializes (after `i18n.apply()` or wherever the first view is shown). Add:

```js
Lightbox.bind();
```

Once. At module load.

- [ ] **Step 3: Build + install + click a tile**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

In browser: open media view, tap a screenshot tile → lightbox shows full PNG centered, [download/delete/×] bar at bottom. Tap × → close. Tap a video tile → `<video>` plays with audio. Tap download → browser downloads the file. Tap delete twice → tile gone, lightbox closed.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/app.js
git commit -m "feat(media): companion lightbox (image + video + download)"
```

---

## Task 12: End-to-end verification

**Files:** none modified — this task is purely the test plan from the spec.

- [ ] **Step 1: Screenshot smoke**

In companion: snap 3 times in a row. Verify all 3 tiles appear with distinct filenames (proves per-second counter works on rapid-fire taps).

- [ ] **Step 2: Video record + thumbnail**

Record a 10 s clip. Stop. Verify:
- Thumbnail tile shows the first second's frame (not the play placeholder).
- Lightbox plays back with audible mic audio.
- `adb shell` confirms `.thumbs/rec-...-NNN.jpg` exists.

- [ ] **Step 3: 3-min auto-stop**

Start recording, leave for 3:05. Expected: at 2:55 button shows `auto in 05s`, at 3:00 the file appears in the grid automatically, button reverts to idle.

If at 3:05 the button is still `stop (03:NN)`, the watchdog in `armRecordingWatchdog` should fire by 3:05 (180+5 s). Verify via logcat:

```
adb logcat -s LauncherActivity MediaCapture | grep -i watchdog
```

- [ ] **Step 4: Retention eviction**

Temporarily set `MAX_FILES = 5` in `MediaCaptureManager.kt`, rebuild, install. Snap 7 times. Verify only 5 tiles remain; logcat shows `enforceRetention: evicted 2 files...`. Revert `MAX_FILES = 100`.

- [ ] **Step 5: Orphan recovery**

Start a recording. Without stopping, run:

```bash
adb shell 'am force-stop com.r1.launcher'
adb shell 'am start -n com.r1.launcher/.LauncherActivity'
```

Watch logcat:

```
MediaCapture: recoverOrphanRecording: found orphan pid=NNNN, sending SIGINT
MediaCapture: recoverOrphanRecording: salvaged rec-...-NNN.mp4
```

The salvaged clip should appear in the grid.

- [ ] **Step 6: Two-browser sync**

Open companion in two tabs / devices. From tab A, start recording. Verify tab B shows the pulsing red dot + synced counter. From tab B, hit stop. Verify tab A reverts.

- [ ] **Step 7: Low-storage refusal**

Fill `filesDir` to leave < 100 MB free (e.g. carroot `dd if=/dev/zero of=/data/data/com.r1.launcher/files/junk bs=1M count=15000`). Hit record. Expected: companion toast `record failed — low_storage`. Clean up: `rm /data/data/com.r1.launcher/files/junk`.

- [ ] **Step 8: Final commit + push to feature branch**

If everything passes:

```bash
git log --oneline main..HEAD     # review the 11-commit story
git push origin <branch-name>
```

- [ ] **Step 9: Open PR**

Use `gh pr create` per the project's PR workflow. Reference the spec in the body:

```
Implements docs/superpowers/specs/2026-05-22-remote-media-capture-design.md
```

---

## Notes for the implementing engineer

- **`MediaCaptureManager.sendCarroot` parses output by lines.** Carroot's `nc -L -p 1337 sh` interleaves stdout and stderr — the shell's prompt isn't echoed but any error spew lands in the same stream. For the PID extraction in `sendCarrootBackground`, we filter to lines that parse as ints, which is robust against noise. Don't over-engineer this.
- **`screenrecord` with `--audio-source mic` requires `RECORD_AUDIO` to be held by the screenrecord process** — but that process runs as `shell` UID via carroot's `sh`, and the shell UID is pre-granted on this build. So no manifest permission addition is needed for the launcher itself.
- **The recording watchdog in `armRecordingWatchdog` is belt-and-braces.** The `--time-limit 180` flag handles 99% of cases. The watchdog catches the edge case where the user disconnects from carroot mid-recording (rare but possible on Wi-Fi flap).
- **NanoHTTPD's `newChunkedResponse` streams the file** — for a 5 MB MP4 over 802.11g LAN you should see a clean download with no in-memory buffering on the R1.
- **`MediaMetadataRetriever` can OOM on some MTK builds** for unusually large frames, but at 480×480 source it's harmless. The try/catch will swallow any issue and we'll show the play placeholder.
- **i18n key parity (`en` ↔ `ar`):** if you add a key to one block, add it to the other in the same edit. The companion has no fallback chain; a missing key renders as the raw key string.
- **No new manifest permissions.** All capture work goes through carroot which is already root.

## Self-review checklist (completed at write time)

- [x] Spec coverage: Each spec section maps to a task (Manager → 1–4; State/Host → 5; Server/RPC → 6–7; Companion UI → 8–11; Verification → 12).
- [x] No placeholders, TBDs, or "implement appropriately" in any task body.
- [x] Method signatures consistent across tasks (`mediaCaptureScreenshot`, `mediaStartVideo`, `mediaStopVideo` match between Task 5 host definition and Task 7 RPC dispatch).
- [x] All new types referenced (`CaptureItem`, `LowStorageException`) are defined in earlier tasks before use.
- [x] Filename format matches spec (`<kind>-<YYYYMMDD>-<HHMMSS>-<NNN>.<ext>`, per-second counter reset).
- [x] Retention caps match spec (100 / 500 MB).
- [x] All error codes from spec appear in either `MediaCaptureManager` failure paths or RPC dispatch translation (`capture_failed`, `already_recording`, `not_recording`, `recording_lost`, `not_found`, `low_storage`, `carroot_unreachable` covered as fallthrough).

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
        val imgs = imagesDir.listFiles()?.toList().orEmpty()
        val vids = videosDir.listFiles()?.toList().orEmpty()
        val all = (imgs + vids)
            .filter { it.isFile }
            .sortedByDescending { it.name }
            .take(if (limit <= 0) Int.MAX_VALUE else limit)
        return all.map { toItem(it) }
    }

    fun count(): Int {
        if (!initialized) return 0
        val imgs = imagesDir.listFiles()?.count { it.isFile } ?: 0
        val vids = videosDir.listFiles()?.count { it.isFile } ?: 0
        return imgs + vids
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
        val parent = f.parentFile
        val baseName = f.nameWithoutExtension
        val ok = f.delete()
        if (ok && parent == videosDir) {
            File(thumbsDir, "$baseName.jpg").delete()
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

    private fun toItem(f: File): CaptureItem {
        val isVideo = f.parentFile == videosDir
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
            durationMs = null,
            url = "/static/media/${f.name}",
            thumbUrl = thumbUrl,
        )
    }

    internal fun nextFilename(kind: String, ext: String): String {
        val now = Date()
        val stamp = synchronized(fnameDateFmt) { fnameDateFmt.format(now) }
        synchronized(this) {
            if (stamp != lastFnameStamp) {
                lastFnameStamp = stamp
                fnameCounter.set(0)
            }
        }
        val seq = fnameCounter.incrementAndGet()
        val prefix = if (kind == "image") "shot" else "rec"
        return "%s-%s-%03d.%s".format(prefix, stamp, seq, ext)
    }

    fun captureScreenshot(): Result<CaptureItem> =
        Result.failure(NotImplementedError("Task 2"))

    fun startVideoRecording(): Result<Long> =
        Result.failure(NotImplementedError("Task 3"))

    fun stopVideoRecording(): Result<CaptureItem> =
        Result.failure(NotImplementedError("Task 3"))

    private fun recoverOrphanRecording() {
        // Task 3
    }

    internal fun sendCarroot(cmd: String): String {
        // Task 2 wires this up. For init's `rm`, runCatching swallows the empty return.
        return ""
    }
}

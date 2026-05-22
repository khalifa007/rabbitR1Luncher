package com.r1.launcher.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    fun captureScreenshot(): Result<CaptureItem> {
        if (!initialized) return Result.failure(IllegalStateException("not_initialized"))

        val fname = nextFilename("image", "png")
        val tmpPath = "/data/local/tmp/r1cap-${System.nanoTime()}.png"
        val destFile = File(imagesDir, fname)

        var copied = runCapture(tmpPath, destFile)
        if (!copied) {
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
        val cpOut = sendCarroot(
            "cp $tmpPath ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK"
        )
        return cpOut.contains("OK") && destFile.exists() && destFile.length() > 1024
    }

    private fun enforceRetention() {
        val imgs = imagesDir.listFiles()?.toList().orEmpty()
        val vids = videosDir.listFiles()?.toList().orEmpty()
        val all = (imgs + vids)
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var totalBytes = all.sumOf { it.length() }
        val beforeBytes = totalBytes
        var evicted = 0

        while (all.isNotEmpty() && (all.size > MAX_FILES || totalBytes > MAX_BYTES)) {
            val victim = all.removeAt(0)
            val size = victim.length()
            val isVideo = victim.parentFile == videosDir
            val baseName = victim.nameWithoutExtension
            if (victim.delete()) {
                totalBytes -= size
                evicted++
                if (isVideo) {
                    File(thumbsDir, "$baseName.jpg").delete()
                }
            } else {
                break
            }
        }

        if (evicted > 0) {
            Log.i(TAG, "enforceRetention: evicted $evicted files (was ${beforeBytes}B, now ${totalBytes}B)")
        }
    }

    class LowStorageException(val freeBytes: Long) : RuntimeException("low_storage")

    fun startVideoRecording(): Result<Long> {
        if (!initialized) return Result.failure(IllegalStateException("not_initialized"))
        if (recordingPid > 0) return Result.failure(IllegalStateException("already_recording"))

        val freeBytes = rootDir.usableSpace
        if (freeBytes < LOW_STORAGE_FREE_BYTES) {
            return Result.failure(LowStorageException(freeBytes))
        }

        // screenrecord aborts with "INVALID_LAYER_STACK" when the display
        // surface is OFF. Force a wake before starting. KEYCODE_WAKEUP alone
        // is not enough on this build — display surface stays OFF; a touch
        // event reliably flips mState to ON. Sleep 200ms for SurfaceFlinger
        // to settle before screenrecord queries the display.
        sendCarroot("input keyevent KEYCODE_WAKEUP; input touchscreen tap 240 240")
        Thread.sleep(200)

        val tmpPath = "/data/local/tmp/r1cap-${System.nanoTime()}.mp4"
        val logPath = "/data/local/tmp/r1cap-${System.nanoTime()}.screenrecord.log"
        // nohup (not setsid): toybox setsid forks before exec, so `echo $!`
        // returns the PID of the dying setsid wrapper, not screenrecord —
        // then our kill -2 in stopVideoRecording is a no-op and the mp4
        // is cp'd while screenrecord is still writing it (no moov atom).
        // nohup exec's into the child without forking, so $! is correct,
        // AND it makes screenrecord ignore SIGHUP when our carroot shell
        // exits. Both are required.
        //
        // No --audio-source flag: CarrotOS ships screenrecord v1.3 which
        // predates the Android 12+ audio support. Video is silent. Audio
        // could be added via a parallel AudioRecord + MediaMuxer pass on
        // stop, but that's a follow-up.
        //
        // Note on static UIs: screenrecord encodes a new frame only when
        // SurfaceFlinger reports a buffer update. Recording the R1's clock
        // home screen produces a ~1-frame mp4 because nothing redraws.
        // Verified by recording the Settings activity (253 frames / 8s).
        val cmd = "nohup screenrecord --bit-rate $VIDEO_BIT_RATE " +
            "--time-limit $VIDEO_TIME_LIMIT_S $tmpPath > $logPath 2>&1"

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

    fun stopVideoRecording(): Result<CaptureItem> {
        if (!initialized) return Result.failure(IllegalStateException("not_initialized"))
        val pid = recordingPid
        val tmpPath = recordingTmpPath
        val startedAt = recordingStartedAt
        if (pid <= 0) return Result.failure(IllegalStateException("not_recording"))

        sendCarroot("kill -2 $pid")
        Thread.sleep(500)
        val stillAlive = sendCarroot("kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD").contains("ALIVE")
        if (stillAlive) {
            Thread.sleep(500)
            sendCarroot("kill -9 $pid")
        }

        val durationMs = System.currentTimeMillis() - startedAt
        val fname = nextFilename("video", "mp4")
        val destFile = File(videosDir, fname)
        val cpOut = sendCarroot(
            "cp $tmpPath ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK"
        )
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

        Log.i(TAG, "stopVideoRecording: $fname ${destFile.length()}B ${durationMs}ms")
        return Result.success(toItem(destFile).copy(durationMs = durationMs))
    }

    private fun generateThumb(mp4: File): File? {
        val out = File(thumbsDir, mp4.nameWithoutExtension + ".jpg")
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(mp4.absolutePath)
            val bmp = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            mmr.release()
            if (bmp == null) {
                Log.w(TAG, "generateThumb: null frame for ${mp4.name}")
                return null
            }
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

    private fun recoverOrphanRecording() {
        // pgrep -f is broken on this toybox; use ps + grep + awk.
        val out = sendCarroot("ps -ef | grep screenrecord | grep -v grep | awk '{print \$2}'")
        val pid = out.lines().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: return
        Log.w(TAG, "recoverOrphanRecording: found orphan pid=$pid, sending SIGINT")
        sendCarroot("kill -2 $pid")
        Thread.sleep(500)
        val tmp = sendCarroot("ls /data/local/tmp/r1cap-*.mp4 2>/dev/null | head -1").trim()
        if (tmp.isEmpty()) return
        val fname = nextFilename("video", "mp4")
        val destFile = File(videosDir, fname)
        val cpOut = sendCarroot(
            "cp $tmp ${destFile.absolutePath} && chmod 644 ${destFile.absolutePath} && echo OK"
        )
        sendCarroot("rm -f $tmp")
        if (cpOut.contains("OK") && destFile.length() > 1024) {
            generateThumb(destFile)
            Log.i(TAG, "recoverOrphanRecording: salvaged ${destFile.name}")
        } else {
            destFile.delete()
        }
    }

    // Socket I/O must run off the main thread (StrictMode throws
    // NetworkOnMainThreadException on Android 4+). The RPC dispatch invokes
    // host.mediaX from the UI thread, so we shunt every carroot call through
    // this single-thread executor and block the caller on Future.get. UI
    // freezes briefly (~screencap ~1s, stopVideo ~500ms) — acceptable for
    // the current button-disabled-during-capture UX. If we ever want a
    // jank-free async path the LauncherActivity overrides should hand the
    // whole Result<T> back via broadcasts instead.
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MediaCaptureIO").apply { isDaemon = true }
    }

    internal fun sendCarroot(cmd: String, timeoutMs: Int = 5000): String {
        val callable = Callable {
            try {
                Socket("127.0.0.1", 1337).use { sock ->
                    sock.soTimeout = timeoutMs
                    sock.getOutputStream().write((cmd + "\nexit\n").toByteArray())
                    sock.getOutputStream().flush()
                    sock.shutdownOutput()
                    BufferedReader(InputStreamReader(sock.getInputStream())).readText()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendCarroot failed: ${t.javaClass.simpleName}: ${t.message}")
                ""
            }
        }
        return try {
            ioExecutor.submit(callable).get(timeoutMs.toLong() + 1000, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "sendCarroot future timed out: ${t.javaClass.simpleName}")
            ""
        }
    }

    internal fun sendCarrootBackground(cmd: String): Int {
        val out = sendCarroot("$cmd & echo \$!")
        return out.lines().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: -1
    }
}

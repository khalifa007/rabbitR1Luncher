package com.r1.launcher.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.r1.launcher.LauncherActivity
import com.r1.launcher.R

/**
 * The launcher's first foreground service. Owns a `MediaRecorder` for the
 * duration of one meeting recording and exposes live state (elapsed ms, peak
 * amplitude, output path) to the bound activity via [LocalBinder].
 *
 * Why an FGS at all: without one, AudioRecord/MediaRecorder is silently
 * killed when the screen turns off on API 31+. A `microphone`-typed FGS is
 * the only way to keep the mic source held across a 30-min meeting where
 * the user might lock the device on the table.
 *
 * Lifecycle:
 *   START → MediaRecorder.prepare/start, startForeground()
 *   STOP  → recorder.stop/release, stopForeground(REMOVE), stopSelf()
 *
 * Note: [START_NOT_STICKY] is intentional — we never want the OS to restart
 * a recording with a null intent (it would have no output path). If the
 * service is killed mid-recording, the m4a on disk will be missing the
 * `moov` atom (MediaRecorder writes it on stop) and unplayable. The activity
 * marks such meetings as FAILED on next launch via the [LocalBinder.isRecording]
 * check.
 */
class TranscriberRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.r1.launcher.transcriber.START"
        const val ACTION_STOP = "com.r1.launcher.transcriber.STOP"
        const val EXTRA_OUT_PATH = "out_path"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "transcriber.recording"
        private const val NOTIF_ID = 0x52_45_43_31 // "REC1"

        fun startIntent(ctx: Context, outPath: String, title: String): Intent =
            Intent(ctx, TranscriberRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OUT_PATH, outPath)
                .putExtra(EXTRA_TITLE, title)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, TranscriberRecordingService::class.java).setAction(ACTION_STOP)
    }

    inner class LocalBinder : Binder() {
        val isRecording: Boolean get() = recorder != null
        val elapsedMs: Long get() =
            if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs
        /** MediaRecorder.getMaxAmplitude resets on every read; expect 0 for
         *  the first ~200ms of warm-up. Range [0, 32767]. */
        val peakLevel: Int get() = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        val activePath: String? get() = outPath
    }

    private val binder = LocalBinder()
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var outPath: String? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_OUT_PATH)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "meeting"
                if (path == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(path, title)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(path: String, title: String) {
        if (recorder != null) return
        ensureChannel()
        // Must call startForeground() within 10s of startForegroundService() or
        // ANR. Issue the notification BEFORE MediaRecorder.prepare() in case the
        // recorder takes a noticeable warm-up.
        startForeground(NOTIF_ID, buildNotification(title))

        outPath = path
        startedAtMs = SystemClock.elapsedRealtime()
        recorder = runCatching {
            @Suppress("DEPRECATION") // MediaRecorder() constructor was deprecated in API 31 in favor
            // of MediaRecorder(Context), but the older form still works on API 33 and avoids the
            // need to differentiate between minSdk 23 and the new constructor. Keep until minSdk≥31.
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)        // matches Scribe v2 sweet spot
                setAudioEncodingBitRate(64_000)     // ~28 MB / hour
                setAudioChannels(1)
                setOutputFile(path)
                prepare()
                start()
            }
        }.getOrElse {
            android.util.Log.e(TAG, "MediaRecorder.start failed for $path", it)
            null
        }

        if (recorder == null) {
            // Couldn't start the recorder — tear the FGS back down so we don't
            // hold the mic source under a stale notification.
            stopForeground(STOP_FOREGROUND_REMOVE)
            outPath = null
            startedAtMs = 0L
            stopSelf()
        }
    }

    private fun stopRecording() {
        val r = recorder
        recorder = null
        if (r != null) {
            // stop() finalizes the MP4 moov atom — file is unreadable until
            // this returns. Wrap to survive "stop called too early" IllegalState.
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        startedAtMs = 0L
        outPath = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Defensive: if the service is destroyed without going through the
        // STOP path (low-mem kill, force-stop), still try to finalize the file
        // so we don't leak the mic source.
        val r = recorder
        recorder = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        startedAtMs = 0L
        outPath = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Recording in progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a meeting is being recorded."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(title: String): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording meeting")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

private const val TAG = "TranscriberRec"

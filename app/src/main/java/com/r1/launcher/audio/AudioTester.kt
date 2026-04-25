package com.r1.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mic capture + immediate playback for diagnosing the R1's microphone path.
 *
 * Records 16 kHz mono PCM_16BIT into memory (capped at 10 s = 320 KB), reports
 * RMS + peak per audio chunk on the main looper so the panel can draw a live
 * meter, then plays the buffer back through AudioTrack so the user can hear
 * exactly what reached userspace. If playback is silent or muffled, the mic
 * path is the problem; if it's clear, the recognizer is.
 *
 * Playback uses AudioAttributes USAGE_MEDIA + MODE_STREAM (more reliable on
 * the R1 than MODE_STATIC + raw stream-type ctor) and force-bumps the music
 * stream to max during playback so a low system volume doesn't mute the test.
 */
class AudioTester(private val context: Context? = null) {

    private val tag = "AudioTester"

    enum class Source(val raw: Int, val label: String) {
        VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "voice_recognition"),
        MIC(MediaRecorder.AudioSource.MIC, "mic"),
        CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "camcorder"),
        DEFAULT(MediaRecorder.AudioSource.DEFAULT, "default"),
    }

    interface Callback {
        fun onLevel(rms: Int, peak: Int)
        fun onRecordingDone(durationMs: Int, samples: Int, peakOverall: Int)
        fun onPlaybackDone()
        fun onError(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sampleRate = 16_000
    private val maxSeconds = 10
    private val maxBytes = sampleRate * 2 * maxSeconds

    @Volatile private var record: AudioRecord? = null
    @Volatile private var feeder: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var player: MediaPlayer? = null
    private var recordedBytes: ByteArray? = null
    private var recordedWav: File? = null

    val isRecording: Boolean get() = feeder != null
    val isPlaying: Boolean get() = player != null
    val hasRecording: Boolean get() = recordedBytes != null

    fun startRecording(source: Source, cb: Callback) {
        if (feeder != null || player != null) return
        stopRequested = false
        Thread {
            try { runCapture(source, cb) } catch (t: Throwable) {
                main.post { cb.onError(t.message ?: t.javaClass.simpleName) }
                feeder = null
            }
        }.also { feeder = it }.start()
    }

    fun stopRecording() {
        stopRequested = true
    }

    fun playback(cb: Callback) {
        val buf = recordedBytes ?: return
        if (player != null || feeder != null) return
        try {
            startMediaPlayer(buf, cb)
        } catch (t: Throwable) {
            Log.e(tag, "playback failed", t)
            main.post { cb.onError(t.message ?: t.javaClass.simpleName) }
        }
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun close() {
        stopRequested = true
        stopPlayback()
        feeder?.let { runCatching { it.join(500) } }
        feeder = null
    }

    private fun runCapture(source: Source, cb: Callback) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = (minBuf * 2).coerceAtLeast(4096)
        val rec = AudioRecord(
            source.raw,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            main.post { cb.onError("audio init failed (source=${source.label})") }
            feeder = null
            return
        }
        record = rec
        val sink = java.io.ByteArrayOutputStream(maxBytes)
        var peakOverall = 0
        try {
            rec.startRecording()
            val buf = ByteArray(bufBytes)
            val started = System.currentTimeMillis()
            while (!stopRequested && sink.size() < maxBytes) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val cap = minOf(n, maxBytes - sink.size())
                sink.write(buf, 0, cap)
                val (rms, peak) = computeLevel(buf, cap)
                if (peak > peakOverall) peakOverall = peak
                main.post { cb.onLevel(rms, peak) }
            }
            val durMs = (System.currentTimeMillis() - started).toInt()
            val bytes = sink.toByteArray()
            recordedBytes = bytes
            val samples = bytes.size / 2
            
            // Stop hardware and clear feeder BEFORE posting to main thread
            // so that playback() doesn't prematurely abort.
            runCatching { rec.stop() }
            runCatching { rec.release() }
            record = null
            feeder = null
            
            main.post { cb.onRecordingDone(durMs, samples, peakOverall) }
        } finally {
            if (feeder != null) {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                record = null
                feeder = null
            }
        }
    }

    private fun startMediaPlayer(buf: ByteArray, cb: Callback) {
        if (buf.isEmpty() || context == null) {
            main.post { cb.onPlaybackDone() }
            return
        }

        // Bump music volume to max so a low system setting doesn't mute playback.
        // (Restored when the panel is exited via close()/stopPlayback path.)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVolume = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        if (am != null && maxVolume > 0) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0) }
        }

        val wav = writeWavFile(buf)
        recordedWav = wav
        Log.i(tag, "playback wav=${wav.absolutePath} size=${wav.length()}")

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(wav.absolutePath)
            setOnCompletionListener {
                Log.i(tag, "MediaPlayer completion")
                runCatching { release() }
                player = null
                main.post { cb.onPlaybackDone() }
            }
            setOnErrorListener { _, what, extra ->
                Log.e(tag, "MediaPlayer error what=$what extra=$extra")
                runCatching { release() }
                player = null
                main.post { cb.onError("playback error $what/$extra") }
                true
            }
            prepare()
        }
        player = mp
        mp.start()
    }

    private fun writeWavFile(pcm: ByteArray): File {
        val dir = File(context!!.cacheDir, "audio-test")
        dir.mkdirs()
        val out = File(dir, "rec.wav")
        FileOutputStream(out).use { fos ->
            fos.write(buildWavHeader(pcm.size, sampleRate, channels = 1, bitsPerSample = 16))
            fos.write(pcm)
        }
        return out
    }

    private fun buildWavHeader(
        pcmBytes: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = 36 + pcmBytes
        val h = ByteArray(44)
        // RIFF chunk
        "RIFF".toByteArray().copyInto(h, 0)
        writeIntLE(h, 4, totalSize)
        "WAVE".toByteArray().copyInto(h, 8)
        // fmt sub-chunk
        "fmt ".toByteArray().copyInto(h, 12)
        writeIntLE(h, 16, 16)            // sub-chunk size for PCM
        writeShortLE(h, 20, 1)           // audio format = PCM
        writeShortLE(h, 22, channels.toShort().toInt())
        writeIntLE(h, 24, sampleRate)
        writeIntLE(h, 28, byteRate)
        writeShortLE(h, 32, blockAlign.toShort().toInt())
        writeShortLE(h, 34, bitsPerSample.toShort().toInt())
        // data sub-chunk
        "data".toByteArray().copyInto(h, 36)
        writeIntLE(h, 40, pcmBytes)
        return h
    }

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off]     = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(b: ByteArray, off: Int, v: Int) {
        b[off]     = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun computeLevel(buf: ByteArray, n: Int): Pair<Int, Int> {
        var sumSq = 0.0
        var peak = 0
        var i = 0
        var samples = 0
        while (i < n - 1) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val v = (hi shl 8) or lo
            val abs = abs(v)
            if (abs > peak) peak = abs
            sumSq += v.toDouble() * v.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0 to 0
        val rms = sqrt(sumSq / samples).toInt()
        val rms100 = (rms * 100 / 32767).coerceIn(0, 100)
        val peak100 = (peak * 100 / 32767).coerceIn(0, 100)
        return rms100 to peak100
    }
}

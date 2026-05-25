package com.r1.launcher.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper

/**
 * Mic → live PCM frame stream. Same audio format as the legacy AudioCapture
 * (16 kHz mono PCM_16BIT, VOICE_RECOGNITION source) — but instead of buffering
 * the whole take into a final WAV, this fires `onPcm(chunk)` every read so the
 * caller can stream it (e.g. into ElevenLabs Realtime STT WebSocket).
 *
 * Hard cap of 60 s to avoid runaway sessions if the user holds the side button
 * forever.
 */
class StreamingAudioCapture {

    interface Callback {
        fun onLevel(levelPct: Int) {}
        /** Called on a background thread for every PCM chunk read from the mic. */
        fun onPcm(chunk: ByteArray) {}
        fun onError(msg: String) {}
        fun onStopped(durationMs: Int, peakPct: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val sampleRate = 16_000
    private val maxSeconds = 60

    @Volatile private var feeder: Thread? = null
    @Volatile private var stopRequested = false

    val isRecording: Boolean get() = feeder != null

    fun start(cb: Callback) {
        if (feeder != null) return
        stopRequested = false
        Thread {
            try { runCapture(cb) } catch (t: Throwable) {
                main.post { cb.onError(t.message ?: t.javaClass.simpleName) }
                feeder = null
            }
        }.also { feeder = it }.start()
    }

    fun stop() { stopRequested = true }

    fun close() {
        stopRequested = true
        feeder?.let { runCatching { it.join(500) } }
        feeder = null
    }

    private fun runCapture(cb: Callback) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = (minBuf * 2).coerceAtLeast(4096)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            main.post { cb.onError("audio init failed") }
            feeder = null
            return
        }
        // Hardware echo cancellation + noise suppression + AGC, when the SoC
        // supports them. Critical for barge-in (mic picks up the TTS through
        // the speaker otherwise, and we'd transcribe the AI's own words).
        // MT6765 reports support but quality varies; failures here are silent
        // so the mic still works without effects.
        val sessionId = rec.audioSessionId
        val aec = if (AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
        val ns = if (NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
        val agc = if (AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
        val maxBytes = sampleRate * 2 * maxSeconds
        var totalBytes = 0
        var peak = 0
        try {
            rec.startRecording()
            val buf = ByteArray(bufBytes)
            val started = System.currentTimeMillis()
            var lastLevelAt = 0L
            while (!stopRequested && totalBytes < maxBytes) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Copy because the inner buffer is reused on next read; we
                // emit to the callback which may be processing async.
                val chunk = ByteArray(n)
                System.arraycopy(buf, 0, chunk, 0, n)
                cb.onPcm(chunk)
                totalBytes += n
                val p = chunkPeak(buf, n)
                if (p > peak) peak = p
                val now = System.currentTimeMillis()
                if (now - lastLevelAt >= 80L) {
                    lastLevelAt = now
                    val levelPct = (p * 100 / 32767).coerceIn(0, 100)
                    main.post { cb.onLevel(levelPct) }
                }
            }
            val durMs = (System.currentTimeMillis() - started).toInt()
            val peakPct = (peak * 100 / 32767).coerceIn(0, 100)
            main.post { cb.onStopped(durMs, peakPct) }
        } finally {
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            runCatching { rec.stop() }
            runCatching { rec.release() }
            feeder = null
        }
    }

    private fun chunkPeak(buf: ByteArray, n: Int): Int {
        var p = 0
        var i = 0
        while (i < n - 1) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val v = (hi shl 8) or lo
            val a = if (v < 0) -v else v
            if (a > p) p = a
            i += 2
        }
        return p
    }
}

package com.r1.launcher.openclaw

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Mic → WAV capture for the openclaw chat panel. Records 16 kHz mono PCM_16BIT
 * into memory (capped at 30 s ≈ 960 KB), wraps it in a 44-byte WAV header on
 * stop, returns the bytes via callback. The gateway transcribes server-side
 * (Whisper or equivalent), so there's no on-device STT here at all.
 *
 * Replaces the previous Vosk-based VoiceRecognizer — accuracy on the R1's mic
 * was poor enough that server-side transcription is the better path.
 */
class AudioCapture {

    interface Callback {
        fun onDone(wavBytes: ByteArray, durationMs: Int, peakPct: Int)
        fun onError(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sampleRate = 16_000
    private val maxSeconds = 30
    private val maxBytes = sampleRate * 2 * maxSeconds

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

    fun stop() {
        stopRequested = true
    }

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
        val sink = java.io.ByteArrayOutputStream(maxBytes)
        var peak = 0
        try {
            rec.startRecording()
            val buf = ByteArray(bufBytes)
            val started = System.currentTimeMillis()
            while (!stopRequested && sink.size() < maxBytes) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val cap = minOf(n, maxBytes - sink.size())
                sink.write(buf, 0, cap)
                val p = chunkPeak(buf, cap)
                if (p > peak) peak = p
            }
            val durMs = (System.currentTimeMillis() - started).toInt()
            val pcm = sink.toByteArray()
            val wav = ByteArray(44 + pcm.size)
            buildWavHeader(pcm.size).copyInto(wav, 0)
            pcm.copyInto(wav, 44)
            val peakPct = (peak * 100 / 32767).coerceIn(0, 100)
            main.post { cb.onDone(wav, durMs, peakPct) }
        } finally {
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

    private fun buildWavHeader(pcmBytes: Int): ByteArray {
        val byteRate = sampleRate * 2
        val totalSize = 36 + pcmBytes
        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0)
        writeIntLE(h, 4, totalSize)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        writeIntLE(h, 16, 16)
        writeShortLE(h, 20, 1)
        writeShortLE(h, 22, 1)
        writeIntLE(h, 24, sampleRate)
        writeIntLE(h, 28, byteRate)
        writeShortLE(h, 32, 2)
        writeShortLE(h, 34, 16)
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
}

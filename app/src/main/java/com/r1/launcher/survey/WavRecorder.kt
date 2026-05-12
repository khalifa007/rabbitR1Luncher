package com.r1.launcher.survey

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Two-channel WAV writer that interleaves G.711 µ-law samples from the
 * customer's side (left channel) and the bot's side (right channel) into one
 * file the post-call email attaches.
 *
 * WAV format:
 *   RIFF header
 *   "WAVE"
 *   "fmt " sub-chunk (16-byte WAVEFORMATEX with audio format=7 µ-law)
 *   "data" sub-chunk (raw interleaved µ-law bytes)
 *
 * We update the RIFF length + data length on [stop] so the file is playable
 * even if the activity is killed mid-call (truncate-to-zero on next crash).
 *
 * Channel rules:
 *   - [appendDownlink] (from customer, RTP receive) → left channel
 *   - [appendUplink]   (from bot, RTP send)         → right channel
 *   - Whenever one side adds a frame, we interleave with the latest pending
 *     buffer on the other side, falling back to µ-law silence (0xFF).
 */
class WavRecorder(private val file: File) {

    private val out = RandomAccessFile(file, "rw")
    private val lock = Any()
    @Volatile private var bytesWritten: Long = 0L  // data chunk bytes (interleaved)
    @Volatile private var started = false

    /** Per-channel pending buffer — interleave is byte-by-byte. */
    private val pendingDownlink = ArrayDeque<Byte>()
    private val pendingUplink = ArrayDeque<Byte>()

    private val SILENCE = 0xFF.toByte()    // µ-law silence

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            // Write 44-byte WAV header skeleton; we patch lengths in [stop].
            out.setLength(0)
            out.write(buildHeader(channels = 2, sampleRate = 8000, dataBytes = 0))
        }
    }

    fun appendDownlink(payload: ByteArray) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            if (!started) return
            for (b in payload) pendingDownlink.addLast(b)
            drainLocked()
        }
    }

    fun appendUplink(payload: ByteArray) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            if (!started) return
            for (b in payload) pendingUplink.addLast(b)
            drainLocked()
        }
    }

    private fun drainLocked() {
        // Emit as many pairs as we have on at least one side.
        while (pendingDownlink.isNotEmpty() || pendingUplink.isNotEmpty()) {
            val d = if (pendingDownlink.isNotEmpty()) pendingDownlink.removeFirst() else SILENCE
            val u = if (pendingUplink.isNotEmpty()) pendingUplink.removeFirst() else SILENCE
            // WAV is little-endian, samples ordered left-then-right per frame.
            out.write(d.toInt())
            out.write(u.toInt())
            bytesWritten += 2
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            // Pad out any remaining buffer with silence on the missing side so
            // the file's right & left lengths stay aligned.
            while (pendingDownlink.isNotEmpty() || pendingUplink.isNotEmpty()) {
                val d = if (pendingDownlink.isNotEmpty()) pendingDownlink.removeFirst() else SILENCE
                val u = if (pendingUplink.isNotEmpty()) pendingUplink.removeFirst() else SILENCE
                out.write(d.toInt()); out.write(u.toInt())
                bytesWritten += 2
            }
            // Patch the RIFF + data lengths now that we know the final size.
            try {
                val dataLen = bytesWritten.toInt()
                out.seek(4)
                out.write(le32(36 + dataLen))
                out.seek(40)
                out.write(le32(dataLen))
            } catch (t: Throwable) {
                Log.w(TAG, "header patch failed: ${t.message}")
            }
            try { out.close() } catch (_: Throwable) {}
            started = false
        }
    }

    private fun buildHeader(channels: Int, sampleRate: Int, dataBytes: Int): ByteArray {
        // WAVE_FORMAT_MULAW = 0x0007. Block align = channels * 1 byte/sample.
        // 16-byte fmt chunk per RFC at https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html
        val byteRate = sampleRate * channels // µ-law is 1 byte/sample
        val out = ByteArray(44)
        var i = 0
        fun w(b: Byte) { out[i++] = b }
        fun ws(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { w(it) }
        fun w16(v: Int) { w((v and 0xFF).toByte()); w(((v ushr 8) and 0xFF).toByte()) }
        fun w32(v: Int) {
            w((v and 0xFF).toByte()); w(((v ushr 8) and 0xFF).toByte())
            w(((v ushr 16) and 0xFF).toByte()); w(((v ushr 24) and 0xFF).toByte())
        }
        ws("RIFF"); w32(36 + dataBytes); ws("WAVE")
        ws("fmt "); w32(16); w16(7); w16(channels)
        w32(sampleRate); w32(byteRate); w16(channels); w16(8)
        ws("data"); w32(dataBytes)
        return out
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    companion object {
        private const val TAG = "SurveyWav"
    }
}

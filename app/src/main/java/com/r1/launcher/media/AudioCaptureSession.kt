package com.r1.launcher.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Parallel mic + REMOTE_SUBMIX capture, mixed in software to a single mono
 * AAC track muxed into an audio-only MP4. Pairs with the screenrecord MP4
 * and is muxed together by [AvMuxer] when the recording stops.
 *
 * Mic uses [MediaRecorder.AudioSource.MIC] (no DSP) — VOICE_RECOGNITION would
 * apply AEC/NR that hurts ambient/music capture.
 *
 * Submix uses [MediaRecorder.AudioSource.REMOTE_SUBMIX], gated by the
 * signature-level CAPTURE_AUDIO_OUTPUT perm (auto-granted on this
 * platform-signed app). If submix init fails (perm missing, route blocked) the
 * session degrades to mic-only — the recording still completes with the user's
 * voice, just no system sounds.
 */
/**
 * @param micEnabled if false, the mic AudioRecord is never opened. The output
 *        still contains AAC frames as long as [playbackEnabled] is true.
 * @param playbackEnabled if false, the playback-capture leg is skipped. If
 *        both flags are false the session must not be constructed at all —
 *        callers (the manager) should produce a silent video instead.
 * @param playbackProjection when non-null AND [playbackEnabled], the playback
 *        leg uses [AudioPlaybackCaptureConfiguration] over the projection —
 *        which captures media-stream audio in parallel without dimming the
 *        device speakers. When null AND [playbackEnabled], the legacy
 *        REMOTE_SUBMIX path is used as a fallback (older Android, projection
 *        acquire failure). REMOTE_SUBMIX dims speakers on this MTK build.
 */
class AudioCaptureSession(
    private val outFile: File,
    private val micEnabled: Boolean = true,
    private val playbackEnabled: Boolean = true,
    private val playbackProjection: MediaProjection? = null,
) {
    companion object {
        private const val TAG = "AudioCaptureSession"
        const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 128_000
        private const val FRAMES_PER_READ = 1024 // ~23ms at 44.1kHz
        private const val BYTES_PER_FRAME = 2 // PCM 16-bit mono
    }

    private val running = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var mic: AudioRecord? = null
    private var submix: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioTrackIdx = -1
    private var muxerStarted = false
    private var worker: Thread? = null
    private var presentationTimeUs: Long = 0

    @Volatile var submixActive: Boolean = false
        private set
    /** True when the playback leg used [AudioPlaybackCaptureConfiguration]
     *  (projection-backed, no speaker dimming). False when the leg fell back
     *  to REMOTE_SUBMIX or wasn't started at all. Logged at startup so we
     *  can tell from logcat which path actually engaged. */
    @Volatile var playbackUsedProjection: Boolean = false
        private set

    fun start(): Boolean {
        if (!micEnabled && !playbackEnabled) {
            Log.w(TAG, "start: both mic and playback disabled — refusing")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "start: getMinBufferSize=$minBuf")
            return false
        }
        val bufBytes = maxOf(minBuf, FRAMES_PER_READ * BYTES_PER_FRAME * 4)

        val micRec: AudioRecord? = if (micEnabled) {
            val rec = try {
                @Suppress("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "start: mic ctor: ${t.message}")
                null
            }
            if (rec != null && rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "start: mic state=${rec.state}")
                rec.release()
                null
            } else rec
        } else null

        val submixRec: AudioRecord? = if (playbackEnabled) {
            // Prefer AudioPlaybackCaptureConfiguration over REMOTE_SUBMIX —
            // the modern API captures playback in parallel without dimming
            // device speakers. Falls back to REMOTE_SUBMIX if the projection
            // isn't available (acquire failed, pre-Android-10) or if the
            // playback-capture build fails (HAL doesn't support it).
            val projRec = if (playbackProjection != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                buildPlaybackCaptureRecord(playbackProjection, bufBytes)
            } else null
            if (projRec != null) {
                playbackUsedProjection = true
                projRec
            } else {
                buildRemoteSubmixRecord(bufBytes)
            }
        } else null

        // At least one source must be live. If the user wanted mic only and it
        // failed, we don't silently fall back to playback (or vice versa) —
        // surfacing the failure to the caller is more honest.
        if (micRec == null && submixRec == null) {
            Log.w(TAG, "start: no audio source available (mic=$micEnabled playback=$playbackEnabled)")
            return false
        }
        submixActive = submixRec != null

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufBytes * 2)
        }
        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start: encoder: ${t.message}")
            micRec?.release()
            submixRec?.release()
            return false
        }

        val mux = try {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            Log.w(TAG, "start: muxer: ${t.message}")
            runCatching { enc.stop() }; enc.release()
            micRec?.release()
            submixRec?.release()
            return false
        }

        mic = micRec
        submix = submixRec
        encoder = enc
        muxer = mux
        running.set(true)

        try {
            micRec?.startRecording()
            submixRec?.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "start: startRecording: ${t.message}")
            cleanup()
            return false
        }

        worker = Thread({ runLoop() }, "AudioCaptureSession").apply {
            isDaemon = true
            start()
        }

        Log.i(
            TAG,
            "start: out=${outFile.name} mic=${micRec != null} " +
                "playback=$submixActive viaProjection=$playbackUsedProjection",
        )
        return true
    }

    private fun buildPlaybackCaptureRecord(
        projection: MediaProjection,
        bufBytes: Int,
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                // Cover the usages our launcher actually emits + common media-app
                // usages. addMatchingUsage acts as an OR-filter; unspecified
                // usages are silently dropped, which is fine for our case
                // (we don't want to capture e.g. ringtones or alarms).
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "buildPlaybackCaptureRecord: state=${rec.state}")
                rec.release()
                null
            } else rec
        } catch (t: Throwable) {
            Log.w(TAG, "buildPlaybackCaptureRecord failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun buildRemoteSubmixRecord(bufBytes: Int): AudioRecord? {
        return try {
            @Suppress("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "buildRemoteSubmixRecord: state=${rec.state}")
                rec.release()
                null
            } else rec
        } catch (t: Throwable) {
            Log.w(TAG, "buildRemoteSubmixRecord failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Blocks up to ~7s for the worker to flush + finalize the MP4. If the
     *  initial 5 s join times out (most commonly because [AudioRecord.read]
     *  is blocked waiting for more samples), externally stop the records to
     *  unblock the loop, then re-join briefly so cleanup() can run inside
     *  the worker's finally. Without this, a stuck worker would leak the
     *  mic + encoder + muxer until the process exits. */
    fun stop(): Boolean {
        if (!running.get()) return finished.get()
        running.set(false)
        runCatching { worker?.join(5000) }
        if (worker?.isAlive == true) {
            // AudioRecord.stop is documented as thread-safe; calling it from
            // outside the worker forces in-flight read() calls to return.
            runCatching { mic?.stop() }
            runCatching { submix?.stop() }
            runCatching { worker?.join(2000) }
        }
        return finished.get()
    }

    private fun runLoop() {
        val micRec = mic
        val submixRec = submix
        val enc = encoder!!
        val mux = muxer!!
        // Clock source: prefer mic for tighter latency; fall back to submix
        // when mic is off. The other source (if present) is mixed in as a
        // best-effort secondary.
        val primary = micRec ?: submixRec!!
        val secondary = if (primary === micRec) submixRec else null

        val primaryBuf = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val secondaryBuf = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val mixBuf = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val bufInfo = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                val pRead = primary.read(primaryBuf, 0, primaryBuf.size)
                if (pRead < 0) {
                    Log.w(TAG, "primary read=$pRead, stopping")
                    break
                }
                if (pRead == 0) continue
                val outBuf: ByteArray
                if (secondary != null) {
                    val sRead = secondary.read(secondaryBuf, 0, secondaryBuf.size)
                    mixSamples(primaryBuf, pRead, secondaryBuf, if (sRead > 0) sRead else 0, mixBuf)
                    outBuf = mixBuf
                } else {
                    outBuf = primaryBuf
                }

                feedEncoder(enc, outBuf, pRead, eos = false)
                drainEncoder(enc, mux, bufInfo, untilEos = false)
            }

            feedEncoder(enc, mixBuf, 0, eos = true)
            drainEncoder(enc, mux, bufInfo, untilEos = true)
            finished.set(muxerStarted)
        } catch (t: Throwable) {
            Log.w(TAG, "runLoop: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            cleanup()
        }
    }

    /** PCM 16-bit little-endian saturating sum. `aLen` drives output length;
     *  bytes beyond `bLen` are treated as silence. */
    private fun mixSamples(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int, out: ByteArray) {
        var i = 0
        while (i < aLen) {
            val sA = ((a[i].toInt() and 0xFF) or (a[i + 1].toInt() shl 8)).toShort().toInt()
            val sB = if (i + 1 < bLen)
                ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            else 0
            var sum = sA + sB
            if (sum > Short.MAX_VALUE.toInt()) sum = Short.MAX_VALUE.toInt()
            else if (sum < Short.MIN_VALUE.toInt()) sum = Short.MIN_VALUE.toInt()
            out[i] = (sum and 0xFF).toByte()
            out[i + 1] = ((sum shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun feedEncoder(enc: MediaCodec, data: ByteArray, len: Int, eos: Boolean) {
        val idx = enc.dequeueInputBuffer(10_000)
        if (idx < 0) return
        val inBuf = enc.getInputBuffer(idx) ?: return
        inBuf.clear()
        if (len > 0) inBuf.put(data, 0, len)
        val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        enc.queueInputBuffer(idx, 0, len, presentationTimeUs, flags)
        if (len > 0) {
            val frames = len / BYTES_PER_FRAME
            presentationTimeUs += frames * 1_000_000L / SAMPLE_RATE
        }
    }

    private fun drainEncoder(
        enc: MediaCodec,
        mux: MediaMuxer,
        bufInfo: MediaCodec.BufferInfo,
        untilEos: Boolean,
    ) {
        // EOS drain is bounded so a misbehaving encoder (never emits the EOS
        // marker) can't pin a CPU core until the process dies. 200 iterations
        // × 10 ms timeout ≈ 2 s ceiling, well past any real-world flush time.
        val drainDeadlineIter = if (untilEos) 200 else Int.MAX_VALUE
        var iters = 0
        while (true) {
            if (++iters > drainDeadlineIter) {
                Log.w(TAG, "drainEncoder: EOS drain timeout after $iters iters")
                return
            }
            val outIdx = enc.dequeueOutputBuffer(bufInfo, 10_000)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (untilEos) continue else return
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    audioTrackIdx = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
            } else if (outIdx >= 0) {
                val out = enc.getOutputBuffer(outIdx)
                val isCodecConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (out != null && bufInfo.size > 0 && muxerStarted && !isCodecConfig) {
                    out.position(bufInfo.offset)
                    out.limit(bufInfo.offset + bufInfo.size)
                    mux.writeSampleData(audioTrackIdx, out, bufInfo)
                }
                enc.releaseOutputBuffer(outIdx, false)
                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    private fun cleanup() {
        runCatching { mic?.stop() }
        runCatching { mic?.release() }
        runCatching { submix?.stop() }
        runCatching { submix?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        mic = null; submix = null; encoder = null; muxer = null
    }
}

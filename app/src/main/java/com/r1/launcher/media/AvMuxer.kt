package com.r1.launcher.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Combine the silent screenrecord MP4 with the [AudioCaptureSession] audio
 * MP4 into a single MP4 holding both tracks. Pure remux — no transcoding —
 * since both inputs already carry MP4-compatible samples (H.264 + AAC).
 */
object AvMuxer {
    private const val TAG = "AvMuxer"
    private const val BUFFER_SIZE = 1 shl 20 // 1 MiB

    /**
     * @return true if the muxed file is valid and contains the video track.
     *         Audio is best-effort: if [audioMp4] is missing or unreadable,
     *         the muxed file is still produced with video only and the
     *         function returns true.
     */
    fun mux(videoMp4: File, audioMp4: File, outMp4: File): Boolean {
        if (!videoMp4.exists() || videoMp4.length() < 1024) {
            Log.w(TAG, "mux: video missing or tiny (${videoMp4.length()}B)")
            return false
        }
        var vExt: MediaExtractor? = null
        var aExt: MediaExtractor? = null
        var mux: MediaMuxer? = null
        var muxStarted = false
        try {
            vExt = MediaExtractor().apply { setDataSource(videoMp4.absolutePath) }
            val vTrack = selectTrack(vExt, "video/")
            if (vTrack < 0) {
                Log.w(TAG, "mux: no video track in ${videoMp4.name}")
                return false
            }
            val vFormat = vExt.getTrackFormat(vTrack)
            vExt.selectTrack(vTrack)

            var aFormat: MediaFormat? = null
            if (audioMp4.exists() && audioMp4.length() > 1024) {
                val tmp = MediaExtractor().apply { setDataSource(audioMp4.absolutePath) }
                val aTrack = selectTrack(tmp, "audio/")
                if (aTrack >= 0) {
                    aFormat = tmp.getTrackFormat(aTrack)
                    tmp.selectTrack(aTrack)
                    aExt = tmp
                } else {
                    Log.w(TAG, "mux: no audio track in ${audioMp4.name}")
                    tmp.release()
                }
            } else {
                Log.w(TAG, "mux: audio file missing or tiny (${audioMp4.length()}B)")
            }

            mux = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVTrack = mux.addTrack(vFormat)
            val outATrack = aFormat?.let { mux.addTrack(it) } ?: -1
            mux.start()
            muxStarted = true

            val buf = ByteBuffer.allocate(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            copyTrack(vExt, mux, outVTrack, buf, info)
            if (aExt != null && outATrack >= 0) {
                copyTrack(aExt, mux, outATrack, buf, info)
            }
            return outMp4.length() > 1024
        } catch (t: Throwable) {
            Log.w(TAG, "mux: ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            runCatching { vExt?.release() }
            runCatching { aExt?.release() }
            if (mux != null) {
                if (muxStarted) runCatching { mux.stop() }
                runCatching { mux.release() }
            }
        }
    }

    private fun selectTrack(ext: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun copyTrack(
        ext: MediaExtractor,
        mux: MediaMuxer,
        trackIdx: Int,
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        while (true) {
            buf.clear()
            val size = ext.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ext.sampleTime
            // MediaExtractor.SAMPLE_FLAG_SYNC maps to MediaCodec.BUFFER_FLAG_KEY_FRAME
            // (both = 1); ignore other extractor flags (partial-frame, encrypted)
            // since we're just copying H.264/AAC samples that don't carry them.
            info.flags = if ((ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(trackIdx, buf, info)
            ext.advance()
        }
    }
}

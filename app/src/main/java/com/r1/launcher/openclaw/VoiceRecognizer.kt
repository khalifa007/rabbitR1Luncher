package com.r1.launcher.openclaw

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File

/**
 * On-device speech-to-text using Vosk.
 *
 * Lifecycle:
 *   - Construct once. First start() unpacks the model from assets/model-en-us
 *     into filesDir/vosk-model on a background thread (~3s on R1, blocking).
 *   - start(onPartial, onFinal) opens an AudioRecord at 16 kHz mono PCM, spawns
 *     a feeder thread that pumps frames into a Recognizer. Partials emit on the
 *     main looper as the user speaks; the final transcript fires on stop().
 *   - stop() halts capture, flushes the recognizer, fires onFinal with the
 *     concatenation of all partial+final segments. Idempotent.
 *   - close() releases the model (only call from onDestroy).
 */
class VoiceRecognizer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var feeder: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var finalCallbackInvoked = false

    val isRecording: Boolean get() = feeder != null

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (feeder != null) return
        finalCallbackInvoked = false
        stopRequested = false
        Thread {
            try {
                val m = ensureModel()
                runRecording(m, onPartial, onFinal, onError)
            } catch (t: Throwable) {
                main.post { onError(t.message ?: t.javaClass.simpleName) }
            }
        }.also { feeder = it }.start()
    }

    fun stop() {
        stopRequested = true
        // Don't interrupt the feeder — it polls stopRequested between reads
        // and exits cleanly so the final-result JSON makes it back to the UI.
    }

    fun close() {
        stopRequested = true
        feeder?.let { runCatching { it.join(500) } }
        feeder = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun ensureModel(): Model {
        model?.let { return it }
        val target = File(context.filesDir, "vosk-model")
        val flag = File(target, ".unpacked-v2-lgraph")
        if (!flag.exists()) {
            // Synchronous unpack — copies the asset tree into filesDir. Vosk's
            // StorageService.unpack only exposes an async LiveData/Future API,
            // so we walk assets ourselves.
            target.deleteRecursively()
            target.mkdirs()
            copyAssetDir("model-en-us", target)
            flag.writeText("ok")
        }
        // Vosk's Model resolves the model dir; it expects 'am/', 'conf/', etc. directly inside.
        val m = Model(target.absolutePath)
        model = m
        return m
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // It's a file — copy it.
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(dest, child))
        }
    }

    private fun runRecording(
        m: Model,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = (minBuf * 2).coerceAtLeast(4096)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (t: Throwable) {
            invokeFinal(onError, t.message ?: "audio init failed")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            invokeFinal(onError, "audio init failed")
            return
        }
        record = rec

        val r = Recognizer(m, sampleRate.toFloat())
        recognizer = r

        val collected = StringBuilder()

        try {
            rec.startRecording()
            val buf = ByteArray(bufBytes)
            while (!stopRequested) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val finalSegment = r.acceptWaveForm(buf, n)
                if (finalSegment) {
                    val seg = parseText(r.result)
                    if (seg.isNotEmpty()) {
                        collected.appendSpace(seg)
                        val snapshot = collected.toString()
                        main.post { onPartial(snapshot) }
                    }
                } else {
                    val partial = parsePartial(r.partialResult)
                    if (partial.isNotEmpty()) {
                        val snapshot = if (collected.isEmpty()) partial
                            else "${collected.trim()} $partial"
                        main.post { onPartial(snapshot) }
                    }
                }
            }
            // Flush
            val tail = parseText(r.finalResult)
            if (tail.isNotEmpty()) collected.appendSpace(tail)
            val finalText = collected.toString().trim()
            main.post {
                if (!finalCallbackInvoked) {
                    finalCallbackInvoked = true
                    onFinal(finalText)
                }
            }
        } catch (t: Throwable) {
            invokeFinal(onError, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            record = null
            runCatching { r.close() }
            recognizer = null
            feeder = null
        }
    }

    private fun invokeFinal(cb: (String) -> Unit, msg: String) {
        main.post {
            if (!finalCallbackInvoked) {
                finalCallbackInvoked = true
                cb(msg)
            }
        }
    }

    private fun parseText(json: String?): String =
        json?.let { runCatching { JSONObject(it).optString("text", "") }.getOrDefault("") }
            ?.trim().orEmpty()

    private fun parsePartial(json: String?): String =
        json?.let { runCatching { JSONObject(it).optString("partial", "") }.getOrDefault("") }
            ?.trim().orEmpty()

    private fun StringBuilder.appendSpace(s: String) {
        if (isNotEmpty() && !endsWith(' ')) append(' ')
        append(s)
    }
}

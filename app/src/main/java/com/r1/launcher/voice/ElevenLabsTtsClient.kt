package com.r1.launcher.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs TTS REST client (Flash v2.5, ~75ms inference TTFB).
 *
 * Uses the chunked /stream endpoint with low-bitrate mp3_22050_32 + the
 * server-side optimize_streaming_latency=4 hint. The audio is downloaded
 * progressively into a file as bytes arrive; once the full stream is in,
 * onResult fires with the complete bytes (callers play via MediaPlayer).
 *
 * synthesize() returns the OkHttp [Call] so callers can [Call.cancel] mid-flight
 * — used to interrupt a playing reply when the user starts a new recording.
 *
 * POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream
 *   ?output_format=mp3_22050_32
 *   &optimize_streaming_latency=4
 * Header: xi-api-key
 * Body:   {"text": "...", "model_id": "eleven_flash_v2_5"}
 * Returns: chunked audio/mpeg
 */
object ElevenLabsTtsClient {

    private const val TAG = "ElevenLabsTts"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json".toMediaType()

    /**
     * Stream synthesis to a file, then deliver the complete bytes via [onResult].
     * Returns the OkHttp [Call] so callers can cancel mid-flight.
     */
    fun synthesize(
        text: String,
        apiKey: String,
        voiceId: String = VoicePrefs.DEFAULT_VOICE_ID,
        outFile: File,
        onResult: (mp3Bytes: ByteArray?, errorMsg: String?) -> Unit,
    ): Call? {
        val clean = text.trim().take(2000)
        if (clean.isEmpty()) {
            main.post { onResult(null, "empty speech text") }
            return null
        }
        val payload = JSONObject()
            .put("text", clean)
            .put("model_id", "eleven_flash_v2_5")
            .toString()
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream" +
                "?output_format=mp3_22050_32" +
                "&optimize_streaming_latency=4")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(payload.toRequestBody(jsonType))
            .build()
        val call = client.newCall(req)
        Thread {
            try {
                call.execute().use { res ->
                    val body = res.body
                    if (!res.isSuccessful || body == null) {
                        val raw = body?.string().orEmpty()
                        Log.w(TAG, "http ${res.code}: ${raw.take(400)}")
                        val msg = parseError(raw, res.code)
                        main.post { onResult(null, msg) }
                        return@Thread
                    }
                    outFile.parentFile?.mkdirs()
                    val buf = ByteArray(8 * 1024)
                    var totalBytes = 0
                    body.byteStream().use { input ->
                        outFile.outputStream().use { out ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                totalBytes += n
                            }
                        }
                    }
                    if (totalBytes == 0) {
                        main.post { onResult(null, "empty audio") }
                    } else {
                        val bytes = outFile.readBytes()
                        main.post { onResult(bytes, null) }
                    }
                }
            } catch (t: Throwable) {
                if (call.isCanceled()) {
                    Log.i(TAG, "tts call canceled (interrupt)")
                    // Cancellation is intentional — surface as a soft error so
                    // the caller can distinguish from real failures and skip
                    // the "voice playback failed" toast.
                    main.post { onResult(null, "canceled") }
                } else {
                    Log.w(TAG, "tts request failed: ${t.message}")
                    main.post { onResult(null, t.message ?: t.javaClass.simpleName) }
                }
            }
        }.start()
        return call
    }

    private fun parseError(raw: String, code: Int): String {
        // ElevenLabs error shapes:
        //   {"detail":{"status":"quota_exceeded","message":"..."}}
        //   {"detail":"some string"}
        //   {"message":"..."}
        val parsed = runCatching {
            val o = JSONObject(raw)
            val detail = o.opt("detail")
            when (detail) {
                is JSONObject -> {
                    val status = detail.optString("status").ifEmpty { null }
                    val message = detail.optString("message").ifEmpty { null }
                    when {
                        status != null && message != null -> "$status: $message"
                        message != null -> message
                        status != null -> status
                        else -> null
                    }
                }
                is String -> detail.takeIf { it.isNotBlank() }
                else -> o.optString("message").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        return parsed ?: "http $code: ${raw.take(120)}"
    }
}

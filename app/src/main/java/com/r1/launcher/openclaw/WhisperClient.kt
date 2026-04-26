package com.r1.launcher.openclaw

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Whisper REST client for on-device speech-to-text.
 *
 * Posts a WAV blob as multipart/form-data to /v1/audio/transcriptions and
 * returns the transcribed text via callback on the main looper. Single shot,
 * no streaming. Reuses one OkHttpClient across calls so TLS handshakes get
 * pooled for repeated voice-to-text turns.
 */
object WhisperClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun transcribe(
        wavBytes: ByteArray,
        apiKey: String,
        onResult: (transcript: String?, errorMsg: String?) -> Unit,
    ) {
        Thread {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "voice.wav",
                        wavBytes.toRequestBody("audio/wav".toMediaType()),
                    )
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("response_format", "json")
                    // Lock detection to English so a misheard accent doesn't
                    // flip the transcript into the wrong language. Make this
                    // configurable later if multi-language input is needed.
                    .addFormDataPart("language", "en")
                    .build()
                val req = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        // Whisper errors are JSON {error:{message}} — surface that
                        // text directly so users can see "invalid api key" etc.
                        val msg = runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                            ?: "http ${res.code}: ${raw.take(120)}"
                        main.post { onResult(null, msg) }
                        return@Thread
                    }
                    val text = runCatching { JSONObject(raw).optString("text", "") }
                        .getOrDefault("").trim()
                    main.post { onResult(text, null) }
                }
            } catch (t: Throwable) {
                main.post { onResult(null, t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }
}

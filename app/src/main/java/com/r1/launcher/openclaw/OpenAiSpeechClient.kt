package com.r1.launcher.openclaw

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI text-to-speech REST client for assistant voice output.
 *
 * Requests WAV output so R1 playback can use MediaPlayer with a normal file,
 * matching the reliable audio-test playback path.
 */
object OpenAiSpeechClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json".toMediaType()

    fun synthesize(
        text: String,
        apiKey: String,
        onResult: (wavBytes: ByteArray?, errorMsg: String?) -> Unit,
    ) {
        val clean = text.trim().take(1800)
        if (clean.isEmpty()) {
            main.post { onResult(null, "empty speech text") }
            return
        }
        Thread {
            try {
                val payload = JSONObject()
                    .put("model", "gpt-4o-mini-tts")
                    .put("voice", "coral")
                    .put("input", clean)
                    .put("instructions", "Speak naturally, warmly, and briefly, like a helpful device assistant.")
                    .put("response_format", "wav")
                    .toString()
                val req = Request.Builder()
                    .url("https://api.openai.com/v1/audio/speech")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(req).execute().use { res ->
                    val body = res.body
                    if (!res.isSuccessful || body == null) {
                        val raw = body?.string().orEmpty()
                        val msg = runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                            ?: "http ${res.code}: ${raw.take(120)}"
                        main.post { onResult(null, msg) }
                        return@Thread
                    }
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) {
                        main.post { onResult(null, "empty speech audio") }
                    } else {
                        main.post { onResult(bytes, null) }
                    }
                }
            } catch (t: Throwable) {
                main.post { onResult(null, t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }
}

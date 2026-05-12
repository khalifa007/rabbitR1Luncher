package com.r1.launcher.transcriber

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Synchronous client for the ElevenLabs Scribe v2 batch transcription endpoint.
 *
 * The realtime WebSocket API (used elsewhere in the launcher for push-to-talk
 * STT) is the wrong tool for long-form meetings — we'd hold a 30+ minute
 * socket open and lose the diarization-quality benefits of the batch path.
 * Batch returns one JSON blob with per-word `speaker_id` after the file
 * uploads.
 *
 * Endpoint: POST https://api.elevenlabs.io/v1/speech-to-text
 *   model_id=scribe_v2
 *   diarize=true
 *   timestamps_granularity=word
 *   file=<m4a binary>
 *
 * Limits (per ElevenLabs docs as of May 2026):
 *   - 3 GB file size
 *   - 10 hr duration
 *   - synchronous; no webhook needed below those
 *
 * A 60-min m4a at 16 kHz / 64 kbps = ~28 MB upload, well within bounds.
 */
class ScribeClient(private val apiKey: String) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        // Default 10s callTimeout would kill any meeting > a few minutes.
        // 30 min covers a 60-min m4a upload + server processing on 4G.
        .callTimeout(30, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    sealed class Result {
        data class Success(val response: ScribeResponse, val rawJson: String) : Result()
        data class Failure(val httpCode: Int, val message: String) : Result()
    }

    /** Blocks the calling thread until upload + server processing finish. Run
     *  on a background coroutine / executor. */
    fun transcribe(audio: File): Result {
        if (!audio.exists() || audio.length() == 0L) {
            return Result.Failure(0, "audio file missing or empty: ${audio.path}")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audio.name,
                // audio/mp4 is the canonical MIME for .m4a (m4a is just MP4
                // with audio-only ISO boxes). audio/x-m4a sometimes gets
                // stripped by intermediate proxies.
                audio.asRequestBody("audio/mp4".toMediaType()),
            )
            .addFormDataPart("model_id", "scribe_v2")
            .addFormDataPart("diarize", "true")
            .addFormDataPart("timestamps_granularity", "word")
            .build()

        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(body)
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Result.Failure(resp.code, extractErrorMessage(raw) ?: raw.take(200))
                } else {
                    val parsed = runCatching { json.decodeFromString<ScribeResponse>(raw) }
                        .getOrElse {
                            return Result.Failure(resp.code, "parse error: ${it.message}")
                        }
                    Result.Success(parsed, raw)
                }
            }
        } catch (t: Throwable) {
            Result.Failure(0, "network error: ${t.message}")
        }
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            // ElevenLabs returns {"detail": {"status": "...", "message": "..."}}
            // OR {"detail": "..."} OR plain text — accept any of them.
            val obj = json.parseToJsonElement(body).let {
                if (it is kotlinx.serialization.json.JsonObject) it else return@runCatching null
            }
            val detail = obj["detail"]
            when (detail) {
                is kotlinx.serialization.json.JsonObject -> detail["message"]?.toString()?.trim('"')
                is kotlinx.serialization.json.JsonPrimitive -> detail.content
                else -> null
            }
        }.getOrNull()
    }
}

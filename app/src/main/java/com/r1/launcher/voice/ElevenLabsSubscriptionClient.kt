package com.r1.launcher.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the user's current ElevenLabs plan + balance via
 * `GET https://api.elevenlabs.io/v1/user/subscription`.
 *
 * ElevenLabs unifies STT minutes and TTS characters into a single "credit"
 * bucket — `character_count` (used) and `character_limit` (total) cover ALL
 * features against the same key, including Scribe usage by the Meetings app.
 *
 * Cheap call (small JSON, no audio upload) so we can call it on every panel
 * entry without a noticeable cost. Caller still gates behind a 60s in-memory
 * cache so wheel-spamming the row doesn't hammer the API.
 */
class ElevenLabsSubscriptionClient(private val apiKey: String) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    sealed class Result {
        data class Success(val data: SubscriptionData) : Result()
        data class Failure(val httpCode: Int, val message: String) : Result()
    }

    fun fetch(): Result {
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/user/subscription")
            .header("xi-api-key", apiKey)
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Result.Failure(resp.code, body.take(200))
                } else {
                    val parsed = runCatching { json.decodeFromString<SubscriptionData>(body) }
                        .getOrElse { return Result.Failure(resp.code, "parse error: ${it.message}") }
                    Result.Success(parsed)
                }
            }
        } catch (t: Throwable) {
            Result.Failure(0, "network error: ${t.message}")
        }
    }
}

@Serializable
data class SubscriptionData(
    @SerialName("tier") val tier: String? = null,
    @SerialName("character_count") val characterCount: Long = 0,
    @SerialName("character_limit") val characterLimit: Long = 0,
    @SerialName("can_extend_character_limit") val canExtendCharacterLimit: Boolean = false,
    @SerialName("max_character_limit_extension") val maxCharacterLimitExtension: Long = 0,
    @SerialName("next_character_count_reset_unix") val nextResetUnix: Long = 0,
    @SerialName("status") val status: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("character_refresh_period") val refreshPeriod: String? = null,
)

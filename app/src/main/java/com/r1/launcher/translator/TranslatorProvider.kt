package com.r1.launcher.translator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One translation backend. Translation is a stateless one-shot — no streaming,
 * no chat history, no session id. Each [translate] call hits the provider's
 * HTTPS endpoint and returns the translated text via [onResult].
 *
 * Implementations share a single OkHttp client (constructed in
 * [TranslatorClient]) and a single JSON prompt:
 *
 *   System: You are a translator. Translate the user's message from {src}
 *           to {tgt}. Output ONLY the translation. No preamble, no quotes,
 *           no notes. If the input is already in {tgt}, output it unchanged.
 *   User:   {text}
 *
 * `temperature: 0.1` everywhere — translation should be deterministic, not
 * creative. Max tokens capped at 500 (covers 95% of conversational utterances,
 * prevents runaway responses).
 *
 * The output is post-processed: surrounding quotes / asterisks are stripped
 * because all three models occasionally wrap output in `"…"` despite the
 * "no quotes" instruction.
 */
sealed interface TranslatorProvider {
    val id: ProviderId
    fun translate(
        http: OkHttpClient,
        apiKey: String,
        text: String,
        sourceLangName: String,
        targetLangName: String,
        onResult: (Result<String>) -> Unit,
    ): Call

    companion object {
        fun of(id: ProviderId): TranslatorProvider = when (id) {
            ProviderId.GEMINI    -> GeminiProvider
            ProviderId.OPENAI    -> OpenAIProvider
            ProviderId.ANTHROPIC -> AnthropicProvider
        }

        internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        internal val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Strip the wrapping artifacts that all three providers occasionally
         *  emit despite the "output only the translation" instruction.
         *  Repeated trim because some models do `"*translation*"`. */
        internal fun cleanOutput(raw: String): String {
            var s = raw.trim()
            repeat(3) {
                if (s.length >= 2 && s.first() == s.last() && s.first() in "\"'`*_") {
                    s = s.substring(1, s.length - 1).trim()
                }
            }
            return s
        }

        internal fun systemPrompt(src: String, tgt: String): String {
            // "auto-detect" source → let the model identify the language itself.
            val from = if (src.startsWith("auto")) "the user's language (detect it automatically)" else src
            return "You are a translator. Translate the user's message from $from to $tgt. " +
                "Output ONLY the translation. No preamble, no quotes, no notes. " +
                "If the input is already in $tgt, output it unchanged."
        }
    }
}

/**
 * Gemini 2.5 Flash — `key` lives in the query string (`?key=…`), NOT a header.
 * Has a generous free tier (1500 req/day on Flash) so it's the default.
 */
object GeminiProvider : TranslatorProvider {
    override val id = ProviderId.GEMINI
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    override fun translate(
        http: OkHttpClient,
        apiKey: String,
        text: String,
        sourceLangName: String,
        targetLangName: String,
        onResult: (Result<String>) -> Unit,
    ): Call {
        val body = buildJsonObject {
            // Gemini wants the system instruction in `systemInstruction.parts`,
            // separate from `contents`. Bundling it as a user message also
            // works but loses the system-priority weight.
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", JsonPrimitive(TranslatorProvider.systemPrompt(sourceLangName, targetLangName)))
                    })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(text)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(0.1))
                put("maxOutputTokens", JsonPrimitive(500))
            })
        }.toString().toRequestBody(TranslatorProvider.JSON_MEDIA)

        val req = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(body)
            .build()

        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val raw = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (!r.isSuccessful) {
                        onResult(Result.failure(IOException(parseGeminiError(raw, r.code))))
                        return
                    }
                    val text = parseGeminiResponse(raw)
                    if (text == null) {
                        onResult(Result.failure(IOException("empty response")))
                    } else {
                        onResult(Result.success(TranslatorProvider.cleanOutput(text)))
                    }
                }
            }
        })
        return call
    }

    private fun parseGeminiResponse(raw: String): String? = runCatching {
        val root = TranslatorProvider.JSON.parseToJsonElement(raw).jsonObject
        val candidates = root["candidates"] as? JsonArray ?: return@runCatching null
        val first = candidates.firstOrNull()?.jsonObject ?: return@runCatching null
        val content = first["content"]?.jsonObject ?: return@runCatching null
        val parts = content["parts"]?.jsonArray ?: return@runCatching null
        parts.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseGeminiError(body: String, code: Int): String {
        val msg = runCatching {
            val root = TranslatorProvider.JSON.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (!msg.isNullOrBlank()) "gemini $code: $msg" else "gemini http $code"
    }
}

/**
 * OpenAI chat completions — `gpt-4o-mini`. Bearer auth, standard schema.
 */
object OpenAIProvider : TranslatorProvider {
    override val id = ProviderId.OPENAI
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o-mini"

    override fun translate(
        http: OkHttpClient,
        apiKey: String,
        text: String,
        sourceLangName: String,
        targetLangName: String,
        onResult: (Result<String>) -> Unit,
    ): Call {
        val body = buildJsonObject {
            put("model", JsonPrimitive(MODEL))
            put("temperature", JsonPrimitive(0.1))
            put("max_tokens", JsonPrimitive(500))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(TranslatorProvider.systemPrompt(sourceLangName, targetLangName)))
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(text))
                })
            })
        }.toString().toRequestBody(TranslatorProvider.JSON_MEDIA)

        val req = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("Authorization", "Bearer $apiKey")
            .build()

        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val raw = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (!r.isSuccessful) {
                        onResult(Result.failure(IOException(parseOpenAIError(raw, r.code))))
                        return
                    }
                    val text = parseOpenAIResponse(raw)
                    if (text == null) onResult(Result.failure(IOException("empty response")))
                    else onResult(Result.success(TranslatorProvider.cleanOutput(text)))
                }
            }
        })
        return call
    }

    private fun parseOpenAIResponse(raw: String): String? = runCatching {
        val root = TranslatorProvider.JSON.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return@runCatching null
        val first = choices.firstOrNull()?.jsonObject ?: return@runCatching null
        val message = first["message"]?.jsonObject ?: return@runCatching null
        message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseOpenAIError(body: String, code: Int): String {
        val msg = runCatching {
            val root = TranslatorProvider.JSON.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (!msg.isNullOrBlank()) "openai $code: $msg" else "openai http $code"
    }
}

/**
 * Anthropic Messages API — `claude-haiku-4-5`. Uses `x-api-key` + version header.
 */
object AnthropicProvider : TranslatorProvider {
    override val id = ProviderId.ANTHROPIC
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    override fun translate(
        http: OkHttpClient,
        apiKey: String,
        text: String,
        sourceLangName: String,
        targetLangName: String,
        onResult: (Result<String>) -> Unit,
    ): Call {
        val body = buildJsonObject {
            put("model", JsonPrimitive(MODEL))
            put("max_tokens", JsonPrimitive(500))
            put("temperature", JsonPrimitive(0.1))
            put("system", JsonPrimitive(TranslatorProvider.systemPrompt(sourceLangName, targetLangName)))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(text))
                })
            })
        }.toString().toRequestBody(TranslatorProvider.JSON_MEDIA)

        val req = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .build()

        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val raw = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (!r.isSuccessful) {
                        onResult(Result.failure(IOException(parseAnthropicError(raw, r.code))))
                        return
                    }
                    val text = parseAnthropicResponse(raw)
                    if (text == null) onResult(Result.failure(IOException("empty response")))
                    else onResult(Result.success(TranslatorProvider.cleanOutput(text)))
                }
            }
        })
        return call
    }

    private fun parseAnthropicResponse(raw: String): String? = runCatching {
        val root = TranslatorProvider.JSON.parseToJsonElement(raw).jsonObject
        val content = root["content"] as? JsonArray ?: return@runCatching null
        // `content` is an array of blocks; each text block has `{type:"text", text:"…"}`.
        content.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseAnthropicError(body: String, code: Int): String {
        val msg = runCatching {
            val root = TranslatorProvider.JSON.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (!msg.isNullOrBlank()) "claude $code: $msg" else "claude http $code"
    }
}

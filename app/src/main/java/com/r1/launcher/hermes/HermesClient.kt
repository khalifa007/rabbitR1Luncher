package com.r1.launcher.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * Thin client for the Hermes Agent OpenAI-compatible gateway
 * (`gateway/platforms/api_server.py` in NousResearch/hermes-agent).
 *
 * Two endpoints used:
 *   - `GET  ${baseRoot}/health`            — connection probe ("test connection" row)
 *   - `POST ${baseRoot}/v1/chat/completions` (stream=true) — main chat turn
 *
 * The chat call reads SSE manually rather than pulling in an EventSource library
 * — the format is simple enough (one `data: <json>` line per chunk, blank
 * separator, terminator `data: [DONE]`) and avoiding the extra dep keeps
 * mainDexList small.
 */
class HermesClient(private val prefs: HermesPrefs) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        // No call timeout — streamed completions can legitimately run for minutes
        // on a slow LLM. Set generous read timeout for the *first* byte; once
        // the SSE stream is flowing we hold the connection until the server closes.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Tracks the in-flight streaming call so [cancel] can abort it. */
    @Volatile private var inflight: Call? = null

    /** GET ${baseRoot}/health. Fires `onResult(ok, msg)` on the caller's thread
     *  via OkHttp's async callback machinery — caller is responsible for
     *  marshalling back to the main thread if it needs to touch Compose state. */
    fun testConnection(onResult: (ok: Boolean, msg: String) -> Unit) {
        val url = runCatching { prefs.healthUrl() }.getOrNull()
        if (url.isNullOrBlank()) {
            onResult(false, "no server url"); return
        }
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { if (prefs.apiKey.isNotBlank()) header("Authorization", "Bearer ${prefs.apiKey}") }
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "connect failed")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) onResult(true, "ok")
                    else onResult(false, "http ${r.code}")
                }
            }
        })
    }

    /**
     * POST /v1/chat/completions with stream=true.
     *
     * Per-chunk SSE format from Hermes (OpenAI-compatible):
     *   data: {"id":"...", "choices":[{"delta":{"content":"hello"}}], ...}\n
     *   data: {"id":"...", "choices":[{"delta":{"content":" world"}}], ...}\n
     *   data: [DONE]\n
     *
     * @param history     Full conversation so far. Hermes is stateless on this
     *                    endpoint; the client owns the message list.
     * @param onDelta     Fired per content chunk (background thread).
     * @param onDone      Fired exactly once at stream-end with the full assistant text.
     * @param onError     Fired exactly once on transport/HTTP/protocol error.
     */
    fun streamChat(
        history: List<HermesMessage>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ): Call {
        val url = runCatching { prefs.chatCompletionsUrl() }.getOrNull()
        if (url.isNullOrBlank()) {
            onError("no server url")
            return failedCall()
        }

        val body = buildJsonObject {
            // Always send "hermes-agent" — the actual upstream model is decided
            // server-side by /root/.hermes/config.yaml > model.provider + api_mode.
            // Letting the client pick a model id only causes mystery
            // model_not_supported errors when it disagrees with server config.
            put("model", JsonPrimitive("hermes-agent"))
            put("stream", JsonPrimitive(true))
            put("messages", buildJsonArray {
                for (m in history) {
                    if (m.role != "user" && m.role != "assistant" && m.role != "system") continue
                    add(buildJsonObject {
                        put("role", JsonPrimitive(m.role))
                        put("content", JsonPrimitive(m.text))
                    })
                }
            })
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "text/event-stream")
            .header("X-Hermes-Session-Id", prefs.sessionId)
            .apply { if (prefs.apiKey.isNotBlank()) header("Authorization", "Bearer ${prefs.apiKey}") }
            .build()

        val call = http.newCall(req)
        inflight = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight = null
                if (call.isCanceled()) return
                onError(e.message ?: "stream failed")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        onError(parseErrorMessage(errBody, response.code))
                        return
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        onError("empty stream")
                        return
                    }
                    val full = StringBuilder()
                    while (!source.exhausted()) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (line.isEmpty()) continue
                        if (line.startsWith(":")) continue              // SSE comment / keepalive
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue
                        val delta = parseDeltaContent(payload)
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onDelta(delta)
                        }
                    }
                    onDone(full.toString())
                } catch (e: Exception) {
                    if (!call.isCanceled()) onError(e.message ?: "stream parse failed")
                } finally {
                    runCatching { response.close() }
                    inflight = null
                }
            }
        })
        return call
    }

    /** Abort any in-flight streaming call. Safe to call when nothing is running. */
    fun cancel() {
        runCatching { inflight?.cancel() }
        inflight = null
    }

    private fun parseDeltaContent(payload: String): String {
        val el: JsonElement = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return ""
        val obj = (el as? JsonObject) ?: return ""
        val choices = (obj["choices"] as? JsonArray) ?: return ""
        val first = (choices.firstOrNull() as? JsonObject) ?: return ""
        val delta = (first["delta"] as? JsonObject) ?: return ""
        return delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Hermes (and the OpenAI shape it copies) returns errors as
     *  `{"error":{"message":"...","type":"...","code":"..."}}`. Surface the
     *  `message` if present, otherwise fall back to the HTTP status. */
    private fun parseErrorMessage(body: String, code: Int): String {
        val el = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
        val errObj = (el as? JsonObject)?.get("error") as? JsonObject
        val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
        return if (!msg.isNullOrBlank()) "$code $msg" else "http $code"
    }

    /** Returns a pre-canceled Call so callers always get a non-null handle even
     *  when we bail out before [http.newCall]. */
    private fun failedCall(): Call {
        val dummy = http.newCall(Request.Builder().url("http://127.0.0.1/").build())
        dummy.cancel()
        return dummy
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

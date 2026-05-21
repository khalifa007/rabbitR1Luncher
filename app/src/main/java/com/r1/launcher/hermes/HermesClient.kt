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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stateless-per-connection client for the Hermes OpenAI-compatible gateway.
 *
 * Each call takes a [HermesConnection] snapshot so an in-flight stream remains
 * bound to its originating connection even if the user switches active mid-stream.
 * Inflight calls are tracked per connection id so [cancel] / [cancelAll] can
 * tear down exactly the right ones.
 */
class HermesClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val inflight = ConcurrentHashMap<String, Call>()

    fun testConnection(connection: HermesConnection, onResult: (ok: Boolean, msg: String) -> Unit) {
        val url = connection.healthUrl()
        if (url.isBlank()) {
            onResult(false, "no server url"); return
        }
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
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

    fun streamChat(
        connection: HermesConnection,
        history: List<HermesMessage>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onToolProgress: (HermesToolEvent) -> Unit = {},
    ): Call {
        val url = connection.chatCompletionsUrl()
        if (url.isBlank()) {
            onError("no server url")
            return failedCall()
        }

        val body = buildJsonObject {
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
            .header("X-Hermes-Session-Id", connection.sessionId)
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .build()

        val call = http.newCall(req)
        inflight[connection.id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight.remove(connection.id, call)
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
                    val splitter = ThinkSplitter()
                    var currentEvent: String? = null
                    while (!source.exhausted()) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (line.isEmpty()) {
                            currentEvent = null
                            continue
                        }
                        if (line.startsWith(":")) continue
                        if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim()
                            continue
                        }
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue

                        when (currentEvent) {
                            "hermes.tool.progress" -> {
                                parseToolProgress(payload)?.let(onToolProgress)
                            }
                            else -> {
                                val (content, reasoning) = parseDelta(payload)
                                if (reasoning.isNotEmpty()) onReasoning(reasoning)
                                if (content.isNotEmpty()) {
                                    splitter.feed(
                                        content,
                                        onAnswer = { a ->
                                            full.append(a)
                                            onDelta(a)
                                        },
                                        onReasoning = onReasoning,
                                    )
                                }
                            }
                        }
                    }
                    splitter.flush(
                        onAnswer = { a ->
                            full.append(a)
                            onDelta(a)
                        },
                        onReasoning = onReasoning,
                    )
                    onDone(full.toString())
                } catch (e: Exception) {
                    if (!call.isCanceled()) onError(e.message ?: "stream parse failed")
                } finally {
                    runCatching { response.close() }
                    inflight.remove(connection.id, call)
                }
            }
        })
        return call
    }

    /** Cancel any in-flight call bound to [connectionId]. Null cancels all. */
    fun cancel(connectionId: String?) {
        if (connectionId == null) {
            cancelAll()
            return
        }
        runCatching { inflight.remove(connectionId)?.cancel() }
    }

    fun cancelAll() {
        val snap = inflight.values.toList()
        inflight.clear()
        snap.forEach { runCatching { it.cancel() } }
    }

    /** Returns (content, reasoning_content) — either may be empty. */
    private fun parseDelta(payload: String): Pair<String, String> {
        val el: JsonElement = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return "" to ""
        val obj = (el as? JsonObject) ?: return "" to ""
        val choices = (obj["choices"] as? JsonArray) ?: return "" to ""
        val first = (choices.firstOrNull() as? JsonObject) ?: return "" to ""
        val delta = (first["delta"] as? JsonObject) ?: return "" to ""
        val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return content to reasoning
    }

    /** Decode a `hermes.tool.progress` payload. Returns null on malformed input. */
    private fun parseToolProgress(payload: String): HermesToolEvent? {
        val el = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return null
        val obj = (el as? JsonObject) ?: return null
        val tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return null
        val emoji = obj["emoji"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val callId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "running"
        return HermesToolEvent(
            tool = tool,
            emoji = emoji,
            label = label,
            toolCallId = callId,
            status = status,
        )
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        val el = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
        val errObj = (el as? JsonObject)?.get("error") as? JsonObject
        val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
        return if (!msg.isNullOrBlank()) "$code $msg" else "http $code"
    }

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

/**
 * Splits incoming SSE content into answer-text and `<think>`-block reasoning,
 * with a small tail buffer so a tag arriving across chunk boundaries
 * (`...<thi` then `nk>...`) resolves on the next feed instead of leaking
 * a literal angle bracket into the answer.
 *
 * Not thread-safe — one instance per in-flight stream.
 */
private class ThinkSplitter {
    private enum class Mode { OUTSIDE, INSIDE }
    private var mode = Mode.OUTSIDE
    private var pending = StringBuilder()

    private val openTag = "<think>"
    private val closeTag = "</think>"

    fun feed(chunk: String, onAnswer: (String) -> Unit, onReasoning: (String) -> Unit) {
        pending.append(chunk)
        drain(flushAll = false, onAnswer = onAnswer, onReasoning = onReasoning)
    }

    fun flush(onAnswer: (String) -> Unit, onReasoning: (String) -> Unit) {
        drain(flushAll = true, onAnswer = onAnswer, onReasoning = onReasoning)
        // Anything left after a final drain is residual content that couldn't
        // possibly start a tag — emit per current mode.
        if (pending.isNotEmpty()) {
            val tail = pending.toString()
            pending.setLength(0)
            if (mode == Mode.INSIDE) onReasoning(tail) else onAnswer(tail)
        }
    }

    private fun drain(flushAll: Boolean, onAnswer: (String) -> Unit, onReasoning: (String) -> Unit) {
        while (true) {
            val target = if (mode == Mode.OUTSIDE) openTag else closeTag
            val idx = pending.indexOf(target)
            if (idx >= 0) {
                val before = pending.substring(0, idx)
                if (before.isNotEmpty()) {
                    if (mode == Mode.OUTSIDE) onAnswer(before) else onReasoning(before)
                }
                pending.delete(0, idx + target.length)
                mode = if (mode == Mode.OUTSIDE) Mode.INSIDE else Mode.OUTSIDE
                continue
            }
            // No full tag in the buffer. Emit everything except the longest
            // possible tag-prefix suffix so a cross-chunk tag still resolves.
            val keep = if (flushAll) 0 else maxIncompleteTagPrefix(pending, target)
            val emittable = pending.length - keep
            if (emittable > 0) {
                val out = pending.substring(0, emittable)
                pending.delete(0, emittable)
                if (mode == Mode.OUTSIDE) onAnswer(out) else onReasoning(out)
            }
            return
        }
    }

    /** Longest k such that the last k chars of [buf] equal the first k chars
     *  of [tag]. Used to hold back a possible partial tag at the tail. */
    private fun maxIncompleteTagPrefix(buf: CharSequence, tag: String): Int {
        val max = minOf(buf.length, tag.length - 1)
        for (k in max downTo 1) {
            var match = true
            for (i in 0 until k) {
                if (buf[buf.length - k + i] != tag[i]) { match = false; break }
            }
            if (match) return k
        }
        return 0
    }
}

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
                    while (!source.exhausted()) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (line.isEmpty()) continue
                        if (line.startsWith(":")) continue
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

    private fun parseDeltaContent(payload: String): String {
        val el: JsonElement = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return ""
        val obj = (el as? JsonObject) ?: return ""
        val choices = (obj["choices"] as? JsonArray) ?: return ""
        val first = (choices.firstOrNull() as? JsonObject) ?: return ""
        val delta = (first["delta"] as? JsonObject) ?: return ""
        return delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
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

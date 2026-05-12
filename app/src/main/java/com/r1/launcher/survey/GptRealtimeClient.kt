package com.r1.launcher.survey

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OpenAI gpt-realtime-2 WebSocket client. Single low-latency pipe carrying
 * bidirectional PCM16 audio + a JSON control plane (session config, tool calls,
 * transcripts, VAD signals).
 *
 * Protocol: https://platform.openai.com/docs/guides/realtime
 *
 *   wss://api.openai.com/v1/realtime?model=gpt-realtime-2
 *   Headers: Authorization: Bearer <key>
 *            OpenAI-Beta: realtime=v1
 *
 * Audio in: base64-encoded PCM16 mono 24 kHz, sent as `input_audio_buffer.append`
 *   frames. Server-side VAD detects turn boundaries.
 * Audio out: base64 PCM16 chunks streamed as `response.audio.delta`. Decode and
 *   pump into the SIP uplink resampler.
 *
 * Caller pattern:
 *   val client = GptRealtimeClient.open(apiKey, sessionConfig, callback)
 *   client.sendInputAudio(pcm)          // for each downlink frame
 *   client.sendToolResult(callId, ...)  // when callback.onToolCall fires
 *   client.cancelResponse()             // barge-in
 *   client.close()                      // teardown
 */
class GptRealtimeClient private constructor(
    apiKey: String,
    private val sessionConfig: JsonObject,
    private val cb: Callback,
) {

    interface Callback {
        fun onConnected()
        fun onSessionReady()
        fun onUserSpeechStarted()
        fun onUserSpeechStopped()
        /** Bot audio output, PCM16 mono 24 kHz per OpenAI Realtime spec. */
        fun onAudioOutDelta(pcm: ByteArray)
        fun onAudioOutDone()
        /** Streaming assistant text — accumulate for transcript. */
        fun onAssistantTranscriptDelta(text: String)
        /** Committed user-side transcription. */
        fun onUserTranscriptFinal(text: String)
        /** Model called a tool. Reply with [sendToolResult] using the same callId. */
        fun onToolCall(name: String, callId: String, args: JsonObject)
        /** Bot's response cycle completed (audio + transcript flushed). */
        fun onResponseDone()
        fun onError(message: String)
        fun onDisconnected()
    }

    private val main = Handler(Looper.getMainLooper())
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gpt-realtime-send").apply { isDaemon = true }
    }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var sessionReady = false

    init {
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-realtime")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        ws = http.newWebSocket(req, Listener())
    }

    /** Append a PCM16 mono 24 kHz chunk to the input audio buffer. Server-side
     *  VAD will detect turn boundaries automatically. */
    fun sendInputAudio(pcm16: ByteArray) {
        if (closed || pcm16.isEmpty()) return
        val socket = ws ?: return
        sendExecutor.execute {
            runCatching {
                val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
                val msg = buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", b64)
                }
                socket.send(msg.toString())
            }
        }
    }

    /** Force-commit the input buffer. Usually unneeded — server_vad handles
     *  end-of-turn on its own. Kept for explicit-control scenarios. */
    fun commitInputBuffer() {
        if (closed) return
        sendRaw(buildJsonObject { put("type", "input_audio_buffer.commit") })
    }

    /** Tell the model to begin a response. Use to trigger the opening consent
     *  disclosure at session start, or to nudge after a tool result. */
    fun createResponse(instructions: String? = null) {
        if (closed) return
        sendRaw(buildJsonObject {
            put("type", "response.create")
            put("response", buildJsonObject {
                put("modalities", buildJsonArray { add("audio"); add("text") })
                if (instructions != null) put("instructions", instructions)
            })
        })
    }

    /** Reply to a function-call event. */
    fun sendToolResult(callId: String, output: JsonObject) {
        if (closed) return
        sendRaw(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId)
                put("output", output.toString())
            })
        })
        // Triggers the model to continue speaking after the tool result.
        createResponse()
    }

    /** Barge-in: user started speaking, cancel the in-flight assistant response. */
    fun cancelResponse() {
        if (closed) return
        sendRaw(buildJsonObject { put("type", "response.cancel") })
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { ws?.close(1000, "client_close") }
        ws = null
        runCatching { sendExecutor.shutdown() }
    }

    private fun sendRaw(obj: JsonObject) {
        val socket = ws ?: return
        sendExecutor.execute { runCatching { socket.send(obj.toString()) } }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open (HTTP ${response.code})")
            main.post { cb.onConnected() }
            sendRaw(buildJsonObject {
                put("type", "session.update")
                put("session", sessionConfig)
            })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val o = json.parseToJsonElement(text).jsonObject
                val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                when (type) {
                    "session.created" -> {
                        // Server confirmed the session; our session.update was already
                        // posted in onOpen so the config is in flight.
                        Log.i(TAG, "session.created")
                    }
                    "session.updated" -> {
                        if (!sessionReady) {
                            sessionReady = true
                            Log.i(TAG, "session.updated — ready")
                            main.post { cb.onSessionReady() }
                        }
                    }
                    "input_audio_buffer.speech_started" -> {
                        main.post { cb.onUserSpeechStarted() }
                    }
                    "input_audio_buffer.speech_stopped" -> {
                        main.post { cb.onUserSpeechStopped() }
                    }
                    "response.audio.delta" -> {
                        val b64 = o["delta"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val pcm = Base64.decode(b64, Base64.NO_WRAP)
                        main.post { cb.onAudioOutDelta(pcm) }
                    }
                    "response.audio.done" -> {
                        main.post { cb.onAudioOutDone() }
                    }
                    "response.audio_transcript.delta" -> {
                        val delta = o["delta"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        main.post { cb.onAssistantTranscriptDelta(delta) }
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        val t = o["transcript"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        main.post { cb.onUserTranscriptFinal(t) }
                    }
                    "response.function_call_arguments.done" -> {
                        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val callId = o["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val argsStr = o["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                        val args = runCatching { json.parseToJsonElement(argsStr).jsonObject }
                            .getOrElse { buildJsonObject {} }
                        Log.i(TAG, "tool_call name=$name call_id=$callId")
                        main.post { cb.onToolCall(name, callId, args) }
                    }
                    "response.done" -> {
                        // Surface any response.status_details.error inside response.done.
                        val response = o["response"]?.jsonObject
                        val status = response?.get("status")?.jsonPrimitive?.contentOrNull
                        if (status == "failed") {
                            val err = response["status_details"]?.jsonObject
                                ?.get("error")?.jsonObject
                                ?.get("message")?.jsonPrimitive?.contentOrNull ?: "response failed"
                            main.post { cb.onError(err) }
                        }
                        main.post { cb.onResponseDone() }
                    }
                    "error" -> {
                        val err = o["error"]?.jsonObject
                        val message = err?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "openai realtime error"
                        Log.w(TAG, "error frame: $message")
                        main.post { cb.onError(message) }
                    }
                    "rate_limits.updated", "response.created", "response.output_item.added",
                    "response.output_item.done", "response.content_part.added",
                    "response.content_part.done", "response.audio_transcript.done",
                    "conversation.item.created", "conversation.item.input_audio_transcription.delta",
                    "input_audio_buffer.committed", "response.text.delta", "response.text.done",
                    "response.function_call_arguments.delta" -> {
                        // Informational — no consumer needs these.
                    }
                    else -> Log.v(TAG, "unhandled type=$type")
                }
            }.onFailure {
                Log.w(TAG, "bad frame: ${it.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            ws = null
            runCatching { sendExecutor.shutdown() }
            main.post { cb.onDisconnected() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed = true
            ws = null
            runCatching { sendExecutor.shutdownNow() }
            val code = response?.code ?: 0
            val msg = when {
                code == 401 || code == 403 -> "openai: auth failed (check api key)"
                code == 429 -> "openai: rate limited"
                else -> t.message ?: "ws failure (http $code)"
            }
            Log.w(TAG, "ws failure: $msg")
            main.post {
                cb.onError(msg)
                cb.onDisconnected()
            }
        }
    }

    companion object {
        private const val TAG = "SurveyGptRT"

        private val http: OkHttpClient = OkHttpClient.Builder()
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun open(apiKey: String, sessionConfig: JsonObject, cb: Callback): GptRealtimeClient =
            GptRealtimeClient(apiKey, sessionConfig, cb)
    }
}

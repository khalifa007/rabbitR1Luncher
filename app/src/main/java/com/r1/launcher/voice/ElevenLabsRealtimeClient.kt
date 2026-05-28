package com.r1.launcher.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs Realtime STT (Scribe v2 Realtime) WebSocket client.
 *
 * Protocol: https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime
 *
 * Connects to:
 *   wss://api.elevenlabs.io/v1/speech-to-text/realtime
 *     ?model_id=scribe_v2_realtime
 *     &audio_format=pcm_16000
 *     &language_code=en
 *     &commit_strategy=vad
 *
 * Auth via `xi-api-key` header. Audio chunks are JSON messages with the PCM
 * base64-encoded — base64-in-JSON is what the API requires (no binary frames).
 *
 * Server emits `partial_transcript` (live updates) and `committed_transcript`
 * (final, fired by VAD-detected silence or when client sets `commit:true` on
 * the last chunk). On any error the client falls back to a single onError().
 *
 * One session = one user turn. Caller opens, streams PCM via sendPcm(), then
 * calls finish() to send the last chunk with commit=true and close cleanly.
 */
class ElevenLabsRealtimeClient private constructor(
    apiKey: String,
    private val onPartial: (String) -> Unit,
    private val onCommitted: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    // Daemon thread factory: matches R1WebServer.sendExecutor (line ~54) so the
    // worker doesn't keep the JVM alive if shutdown is missed. Per-instance —
    // the executor is also explicitly shutdown() on cancel/finish/onClosed/
    // onFailure below so each PTT session releases its thread immediately
    // instead of accumulating one per voice turn over a 24/7 launcher's life.
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elevenlabs-stt-send").apply { isDaemon = true }
    }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    // Latched once the first committed/final frame is delivered. Any further
    // `partial_transcript` events from the server are dropped — otherwise late
    // partials (buffered audio after VAD commit, or a new utterance fragment
    // after VAD auto-commit while the user is still holding the button) would
    // repopulate the chat panel's pending bubble *after* the consumer cleared
    // it on commit, leaving a duplicate gray bubble next to the orange one.
    @Volatile private var committed = false

    init {
        // No language_code — Scribe v2 Realtime auto-detects the spoken
        // language per utterance (Arabic, Spanish, French, English, etc.).
        // Hardcoding language_code=en forced English-only transcription and
        // garbled non-English speech.
        val url = "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
            "?model_id=scribe_v2_realtime" +
            "&audio_format=pcm_16000" +
            "&commit_strategy=vad"
        val req = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .build()
        ws = http.newWebSocket(req, Listener())
    }

    fun sendPcm(chunk: ByteArray) {
        if (closed) return
        val socket = ws ?: return
        sendExecutor.execute {
            runCatching {
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val msg = JSONObject()
                    .put("message_type", "input_audio_chunk")
                    .put("audio_base_64", b64)
                    .put("commit", false)
                    .put("sample_rate", 16_000)
                socket.send(msg.toString())
            }
        }
    }

    /** Send a zero-byte chunk with commit=true and close after the final
     *  `committed_transcript` arrives. The VAD strategy will usually have
     *  committed already if the user paused, but explicit commit is a safety
     *  net for "release to send" UX. */
    fun finish() {
        if (closed) return
        val socket = ws ?: return
        sendExecutor.execute {
            runCatching {
                val msg = JSONObject()
                    .put("message_type", "input_audio_chunk")
                    .put("audio_base_64", "")
                    .put("commit", true)
                    .put("sample_rate", 16_000)
                socket.send(msg.toString())
            }
        }
        // Stop accepting new chunks; the queued commit above still runs because
        // shutdown() drains existing tasks (unlike shutdownNow which interrupts).
        runCatching { sendExecutor.shutdown() }
    }

    /** Abort without committing. Discards any in-flight audio. */
    fun cancel() {
        closed = true
        runCatching { ws?.close(1000, "cancel") }
        ws = null
        runCatching { sendExecutor.shutdownNow() }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Frames contain the user's transcript — only dump them in debug
            // builds. In release, log nothing here (content is privacy-sensitive).
            if (com.r1.launcher.BuildConfig.DEBUG) {
                val trunc = if (text.length > 400) text.substring(0, 400) + "…(${text.length})" else text
                Log.d(TAG, "frame: $trunc")
            }
            runCatching {
                val o = JSONObject(text)
                val mt = o.optString("message_type").ifEmpty { o.optString("type") }
                when (mt) {
                    "partial_transcript" -> {
                        if (committed) {
                            // Late partial after commit — drop. Don't log the
                            // text (privacy); just note the drop at verbose.
                            Log.v(TAG, "partial after commit, dropping")
                        } else {
                            val t = o.optString("text")
                            main.post { onPartial(t) }
                        }
                    }
                    "committed_transcript",
                    "committed_transcript_with_timestamps",
                    "final_transcript",
                    "transcript_completed" -> {
                        val t = o.optString("text")
                        Log.i(TAG, "committed (${t.length} chars)")
                        committed = true
                        main.post { onCommitted(t) }
                    }
                    "error" -> {
                        val msg = o.optString("error", "stt error")
                        Log.w(TAG, "stt error: $msg")
                        main.post { onError(msg) }
                    }
                    "quota_exceeded" -> {
                        val msg = o.optString("error", "elevenlabs quota exceeded")
                        Log.w(TAG, "stt $mt: $msg")
                        main.post { onError("quota exceeded — top up at elevenlabs.io") }
                    }
                    "auth_error", "unauthorized" -> {
                        val msg = o.optString("error", "auth failed")
                        Log.w(TAG, "stt $mt: $msg")
                        main.post { onError("auth failed — check elevenlabs key") }
                    }
                    "session_started" -> {
                        Log.i(TAG, "session_started ${o.optString("session_id")}")
                    }
                    else -> {
                        // Catch-all for any error-shaped frame we haven't seen yet
                        // (e.g. rate_limit_exceeded, model_unavailable). If the
                        // server gave us a message body, surface it.
                        if (mt.contains("error") || mt.contains("exceed") || o.has("error")) {
                            val msg = o.optString("error", mt)
                            Log.w(TAG, "stt unknown error mt='$mt': $msg")
                            main.post { onError(msg) }
                        } else {
                            Log.w(TAG, "unhandled message_type='$mt'")
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "bad frame: ${it.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed = true
            ws = null
            runCatching { sendExecutor.shutdownNow() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed = true
            ws = null
            runCatching { sendExecutor.shutdownNow() }
            val code = response?.code ?: 0
            val msg = if (code == 401 || code == 403) "elevenlabs: auth failed (key invalid?)"
                      else (t.message ?: "ws failure")
            main.post { onError(msg) }
        }
    }

    companion object {
        private const val TAG = "ElevenLabsRealtime"

        // Long-lived OkHttpClient — reused across sessions. Same shape as
        // GatewaySession's client (no read timeout, ping every 25s) which is
        // proven to work on this device's networking stack.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        fun open(
            apiKey: String,
            onPartial: (String) -> Unit,
            onCommitted: (String) -> Unit,
            onError: (String) -> Unit,
        ): ElevenLabsRealtimeClient =
            ElevenLabsRealtimeClient(apiKey, onPartial, onCommitted, onError)
    }
}

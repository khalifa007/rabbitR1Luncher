package com.r1.launcher.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Embedded HTTP + WebSocket server. Hosts a single-page web UI from
 * `assets/web/` and exposes a JSON-RPC channel at /api/rpc that mirrors the
 * shape of [com.r1.launcher.openclaw.GatewaySession].
 *
 * One instance is held by [com.r1.launcher.LauncherActivity]; lifecycle is
 * `start()` when the user toggles "remote panel" on, `stop()` on toggle off
 * or activity destroy.
 *
 * Auth: physical proximity. The hotspot's WPA2 password gates who can reach
 * the IP; on regular Wi-Fi the user must explicitly opt in via the toggle.
 * No additional token exchange in v1.
 */
class R1WebServer(
    private val ctx: Context,
    private val host: LauncherHost,
    private val state: LauncherState,
    port: Int = DEFAULT_PORT,
) : NanoWSD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "R1WebServer"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val sockets = CopyOnWriteArrayList<RpcSocket>()
    private var stateTickActive = false
    /** All socket writes go through this — Android forbids socket I/O on the main thread. */
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-ws-send").apply { isDaemon = true }
    }

    private val stateTick = object : Runnable {
        override fun run() {
            if (!stateTickActive) return
            broadcastSnapshot()
            ui.postDelayed(this, 1000L)
        }
    }

    fun startServer() {
        // Pass 0 (no timeout) instead of SOCKET_READ_TIMEOUT (5s default).
        // WebSocket connections sit idle between events; the 5s timeout from
        // NanoHTTPD would tear them down between every RPC frame.
        runCatching { start(0, false) }
            .onFailure { Log.w(TAG, "start failed: ${it.message}") }
        if (!stateTickActive) {
            stateTickActive = true
            ui.post(stateTick)
        }
    }

    fun stopServer() {
        stateTickActive = false
        ui.removeCallbacks(stateTick)
        sockets.toList().forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "server stopping", false) } }
        sockets.clear()
        runCatching { stop() }
        runCatching { sendExecutor.shutdownNow() }
    }

    // --- HTTP routing ---

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return runCatching {
            when {
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")
                uri.startsWith("/static/") -> serveAsset("web/" + uri.removePrefix("/static/"), guessMime(uri))
                uri == "/app.js" -> serveAsset("web/app.js", "application/javascript")
                uri == "/i18n.js" -> serveAsset("web/i18n.js", "application/javascript")
                uri == "/style.css" -> serveAsset("web/style.css", "text/css")
                uri == "/api/state" -> jsonResponse(WebRpc.buildSnapshot(state, ctx))
                uri == "/favicon.ico" -> notFound()
                uri.startsWith("/api/transcriber/audio/") ->
                    serveTranscriberAudio(uri.removePrefix("/api/transcriber/audio/"))
                uri.startsWith("/api/transcriber/transcript/") ->
                    serveTranscriberTranscript(uri.removePrefix("/api/transcriber/transcript/"))
                else -> notFound()
            }
        }.getOrElse { e ->
            Log.w(TAG, "serve $uri failed: ${e.message}")
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    /** Stream the m4a recording. Don't readBytes() — files can be 30 MB+. */
    private fun serveTranscriberAudio(uuidWithExt: String): Response {
        val uuid = uuidWithExt.removeSuffix(".m4a").removeSuffix(".mp4")
        if (!uuid.matches(Regex("[a-zA-Z0-9-]+"))) return notFound()
        val store = com.r1.launcher.transcriber.MeetingStore.get(ctx)
        val file = store.audioFile(uuid)
        if (!file.exists() || file.length() == 0L) return notFound()
        val resp = newChunkedResponse(
            Response.Status.OK,
            "audio/mp4",
            java.io.FileInputStream(file),
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$uuid.m4a\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }


    /** `<uuid>.txt` returns the rendered transcript; `<uuid>.json` returns the
     *  raw Scribe response (suitable for re-rendering with renamed speakers). */
    private fun serveTranscriberTranscript(pathWithExt: String): Response {
        val uuid: String
        val isJson: Boolean
        when {
            pathWithExt.endsWith(".json") -> { uuid = pathWithExt.removeSuffix(".json"); isJson = true }
            pathWithExt.endsWith(".txt") -> { uuid = pathWithExt.removeSuffix(".txt"); isJson = false }
            else -> { uuid = pathWithExt; isJson = false }
        }
        if (!uuid.matches(Regex("[a-zA-Z0-9-]+"))) return notFound()
        val store = com.r1.launcher.transcriber.MeetingStore.get(ctx)
        val meeting = store.loadMeeting(uuid) ?: return notFound()
        val body = if (isJson) meeting.transcriptJson ?: "{}" else meeting.transcriptText.orEmpty()
        val mime = if (isJson) "application/json" else "text/plain; charset=utf-8"
        val resp = newFixedLengthResponse(Response.Status.OK, mime, body)
        resp.addHeader(
            "Content-Disposition",
            "attachment; filename=\"$uuid.${if (isJson) "json" else "txt"}\"",
        )
        return resp
    }

    private fun serveAsset(path: String, mime: String): Response {
        val bytes = runCatching {
            ctx.assets.open(path).use { it.readBytes() }
        }.getOrElse { return notFound() }
        return newFixedLengthResponse(
            Response.Status.OK,
            mime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun jsonResponse(payload: JsonElement): Response {
        val txt = json.encodeToString(JsonElement.serializer(), payload)
        return newFixedLengthResponse(Response.Status.OK, "application/json", txt)
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    private fun errorResponse(status: Response.Status, msg: String) =
        newFixedLengthResponse(status, "text/plain", msg)

    private fun guessMime(uri: String): String = when {
        uri.endsWith(".html") -> "text/html"
        uri.endsWith(".js") -> "application/javascript"
        uri.endsWith(".css") -> "text/css"
        uri.endsWith(".png") -> "image/png"
        uri.endsWith(".svg") -> "image/svg+xml"
        uri.endsWith(".json") -> "application/json"
        uri.endsWith(".ttf") -> "font/ttf"
        uri.endsWith(".woff") -> "font/woff"
        uri.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    // --- WebSocket routing ---

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri ?: ""
        // We only expect /api/rpc but accept any path for simplicity.
        val sock = RpcSocket(handshake)
        Log.i(TAG, "ws open $uri (${sockets.size + 1} live)")
        return sock
    }

    inner class RpcSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        override fun onOpen() {
            sockets.add(this)
            // Push the initial full snapshot so the client renders immediately.
            sendEvent("state.snapshot", WebRpc.buildSnapshot(state, ctx))
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean,
        ) {
            sockets.remove(this)
            Log.i(TAG, "ws close $code $reason (${sockets.size} live)")
        }

        override fun onMessage(message: WebSocketFrame) {
            Log.i(TAG, "onMessage opcode=${message.opCode} text=${message.textPayload?.take(80)}")
            val text = message.textPayload ?: return
            val req = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return sendError("", "bad_json", "could not parse")

            val type = req["type"]?.jsonPrimitive?.contentOrNull
            val id = req["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type != "req") return

            val method = req["method"]?.jsonPrimitive?.contentOrNull
                ?: return sendError(id, "missing_method", "method required")
            val params = req["params"] as? JsonObject

            // Dispatch on the UI thread because most LauncherHost methods
            // mutate Compose state and post back to it.
            ui.post {
                runCatching {
                    val payload = WebRpc.dispatch(host, state, ctx, method, params)
                    sendResponse(id, payload)
                }.onFailure { e ->
                    val code = (e as? RpcException)?.code ?: "internal_error"
                    sendError(id, code, e.message ?: "error")
                }
            }
        }

        override fun onPong(pong: WebSocketFrame) {}

        override fun onException(exception: IOException) {
            Log.w(TAG, "ws exception: ${exception.message}")
            sockets.remove(this)
        }

        fun sendResponse(id: String, payload: JsonElement) {
            val frame = buildJsonObject {
                put("type", "res")
                put("id", id)
                put("ok", true)
                put("payload", payload)
            }
            sendJson(frame)
        }

        fun sendError(id: String, code: String, message: String) {
            val frame = buildJsonObject {
                put("type", "res")
                put("id", id)
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            }
            sendJson(frame)
        }

        fun sendEvent(event: String, payload: JsonElement) {
            val frame = buildJsonObject {
                put("type", "event")
                put("event", event)
                put("payload", payload)
            }
            sendJson(frame)
        }

        private fun sendJson(obj: JsonObject) {
            val payload = json.encodeToString(JsonElement.serializer(), obj)
            // Always dispatch to the send executor — Android crashes on socket I/O
            // from the main thread, and onOpen is invoked from there for us.
            runCatching {
                sendExecutor.execute {
                    runCatching { send(payload) }
                        .onFailure {
                            Log.w(TAG, "ws send failed (${it.javaClass.simpleName}): ${it.message}")
                            sockets.remove(this)
                        }
                }
            }
        }
    }

    // --- broadcast tick ---

    private fun broadcastSnapshot() {
        if (sockets.isEmpty()) return
        val payload = WebRpc.buildSnapshot(state, ctx)
        sockets.toList().forEach { it.sendEvent("state.snapshot", payload) }
    }

    /** Stream a single line of terminal output to every connected client.
     *  Called from [com.r1.launcher.LauncherActivity.terminalRun]'s onLine
     *  callback so the web Terminal tab mirrors the on-device buffer in real
     *  time. Skipped when no clients are connected to avoid pointless work. */
    fun broadcastTerminalOutput(line: String, cwd: String) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject {
            put("line", line)
            put("cwd", cwd)
        }
        sockets.toList().forEach { it.sendEvent("terminal.output", payload) }
    }

    /** Push a freshly-committed Claude Code chat message to every connected
     *  client so the web Claude tab mirrors the on-device bubble list. Both
     *  user (echo) and assistant (reply) turns flow through here. */
    fun broadcastClaudeMessage(role: String, text: String, error: Boolean) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject {
            put("role", role)
            put("text", text)
            put("error", error)
        }
        sockets.toList().forEach { it.sendEvent("claude.message", payload) }
    }

    /** Live streaming preview (claude --print emits line-by-line; we render
     *  the accumulated tail before commit). Empty `text` means "stream done,
     *  preview gone" — the web client clears its live bubble. */
    fun broadcastClaudeStreaming(text: String) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject { put("text", text) }
        sockets.toList().forEach { it.sendEvent("claude.streaming", payload) }
    }

    /** Busy indicator transitions (true on send, false on completion) so
     *  the web tab can flip its `...` indicator + disable the send button. */
    fun broadcastClaudeBusy(busy: Boolean) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject { put("busy", busy) }
        sockets.toList().forEach { it.sendEvent("claude.busy", payload) }
    }

    /** Wipe signal — fires when the on-device `clr` pill clears history so
     *  web clients drop their bubble list too. */
    fun broadcastClaudeCleared() {
        if (sockets.isEmpty()) return
        sockets.toList().forEach { it.sendEvent("claude.cleared", buildJsonObject {}) }
    }

    /** Single line of bootstrap stdout — pushed to the setup view's log pane
     *  in real time so the user sees `apk add` / `tar -xz` / `[r1-claude]`
     *  output instead of a 5-minute spinner with no feedback. */
    fun broadcastClaudeSetupProgress(line: String) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject { put("line", line) }
        sockets.toList().forEach { it.sendEvent("claude.setup.progress", payload) }
    }

    /** Terminal signal for the bootstrap chain — `ok=true` means the chroot
     *  is now usable and the web UI should swap the setup pane for the
     *  login pane. */
    fun broadcastClaudeSetupDone(ok: Boolean) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject { put("ok", ok) }
        sockets.toList().forEach { it.sendEvent("claude.setup.done", payload) }
    }

}

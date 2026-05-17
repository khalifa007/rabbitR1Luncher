package com.r1.launcher.web

import android.content.Context
import android.net.wifi.WifiManager
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
    /** Kept alive for as long as the server is running so the Wi-Fi radio
     *  stays in high-performance mode even when the screen is off. Without
     *  this, the radio drops to DTIM-driven sleep and packets to the
     *  listening socket can be delayed by hundreds of ms — fine for the
     *  state-snapshot 1Hz tick, bad for "ring me when this webhook fires"
     *  notifications. Released in stopServer(). */
    private var wifiLock: WifiManager.WifiLock? = null
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
        acquireWifiLock()
    }

    fun stopServer() {
        stateTickActive = false
        ui.removeCallbacks(stateTick)
        sockets.toList().forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "server stopping", false) } }
        sockets.clear()
        runCatching { stop() }
        runCatching { sendExecutor.shutdownNow() }
        releaseWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching
            // FULL_HIGH_PERF disables Wi-Fi power save mode entirely. Battery
            // cost is real but small at idle; alternative FULL_LOW_LATENCY
            // (API 29+) is more aggressive than we need for HTTP webhooks.
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "r1.webserver")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            Log.i(TAG, "wifi lock acquired (full_high_perf)")
        }.onFailure { Log.w(TAG, "wifi lock failed: ${it.message}") }
    }

    private fun releaseWifiLock() {
        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
            wifiLock = null
            Log.i(TAG, "wifi lock released")
        }
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
                uri == "/api/notify" -> handleNotifyPost(session)
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

    /**
     * Generic notification ingress for any client that can hit the LAN —
     * Hermes agent, GitHub webhooks, Zapier, custom cron jobs, etc.
     *
     * Auth: bearer token in either the `Authorization: Bearer <token>` header
     * or a `?token=...` query param. The token is generated lazily on first
     * read of [NotifPrefs.webhookToken] and surfaces in Settings → Network
     * (planned) + via the web companion. 401 on mismatch, 415 on non-POST.
     *
     * Body: `{title, body, source?, deeplink?}` — all fields optional except
     * we require at least one of title/body to be non-empty. Anything else in
     * the JSON is ignored.
     */
    private fun handleNotifyPost(session: IHTTPSession): Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return errorResponse(Response.Status.METHOD_NOT_ALLOWED, "POST required")
        }
        val prefs = com.r1.launcher.notifications.NotifPrefs.get(ctx)
        val expected = prefs.webhookToken
        val auth = session.headers["authorization"].orEmpty()
        val headerToken = if (auth.startsWith("Bearer ", ignoreCase = true)) {
            auth.removePrefix("Bearer ").trim()
        } else null
        val queryToken = session.parameters["token"]?.firstOrNull()
        val provided = headerToken ?: queryToken
        if (provided.isNullOrBlank() || provided != expected) {
            return errorResponse(Response.Status.UNAUTHORIZED, "bad token")
        }

        // Read the POST body. NanoHTTPD wants us to parseBody first; the result
        // lands in `files["postData"]` when Content-Type is application/json or
        // anything not form-encoded.
        val files = mutableMapOf<String, String>()
        runCatching { session.parseBody(files) }
        val rawBody = files["postData"]
            ?: session.parameters["body"]?.firstOrNull()
            ?: ""
        if (rawBody.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "empty body")
        }
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return errorResponse(Response.Status.BAD_REQUEST, "bad json")
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80)
        val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400)
        val source = obj["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "webhook"
        val deeplink = obj["deeplink"]?.jsonPrimitive?.contentOrNull
        if (title.isBlank() && body.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "title or body required")
        }
        // Hop to UI thread — host.notify mutates Compose state.
        ui.post { host.notify(source, title, body, deeplink) }
        val payload = buildJsonObject { put("ok", true) }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            json.encodeToString(JsonElement.serializer(), payload),
        )
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

    /** New notification landed — push to every web client so the companion
     *  panel can mirror the on-device badge / list. Cheap no-op when no one
     *  is connected. */
    fun broadcastNotification(n: com.r1.launcher.notifications.Notification) {
        if (sockets.isEmpty()) return
        val payload = buildJsonObject {
            put("id", n.id)
            put("source", n.source)
            put("title", n.title)
            put("body", n.body)
            put("timestamp", n.timestamp)
            put("read", n.read)
            n.deeplink?.let { put("deeplink", it) }
        }
        sockets.toList().forEach { it.sendEvent("notification", payload) }
    }

}

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
import java.util.concurrent.ConcurrentHashMap
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
 * Auth (two-tier, phone-friendly):
 *  - The user-facing credential is a 4-digit `panelPasscode` (default 0000,
 *    editable in Settings). The phone POSTs it to `/api/auth`; on match the
 *    server returns the strong, long-lived `panelToken`. The phone stores the
 *    token in sessionStorage and uses it on every WS connect + sensitive HTTP
 *    request (Bearer or `?t=`).
 *  - The 4-digit keyspace is brute-forceable in seconds without a guard, so
 *    `/api/auth` is rate-limited per remote IP — 5 failures within a 60s
 *    sliding window triggers a 30s lockout. The map is in-memory (cleared on
 *    server restart). See [authAttempts] / [handleAuthPost].
 *  - SPA assets (/, /app.js, /style.css, /i18n.js) are intentionally ungated —
 *    chicken-and-egg, the page has to load to render the passcode prompt.
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
        // /api/auth rate-limit knobs — chosen so a brute-forcer can only test
        // ~10 codes/minute (5 attempts, then 30s lockout). At that rate, an
        // exhaustive 10k-code search takes ~16 hours — well beyond any human
        // attention threshold. Tighter values would frustrate fat-fingering.
        private const val AUTH_MAX_ATTEMPTS = 5
        private const val AUTH_WINDOW_MS = 60_000L
        private const val AUTH_LOCKOUT_MS = 30_000L
    }

    /** Per-remote-IP failed-attempt log. Reset on each successful auth, and
     *  pruned lazily on every new attempt. The map itself is intentionally
     *  in-memory and ephemeral — a server restart wipes lockouts, which is
     *  fine because rebooting requires physical access in our threat model. */
    private val authAttempts = ConcurrentHashMap<String, AuthAttemptState>()

    private class AuthAttemptState {
        val failures: ArrayDeque<Long> = ArrayDeque()
        var lockUntilMs: Long = 0L
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
                // SPA assets — ungated, see class-doc auth section
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")
                uri.startsWith("/static/media/") -> serveMediaStatic(session, uri.removePrefix("/static/media/"))
                uri.startsWith("/static/") -> serveAsset("web/" + uri.removePrefix("/static/"), guessMime(uri))
                uri == "/app.js" -> serveAsset("web/app.js", "application/javascript")
                uri == "/i18n.js" -> serveAsset("web/i18n.js", "application/javascript")
                uri == "/style.css" -> serveAsset("web/style.css", "text/css")
                uri == "/favicon.ico" -> notFound()
                // Passcode exchange — rate-limited, see [handleAuthPost]
                uri == "/api/auth" -> handleAuthPost(session)
                // Notify endpoint has its own bearer-token auth (see [handleNotifyPost])
                uri == "/api/notify" -> handleNotifyPost(session)
                // Sensitive endpoints — gated on the panel token
                uri == "/api/state" -> requirePanelToken(session)
                    ?: jsonResponse(WebRpc.buildSnapshot(state, ctx))
                uri.startsWith("/api/transcriber/audio/") -> requirePanelToken(session)
                    ?: serveTranscriberAudio(uri.removePrefix("/api/transcriber/audio/"))
                uri.startsWith("/api/transcriber/transcript/") -> requirePanelToken(session)
                    ?: serveTranscriberTranscript(uri.removePrefix("/api/transcriber/transcript/"))
                else -> notFound()
            }
        }.getOrElse { e ->
            Log.w(TAG, "serve $uri failed: ${e.message}")
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    /** Validate the panel token from this request. Accepts the token in either
     *  the `Authorization: Bearer <token>` header or a `?t=<token>` query
     *  parameter. Returns `null` when the token is valid (caller proceeds to
     *  serve the response), or a 401 Response when it's missing/wrong. Uses
     *  the null-as-success / Response-as-failure shape so call sites can use
     *  the Elvis operator `requirePanelToken(session) ?: <serve>`. */
    private fun requirePanelToken(session: IHTTPSession): Response? {
        val expected = com.r1.launcher.notifications.NotifPrefs.get(ctx).panelToken
        val auth = session.headers["authorization"].orEmpty()
        val headerToken = if (auth.startsWith("Bearer ", ignoreCase = true)) {
            auth.removePrefix("Bearer ").trim()
        } else null
        val queryToken = session.parameters["t"]?.firstOrNull()
            ?: session.parameters["token"]?.firstOrNull()
        val provided = headerToken ?: queryToken
        if (provided.isNullOrBlank() || provided != expected) {
            return errorResponse(Response.Status.UNAUTHORIZED, "bad panel token")
        }
        return null
    }

    /** Exchange a 4-digit passcode for the long panel token.
     *
     *  Body: `{"passcode": "1234"}`. On match returns `{"ok":true,"token":"..."}`,
     *  the phone stashes the token in sessionStorage and uses it for all
     *  subsequent WS + HTTP calls. On mismatch returns 401 with the number of
     *  attempts remaining before lockout. Once the per-IP attempt count hits
     *  [AUTH_MAX_ATTEMPTS] within [AUTH_WINDOW_MS], that IP is locked out for
     *  [AUTH_LOCKOUT_MS] and gets 429 + a `retry_after_ms` field so the SPA
     *  can render a countdown. */
    private fun handleAuthPost(session: IHTTPSession): Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return errorResponse(Response.Status.METHOD_NOT_ALLOWED, "POST required")
        }
        val ip = session.remoteIpAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val attemptState = authAttempts.computeIfAbsent(ip) { AuthAttemptState() }

        synchronized(attemptState) {
            if (attemptState.lockUntilMs > now) {
                val retryMs = attemptState.lockUntilMs - now
                Log.w(TAG, "auth from $ip BLOCKED — lockout ${retryMs}ms left")
                val body = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                    put("ok", false)
                    put("error", "rate limited")
                    put("retry_after_ms", retryMs)
                })
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS, "application/json", body,
                )
            }
        }

        val files = mutableMapOf<String, String>()
        runCatching { session.parseBody(files) }
        val rawBody = files["postData"]
            ?: session.parameters["body"]?.firstOrNull()
            ?: ""
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return errorResponse(Response.Status.BAD_REQUEST, "bad json")
        val passcode = obj["passcode"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val prefs = com.r1.launcher.notifications.NotifPrefs.get(ctx)
        val expected = prefs.panelPasscode

        if (passcode.length == 4 && passcode == expected) {
            // Successful login — clear state so retry counter doesn't bleed
            // into the next session.
            authAttempts.remove(ip)
            Log.i(TAG, "auth ok from $ip")
            val body = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("ok", true)
                put("token", prefs.panelToken)
            })
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }

        synchronized(attemptState) {
            // Drop attempts that fell out of the sliding window before recording
            // the new one — the window is rolling, not fixed.
            val cutoff = now - AUTH_WINDOW_MS
            while (attemptState.failures.isNotEmpty() && attemptState.failures.first() < cutoff) {
                attemptState.failures.removeFirst()
            }
            attemptState.failures.addLast(now)
            val attemptsLeft = AUTH_MAX_ATTEMPTS - attemptState.failures.size
            if (attemptState.failures.size >= AUTH_MAX_ATTEMPTS) {
                attemptState.lockUntilMs = now + AUTH_LOCKOUT_MS
                attemptState.failures.clear()
                Log.w(TAG, "auth from $ip LOCKED OUT for ${AUTH_LOCKOUT_MS}ms")
                val body = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                    put("ok", false)
                    put("error", "rate limited")
                    put("retry_after_ms", AUTH_LOCKOUT_MS)
                })
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS, "application/json", body,
                )
            }
            Log.w(TAG, "auth FAIL from $ip — attemptsLeft=$attemptsLeft")
            val body = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("ok", false)
                put("error", "bad passcode")
                put("attempts_left", attemptsLeft)
            })
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json", body,
            )
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
        uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
        uri.endsWith(".mp4") -> "video/mp4"
        uri.endsWith(".svg") -> "image/svg+xml"
        uri.endsWith(".json") -> "application/json"
        uri.endsWith(".ttf") -> "font/ttf"
        uri.endsWith(".woff") -> "font/woff"
        uri.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun serveMediaStatic(session: IHTTPSession, rest: String): Response {
        if (rest == "_play_placeholder") {
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120">
<rect width="120" height="120" fill="#222"/>
<polygon points="45,30 90,60 45,90" fill="#FF6A00"/>
</svg>""".trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg)
        }

        val captures = java.io.File(ctx.filesDir, "captures")
        val file: java.io.File = if (rest.startsWith(".thumbs/")) {
            java.io.File(captures, "videos/$rest")
        } else {
            val img = java.io.File(captures, "images/$rest")
            val vid = java.io.File(captures, "videos/$rest")
            when {
                img.exists() -> img
                vid.exists() -> vid
                else -> img
            }
        }
        if (!file.exists() || !file.isFile) return notFound()

        val mime = guessMime(file.name)
        val download = session.parameters["download"]?.firstOrNull() == "1"
        val resp = newChunkedResponse(Response.Status.OK, mime, file.inputStream())
        if (download) {
            resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        }
        return resp
    }

    // --- WebSocket routing ---

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri ?: ""
        // Validate the panel token from `?t=<token>` query param. NanoWSD
        // doesn't let us reject a handshake with 401 — by the time
        // openWebSocket is called, the upgrade has already been accepted.
        // So a rejected client gets an immediate close frame in onOpen; the
        // browser sees the WS connect "succeed" then close, which the SPA
        // surfaces as offline. The token is never echoed in logs.
        val expected = com.r1.launcher.notifications.NotifPrefs.get(ctx).panelToken
        val provided = handshake.parameters["t"]?.firstOrNull()
            ?: handshake.parameters["token"]?.firstOrNull()
        val authed = !provided.isNullOrBlank() && provided == expected
        if (!authed) {
            Log.w(TAG, "ws open $uri REJECTED — bad/missing panel token")
            return RejectedSocket(handshake)
        }
        val sock = RpcSocket(handshake)
        Log.i(TAG, "ws open $uri (${sockets.size + 1} live)")
        return sock
    }

    /** WebSocket that closes itself immediately on open. Used to reject
     *  handshakes that failed token validation — see [openWebSocket]. The
     *  RFC 6455 close code 1008 (PolicyViolation) is the closest fit. */
    inner class RejectedSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            runCatching {
                close(WebSocketFrame.CloseCode.PolicyViolation, "bad panel token", false)
            }
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {}
        override fun onMessage(message: WebSocketFrame) {}
        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) {}
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

    /** New notification landed — push to every web client so the companion
     *  panel can mirror the on-device badge / list. Cheap no-op when no one
     *  is connected. */
    fun broadcastCaptureAdded(item: com.r1.launcher.media.CaptureItem) {
        // Real impl in Task 7
    }

    fun broadcastCaptureRecording(recording: Boolean, startedAt: Long) {
        // Real impl in Task 7
    }

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

package com.r1.launcher.openclaw

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Minimal openclaw gateway client: opens one OkHttp WebSocket, sends JSON-RPC
 * `connect` (Ed25519-signed), then `chat.subscribe` + `chat.history`, and pumps
 * `chat`/`agent` events to the UI. Send path: `chat.send` with optional audio
 * attachment. v1 closes the socket on panel exit; reconnects on next open.
 *
 * NOT a full port — no mDNS, no TLS pinning, no token rotation, no manual auth.
 * Errors degrade to onState(error="...") so the UI can show a message.
 */
class GatewaySession(
    context: Context,
    private val prefs: OpenClawPrefs,
) {
    sealed class State {
        object Idle : State()
        object Connecting : State()
        data class Live(val sessionKey: String) : State()
        data class Switching(val sessionKey: String) : State()
        data class Error(val message: String) : State()
    }

    private val app = context.applicationContext
    private val identityStore = DeviceIdentityStore(app)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + parentJob)

    @Volatile private var socket: WebSocket? = null
    /** Seeded from prefs so a returning user lands back in the thread they last used. */
    @Volatile private var sessionKey: String = prefs.selectedSessionKey?.takeUnless { it.isBlank() } ?: "main"
    /** Server-issued main session key from connect snapshot. */
    @Volatile private var cachedMainSessionKey: String = prefs.lastMainSessionKey?.takeUnless { it.isBlank() } ?: "main"
    /** Monotonic switch token — onHistory callbacks tagged with stale tokens are dropped. */
    private val switchToken = java.util.concurrent.atomic.AtomicLong(0L)
    private val isClosed = AtomicBoolean(false)
    /** True only when the user explicitly called stop(). Prevents auto-reconnect. */
    private val userStopped = AtomicBoolean(false)
    /** Current reconnect backoff delay in ms. Reset on successful connect. */
    @Volatile private var reconnectDelayMs = 2_000L
    private val MAX_RECONNECT_DELAY_MS = 16_000L

    var onState: (State) -> Unit = {}
    /** Streaming `delta` events — text accumulates server-side; latest text is sent each tick. */
    var onChatDelta: (runId: String?, text: String) -> Unit = { _, _ -> }
    /** Terminal `final`/`aborted`/`error` events — UI should refresh chat.history. */
    var onChatTerminal: (runId: String?, state: String, errorMessage: String?) -> Unit = { _, _, _ -> }
    var onHistory: (List<ChatMessage>) -> Unit = {}
    /** Available threads from `sessions.list`. Empty list = unknown / not yet fetched. */
    var onSessions: (List<SessionEntry>) -> Unit = {}
    /** Server-snapshot main session key, fired once per `connect` after handshake. */
    var onMainSessionKey: (String) -> Unit = {}

    fun start() {
        if (socket != null) return
        isClosed.set(false)
        userStopped.set(false)
        val url = prefs.gatewayUrl ?: run {
            onState(State.Error("no gateway url"))
            return
        }
        val wsUrl = toWsUrl(url) ?: run {
            onState(State.Error("bad gateway url: $url"))
            return
        }
        onState(State.Connecting)
        val req = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(req, Listener())
    }

    fun stop() {
        userStopped.set(true)
        if (isClosed.compareAndSet(false, true)) failPending("session stopped")
        runCatching { socket?.close(1000, "panel closed") }
        socket = null
        // Cancel in-flight launches but keep the SupervisorJob alive — calling
        // `scope.cancel()` would make any subsequent start() a silent no-op
        // because the scope itself would be dead.
        parentJob.cancelChildren()
    }

    /** Schedule a reconnect unless the user explicitly stopped the session. */
    private fun scheduleReconnect() {
        if (userStopped.get()) return
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        android.util.Log.i("OpenClaw", "reconnecting in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (!userStopped.get()) {
                socket = null
                start()
            }
        }
    }

    /** Drain pending JSON-RPC waiters with an error so callers don't hang until timeout. */
    private fun failPending(reason: String) {
        val snapshot = pending.toMap()
        pending.clear()
        snapshot.values.forEach {
            runCatching { it.completeExceptionally(IllegalStateException(reason)) }
        }
    }

    fun send(
        text: String,
        audioBase64: String? = null,
        onAck: (success: Boolean, runId: String?, error: String?) -> Unit = { _, _, _ -> },
    ) {
        scope.launch {
            try {
                val params = buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("message", JsonPrimitive(text))
                    put("thinking", JsonPrimitive("off"))
                    put("timeoutMs", JsonPrimitive(60_000L))
                    put("idempotencyKey", JsonPrimitive(UUID.randomUUID().toString()))
                    if (audioBase64 != null) {
                        put("attachments", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("audio"))
                                put("mimeType", JsonPrimitive("audio/wav"))
                                put("fileName", JsonPrimitive("voice.wav"))
                                put("content", JsonPrimitive(audioBase64))
                            })
                        })
                    }
                }
                val res = request("chat.send", params, timeoutMs = 65_000L)
                val ok = res["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok) {
                    val err = (res["error"] as? JsonObject)?.let { e ->
                        e["message"]?.jsonPrimitive?.contentOrNull
                            ?: e["code"]?.jsonPrimitive?.contentOrNull
                    }
                    android.util.Log.w("OpenClaw", "chat.send rejected: $res")
                    onState(State.Error("send rejected: ${err ?: "unknown"}"))
                    onAck(false, null, err)
                } else {
                    val runId = (res["payload"] as? JsonObject)
                        ?.get("runId")?.jsonPrimitive?.contentOrNull
                    android.util.Log.v("OpenClaw", "chat.send accepted runId=$runId")
                    onAck(true, runId, null)
                }
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "chat.send threw", t)
                onState(State.Error(t.message ?: "send failed"))
                onAck(false, null, t.message)
            }
        }
    }

    fun refreshHistory() {
        scope.launch {
            val token = switchToken.get()
            try {
                val hist = request("chat.history", buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                }, timeoutMs = 15_000L)
                val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                    .orEmpty()
                if (token == switchToken.get()) onHistory(msgs)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "refreshHistory threw", t)
            }
        }
    }

    /**
     * Switch the active thread without dropping the WebSocket. Re-subscribes via
     * `node.event(chat.subscribe)` and re-fetches history for the new key. Stale
     * switches (rapid pill taps) are tagged with a monotonic token; only the
     * latest token's history is delivered to the UI.
     */
    fun switchSession(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isEmpty() || trimmed == sessionKey) return
        sessionKey = trimmed
        prefs.selectedSessionKey = trimmed
        val token = switchToken.incrementAndGet()
        onState(State.Switching(trimmed))
        scope.launch {
            try {
                request("node.event", buildJsonObject {
                    put("event", JsonPrimitive("chat.subscribe"))
                    put("payloadJSON", JsonPrimitive(buildJsonObject {
                        put("sessionKey", JsonPrimitive(trimmed))
                    }.toString()))
                }, timeoutMs = 10_000L)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "switch subscribe threw", t)
            }
            if (token != switchToken.get()) return@launch
            try {
                val hist = request("chat.history", buildJsonObject {
                    put("sessionKey", JsonPrimitive(trimmed))
                }, timeoutMs = 15_000L)
                val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                    .orEmpty()
                if (token == switchToken.get()) {
                    onHistory(msgs)
                    onState(State.Live(trimmed))
                }
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "switch history threw", t)
                if (token == switchToken.get()) {
                    onState(State.Error(t.message ?: "switch failed"))
                }
            }
        }
    }

    /**
     * Fetch the list of available threads. Tries direct `sessions.list` first,
     * falls back to `node.event(sessions.list)` on permission errors (the
     * bootstrap profile may not grant `operator.admin` for direct invocation,
     * mirroring the same constraint that affects `chat.subscribe`).
     */
    fun listSessions() {
        scope.launch {
            try {
                val params = buildJsonObject {
                    put("includeGlobal", JsonPrimitive(true))
                    put("includeUnknown", JsonPrimitive(false))
                    put("limit", JsonPrimitive(50))
                }
                var res = runCatching {
                    request("sessions.list", params, timeoutMs = 10_000L)
                }.getOrNull()
                val ok = res?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok) {
                    // Retry wrapped — same pattern we use for chat.subscribe.
                    res = runCatching {
                        request("node.event", buildJsonObject {
                            put("event", JsonPrimitive("sessions.list"))
                            put("payloadJSON", JsonPrimitive(params.toString()))
                        }, timeoutMs = 10_000L)
                    }.getOrNull()
                }
                val arr = (res?.get("payload") as? JsonObject)?.get("sessions") as? JsonArray
                val parsed = arr?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val key = obj["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (key.isEmpty()) return@mapNotNull null
                    val updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                    SessionEntry(key = key, updatedAtMs = updatedAt, displayName = displayName)
                }.orEmpty()
                onSessions(parsed)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "listSessions threw", t)
                onSessions(emptyList())
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Don't send connect yet — gateway sends `connect.challenge` event
            // with a server-issued nonce that must be echoed back. handleFrame
            // triggers handshake(nonce) once the challenge arrives.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleFrame(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (isClosed.compareAndSet(false, true)) failPending("ws closed: $reason")
            if (!userStopped.get()) {
                onState(State.Connecting)
                scheduleReconnect()
            } else {
                onState(State.Idle)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (isClosed.compareAndSet(false, true)) {
                failPending("ws failure: ${t.message ?: t.javaClass.simpleName}")
            }
            if (!userStopped.get()) {
                onState(State.Connecting)
                scheduleReconnect()
            } else {
                onState(State.Error(t.message ?: "ws failure"))
            }
        }
    }

    private suspend fun handshake(nonce: String) {
        try {
            val identity = identityStore.loadOrCreate()
            val signedAt = System.currentTimeMillis()
            // Connect as operator UI — `node` role can't subscribe/send chat.
            // Matches openclaw android ConnectionManager.kt:160. The pairing
            // setup bootstrap profile whitelists these operator.* scopes.
            val role = "operator"
            val scopes = listOf("operator.read", "operator.write", "operator.talk.secrets")
            val mode = "ui"
            val clientId = "openclaw-android"
            val platform = "android"
            val deviceFamily = "rabbit-r1"

            val deviceTokenForSig = prefs.deviceToken ?: prefs.sharedToken ?: prefs.bootstrapToken
            val payloadStr = DeviceIdentityStore.buildAuthPayloadV3(
                deviceId = identity.deviceId,
                clientId = clientId,
                clientMode = mode,
                role = role,
                scopes = scopes,
                signedAtMs = signedAt,
                token = deviceTokenForSig,
                nonce = nonce,
                platform = platform,
                deviceFamily = deviceFamily,
            )
            val signature = identityStore.signPayload(payloadStr, identity)
            val publicKey = identityStore.publicKeyBase64Url(identity)

            val authObj = when {
                prefs.deviceToken != null -> buildJsonObject {
                    put("token", JsonPrimitive(prefs.deviceToken))
                    put("deviceToken", JsonPrimitive(prefs.deviceToken))
                }
                prefs.sharedToken != null -> buildJsonObject {
                    put("token", JsonPrimitive(prefs.sharedToken))
                }
                prefs.bootstrapToken != null -> buildJsonObject {
                    put("bootstrapToken", JsonPrimitive(prefs.bootstrapToken))
                }
                else -> null
            }

            val params = buildJsonObject {
                put("minProtocol", JsonPrimitive(3))
                put("maxProtocol", JsonPrimitive(3))
                put("client", buildJsonObject {
                    put("id", JsonPrimitive(clientId))
                    put("displayName", JsonPrimitive("R1 Launcher"))
                    put("version", JsonPrimitive("3.1.0"))
                    put("platform", JsonPrimitive(platform))
                    put("mode", JsonPrimitive(mode))
                    put("instanceId", JsonPrimitive(prefs.instanceId))
                    put("deviceFamily", JsonPrimitive(deviceFamily))
                })
                put("role", JsonPrimitive(role))
                if (scopes.isNotEmpty()) {
                    put("scopes", buildJsonArray { scopes.forEach { add(JsonPrimitive(it)) } })
                }
                // Operator/UI connects send no caps and no commands — those
                // are for node-role hosts. Matches openclaw ConnectionManager
                // buildOperatorConnectOptions.
                if (authObj != null) put("auth", authObj)
                if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                    put("device", buildJsonObject {
                        put("id", JsonPrimitive(identity.deviceId))
                        put("publicKey", JsonPrimitive(publicKey))
                        put("signature", JsonPrimitive(signature))
                        put("signedAt", JsonPrimitive(signedAt))
                        put("nonce", JsonPrimitive(nonce))
                    })
                }
            }

            val res = request("connect", params, timeoutMs = 15_000L)
            if (!(res["ok"]?.jsonPrimitive?.booleanOrNull ?: false)) {
                val err = (res["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                onState(State.Error("connect rejected: ${err ?: "unknown"}"))
                return
            }
            val payload = res["payload"] as? JsonObject
            val auth = payload?.get("auth") as? JsonObject
            val newDeviceToken = auth?.get("deviceToken")?.jsonPrimitive?.contentOrNull
            if (!newDeviceToken.isNullOrBlank()) {
                prefs.deviceToken = newDeviceToken
                // Bootstrap token is single-use; clear it now that we have a device token.
                prefs.bootstrapToken = null
            }
            val snap = payload?.get("snapshot") as? JsonObject
            val sessDefaults = snap?.get("sessionDefaults") as? JsonObject
            val main = sessDefaults?.get("mainSessionKey")?.jsonPrimitive?.contentOrNull
            if (!main.isNullOrBlank()) {
                cachedMainSessionKey = main
                prefs.lastMainSessionKey = main
                onMainSessionKey(main)
            }
            // Honor the persisted user pick over the snapshot main — otherwise
            // a returning user gets snapped back to "main" every connect.
            val persisted = prefs.selectedSessionKey?.takeUnless { it.isBlank() }
            sessionKey = when {
                !persisted.isNullOrBlank() -> persisted
                !main.isNullOrBlank() -> main
                else -> sessionKey
            }

            // Reset backoff on successful connect
            reconnectDelayMs = 2_000L
            onState(State.Live(sessionKey))

            // chat.subscribe is delivered via node.event (the openclaw client
            // does the same in ChatController.kt:294). The direct RPC route
            // requires operator.admin which the bootstrap profile doesn't grant.
            try {
                val sub = request("node.event", buildJsonObject {
                    put("event", JsonPrimitive("chat.subscribe"))
                    put("payloadJSON", JsonPrimitive(buildJsonObject {
                        put("sessionKey", JsonPrimitive(sessionKey))
                    }.toString()))
                }, timeoutMs = 10_000L)
                android.util.Log.i("OpenClaw", "node.event(chat.subscribe) -> $sub")
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "node.event(chat.subscribe) threw", t)
            }
            val initialToken = switchToken.get()
            try {
                val hist = request("chat.history", buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                }, timeoutMs = 15_000L)
                android.util.Log.i("OpenClaw", "chat.history ok=${hist["ok"]}")
                val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                    .orEmpty()
                if (initialToken == switchToken.get()) onHistory(msgs)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "chat.history threw", t)
            }
        } catch (t: Throwable) {
            onState(State.Error(t.message ?: "handshake failed"))
        }
    }

    private suspend fun handleFrame(text: String) {
        android.util.Log.v("OpenClaw", "<- ${text.take(500)}")
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "res" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
                pending.remove(id)?.complete(obj)
            }
            "event" -> {
                val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
                val payload = obj["payload"] as? JsonObject ?: return
                when (event) {
                    "connect.challenge" -> {
                        val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNull ?: return
                        scope.launch { handshake(nonce) }
                    }
                    "chat" -> handleChatEvent(payload)
                }
            }
        }
    }

    /**
     * Mirrors the official client (`apps/android/.../ChatController.kt:340`):
     *   - `delta` → live preview text (UI shows as a single streaming line).
     *   - `final|aborted|error` → tell UI to refresh `chat.history`. Server is
     *     authoritative; this avoids races with slash commands, multi-operator
     *     scenarios, and out-of-order events.
     */
    private fun handleChatEvent(payload: JsonObject) {
        val state = payload["state"]?.jsonPrimitive?.contentOrNull ?: return
        val runId = payload["runId"]?.jsonPrimitive?.contentOrNull
        when (state) {
            "delta" -> {
                val msg = payload["message"] as? JsonObject ?: return
                val deltaText = extractText(msg["content"] as? JsonArray)
                if (deltaText.isNotEmpty()) onChatDelta(runId, deltaText)
            }
            "final", "aborted", "error" -> {
                val errMsg = if (state == "error") {
                    payload["errorMessage"]?.jsonPrimitive?.contentOrNull
                } else null
                onChatTerminal(runId, state, errMsg)
            }
        }
    }

    private suspend fun request(method: String, params: JsonElement?, timeoutMs: Long): JsonObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("type", JsonPrimitive("req"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            if (params != null) put("params", params)
        }
        val ws = socket ?: throw IllegalStateException("not connected")
        val frameStr = frame.toString()
        android.util.Log.v("OpenClaw", "-> ${frameStr.take(500)}")
        if (!ws.send(frameStr)) {
            pending.remove(id)
            throw IllegalStateException("ws send failed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    private fun toWsUrl(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
    }
}

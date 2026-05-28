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
import java.net.URLEncoder
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
        /**
         * Emitted only when the connect RPC was rejected with a known
         * auth/token error code. The activity wipes pairing and routes
         * back to QR for this specific signal — generic Error events do
         * not, since they cover send-rejected, switch-failed, and other
         * recoverable runtime errors.
         */
        data class AuthExpired(val message: String) : State()
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
    /** Bumped on every connect() and on stop(). A Listener captures the value
     *  at open; once it no longer matches, that socket is superseded and its
     *  late onClosed/onFailure must NOT null `socket` or schedule a reconnect —
     *  otherwise a stale callback orphans a newer live socket or resurrects a
     *  stopped session. */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    /** Pending reconnect coroutine, cancelled by stop(). */
    @Volatile private var reconnectJob: Job? = null

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

    /** Explicit (re)start from the UI. This is the ONLY place that clears
     *  [userStopped] — the internal reconnect path must not, or a reconnect
     *  scheduled just before stop() would erase the user's intent and
     *  resurrect the session. */
    fun start() {
        if (socket != null) return
        reconnectJob?.cancel()
        isClosed.set(false)
        userStopped.set(false)
        connect()
    }

    /** Open a fresh socket under a new generation. Does NOT touch userStopped
     *  (see [start]). Used by both [start] and the reconnect path. */
    private fun connect() {
        val url = prefs.gatewayUrl ?: run {
            onState(State.Error("no gateway url"))
            return
        }
        val wsUrl = toWsUrl(url) ?: run {
            onState(State.Error("bad gateway url: $url"))
            return
        }
        onState(State.Connecting)
        val gen = generation.incrementAndGet()
        val req = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(req, Listener(gen))
    }

    fun stop() {
        userStopped.set(true)
        reconnectJob?.cancel()
        // Invalidate the current socket's callbacks so its imminent onClosed
        // (from the close below) can't schedule a reconnect.
        generation.incrementAndGet()
        if (isClosed.compareAndSet(false, true)) failPending("session stopped")
        runCatching { socket?.close(1000, "panel closed") }
        socket = null
        synchronized(mediaCache) { mediaCache.clear() }
        // Cancel in-flight launches but keep the SupervisorJob alive — calling
        // `scope.cancel()` would make any subsequent start() a silent no-op
        // because the scope itself would be dead.
        parentJob.cancelChildren()
    }

    /** Schedule a reconnect unless the user explicitly stopped the session. */
    private fun scheduleReconnect() {
        if (userStopped.get()) return
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        android.util.Log.i("OpenClaw", "reconnecting in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            // Re-check after the backoff. Don't reset userStopped here.
            if (!userStopped.get() && socket == null) {
                isClosed.set(false)
                connect()
            }
        }
    }

    /**
     * LRU cache for assistant image hydration. Without this, every
     * `chat.history` refresh re-downloads every image; switching sessions
     * back and forth re-fetches the same bytes from the gateway repeatedly.
     */
    private val mediaCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 32
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
        imageBase64: String? = null,
        onAck: (success: Boolean, runId: String?, error: String?) -> Unit = { _, _, _ -> },
    ) {
        // Capture before launch — sessionKey is volatile and a concurrent
        // switchSession() could otherwise route this message to the wrong
        // thread between launch and the buildJsonObject below.
        val targetKey = sessionKey
        scope.launch {
            try {
                val params = buildJsonObject {
                    put("sessionKey", JsonPrimitive(targetKey))
                    put("message", JsonPrimitive(text))
                    put("thinking", JsonPrimitive("off"))
                    put("timeoutMs", JsonPrimitive(60_000L))
                    put("idempotencyKey", JsonPrimitive(UUID.randomUUID().toString()))
                    if (audioBase64 != null || imageBase64 != null) {
                        put("attachments", buildJsonArray {
                            if (audioBase64 != null) {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("audio"))
                                    put("mimeType", JsonPrimitive("audio/wav"))
                                    put("fileName", JsonPrimitive("voice.wav"))
                                    put("content", JsonPrimitive(audioBase64))
                                })
                            }
                            if (imageBase64 != null) {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("image"))
                                    put("mimeType", JsonPrimitive("image/jpeg"))
                                    put("fileName", JsonPrimitive("r1-camera.jpg"))
                                    put("content", JsonPrimitive(imageBase64))
                                })
                            }
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
                    ?.map { hydrateAssistantMedia(it) }
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
                val direct = request("chat.subscribe", buildJsonObject {
                    put("sessionKey", JsonPrimitive(trimmed))
                }, timeoutMs = 10_000L)
                val ok = direct["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val errCode = (direct["error"] as? JsonObject)
                    ?.get("code")?.jsonPrimitive?.contentOrNull
                if (!ok && errCode == "METHOD_NOT_FOUND") {
                    request("node.event", buildJsonObject {
                        put("event", JsonPrimitive("chat.subscribe"))
                        put("payloadJSON", JsonPrimitive(buildJsonObject {
                            put("sessionKey", JsonPrimitive(trimmed))
                        }.toString()))
                    }, timeoutMs = 10_000L)
                }
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
                    ?.map { hydrateAssistantMedia(it) }
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
                // Gateway 2026.5.4 returns the array under `rows`; older
                // builds used `sessions`. Probe both so the panel works
                // across versions.
                val payload = res?.get("payload") as? JsonObject
                val arr = (payload?.get("rows") as? JsonArray)
                    ?: (payload?.get("sessions") as? JsonArray)
                    ?: (payload?.get("items") as? JsonArray)
                val parsed = arr?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    // 2026.5.4 emits `sessionKey`; pre-2026.5 used `key`.
                    val key = (obj["sessionKey"]?.jsonPrimitive?.contentOrNull
                        ?: obj["key"]?.jsonPrimitive?.contentOrNull)
                        ?.trim().orEmpty()
                    if (key.isEmpty()) return@mapNotNull null
                    val updatedAt = (obj["updatedAt"]?.jsonPrimitive?.contentOrNull
                        ?: obj["updatedAtMs"]?.jsonPrimitive?.contentOrNull
                        ?: obj["lastActivityAt"]?.jsonPrimitive?.contentOrNull)?.toLongOrNull()
                    val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                        ?: obj["title"]?.jsonPrimitive?.contentOrNull
                    SessionEntry(key = key, updatedAtMs = updatedAt, displayName = displayName)
                }.orEmpty()
                android.util.Log.i("OpenClaw", "sessions.list parsed=${parsed.size} arrayKey=" +
                    when {
                        payload?.get("rows") is JsonArray -> "rows"
                        payload?.get("sessions") is JsonArray -> "sessions"
                        payload?.get("items") is JsonArray -> "items"
                        else -> "none"
                    })
                onSessions(parsed)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "listSessions threw", t)
                onSessions(emptyList())
            }
        }
    }

    /**
     * Compact a thread's history server-side — gateway summarizes old messages
     * into a single synthetic block, freeing context tokens while keeping the
     * thread alive. SDK signature is `session.compact({ maxLines })`. Refreshes
     * `chat.history` on success so the panel shows the post-compaction state.
     *
     * `sessions.compact` is `operator.admin`-scoped upstream (core-descriptors
     * line 143); the bootstrap pairing profile we use only grants read/write/
     * talk.secrets, so this RPC fails unless the gateway operator has
     * post-pair-elevated the device. Surface the real gateway error in that
     * case — the old `node.event(sessions.compact)` fallback can never
     * succeed for an operator-role client (`node.event` is `node`-scope)
     * and only ever produced a misleading "unauthorized role: operator" toast.
     */
    fun compactSession(targetKey: String = sessionKey, maxLines: Int = 200, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                val params = buildJsonObject {
                    put("sessionKey", JsonPrimitive(targetKey))
                    put("maxLines", JsonPrimitive(maxLines))
                }
                val res = runCatching { request("sessions.compact", params, timeoutMs = 30_000L) }.getOrNull()
                val ok = res?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false
                val errMsg = (res?.get("error") as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                if (ok) {
                    runCatching {
                        val hist = request("chat.history", buildJsonObject {
                            put("sessionKey", JsonPrimitive(targetKey))
                        }, timeoutMs = 15_000L)
                        val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                            ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                            ?.map { hydrateAssistantMedia(it) }
                            .orEmpty()
                        onHistory(msgs)
                    }
                }
                onDone(ok, errMsg)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "compactSession threw", t)
                onDone(false, t.message)
            }
        }
    }

    /**
     * Reset a thread's context server-side — wipes the transcript entirely
     * (the gateway's `sessions.reset` maintenance op). Destructive: the
     * conversation is gone after this. Refreshes `chat.history` so the
     * panel reflects the empty state.
     *
     * Same admin-scope caveat as `compactSession`: `sessions.reset` is
     * `operator.admin` upstream, unreachable from a bootstrap-paired
     * operator client unless the gateway elevates the device. The launcher's
     * "clear context" button avoids calling this in normal cases by
     * switching to a fresh thread key instead (see `openClawClearContext`).
     */
    fun resetSession(targetKey: String = sessionKey, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                val params = buildJsonObject {
                    put("sessionKey", JsonPrimitive(targetKey))
                }
                val res = runCatching { request("sessions.reset", params, timeoutMs = 15_000L) }.getOrNull()
                val ok = res?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false
                val errMsg = (res?.get("error") as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                if (ok) {
                    runCatching {
                        val hist = request("chat.history", buildJsonObject {
                            put("sessionKey", JsonPrimitive(targetKey))
                        }, timeoutMs = 15_000L)
                        val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                            ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                            ?.map { hydrateAssistantMedia(it) }
                            .orEmpty()
                        onHistory(msgs)
                    }
                }
                onDone(ok, errMsg)
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "resetSession threw", t)
                onDone(false, t.message)
            }
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        /** True only while this socket is still the current one. A superseded
         *  socket (newer connect, or stop()) must not mutate shared state. */
        private fun current(): Boolean = gen == generation.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Don't send connect yet — gateway sends `connect.challenge` event
            // with a server-issued nonce that must be echoed back. handleFrame
            // triggers handshake(nonce) once the challenge arrives.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current()) return
            scope.launch { handleFrame(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return // stale socket — don't touch `socket`/reconnect
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
            if (!current()) return // stale socket — don't touch `socket`/reconnect
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
            // Connect as operator UI — `node` role can't subscribe/send chat.
            // Matches openclaw android ConnectionManager.kt:160. The pairing
            // setup bootstrap profile whitelists these operator.* scopes.
            // Asking for scopes outside the bound profile (e.g. operator.admin,
            // operator.pairing) fails with `bootstrap_token_invalid` because
            // the gateway uses sameStringSet against the bound set since
            // GHSA-gg9v-mgcp-v6m7 (commit a600c72).
            val scopes = listOf("operator.read", "operator.write", "operator.talk.secrets")
            val mode = "ui"
            val clientId = "openclaw-android"
            val platform = "android"
            val deviceFamily = "rabbit-r1"

            // Snapshot tokens once so the signed payload and the auth field
            // can never disagree if prefs are mutated mid-handshake (e.g. a
            // token rotation racing with the connect RPC).
            val devToken = prefs.deviceToken
            val shrToken = prefs.sharedToken
            val bsToken = prefs.bootstrapToken
            val deviceTokenForSig = devToken ?: shrToken ?: bsToken
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
                devToken != null -> buildJsonObject {
                    put("token", JsonPrimitive(devToken))
                    put("deviceToken", JsonPrimitive(devToken))
                }
                shrToken != null -> buildJsonObject {
                    put("token", JsonPrimitive(shrToken))
                }
                bsToken != null -> buildJsonObject {
                    put("bootstrapToken", JsonPrimitive(bsToken))
                }
                else -> null
            }

            val params = buildJsonObject {
                put("minProtocol", JsonPrimitive(4))
                put("maxProtocol", JsonPrimitive(4))
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
                val errObj = res["error"] as? JsonObject
                val errMsg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
                val details = errObj?.get("details") as? JsonObject
                val authReason = details?.get("authReason")?.jsonPrimitive?.contentOrNull
                val detailCode = details?.get("code")?.jsonPrimitive?.contentOrNull
                val isAuthFailure = authReason != null ||
                    (detailCode != null && detailCode.startsWith("AUTH_"))
                val full = "connect rejected: ${errMsg ?: "unknown"}"
                if (isAuthFailure) onState(State.AuthExpired(full))
                else onState(State.Error(full))
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
            // Exception: pre-2026.5 prefs hold the literal "main"; the new
            // gateway emits keys like "agent:main:main". Migrate by adopting
            // the snapshot main in that case.
            val persisted = prefs.selectedSessionKey?.takeUnless { it.isBlank() }
            val resolved = when {
                !persisted.isNullOrBlank() && persisted != "main" -> persisted
                !main.isNullOrBlank() -> main
                !persisted.isNullOrBlank() -> persisted
                else -> sessionKey
            }
            if (persisted == "main" && !main.isNullOrBlank() && main != "main") {
                prefs.selectedSessionKey = main
            }
            sessionKey = resolved

            // Reset backoff on successful connect
            reconnectDelayMs = 2_000L
            onState(State.Live(sessionKey))

            // Direct `chat.subscribe` is the 2026.5.x path. The legacy
            // `node.event` wrap is only useful when the gateway returns
            // METHOD_NOT_FOUND (i.e. a pre-2026.5 build that doesn't expose
            // the direct method). Any other error (missing scope,
            // unauthorized role, etc.) tells us the wrap will fail too —
            // skip it to cut wasted RPC + noisy logs.
            try {
                val direct = request("chat.subscribe", buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                }, timeoutMs = 10_000L)
                val ok = direct["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val errCode = (direct["error"] as? JsonObject)
                    ?.get("code")?.jsonPrimitive?.contentOrNull
                android.util.Log.i("OpenClaw", "chat.subscribe direct ok=$ok code=$errCode")
                if (!ok && errCode == "METHOD_NOT_FOUND") {
                    val sub = request("node.event", buildJsonObject {
                        put("event", JsonPrimitive("chat.subscribe"))
                        put("payloadJSON", JsonPrimitive(buildJsonObject {
                            put("sessionKey", JsonPrimitive(sessionKey))
                        }.toString()))
                    }, timeoutMs = 10_000L)
                    android.util.Log.i("OpenClaw", "node.event(chat.subscribe) fallback -> $sub")
                }
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "chat.subscribe threw", t)
            }
            val initialToken = switchToken.get()
            try {
                val hist = request("chat.history", buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                }, timeoutMs = 15_000L)
                android.util.Log.i("OpenClaw", "chat.history ok=${hist["ok"]}")
                val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                    ?.map { hydrateAssistantMedia(it) }
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
        android.util.Log.v("OpenClaw", "<- ${redactSensitive(text).take(2000)}")
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
                if (deltaText.isNotEmpty() && !isInternalMessage(deltaText)) onChatDelta(runId, deltaText)
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
        // Validate the socket BEFORE inserting into pending — otherwise a
        // throw here would leak the entry forever.
        val ws = socket ?: throw IllegalStateException("not connected")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("type", JsonPrimitive("req"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            if (params != null) put("params", params)
        }
        val frameStr = frame.toString()
        android.util.Log.v("OpenClaw", "-> ${redactSensitive(frameStr).take(2000)}")
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

    private fun hydrateAssistantMedia(msg: ChatMessage): ChatMessage {
        if (msg.imageBase64 != null || msg.imageSource.isNullOrBlank()) return msg
        val base64 = fetchAssistantImageBase64(msg.imageSource) ?: return msg
        return msg.copy(imageBase64 = base64, hasImage = true)
    }

    private fun fetchAssistantImageBase64(source: String): String? {
        synchronized(mediaCache) { mediaCache[source] }?.let { return it }
        val httpBase = toHttpUrl(prefs.gatewayUrl ?: return null) ?: return null
        val token = prefs.deviceToken ?: prefs.sharedToken ?: prefs.bootstrapToken
        val url = if (source.startsWith("/api/chat/media/outgoing/")) {
            httpBase.trimEnd('/') + source
        } else {
            val params = buildString {
                append("source=")
                append(URLEncoder.encode(source, "UTF-8"))
                if (!token.isNullOrBlank()) {
                    append("&token=")
                    append(URLEncoder.encode(token, "UTF-8"))
                }
            }
            httpBase.trimEnd('/') + "/__openclaw__/assistant-media?$params"
        }
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    android.util.Log.w("OpenClaw", "assistant media fetch failed ${res.code} for $source")
                    return null
                }
                val bytes = res.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                synchronized(mediaCache) { mediaCache[source] = encoded }
                encoded
            }
        }.onFailure {
            android.util.Log.w("OpenClaw", "assistant media fetch threw for $source", it)
        }.getOrNull()
    }

    /**
     * Strip token/signature/auth values out of a WS frame before logging.
     * Verbose-level frame logs are useful for protocol debugging but must
     * never carry the device's bootstrap/device tokens, the Ed25519
     * signature, or audio/image base64 payloads — those leak via logcat
     * captures on userdebug builds.
     */
    private fun redactSensitive(frame: String): String {
        var out = frame
        out = REDACT_STRING.replace(out) { m -> "\"${m.groupValues[1]}\":\"***\"" }
        out = REDACT_ATTACH_CONTENT.replace(out) { "\"content\":\"***\"" }
        return out
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

    private fun toHttpUrl(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            else -> "http://$trimmed"
        }
    }

    companion object {
        private val REDACT_STRING = Regex(
            "\"(token|deviceToken|bootstrapToken|sharedToken|signature|publicKey|nonce|Authorization)\"\\s*:\\s*\"[^\"]*\""
        )
        private val REDACT_ATTACH_CONTENT = Regex("\"content\"\\s*:\\s*\"[^\"]{40,}\"")
    }
}

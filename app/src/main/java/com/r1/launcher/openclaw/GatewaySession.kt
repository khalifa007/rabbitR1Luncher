package com.r1.launcher.openclaw

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    @Volatile private var sessionKey: String = "main"

    var onState: (State) -> Unit = {}
    var onChatStream: (ChatMessage, String /* state: delta|final|error|aborted */) -> Unit = { _, _ -> }
    var onHistory: (List<ChatMessage>) -> Unit = {}

    fun start() {
        if (socket != null) return
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
        runCatching { socket?.close(1000, "panel closed") }
        socket = null
        pending.values.forEach { runCatching { it.completeExceptionally(IllegalStateException("closed")) } }
        pending.clear()
        scope.coroutineContext.cancel()
    }

    fun send(text: String, audioBase64: String? = null) {
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
                } else {
                    android.util.Log.i("OpenClaw", "chat.send accepted: ${res["payload"]}")
                }
            } catch (t: Throwable) {
                android.util.Log.w("OpenClaw", "chat.send threw", t)
                onState(State.Error(t.message ?: "send failed"))
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
            onState(State.Idle)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            onState(State.Error(t.message ?: "ws failure"))
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
            if (!main.isNullOrBlank()) sessionKey = main

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
            try {
                val hist = request("chat.history", buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                }, timeoutMs = 15_000L)
                android.util.Log.i("OpenClaw", "chat.history ok=${hist["ok"]}")
                val msgs = ((hist["payload"] as? JsonObject)?.get("messages") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseHistoryMessage) }
                    .orEmpty()
                onHistory(msgs)
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
                    "chat" -> {
                        val pair = parseStreamMessage(payload) ?: return
                        onChatStream(pair.first, pair.second)
                    }
                }
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
        if (!ws.send(frame.toString())) {
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

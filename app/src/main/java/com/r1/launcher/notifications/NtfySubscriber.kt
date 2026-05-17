package com.r1.launcher.notifications

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outbound long-poll subscriber for a single ntfy.sh topic.
 *
 * ntfy.sh's `/json` endpoint streams newline-delimited JSON: one object per
 * line, indefinitely, with `{"event":"keepalive"}` frames every ~30s. We
 * hold the connection open with `readTimeout=0`, parse line-by-line, and
 * fire [onMessage] for each `event=="message"` frame. Keepalives advance
 * `?since=` cursor implicitly because they share the same monotonic id space.
 *
 * Reconnect: exponential backoff 2s → 16s on any non-user-initiated close.
 * Resume: `?since=<lastId>` on reconnect replays anything we missed during
 * the drop window (ntfy retains 12h by default).
 *
 * WifiLock: acquired in [start] / released in [stop] so the Wi-Fi radio
 * stays in high-perf mode even with screen off. If the launcher's web
 * server is also running it'll be holding its own lock — that's fine,
 * WifiLock is per-process and Android collapses the radio's actual power
 * state across all current holders.
 */
class NtfySubscriber(
    private val ctx: Context,
    private val prefs: NtfyPrefs,
) {

    enum class Status { DISABLED, CONNECTING, LIVE, RETRYING, ERROR }

    private val app = ctx.applicationContext
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Long-poll: no read timeout so an idle stream isn't torn down.
        // Mirrors the OpenClaw GatewaySession + ElevenLabs Realtime pattern.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var inflight: Call? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    private val userStopped = AtomicBoolean(false)
    @Volatile private var reconnectDelayMs = 2_000L
    private val MAX_RECONNECT_DELAY_MS = 16_000L
    @Volatile private var statusNow: Status = Status.DISABLED

    /** Fired on UI thread when a real ntfy message arrives. Keepalives /
     *  poll-open frames are filtered out before this fires. */
    var onMessage: (title: String, body: String) -> Unit = { _, _ -> }
    /** Fired on UI thread whenever connection status changes. */
    var onStatusChange: (Status) -> Unit = {}

    fun status(): Status = statusNow

    fun start() {
        if (!prefs.isConfigured()) {
            Log.w(TAG, "start refused: no topic")
            updateStatus(Status.DISABLED)
            return
        }
        if (inflight != null) {
            Log.d(TAG, "start: already running")
            return
        }
        userStopped.set(false)
        reconnectDelayMs = 2_000L
        acquireWifiLock()
        openConnection()
    }

    fun stop() {
        userStopped.set(true)
        runCatching { inflight?.cancel() }
        inflight = null
        releaseWifiLock()
        updateStatus(Status.DISABLED)
    }

    /** Apply a new topic at runtime. Tear down any open connection and
     *  re-open against the new URL. No-op if the topic didn't change. */
    fun applyTopicChange() {
        if (userStopped.get()) return
        runCatching { inflight?.cancel() }
        inflight = null
        if (prefs.isConfigured()) {
            // Fresh resume cursor — a different topic has its own id space.
            openConnection()
        } else {
            updateStatus(Status.DISABLED)
        }
    }

    private fun openConnection() {
        val topic = prefs.topic
        if (topic.isBlank()) {
            updateStatus(Status.DISABLED)
            return
        }
        val since = prefs.lastMessageId
        val url = buildString {
            append("https://ntfy.sh/")
            append(topic)
            append("/json")
            if (since.isNotBlank()) append("?since=").append(since)
        }
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/x-ndjson")
            .build()
        updateStatus(Status.CONNECTING)
        Log.i(TAG, "connecting to $url")
        val call = client.newCall(req)
        inflight = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight = null
                if (userStopped.get()) return
                Log.w(TAG, "stream failed: ${e.message}")
                updateStatus(Status.RETRYING)
                scheduleReconnect()
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "http ${response.code} — backing off")
                        updateStatus(Status.ERROR)
                        return
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        Log.w(TAG, "empty stream body")
                        return
                    }
                    updateStatus(Status.LIVE)
                    // Reset backoff after a successful connect+first byte.
                    reconnectDelayMs = 2_000L
                    while (!source.exhausted()) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (line.isEmpty()) continue
                        handleFrame(line)
                    }
                } catch (e: Throwable) {
                    if (!call.isCanceled()) Log.w(TAG, "stream parse failed: ${e.message}")
                } finally {
                    runCatching { response.close() }
                    inflight = null
                    if (!userStopped.get()) {
                        updateStatus(Status.RETRYING)
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    /** Parse a single NDJSON frame from ntfy.sh. Shape:
     *   {"id":"abc", "time":1700000000, "event":"message", "topic":"...",
     *    "message":"hello", "title":"optional", "priority":3, "tags":[]}
     *  Keepalives: `{"event":"keepalive"}` (no message field).
     *  Poll-open  : `{"event":"open"}` (sent once when stream opens). */
    private fun handleFrame(line: String) {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        val event = obj["event"]?.jsonPrimitive?.contentOrNull
        // Advance the resume cursor for *every* frame with an id, not just
        // messages — so an idle stream of keepalives still moves forward
        // and we don't replay them on reconnect.
        obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            prefs.lastMessageId = it
        }
        if (event != "message") return
        val body = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (body.isBlank() && title.isBlank()) return
        ui.post { onMessage(title, body) }
    }

    private fun scheduleReconnect() {
        if (userStopped.get()) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        Log.i(TAG, "reconnect in ${delay}ms")
        ui.postDelayed({
            if (!userStopped.get() && inflight == null) openConnection()
        }, delay)
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "r1.ntfy")
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
        }
    }

    private fun updateStatus(s: Status) {
        if (statusNow == s) return
        statusNow = s
        ui.post { onStatusChange(s) }
    }

    companion object {
        private const val TAG = "NtfySubscriber"
    }
}

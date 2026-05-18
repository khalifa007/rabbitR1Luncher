package com.r1.launcher.notifications

import android.content.Context
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
 * Power: no WifiLock. The launcher's package is whitelisted from Doze via
 * /system/etc/sysconfig/r1-launcher.xml (allow-in-power-save), which lets the
 * long-poll TCP stay open across screen-off and idle. The Wi-Fi firmware
 * wakes the CPU when inbound packets arrive on the open socket; a per-frame
 * PARTIAL_WAKE_LOCK in handleFrame keeps the CPU up long enough to finish
 * dispatch. Without the Doze exemption, holding a WifiLock alone wasn't
 * enough — Doze still firewalls DNS for non-whitelisted apps. With the
 * exemption, the WifiLock is redundant and was costing ~150-400mW of
 * radio idle current 24/7 on a 3000mAh device.
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
    // Short-lived wakelock acquired around each handleFrame so the kernel can't
    // re-suspend the CPU mid-delivery when the screen is off. Held for the
    // duration of one frame parse + UI post (microseconds in practice); 5s
    // timeout is a hard safety belt against a hung handler leaking the lock.
    // Single instance, setReferenceCounted(false) → acquire() is idempotent.
    private val frameWakeLock: android.os.PowerManager.WakeLock? by lazy {
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "r1.ntfy.frame").apply {
                setReferenceCounted(false)
            }
        }.getOrNull()
    }
    private val userStopped = AtomicBoolean(false)
    @Volatile private var reconnectDelayMs = 2_000L
    private val MAX_RECONNECT_DELAY_MS = 16_000L
    @Volatile private var statusNow: Status = Status.DISABLED
    // Per-connection generation token. Bumped before each cancel/open so a
    // stale callback from a previous Call can't null out the new inflight or
    // schedule a duplicate reconnect when topic-change / stop interleaves.
    // All mutations happen on the UI thread; reads happen on the OkHttp
    // dispatcher thread, hence @Volatile (single-writer pattern — no need for
    // AtomicInteger).
    @Volatile private var generation: Int = 0

    // Serializes the user-clear "bump generation + wipe cursor" pair with the
    // dispatcher-thread "check generation + write cursor" pair in handleFrame.
    // Without it, a frame whose check passed before the bump can still write a
    // stale id after the clear, undoing the cursor reset.
    private val cursorLock = Any()

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
        openConnection()
    }

    fun stop() {
        userStopped.set(true)
        // Bump generation before cancel so the cancelled call's onFailure
        // can't race past us and re-schedule a reconnect.
        generation++
        runCatching { inflight?.cancel() }
        inflight = null
        updateStatus(Status.DISABLED)
    }

    /** Apply a new topic at runtime. Tear down any open connection and
     *  re-open against the new URL. No-op if the topic didn't change. */
    fun applyTopicChange() {
        if (userStopped.get()) return
        // Bump first so the cancelled old call's callbacks see a stale gen
        // and bail out instead of nulling out the new call we're about to open.
        generation++
        runCatching { inflight?.cancel() }
        inflight = null
        if (prefs.isConfigured()) {
            // Fresh resume cursor — a different topic has its own id space.
            openConnection()
        } else {
            updateStatus(Status.DISABLED)
        }
    }

    /** User cleared the local notification list. Wipe the resume cursor
     *  and force the current stream closed so any in-flight backlog frames
     *  the dispatcher is mid-parsing are fenced out by the stale-generation
     *  guard before they can re-advance lastMessageId or post a UI callback.
     *  Next reconnect opens with no `?since=` — only frames sent after the
     *  clear will arrive. */
    fun resetCursorAndResync() {
        // Bump-and-clear under cursorLock so any handleFrame already past
        // its myGen check still sees a coherent state: either its cursor
        // write happens entirely before we bump+clear (then we overwrite
        // it), or entirely after (then its own gen-check inside the lock
        // sees the new generation and skips the write).
        val priorId = prefs.lastMessageId
        synchronized(cursorLock) {
            generation++
            prefs.lastMessageId = ""
        }
        Log.i(TAG, "resetCursorAndResync gen->$generation lastId '$priorId' -> '' cleared=${prefs.clearedAtMs}ms")
        if (userStopped.get()) return
        runCatching { inflight?.cancel() }
        inflight = null
        if (prefs.isConfigured()) openConnection()
        else updateStatus(Status.DISABLED)
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
        // Snapshot generation for this Call. Bump first so any prior call
        // can't shadow ours; capture the new value so callbacks can confirm
        // they belong to the current generation before mutating state.
        val myGen = ++generation
        inflight = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Stale callback (a newer connection has already taken over);
                // do nothing — the current owner manages inflight + reconnect.
                if (myGen != generation) return
                inflight = null
                if (userStopped.get()) return
                Log.w(TAG, "stream failed: ${e.message}")
                updateStatus(Status.RETRYING)
                scheduleReconnect()
            }
            override fun onResponse(call: Call, response: Response) {
                // If we've been superseded mid-flight (topic change, stop),
                // drain + close the body without touching shared state.
                if (myGen != generation) {
                    runCatching { response.close() }
                    return
                }
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
                        // Stale-stream guard: if a topic-change / stop happened
                        // mid-read, the network cancel will eventually trip the
                        // read but a few frames can slip through first. Bail
                        // before they advance lastMessageId (wrong topic's
                        // cursor) or post UI callbacks for a stream we no
                        // longer own.
                        if (myGen != generation) break
                        handleFrame(line, myGen)
                    }
                } catch (e: Throwable) {
                    if (!call.isCanceled()) Log.w(TAG, "stream parse failed: ${e.message}")
                } finally {
                    runCatching { response.close() }
                    // Final ownership check — the read loop runs long enough
                    // that a topic change could've happened during it.
                    if (myGen == generation) {
                        inflight = null
                        if (!userStopped.get()) {
                            updateStatus(Status.RETRYING)
                            scheduleReconnect()
                        }
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
    private fun handleFrame(line: String, myGen: Int) {
        // Pin the CPU awake just long enough to parse + dispatch this frame.
        // Without it, an inbound TCP packet that wakes the OkHttp callback can
        // race the kernel's idle suspension — the thread starts, the CPU goes
        // back to sleep mid-stack, and the frame is dropped or delayed.
        val wl = frameWakeLock
        runCatching { wl?.acquire(5_000L) }
        try {
            handleFrameLocked(line, myGen)
        } finally {
            runCatching { if (wl?.isHeld == true) wl.release() }
        }
    }

    private fun handleFrameLocked(line: String, myGen: Int) {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        val event = obj["event"]?.jsonPrimitive?.contentOrNull
        val frameId = obj["id"]?.jsonPrimitive?.contentOrNull
        val frameTimeSec = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        // Advance the resume cursor for *every* frame with an id, not just
        // messages — so an idle stream of keepalives still moves forward
        // and we don't replay them on reconnect. Gen-check + write happens
        // under cursorLock so it interlocks with resetCursorAndResync's
        // bump+clear; without the lock a frame whose loop-level gen check
        // already passed could still write a stale id over the user clear.
        if (frameId != null && frameId.isNotBlank()) {
            synchronized(cursorLock) {
                if (myGen == generation) prefs.lastMessageId = frameId
            }
        }
        if (event != "message") return
        val body = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (body.isBlank() && title.isBlank()) return
        // Time fence: ntfy.sh's `time` is Unix seconds. If the message was
        // sent before the user's most recent clear, drop it — defense in
        // depth against any path that could replay cached frames despite
        // our empty `?since=` (server quirks, in-flight buffers, scheduler
        // races we haven't accounted for). The fence is the only barrier
        // a frame can't pass by re-acquiring stale state.
        //
        // The +CLEAR_FENCE_GRACE_MS slack does two things at once:
        //   1. Closes the same-second truncation hole: `time` is the start
        //      of a 1 s bucket, so a frame sent at T+0.7s and a clear at
        //      T+0.3s would (without grace) wrongly drop the later frame.
        //   2. Absorbs device-vs-server clock skew (NTP lag, etc.) — we'd
        //      rather let a slightly-old replay through than swallow a
        //      legitimate post-clear message.
        // 5 s is well above realistic skew on a working device and tight
        // enough that an actual replay (often hours-old) is still caught.
        val clearedAtMs = prefs.clearedAtMs
        if (clearedAtMs > 0L && frameTimeSec > 0L) {
            val frameMs = frameTimeSec * 1000L
            if (frameMs + CLEAR_FENCE_GRACE_MS < clearedAtMs) {
                Log.i(TAG, "drop pre-clear frame id=$frameId time=${frameTimeSec}s cleared=${clearedAtMs}ms")
                return
            }
        } else if (clearedAtMs > 0L) {
            // Message frame with no usable `time` after a clear is anomalous —
            // ntfy.sh always stamps `time` on message frames per the API
            // spec. Log so a future regression surfaces in logcat instead
            // of silently sneaking past the fence.
            Log.w(TAG, "post-clear frame missing time id=$frameId — letting through")
        }
        Log.d(TAG, "deliver id=$frameId time=${frameTimeSec}s gen=$myGen/$generation")
        // Gate the UI delivery too: if the user clears between this post
        // and the Looper draining it, the queued onMessage would re-add a
        // notification to the just-cleared list. The check runs on the UI
        // thread so it's coherent with notificationsClear()'s gen bump.
        ui.post {
            if (myGen == generation) {
                onMessage(title, body)
            } else {
                Log.i(TAG, "drop stale-gen ui post id=$frameId gen=$myGen vs $generation")
            }
        }
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

    private fun updateStatus(s: Status) {
        if (statusNow == s) return
        statusNow = s
        ui.post { onStatusChange(s) }
    }

    companion object {
        private const val TAG = "NtfySubscriber"
        /** Slack on the post-clear time fence. Closes the same-second
         *  truncation gap (since `time` is whole seconds) and absorbs
         *  device-vs-server clock skew. Bigger than realistic NTP drift,
         *  smaller than ntfy.sh's 12 h cache retention. */
        private const val CLEAR_FENCE_GRACE_MS = 5_000L
    }
}

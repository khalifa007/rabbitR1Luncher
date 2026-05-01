package com.r1.launcher

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.r1.launcher.openclaw.GatewaySession
import com.r1.launcher.openclaw.OpenClawPrefs
import com.r1.launcher.openclaw.decodeGatewaySetupCode
import com.r1.launcher.ui.LauncherRoot
import com.r1.launcher.ui.MOTOR_BACK
import com.r1.launcher.ui.R1Theme
import com.r1.launcher.ui.setMotorOrientation
import com.r1.launcher.updater.OTAUpdater
import java.io.OutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity shell:
 *   - setContent { R1Theme { LauncherRoot(state, host) } }
 *   - dispatchKeyEvent routes wheel/PTT candidates into state/host
 *   - onResume/onPause register BroadcastReceivers (net, battery, package) and
 *     the telephony signal listener, just like the old Java launcher
 *   - clock tick re-posts aligned to the next second boundary
 *
 * LauncherState is plain Kotlin (not a ViewModel) — the activity is singleTask
 * with configChanges flags, so it never recreates; no survival needed.
 */
class LauncherActivity : ComponentActivity(), LauncherHost {

    companion object {
        // Ignore a second press arriving within this window of an earlier one.
        // Catches accidental double-taps and prevents a "go home + lock" combo
        // when the user double-taps the side button while inside another app.
        private const val SIDE_PRESS_DEBOUNCE_MS = 250L
        private const val REQ_CAMERA_PERM = 4801
        private const val REQ_AUDIO_PERM = 4802
        private const val REQ_PHONE_PERM = 4803
        private const val REQ_SMS_PERM = 4804
    }

    private val state = LauncherState()
    private var tone: ToneGenerator? = null
    private var soundPool: SoundPool? = null
    private var popSoundId: Int = 0
    private var movingSoundId: Int = 0
    private var audioManager: AudioManager? = null

    private val openClawPrefs by lazy { OpenClawPrefs.get(this) }
    private val wifiSharePrefs by lazy { com.r1.launcher.wifishare.WifiSharePrefs.get(this) }
    private var wifiShareTimerEndMs: Long = 0L
    private val wifiShareTimerRunnable = Runnable { toggleWifiShare(false) }
    private val wifiShareCountdownRunnable = object : Runnable {
        override fun run() {
            val remaining = ((wifiShareTimerEndMs - System.currentTimeMillis()) / 1000L)
                .toInt().coerceAtLeast(0)
            state.wifiShareTimerRemainingSec = remaining
            if (remaining > 0 && state.wifiShareEnabled) ui.postDelayed(this, 1000L)
        }
    }
    private val wifiShareClientPollRunnable = object : Runnable {
        override fun run() {
            if (!state.wifiShareEnabled) return
            pollWifiShareClients()
            ui.postDelayed(this, 3000L)
        }
    }
    private var openClawSession: GatewaySession? = null
    private var openClawCapture: com.r1.launcher.openclaw.AudioCapture? = null
    private var openClawSpeechPlayer: MediaPlayer? = null
    private var openClawSpeakNextAssistant = false
    private var openClawLastSpokenKey = ""

    private val ui = Handler(Looper.getMainLooper())
    private val hm = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    private var lastSidePressMs: Long = 0L
    private var lastPauseMs: Long = 0L
    private var lastResumeMs: Long = 0L
    private var openClawPttKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    // Side button (BUTTON_1) press detection: distinguishes short-tap, double-tap,
    // and long-press. Tuned values:
    //   short ≤ 500 ms (DOWN→UP duration)
    //   long  ≥ 500 ms (DOWN held without UP fires before UP arrives, see ACTION_DOWN repeat)
    //   double tap window: two short taps within 350 ms of each other
    private var sideDownAtMs: Long = 0L
    private var sideLastShortUpMs: Long = 0L
    private var sideLongFired: Boolean = false
    private var pendingSideSingle: Runnable? = null
    private val SIDE_DOUBLE_PRESS_MS = 350L
    private val SIDE_LONG_PRESS_MS = 500L

    private var telephony: TelephonyManager? = null
    private val phoneListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength?) {
            if (s == null) return
            state.signalLevel = s.level.coerceIn(0, 4)
        }
    }

    private val netRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            refreshNetwork()
            refreshBluetooth()
            refreshSim()
        }
    }

    private val packageRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            loadApps()
        }
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            state.batteryPct = (level.toFloat() / scale).coerceIn(0.08f, 1f)
            state.batteryCharging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    // adb-installable openai key receiver:
    //   adb shell "am broadcast -a com.r1.launcher.SET_OPENAI_KEY --es key sk-..."
    // Lets the user inject the Whisper API key without typing it on a 480x480
    // round screen. Receiver is exported so adb (uid 2000) can reach it.
    private val openaiKeyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            val k = i?.getStringExtra("key")?.trim().orEmpty()
            when {
                k.isEmpty() -> toast("--es key missing")
                !k.startsWith("sk-") || k.length < 20 -> toast("not an openai key")
                else -> {
                    openClawPrefs.openaiKey = k
                    refreshOpenaiKeyState()
                    toast("key saved via adb")
                }
            }
        }
    }

    // SmsReceiver writes incoming SMS to SmsCache and fires this local broadcast
    // so the messages panel can refresh without polling.
    private val smsLocalRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (state.panel == Panel.MESSAGES) loadSmsConversations()
            else if (state.panel == Panel.MESSAGES_THREAD) {
                // re-pull the active thread to surface the new bubble
                openSmsThread(state.smsThreadAddress, state.smsThreadName)
            }
        }
    }

    // adb-callable web-server toggle:
    //   adb shell "am broadcast -a com.r1.launcher.TOGGLE_WEB_SERVER --ez on true"
    private val webToggleRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            val on = i?.getBooleanExtra("on", !state.webServerEnabled) ?: !state.webServerEnabled
            android.util.Log.i("LauncherActivity", "webToggleRx fired on=$on")
            toggleWebServer(on)
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val now = Date()
            state.clockText = hm.format(now)
            state.dateText = dt.format(now)
            // Re-post aligned to the next second boundary so the display doesn't drift.
            ui.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )

        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ensurePhonePerm()

        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 45)
        }.getOrNull()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        runCatching {
            val afd = assets.openFd("pop.mp3")
            popSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }
        runCatching {
            val afd = assets.openFd("moving.mp3")
            movingSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }

        // OTA: silent boot check (no toast on "up to date"), wired through carroot
        // for the post-install reboot. The Settings → "check for updates" row
        // calls back in with forcePrompt = true.
        OTAUpdater.executeRootCommand = { cmd -> sendToCarroot(cmd) }
        OTAUpdater.checkForUpdates(this, state, forcePrompt = false) { msg ->
            toast(msg)
        }

        state.openClawHideChat = openClawPrefs.hideChat
        state.chatFontSize = openClawPrefs.chatFontSize
        state.chatTtsEnabled = openClawPrefs.ttsEnabled
        state.wifiShareSsid = wifiSharePrefs.ssid
        state.wifiSharePassword = wifiSharePrefs.password
        state.wifiShareTimerMinutes = wifiSharePrefs.timerMinutes
        loadApps()

        // Auto-start the companion web server on boot. Cheap when nobody connects;
        // having it always-on means the user just types the URL when they need it.
        toggleWebServer(true)

        setContent {
            R1Theme {
                LauncherRoot(state = state, host = this)
            }
        }
    }

    private fun loadApps() {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packageManager.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName != "com.android.settings" }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        state.apps.clear()
        found.forEach { state.apps.add(AppEntry.Real(it)) }
        state.apps.add(AppEntry.Messages)
        state.apps.add(AppEntry.OpenClaw)
        state.apps.add(AppEntry.Settings)
        state.appsLoaded = true
        if (state.appsFocus >= state.apps.size) state.appsFocus = 0
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The side button is mapped to BUTTON_1 (not HOME), so its handling lives in
        // dispatchKeyEvent. onNewIntent fires only for genuine HOME-category redirects
        // — typically when a third-party app finishes / the user invokes home. Land
        // on the clock screen unless we're already on it.
        if (state.panel != Panel.HOME) state.goHome()
    }

    override fun onResume() {
        super.onResume()
        lastResumeMs = System.currentTimeMillis()
        ui.post(tick)

        val netFilter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(netRx, netFilter)
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageRx, pkgFilter)

        val keyFilter = IntentFilter("com.r1.launcher.SET_OPENAI_KEY")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(openaiKeyRx, keyFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(openaiKeyRx, keyFilter)
        }

        val smsLocalFilter = IntentFilter(com.r1.launcher.messages.SmsReceiver.ACTION_NEW_SMS_LOCAL)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(smsLocalRx, smsLocalFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsLocalRx, smsLocalFilter)
        }

        val webToggleFilter = IntentFilter("com.r1.launcher.TOGGLE_WEB_SERVER")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(webToggleRx, webToggleFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(webToggleRx, webToggleFilter)
        }

        runCatching {
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }

        refreshNetwork()
        refreshBluetooth()
        refreshSim()

        loadApps()
    }

    override fun onPause() {
        super.onPause()
        lastPauseMs = System.currentTimeMillis()
        ui.removeCallbacks(tick)
        runCatching { unregisterReceiver(netRx) }
        runCatching { unregisterReceiver(batteryRx) }
        runCatching { unregisterReceiver(packageRx) }
        runCatching { unregisterReceiver(openaiKeyRx) }
        runCatching { unregisterReceiver(smsLocalRx) }
        runCatching { unregisterReceiver(webToggleRx) }
        runCatching { telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { openClawCloseSessionInternal() }
        runCatching { webServer?.stopServer() }
        webServer = null
        // Clean up UI handlers
        runCatching { ui.removeCallbacksAndMessages(null) }
        runCatching { tone?.release() }
        tone = null
        runCatching { soundPool?.release() }
        soundPool = null
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
    }

    override fun onBackPressed() {
        if (state.panel == Panel.HOME) {
            // No-op on home — we ARE the home activity.
        } else {
            state.backPressed(this)
        }
    }

    // --- network/battery/sim refresh ---

    private fun refreshNetwork() {
        var wifiConnected = false
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            if (n != null) {
                val caps = cm.getNetworkCapabilities(n)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    wifiConnected = true
                }
            }
        }
        state.wifiOn = wifiConnected
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) state.wifiEnabled = wm.isWifiEnabled
        }

        // Asynchronously fetch connected SSID name via root shell
        if (state.wifiOn) {
            Thread {
                if (sendToCarroot("cmd wifi status > /data/local/tmp/wifi_status.txt && chmod 666 /data/local/tmp/wifi_status.txt")) {
                    Thread.sleep(300)
                    try {
                        val txt = java.io.File("/data/local/tmp/wifi_status.txt").readText()
                        val match = Regex("Wifi is connected to \"(.*?)\"").find(txt)
                        val ssid = match?.groupValues?.get(1) ?: ""
                        ui.post { state.wifiConnectedSsid = ssid }
                    } catch (e: Exception) {}
                }
            }.start()
        } else {
            state.wifiConnectedSsid = ""
        }
    }

    private fun refreshBluetooth() {
        state.btOn = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun refreshSim() {
        val tm = telephony ?: run {
            state.simPresent = false; return
        }
        val simState = runCatching { tm.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        val operator = (tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
            ?: "")
        // Be lenient: if we have an operator name we have a SIM in service, even if
        // simState briefly reports something other than READY (locked, loaded, etc.).
        val present = simState == TelephonyManager.SIM_STATE_READY || operator.isNotEmpty()
        state.simPresent = present
        if (!present) {
            state.simOperator = ""
            state.cellularOn = false
            state.networkType = ""
            return
        }
        state.simOperator = operator.ifEmpty { "SIM" }

        // Mobile data on/off. Requires READ_PHONE_STATE (READ_BASIC_PHONE_STATE on API 31+).
        state.cellularOn = runCatching { tm.isDataEnabled }.getOrDefault(false)

        // Direct radio type lookup — no shell-out. Works because the launcher is
        // system-signed in our OS image so READ_PHONE_STATE is granted.
        val radio = runCatching {
            if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else tm.networkType
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        state.networkType = when (radio) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            else -> ""
        }
        android.util.Log.d("LauncherActivity",
            "refreshSim: simState=$simState op='$operator' dataOn=${state.cellularOn} radio=$radio -> '${state.networkType}'")
    }

    // --- LauncherHost: side effects ---

    override fun launchApp(idx: Int) {
        when (val entry = state.apps.getOrNull(idx)) {
            is AppEntry.Real -> {
                val info = entry.info
                launchTone()
                runCatching {
                    val i = Intent().apply {
                        setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    startActivity(i)
                    state.back()
                }
            }
            AppEntry.Settings -> {
                if (!ensureWriteSettingsGrant()) return
                seedSettingsLevels()
                state.openSettings()
                selectTone()
            }
            AppEntry.OpenClaw -> {
                selectTone()
                if (openClawPrefs.hasPairing()) {
                    refreshOpenaiKeyState()
                    openClawStartSession()
                    state.openOpenClawChat()
                } else {
                    state.qrError = null
                    ensureCameraPerm()
                    state.openOpenClawQr()
                }
            }
            AppEntry.Messages -> {
                selectTone()
                state.openMessages()
                if (ensureSmsPerm()) loadSmsConversations()
                else state.smsError = "permission required"
            }
            null -> Unit
        }
    }

    private fun openClawStartSession() {
        if (openClawSession != null) return
        // Hydrate session state from prefs so the chat panel can render pills
        // and pick the right thread before the WebSocket finishes its handshake.
        state.selectedSessionKey = openClawPrefs.selectedSessionKey?.takeUnless { it.isBlank() } ?: "main"
        state.mainSessionKey = openClawPrefs.lastMainSessionKey?.takeUnless { it.isBlank() } ?: "main"
        val session = GatewaySession(this, openClawPrefs)
        session.onState = { st ->
            ui.post {
                state.chatStatus = when (st) {
                    GatewaySession.State.Idle -> "idle"
                    GatewaySession.State.Connecting -> "connecting"
                    is GatewaySession.State.Live -> "live"
                    is GatewaySession.State.Switching -> "switching"
                    is GatewaySession.State.Error -> "error: ${st.message}"
                }
                if (st is GatewaySession.State.Live) {
                    state.selectedSessionKey = st.sessionKey
                    // One-shot fetch of available threads after the gateway is up.
                    if (state.chatSessions.isEmpty() && !state.sessionsLoading) {
                        state.sessionsLoading = true
                        openClawSession?.listSessions()
                    }
                }
                if (st is GatewaySession.State.Switching) {
                    state.selectedSessionKey = st.sessionKey
                }
                // Self-recover if the saved bootstrap is dead. Wipe the bad
                // token + (still-empty) device key, route back to the QR panel
                // for a fresh scan. Don't auto-recover from generic errors —
                // user might just be offline and shouldn't lose their pairing.
                if (st is GatewaySession.State.Error) {
                    state.chatBusy = false
                    val msg = st.message.lowercase()
                    val expired = "expired" in msg || "invalid" in msg || "unauthorized" in msg
                    if (expired) {
                        openClawPrefs.bootstrapToken = null
                        openClawPrefs.deviceToken = null
                        openClawPrefs.sharedToken = null
                        runCatching { openClawCloseSessionInternal() }
                        if (state.panel == Panel.OPENCLAW_CHAT) {
                            state.qrError = st.message
                            state.openOpenClawQr()
                        }
                    }
                }
            }
        }
        session.onHistory = { msgs ->
            ui.post {
                applyOpenClawHistory(msgs)
                state.chatScrollIndex = 0
                speakLatestAssistantIfNeeded()
            }
        }
        session.onChatDelta = { runId, text ->
            ui.post {
                // Only show streaming preview for runs *we* initiated. Other
                // operators talking to the same agent shouldn't bleed into our
                // local UI (matches official client ChatController.kt:347).
                if (runId == null || state.chatPendingRunIds.contains(runId)) {
                    state.chatStreamingText = text
                }
            }
        }
        session.onChatTerminal = { runId, evState, errMsg ->
            ui.post {
                if (runId != null) state.chatPendingRunIds.remove(runId)
                // Clear the streaming preview so it doesn't duplicate the
                // final message that refreshHistory() is about to load.
                state.chatStreamingText = ""
                state.chatBusy = false
                openClawSession?.refreshHistory()
            }
        }
        session.onMainSessionKey = { key ->
            ui.post {
                state.mainSessionKey = key
                openClawPrefs.lastMainSessionKey = key
            }
        }
        session.onSessions = { list ->
            ui.post {
                state.chatSessions.clear()
                state.chatSessions.addAll(list)
                state.sessionsLoading = false
            }
        }
        openClawSession = session
        session.start()
    }

    /** Cap chat list at chatMessagesMax, dropping oldest. Call after each add. */
    private fun trimChatMessages() {
        while (state.chatMessages.size > state.chatMessagesMax) {
            state.chatMessages.removeAt(0)
        }
    }

    /**
     * The gateway is authoritative when it returns a complete history, but some
     * OpenClaw builds can briefly return a shorter tail or empty list during
     * long/tool-heavy runs. Preserve the local prefix in that case so messages
     * already visible on the R1 do not vanish after a refresh.
     */
    private fun applyOpenClawHistory(msgs: List<com.r1.launcher.openclaw.ChatMessage>) {
        val incoming = if (msgs.size > state.chatMessagesMax) {
            msgs.takeLast(state.chatMessagesMax)
        } else msgs
        val current = state.chatMessages.filterNot { it.streaming }
        val lastLocal = current.lastOrNull()?.text?.trim().orEmpty()
        val likelyCommandReset = lastLocal.startsWith("/") && lastLocal.length < 40
        val next = if (current.isNotEmpty() && incoming.size < current.size && !likelyCommandReset) {
            android.util.Log.w(
                "OpenClaw",
                "chat.history returned fewer messages (${incoming.size}) than local UI (${current.size}); preserving local prefix",
            )
            current.take(current.size - incoming.size) + incoming
        } else {
            incoming
        }
        state.chatMessages.clear()
        state.chatMessages.addAll(next.takeLast(state.chatMessagesMax))
    }

    private fun speakLatestAssistantIfNeeded() {
        if (!state.chatTtsEnabled || state.panel != Panel.OPENCLAW_TALK || !openClawSpeakNextAssistant) return
        val msg = state.chatMessages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return
        val key = "${msg.timestamp}:${msg.text.hashCode()}"
        if (key == openClawLastSpokenKey) return
        val apiKey = openClawPrefs.openaiKey
        if (apiKey.isNullOrBlank()) {
            toast("voice needs openai key")
            openClawSpeakNextAssistant = false
            return
        }
        openClawLastSpokenKey = key
        openClawSpeakNextAssistant = false
        com.r1.launcher.openclaw.OpenAiSpeechClient.synthesize(
            text = msg.text,
            apiKey = apiKey,
        ) { wavBytes, err ->
            if (err != null || wavBytes == null) {
                toast("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playOpenClawSpeech(wavBytes)
        }
    }

    private fun playOpenClawSpeech(wavBytes: ByteArray) {
        runCatching {
            val am = audioManager ?: getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
            val dir = File(cacheDir, "openclaw-voice").apply { mkdirs() }
            val out = File(dir, "assistant.wav")
            out.writeBytes(normalizeWavHeader(wavBytes))
            runCatching { openClawSpeechPlayer?.stop() }
            runCatching { openClawSpeechPlayer?.release() }
            openClawSpeechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(out.absolutePath)
                setOnCompletionListener {
                    runCatching { it.release() }
                    if (openClawSpeechPlayer === it) openClawSpeechPlayer = null
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    toast("voice playback failed")
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            toast("voice playback: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun normalizeWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        val isWav = bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        if (!isWav) return bytes
        val out = bytes.copyOf()
        writeIntLe(out, 4, out.size - 8)
        var i = 12
        while (i + 8 <= out.size) {
            val id = String(out, i, 4, Charsets.US_ASCII)
            val size = readIntLe(out, i + 4)
            if (id == "data") {
                val dataBytes = (out.size - (i + 8)).coerceAtLeast(0)
                writeIntLe(out, i + 4, dataBytes)
                break
            }
            if (size <= 0 || size == -1) break
            i += 8 + size + (size and 1)
        }
        return out
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun openClawCloseSessionInternal() {
        runCatching { openClawCapture?.close() }
        openClawCapture = null
        runCatching { openClawSpeechPlayer?.stop() }
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        runCatching { openClawSession?.stop() }
        openClawSession = null

        state.chatRecording = false
        state.chatBusy = false
        state.chatInputLevel = 0
        state.chatPartialText = ""
        state.chatStreamingText = ""
        state.chatPendingRunIds.clear()
        openClawSpeakNextAssistant = false
    }

    private fun seedSettingsLevels() {
        val brightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(state.brightnessLevel)
        state.brightnessLevel = brightness.coerceIn(1, 255)

        audioManager?.let { am ->
            state.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            state.volumeLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                .coerceIn(0, state.volumeMax)
        }
    }

    private fun ensureWriteSettingsGrant(): Boolean {
        if (Settings.System.canWrite(this)) return true
        toast("Grant 'Modify system settings' for R1 Home")
        runCatching {
            val i = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        return false
    }

    override fun setBrightness(level: Int) {
        val clamped = level.coerceIn(1, 255)
        runCatching {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped,
            )
        }
        // Apply to this window too, so the change is visible immediately even
        // before the system broadcasts the new value to other windows.
        val attrs = window.attributes
        attrs.screenBrightness = clamped / 255f
        window.attributes = attrs
    }

    override fun setVolume(level: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        }
    }

    override fun lockScreen() {
        val ok = PowerService.lockScreen()
        if (!ok) toast("Enable Accessibility → R1 Launcher to lock screen")
    }

    override fun checkForUpdate() {
        OTAUpdater.checkForUpdates(this, state, forcePrompt = true) { msg ->
            toast(msg)
        }
    }

    override fun openAirplaneSettings() {
        val actions = listOf(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (a in actions) {
            runCatching {
                val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(packageManager) != null) {
                    startActivity(i)
                    state.back()
                    return
                }
            }
        }
        toast("Settings UI unavailable")
        state.back()
    }

    override fun openDateSettings() {
        runCatching {
            val i = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) != null) {
                startActivity(i)
                state.back()
                return
            }
        }
        toast("Date settings unavailable")
        state.back()
    }


    @Suppress("DEPRECATION")
    override fun toggleWifi(enable: Boolean) {
        state.wifiEnabled = enable
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        Thread {
            // Try framework API first; ignore the boolean (it lies on API 29+).
            runCatching { wm?.setWifiEnabled(enable) }
            Thread.sleep(400)
            val applied = runCatching { wm?.isWifiEnabled == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleWifi($enable) direct applied=$applied")
            val ok = applied || sendToCarroot(
                if (enable) "cmd wifi set-wifi-enabled enabled" else "cmd wifi set-wifi-enabled disabled"
            )
            android.util.Log.d("LauncherActivity", "toggleWifi($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toast("Wi-Fi toggle failed")
                    refreshNetwork()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshNetwork() }
            Thread.sleep(2500)
            ui.post { refreshNetwork() }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun toggleCellular(enable: Boolean) {
        state.cellularOn = enable
        if (!enable) state.networkType = ""
        Thread {
            // Try framework API first — silently no-ops without MODIFY_PHONE_STATE,
            // so we must verify the read-back before trusting it.
            runCatching { telephony?.setDataEnabled(enable) }
            Thread.sleep(400)
            val applied = runCatching { telephony?.isDataEnabled == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleCellular($enable) direct applied=$applied")
            // `svc data enable/disable` is a no-op on this build — modem state doesn't follow.
            // Toggling Settings.Global.mobile_data flips the framework setting which the
            // TelephonyController watches, and the modem deregisters/reattaches accordingly.
            val ok = applied || sendToCarroot(if (enable) "settings put global mobile_data 1" else "settings put global mobile_data 0")
            android.util.Log.d("LauncherActivity", "toggleCellular($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toast("Cellular toggle failed")
                    refreshSim()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshSim() }
            Thread.sleep(2500)
            ui.post { refreshSim() }
        }.start()
    }

    override fun startWifiScan() {
        state.wifiScanResults.clear()
        state.wifiScanResults.add("Scanning...")
        Thread {
            if (sendToCarroot("cmd wifi start-scan")) {
                Thread.sleep(2000)
                sendToCarroot("cmd wifi list-scan-results > /data/local/tmp/wifi.txt && chmod 666 /data/local/tmp/wifi.txt")
                Thread.sleep(500)
                try {
                    val lines = java.io.File("/data/local/tmp/wifi.txt").readLines()
                    val ssids = mutableSetOf<String>()
                    for (i in 1 until lines.size) {
                        val line = lines[i].trim()
                        if (line.isEmpty()) continue
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 6) {
                            val ssid = parts.drop(4).dropLast(1).joinToString(" ").trim()
                            if (ssid.isNotBlank()) ssids.add(ssid)
                        }
                    }
                    ui.post {
                        state.wifiScanResults.clear()
                        if (ssids.isEmpty()) {
                            state.wifiScanResults.add("No networks found")
                        } else {
                            state.wifiScanResults.addAll(ssids.sorted())
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        state.wifiScanResults.clear()
                        state.wifiScanResults.add("Error reading scan")
                    }
                }
            } else {
                ui.post {
                    state.wifiScanResults.clear()
                    state.wifiScanResults.add("Root shell unavailable")
                }
            }
        }.start()
    }

    override fun connectToWifi(ssid: String, pass: String) {
        state.back()
        toast("Connecting to $ssid...")
        Thread {
            val escapedSsid = ssid.replace("\"", "\\\"")
            val escapedPass = pass.replace("\"", "\\\"")
            val cmd = "cmd wifi connect-network \"$escapedSsid\" wpa2 \"$escapedPass\""
            sendToCarroot(cmd)
            Thread.sleep(2000)
            ui.post { refreshNetwork() }
        }.start()
    }

    override fun toggleWifiShare(enable: Boolean) {
        state.wifiShareEnabled = enable
        if (!enable) {
            ui.removeCallbacks(wifiShareTimerRunnable)
            ui.removeCallbacks(wifiShareCountdownRunnable)
            ui.removeCallbacks(wifiShareClientPollRunnable)
            state.wifiShareTimerRemainingSec = 0
            state.wifiShareConnectedClients.clear()
        }
        Thread {
            val ssid = state.wifiShareSsid.replace("\"", "\\\"")
            val pass = state.wifiSharePassword.replace("\"", "\\\"")
            // Single one-shot form on this build: `cmd wifi start-softap <ssid> wpa2 <pass>`.
            // Capture stdout/stderr so we can surface real failure reasons.
            val cmdOut = "/data/local/tmp/softap_cmd.txt"
            val cmd = if (enable) {
                "cmd wifi start-softap \"$ssid\" wpa2 \"$pass\" > $cmdOut 2>&1; chmod 666 $cmdOut"
            } else {
                "cmd wifi stop-softap > $cmdOut 2>&1; chmod 666 $cmdOut"
            }
            sendToCarroot(cmd)
            // The cmd-line tool tails state events for ~3s on success. Verify the
            // ap0 interface — that's the authoritative "is the radio actually up?"
            // signal. Retry up to ~6s because softap brings down STA first on MTK.
            var applied = false
            for (attempt in 0..5) {
                Thread.sleep(1000)
                applied = isWifiShareEnabled() == enable
                android.util.Log.d("LauncherActivity", "toggleWifiShare($enable) attempt=$attempt applied=$applied")
                if (applied) break
            }
            if (!applied) {
                val out = runCatching { java.io.File(cmdOut).readText() }.getOrDefault("")
                android.util.Log.w("LauncherActivity", "toggleWifiShare($enable) FAILED. cmd output:\n$out")
                val reason = out.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.contains("fail", true) || it.contains("error", true) || it.contains("denied", true) }
                    ?: out.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "no response"
                ui.post {
                    state.wifiShareEnabled = !enable
                    toast((if (enable) "Hotspot failed: " else "Stop failed: ") + reason.take(80))
                }
                return@Thread
            }
            ui.post {
                state.wifiShareEnabled = enable
                if (enable) {
                    armWifiShareTimer()
                    ui.removeCallbacks(wifiShareClientPollRunnable)
                    ui.post(wifiShareClientPollRunnable)
                }
            }
        }.start()
    }

    private var webServer: com.r1.launcher.web.R1WebServer? = null

    override fun toggleWebServer(enable: Boolean) {
        android.util.Log.i("LauncherActivity", "toggleWebServer($enable) entry, current=${webServer != null}")
        if (enable && webServer != null) return
        if (!enable && webServer == null) {
            state.webServerEnabled = false
            return
        }
        if (enable) {
            val srv = com.r1.launcher.web.R1WebServer(this, this, state)
            webServer = srv
            Thread {
                runCatching { srv.startServer() }
                    .onFailure { android.util.Log.e("LauncherActivity", "startServer failed", it) }
                ui.post {
                    state.webServerIp = discoverLocalIp()
                    state.webServerEnabled = true
                    android.util.Log.i("LauncherActivity", "web server up at ${state.webServerIp}:${state.webServerPort}")
                    toast("remote panel: http://${state.webServerIp}:${state.webServerPort}")
                }
            }.start()
        } else {
            val srv = webServer
            webServer = null
            state.webServerEnabled = false
            state.webServerIp = ""
            Thread { runCatching { srv?.stopServer() } }.start()
        }
    }

    /**
     * Pick the IP that's actually reachable from a phone/PC on the same LAN.
     * Priority: hotspot (ap0) then Wi-Fi (wlan) then Ethernet (eth) then anything else.
     * Skips cellular modem interfaces (ccmni, rmnet, ppp) — they sit on the
     * carrier's CGN and are not routable from your home network.
     */
    private fun discoverLocalIp(): String {
        val ifaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
        }.getOrDefault(emptyList())

        fun priority(name: String): Int = when {
            name.startsWith("ap0") -> 0
            name.startsWith("wlan") -> 1
            name.startsWith("eth") -> 2
            name.startsWith("ccmni") || name.startsWith("rmnet") || name.startsWith("ppp") -> 99
            else -> 50
        }

        val ordered = ifaces
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { priority(it.name) }

        for (iface in ordered) {
            if (priority(iface.name) >= 99) continue // never use cellular
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                val host = addr.hostAddress ?: continue
                if (host.startsWith("127.")) continue
                return host
            }
        }
        return "0.0.0.0"
    }

    override fun wifiShareSaveEdit() {
        val target = state.wifiShareEditTarget
        val input = state.wifiShareEditInput.trim()
        if (target == WifiShareEditTarget.SSID && input.isEmpty()) {
            toast("name can't be empty")
            return
        }
        if (target == WifiShareEditTarget.PASSWORD && input.length < 8) {
            toast("password needs 8+ chars")
            return
        }
        when (target) {
            WifiShareEditTarget.SSID -> {
                state.wifiShareSsid = input
                wifiSharePrefs.ssid = input
            }
            WifiShareEditTarget.PASSWORD -> {
                state.wifiSharePassword = input
                wifiSharePrefs.password = input
            }
        }
        state.back()
        // If the hotspot is currently up, cycle it so the new config takes effect.
        if (state.wifiShareEnabled) {
            Thread {
                ui.post { toggleWifiShare(false) }
                Thread.sleep(1200)
                ui.post { toggleWifiShare(true) }
            }.start()
        }
    }

    override fun wifiShareCycleTimer() {
        val choices = intArrayOf(0, 15, 30, 60, 120)
        val cur = choices.indexOf(state.wifiShareTimerMinutes).let { if (it < 0) 0 else it }
        val next = choices[(cur + 1) % choices.size]
        state.wifiShareTimerMinutes = next
        wifiSharePrefs.timerMinutes = next
        if (state.wifiShareEnabled) armWifiShareTimer()
    }

    private fun armWifiShareTimer() {
        ui.removeCallbacks(wifiShareTimerRunnable)
        ui.removeCallbacks(wifiShareCountdownRunnable)
        val mins = state.wifiShareTimerMinutes
        if (mins <= 0) {
            state.wifiShareTimerRemainingSec = 0
            return
        }
        val durationMs = mins * 60_000L
        wifiShareTimerEndMs = System.currentTimeMillis() + durationMs
        state.wifiShareTimerRemainingSec = (durationMs / 1000L).toInt()
        ui.postDelayed(wifiShareTimerRunnable, durationMs)
        ui.post(wifiShareCountdownRunnable)
    }

    private fun isWifiShareEnabled(): Boolean {
        // Authoritative signal: kernel interface state. ap0 is the SoftAp iface on
        // this build. When softap is up, `ip link show ap0` reports `state UP` and
        // the UP,LOWER_UP flags; when down (or before first start), DOWN or absent.
        // dumpsys wifi here doesn't include the SoftApState lines we used to grep.
        val out = "/data/local/tmp/softap_iface.txt"
        if (!sendToCarroot("ip link show ap0 > $out 2>&1; chmod 666 $out")) return false
        return runCatching {
            val txt = java.io.File(out).readText()
            txt.contains("state UP") && txt.contains("LOWER_UP")
        }.getOrDefault(false)
    }

    private fun pollWifiShareClients() {
        Thread {
            val out = "/data/local/tmp/softap_clients.txt"
            // ap0 is the SoftAp interface on this build (confirmed via dumpsys).
            // Pull both ip-neigh and ARP and union — early in a connection a
            // client may show in one but not the other.
            sendToCarroot("ip neigh show dev ap0 > $out 2>/dev/null; cat /proc/net/arp >> $out; chmod 666 $out")
            Thread.sleep(250)
            val macs = runCatching {
                val text = java.io.File(out).readText()
                val seen = linkedSetOf<String>()
                Regex("lladdr ([0-9a-fA-F:]{17})").findAll(text).forEach { seen.add(it.groupValues[1].uppercase()) }
                text.lineSequence().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6 && (parts[5] == "ap0" || parts[5].startsWith("wlan"))) {
                        val mac = parts[3]
                        if (mac.matches(Regex("[0-9a-fA-F:]{17}")) && mac != "00:00:00:00:00:00") {
                            seen.add(mac.uppercase())
                        }
                    }
                }
                seen.toList()
            }.getOrDefault(emptyList())
            ui.post {
                state.wifiShareConnectedClients.clear()
                state.wifiShareConnectedClients.addAll(macs)
                if (macs.isEmpty()) state.wifiShareClientsExpanded = false
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun toggleBluetooth(enable: Boolean) {
        state.btOn = enable
        Thread {
            // Try framework API first. BluetoothAdapter.enable()/disable() are deprecated since
            // API 33 but still work for system-signed apps; otherwise they silently no-op.
            val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            runCatching { if (enable) adapter?.enable() else adapter?.disable() }
            Thread.sleep(800)
            val applied = runCatching { (adapter?.isEnabled == true) == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleBluetooth($enable) direct applied=$applied")
            val ok = applied || sendToCarroot(if (enable) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable")
            android.util.Log.d("LauncherActivity", "toggleBluetooth($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toast("Bluetooth toggle failed")
                    refreshBluetooth()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshBluetooth() }
            Thread.sleep(2500)
            ui.post { refreshBluetooth() }
        }.start()
    }

    override fun factoryReset() {
        // Last-line UX: a toast as the wipe broadcast goes out. The system tears the
        // process down within seconds so anything below this rarely runs.
        toast("Wiping device...")
        Thread {
            // Android 14+ uses FACTORY_RESET; older builds expect MASTER_CLEAR. Both are
            // protected broadcasts — must be sent from a privileged shell, hence carroot.
            val a14 = "am broadcast -a android.intent.action.FACTORY_RESET --receiver-foreground -p android"
            val legacy = "am broadcast -a android.intent.action.MASTER_CLEAR -p android"
            if (!sendToCarroot(a14)) {
                Thread.sleep(300)
                if (!sendToCarroot(legacy)) {
                    ui.post { toast("Factory reset failed: no root shell") }
                }
            }
        }.start()
    }

    private fun sendToCarroot(cmd: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
            val os: OutputStream = s.getOutputStream()
            os.write((cmd + "\n").toByteArray())
            os.flush()
            Thread.sleep(500)
        }
        true
    } catch (_: Exception) {
        false
    }

    // --- LauncherHost: openclaw ---

    override fun openClawScanned(raw: String) {
        if (state.qrScanMode == QrScanMode.OPENAI_KEY) {
            val k = raw.trim()
            if (!k.startsWith("sk-") || k.length < 20) {
                state.qrError = "Not an openai key"
                return
            }
            openClawPrefs.openaiKey = k
            refreshOpenaiKeyState()
            state.qrError = null
            selectTone()
            toast("openai key saved via QR")
            // Bounce back to the settings panel where the user came from.
            state.qrScanMode = QrScanMode.GATEWAY_PAIRING
            state.openOpenClawSettings()
            return
        }
        val code = decodeGatewaySetupCode(raw) ?: run {
            state.qrError = "QR not recognised"
            return
        }
        openClawPrefs.gatewayUrl = code.url
        openClawPrefs.bootstrapToken = code.bootstrapToken
        openClawPrefs.sharedToken = code.token
        openClawPrefs.deviceToken = null
        state.qrError = null
        selectTone()
        openClawStartSession()
        state.chatMessages.clear()
        state.openOpenClawChat()
    }

    override fun openClawToggleRecord() {
        val cap = openClawCapture
        if (cap != null && cap.isRecording) openClawRecordStop() else openClawRecordStart()
    }

    override fun openClawRecordStart() {
        val session = openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        if (!ensureAudioPerm()) return
        val cap = openClawCapture ?: com.r1.launcher.openclaw.AudioCapture().also { openClawCapture = it }
        if (cap.isRecording) return
        if (movingSoundId != 0) soundPool?.play(movingSoundId, 1f, 1f, 0, 0, 1f)
        state.chatPartialText = ""
        state.chatRecording = true
        cap.start(object : com.r1.launcher.openclaw.AudioCapture.Callback {
            override fun onLevel(levelPct: Int) {
                state.chatInputLevel = levelPct
            }

            override fun onDone(wavBytes: ByteArray, durationMs: Int, peakPct: Int) {
                ui.post {
                    state.chatRecording = false
                    state.chatInputLevel = 0
                    state.chatPartialText = ""
                    if (durationMs < 300 || peakPct < 2) {
                        toast(if (peakPct < 2) "no audio captured" else "too short")
                        return@post
                    }
                    val key = openClawPrefs.openaiKey
                    if (key.isNullOrBlank()) {
                        toast("set openai key first (tap key pill)")
                        return@post
                    }
                    // Hand the WAV to Whisper. While it's in flight, show
                    // a transcribing indicator in the header.
                    state.chatTranscribing = true
                    com.r1.launcher.openclaw.WhisperClient.transcribe(
                        wavBytes = wavBytes,
                        apiKey = key,
                    ) { transcript, err ->
                        state.chatTranscribing = false
                        if (err != null) {
                            toast("whisper: $err")
                            return@transcribe
                        }
                        val text = transcript?.trim().orEmpty()
                        if (text.isEmpty()) {
                            toast("whisper: empty transcript")
                            return@transcribe
                        }
                        val optimistic = com.r1.launcher.openclaw.ChatMessage(
                            role = "user", text = text,
                        )
                        state.chatMessages.add(optimistic)
                        trimChatMessages()
                        state.chatScrollIndex = 0
                        state.chatBusy = true
                        if (state.chatTtsEnabled && state.panel == Panel.OPENCLAW_TALK) {
                            openClawSpeakNextAssistant = true
                        }
                        session.send(text = text, audioBase64 = null) { ok, runId, err ->
                            ui.post {
                                if (!ok) {
                                    val idx = state.chatMessages
                                        .indexOfFirst { it.id == optimistic.id }
                                    if (idx >= 0) state.chatMessages.removeAt(idx)
                                    state.chatBusy = false
                                    if (!err.isNullOrBlank()) toast("send failed: $err")
                                } else if (runId != null) {
                                    state.chatPendingRunIds.add(runId)
                                }
                            }
                        }
                    }
                }
            }

            override fun onError(msg: String) {
                ui.post {
                    state.chatRecording = false
                    state.chatInputLevel = 0
                    state.chatPartialText = ""
                    toast("mic: $msg")
                }
            }
        })
    }

    override fun openClawRecordStop() {
        val cap = openClawCapture ?: return
        if (!cap.isRecording) return
        cap.stop()
        state.chatRecording = false
        state.chatInputLevel = 0
        if (popSoundId != 0) soundPool?.play(popSoundId, 1f, 1f, 0, 0, 1f)
    }

    override fun openClawSendText(text: String) {
        val session = openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val optimistic = com.r1.launcher.openclaw.ChatMessage(role = "user", text = trimmed)
        state.chatMessages.add(optimistic)
        trimChatMessages()
        state.chatScrollIndex = 0
        if (!trimmed.startsWith("/")) {
            state.chatBusy = true
            if (state.chatTtsEnabled && state.panel == Panel.OPENCLAW_TALK) {
                openClawSpeakNextAssistant = true
            }
        }
        session.send(text = trimmed, audioBase64 = null) { ok, runId, err ->
            ui.post {
                if (!ok) {
                    // Roll back the optimistic bubble — the server never accepted
                    // it. ChatMessage.id makes equality unique even if the user
                    // sends the same text twice in the same millisecond.
                    val idx = state.chatMessages.indexOfFirst { it.id == optimistic.id }
                    if (idx >= 0) state.chatMessages.removeAt(idx)
                    state.chatBusy = false
                    if (!err.isNullOrBlank()) toast("send failed: $err")
                } else if (runId != null) {
                    state.chatPendingRunIds.add(runId)
                }
            }
        }
    }

    override fun openClawOpenTalk() {
        openClawStartSession()
        if (!state.chatTtsEnabled) {
            state.chatTtsEnabled = true
            openClawPrefs.ttsEnabled = true
        }
        state.openOpenClawTalk()
    }

    override fun openClawSetSpeaker(enabled: Boolean) {
        state.chatTtsEnabled = enabled
        openClawPrefs.ttsEnabled = enabled
        if (enabled) {
            popTone()
        } else {
            runCatching { openClawSpeechPlayer?.stop() }
            navTone()
        }
    }

    override fun openClawScrollUp() {
        state.chatScrollIndex++
    }

    override fun openClawScrollDown() {
        state.chatScrollIndex--
    }

    override fun openClawCloseSession() {
        openClawCloseSessionInternal()
    }

    override fun openClawPasteOpenaiKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty"); return
        }
        if (!raw.startsWith("sk-") || raw.length < 20) {
            toast("not an openai key"); return
        }
        openClawPrefs.openaiKey = raw
        refreshOpenaiKeyState()
        toast("key saved")
    }

    override fun openClawClearOpenaiKey() {
        openClawPrefs.openaiKey = null
        refreshOpenaiKeyState()
        toast("key cleared")
    }

    override fun openClawSaveOpenaiKey(key: String) {
        val k = key.trim()
        when {
            k.isEmpty() -> { toast("key is empty"); return }
            !k.startsWith("sk-") || k.length < 20 -> { toast("not an openai key"); return }
            else -> {
                openClawPrefs.openaiKey = k
                refreshOpenaiKeyState()
                toast("key saved")
                state.back()
            }
        }
    }

    override fun openClawSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            // 1 (whisper key) is handled entirely by UI (toggles keyboard)
            2 -> {
                ensureCameraPerm()
                state.openOpenAiKeyQr()
                selectTone()
            }
            3 -> {
                val newHide = !state.openClawHideChat
                state.openClawHideChat = newHide
                openClawPrefs.hideChat = newHide
                popTone()
            }
            // 4 (font size) is handled by +/- buttons in the UI
            5 -> { openClawClearHistory(); popTone() }
            6 -> { openClawDisconnect(); popTone() }
        }
    }

    override fun openClawClearHistory() {
        state.chatMessages.clear()
        toast("chat history cleared")
    }

    override fun openClawDisconnect() {
        openClawPrefs.clear()
        runCatching { openClawCloseSessionInternal() }
        state.back()
        toast("gateway disconnected")
    }

    override fun openClawSwitchSession(key: String) {
        val target = key.trim()
        if (target.isEmpty() || target == state.selectedSessionKey) return
        val session = openClawSession ?: return
        // Reset chat UI immediately so the user sees the switch take effect even
        // before the new history lands. GatewaySession.switchSession will fire
        // onHistory once the new thread's messages are fetched.
        state.chatMessages.clear()
        state.chatStreamingText = ""
        state.chatPendingRunIds.clear()
        state.chatScrollIndex = 0
        state.chatBusy = false
        state.selectedSessionKey = target
        openClawPrefs.selectedSessionKey = target
        session.switchSession(target)
    }

    override fun openClawRefreshSessions() {
        val session = openClawSession ?: return
        if (state.sessionsLoading) return
        state.sessionsLoading = true
        session.listSessions()
    }

    override fun openClawSessionsRowActivate(idx: Int) {
        // Mirrors OpenClawSessionsPanel row order:
        //   0           "< back"
        //   1..choices  switch to that thread
        //   choices+1   "refresh"
        if (idx == 0) {
            state.back(); backTone(); return
        }
        val choices = com.r1.launcher.openclaw.resolveSessionChoices(
            currentSessionKey = state.selectedSessionKey,
            sessions = state.chatSessions.toList(),
            mainSessionKey = state.mainSessionKey,
        )
        val refreshIdx = 1 + choices.size.coerceAtLeast(1)
        if (idx == refreshIdx) {
            openClawRefreshSessions()
            popTone()
            return
        }
        val choice = choices.getOrNull(idx - 1) ?: return
        if (choice.key != state.selectedSessionKey) {
            openClawSwitchSession(choice.key)
        }
        state.back()
    }

    override fun openClawSetFontSize(size: Int) {
        val clamped = size.coerceIn(8, 28)
        state.chatFontSize = clamped
        openClawPrefs.chatFontSize = clamped
        popTone()
    }

    override fun openClawOpenCameraAsk() {
        ensureCameraPerm()
        // Pre-fire BACK before the panel composes. The stepper takes a
        // visible moment to physically rotate from its previous parked
        // angle; firing here gives it a head start while the AnimatedVisibility
        // enter animation runs and Camera2 spins up. The camera panel's
        // DisposableEffect re-fires BACK on compose — serialized through the
        // motor executor, the duplicate is harmless.
        setMotorOrientation(MOTOR_BACK)
        state.openOpenClawCamera()
    }

    override fun openClawCameraCaptured(jpegBytes: ByteArray) {
        state.openClawCameraJpegBase64 = android.util.Base64.encodeToString(
            jpegBytes,
            android.util.Base64.NO_WRAP,
        )
        state.openClawCameraError = null
        popTone()
    }

    override fun openClawCameraRetake() {
        state.openClawCameraJpegBase64 = null
        state.openClawCameraError = null
        state.openClawCameraBusy = false
        navTone()
    }

    override fun openClawCameraMotorNudge(delta: Int) {
        val prev = state.openClawCameraMotor
        val next = (prev + delta).coerceIn(0, 180)
        if (next == prev) return
        state.openClawCameraMotor = next
        setMotorOrientation(next)
        navTone()
    }

    override fun openClawCameraSend(prompt: String) {
        val session = openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        val image = state.openClawCameraJpegBase64 ?: run {
            state.openClawCameraError = "snap first"
            return
        }
        val trimmed = prompt.trim().ifEmpty { "what do you see?" }
        if (state.openClawCameraBusy) return

        state.openClawCameraBusy = true
        state.openClawCameraError = null
        val optimistic = com.r1.launcher.openclaw.ChatMessage(
            role = "user",
            text = trimmed,
            imageBase64 = image,
            hasImage = true,
        )
        state.chatMessages.add(optimistic)
        trimChatMessages()
        state.chatScrollIndex = 0
        state.chatBusy = true

        session.send(text = trimmed, imageBase64 = image) { ok, runId, err ->
            ui.post {
                state.openClawCameraBusy = false
                if (!ok) {
                    val idx = state.chatMessages.indexOfFirst { it.id == optimistic.id }
                    if (idx >= 0) state.chatMessages.removeAt(idx)
                    state.chatBusy = false
                    state.openClawCameraError = err ?: "send failed"
                    if (!err.isNullOrBlank()) toast("send failed: $err")
                } else {
                    if (runId != null) state.chatPendingRunIds.add(runId)
                    state.openClawCameraJpegBase64 = null
                    state.openClawCameraPrompt = "what do you see?"
                    state.back()
                }
            }
        }
    }

    override fun loadSmsConversations() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED) {
            state.smsError = "permission required"
            return
        }
        if (state.smsLoading) return
        state.smsLoading = true
        state.smsError = null
        Thread {
            val list = runCatching {
                com.r1.launcher.messages.SmsLoader.loadConversations(this)
            }.getOrElse { emptyList() }
            ui.post {
                state.smsConversations.clear()
                state.smsConversations.addAll(list)
                state.smsLoading = false
                if (list.isEmpty() && state.smsError == null) state.smsError = "no messages"
            }
        }.start()
    }

    override fun openSmsThread(address: String, displayName: String) {
        state.openMessagesThread(address, displayName)
        Thread {
            val items = runCatching {
                com.r1.launcher.messages.SmsLoader.loadMessagesFor(this, address)
            }.getOrElse { emptyList() }
            ui.post {
                state.smsThreadMessages.clear()
                state.smsThreadMessages.addAll(items)
            }
        }.start()
    }

    private fun refreshOpenaiKeyState() {
        val k = openClawPrefs.openaiKey
        if (k.isNullOrBlank()) {
            state.chatHasOpenaiKey = false
            state.chatOpenaiKeyTail = ""
        } else {
            state.chatHasOpenaiKey = true
            state.chatOpenaiKeyTail = k.takeLast(4)
        }
    }

    private fun ensureCameraPerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
        }
        return granted
    }

    private fun ensureAudioPerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
        }
        return granted
    }

    private fun ensurePhonePerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_PERM)
        }
        return granted
    }

    private fun ensureSmsPerm(): Boolean {
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val recv = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!read || !recv) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
                REQ_SMS_PERM,
            )
        }
        return read && recv
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            refreshSim()
        }
        if (requestCode == REQ_SMS_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // User just granted READ_SMS while the panel is up; populate now.
            if (state.panel == Panel.MESSAGES) {
                state.smsError = null
                loadSmsConversations()
            }
        }
    }

    // --- tones ---

    override fun navTone() {
        if (movingSoundId != 0) soundPool?.play(movingSoundId, 1f, 1f, 0, 0, 1f)
        else playTone(ToneGenerator.TONE_PROP_BEEP, 18)
    }
    
    override fun selectTone() = playTone(ToneGenerator.TONE_PROP_ACK, 55)
    
    override fun popTone() {
        if (popSoundId != 0) soundPool?.play(popSoundId, 1f, 1f, 0, 0, 1f)
    }
    
    override fun backTone() = playTone(ToneGenerator.TONE_PROP_PROMPT, 35)
    
    private fun launchTone() = playTone(ToneGenerator.TONE_PROP_BEEP2, 80)

    private fun playTone(type: Int, durationMs: Int) {
        val t = tone ?: return
        runCatching {
            t.stopTone()
            t.startTone(type, durationMs)
        }
    }


    // --- key dispatch ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        if ((state.panel == Panel.OPENCLAW_CHAT || state.panel == Panel.OPENCLAW_TALK) && isOpenClawPttKey(code)) {
            return handleOpenClawPttKey(event)
        }

        // Side button is remapped from KEY_POWER (116) → BUTTON_1 in the keylayout
        // (system/usr/keylayout/mtk-kpd.kl, also overridable via /data/system/devices/keylayout/).
        // HOME-mapped keys collapse to one onNewIntent and POWER is intercepted by
        // PhoneWindowManager — BUTTON_1 reaches us with full DOWN/UP/REPEAT timing
        // so we can implement single/double/long press here.
        //
        //   single tap  → HOME panel: lock screen ; other panels: activate selection
        //   double tap  → from any non-home panel: return to home/clock screen
        //   long press  → HOME panel: power dialog ; OpenClaw chat/talk: PTT (handled
        //                 above by isOpenClawPttKey branch)
        if (code == KeyEvent.KEYCODE_BUTTON_1) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        sideDownAtMs = event.eventTime
                        sideLongFired = false
                    } else if (!sideLongFired && event.eventTime - sideDownAtMs >= SIDE_LONG_PRESS_MS) {
                        // Long press: fires on the first key-repeat past the threshold,
                        // so the user gets feedback before they release.
                        sideLongFired = true
                        pendingSideSingle?.let { ui.removeCallbacks(it) }
                        pendingSideSingle = null
                        sideLastShortUpMs = 0L
                        if (state.panel == Panel.HOME) {
                            PowerService.openPowerDialog()
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (sideLongFired) {
                        // Long-press already handled at DOWN-repeat; swallow the UP.
                        sideLongFired = false
                        return true
                    }
                    val now = System.currentTimeMillis()
                    val sincePrev = now - sideLastShortUpMs
                    if (sincePrev < SIDE_DOUBLE_PRESS_MS && pendingSideSingle != null) {
                        // Double-tap: cancel the pending single, go to home/clock.
                        ui.removeCallbacks(pendingSideSingle!!)
                        pendingSideSingle = null
                        sideLastShortUpMs = 0L
                        if (state.panel != Panel.HOME) state.goHome()
                    } else {
                        // Defer single-tap so a follow-up press can convert it to a double.
                        sideLastShortUpMs = now
                        val action = Runnable {
                            pendingSideSingle = null
                            if (state.panel == Panel.HOME) lockScreen()
                            else state.activate(this)
                        }
                        pendingSideSingle = action
                        ui.postDelayed(action, SIDE_DOUBLE_PRESS_MS)
                    }
                }
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (isHandled(code)) true else super.dispatchKeyEvent(event)
        }

        return when (code) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP -> { state.wheelUp(this); true }

            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> { state.wheelDown(this); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_POWER -> { state.activate(this); true }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> { state.backPressed(this); true }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleOpenClawPttKey(event: KeyEvent): Boolean {
        val canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0
        android.util.Log.d(
            "LauncherActivity",
            "OPENCLAW_PTT code=${event.keyCode} action=${event.action} rpt=${event.repeatCount} " +
                "canceled=$canceled flags=0x${Integer.toHexString(event.flags)} " +
                "down=${event.downTime} event=${event.eventTime}"
        )
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    openClawPttKeyCode = event.keyCode
                    openClawRecordStart()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (openClawPttKeyCode == event.keyCode) {
                    openClawPttKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    if (!canceled) openClawRecordStop()
                }
                return true
            }
        }
        return true
    }

    private fun isOpenClawPttKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_POWER -> true
        else -> false
    }

    private fun isHandled(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> true
        else -> false
    }


    // --- misc helpers ---

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}

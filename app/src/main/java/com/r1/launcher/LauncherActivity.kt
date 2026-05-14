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
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.r1.launcher.openclaw.GatewaySession
import com.r1.launcher.openclaw.OpenClawPrefs
import com.r1.launcher.openclaw.decodeGatewaySetupCode
import com.r1.launcher.transcriber.Meeting
import com.r1.launcher.transcriber.MeetingStatus
import com.r1.launcher.transcriber.MeetingStore
import com.r1.launcher.transcriber.ScribeClient
import com.r1.launcher.transcriber.SmtpSender
import com.r1.launcher.transcriber.TranscriberPrefs
import com.r1.launcher.transcriber.TranscriberRecordingService
import com.r1.launcher.transcriber.TranscriptFormatter
import kotlinx.serialization.json.put
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
        private const val REQ_NOTIF_PERM = 4805
        private const val REQ_AUDIO_PERM_TRANSCRIBER = 4806
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            com.r1.launcher.locale.applyLocale(
                newBase,
                com.r1.launcher.locale.LocalePrefs.get(newBase).language,
            )
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // We intercept locale|layoutDirection in configChanges so the system
        // doesn't auto-recreate when only the system locale changes (we honour
        // the user's per-app pick). User-initiated locale changes call recreate()
        // explicitly via setLanguage().
        super.onConfigurationChanged(newConfig)
    }

    private val state = LauncherState()
    private var tone: ToneGenerator? = null
    private var soundPool: SoundPool? = null
    private var movingSoundId: Int = 0
    private var uiClickSoundId: Int = 0
    private var selectSoundId: Int = 0
    private var recordStartSoundId: Int = 0
    private var recordStopSoundId: Int = 0
    private var audioManager: AudioManager? = null

    private val openClawPrefs by lazy { OpenClawPrefs.get(this) }
    private val soundPrefs by lazy { com.r1.launcher.sound.SoundPrefs.get(this) }
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
    private var openClawSpeechPlayer: MediaPlayer? = null
    // In-flight TTS HTTP call — cancellable when the user starts a new
    // recording mid-playback (interrupts download + may save credits).
    private var openClawTtsCall: okhttp3.Call? = null
    private var openClawSpeakNextAssistant = false
    private var openClawLastSpokenKey = ""

    // Streaming-TTS pipeline: chunk the assistant reply by sentence as
    // onChatDelta ticks arrive, fire ElevenLabs synth per chunk in parallel,
    // play MP3s sequentially in issuance order. Cuts perceived first-audio
    // latency from "stream end + history round-trip" to "first sentence
    // boundary in streaming text".
    private var openClawStreamingSpokenOffset: Int = 0
    private var openClawStreamingTtsActive: Boolean = false
    private var openClawStreamingTtsTurnId: Long = 0L
    private var openClawSpeechIssuedSeq: Int = 0
    private var openClawSpeechNextToPlay: Int = 1
    private var openClawSpeechPlaying: Boolean = false
    // seq -> File (success) or null (errored/canceled, skip in playback)
    private val openClawSpeechSlots: java.util.TreeMap<Int, File?> = java.util.TreeMap()
    private val openClawTtsChunkCalls: MutableList<okhttp3.Call> = mutableListOf()
    // The file currently feeding openClawSpeechPlayer — tracked so cancel
    // can delete it (it's no longer in the slots map once playback starts).
    private var openClawSpeechCurrentFile: File? = null

    // Voice/STT state — shared across chat / terminal / claude.
    private val voicePrefs by lazy { com.r1.launcher.voice.VoicePrefs.get(this) }
    private var voiceCapture: com.r1.launcher.voice.StreamingAudioCapture? = null
    private var voiceSession: com.r1.launcher.voice.ElevenLabsRealtimeClient? = null
    /** Which sink to deliver the STT transcript to. */
    private enum class VoiceSink { CHAT, TERMINAL, CLAUDE }
    private var voiceSink: VoiceSink? = null

    // --- meetings (transcriber) ---
    private val transcriberPrefs by lazy { TranscriberPrefs.get(this) }
    private val meetingStore by lazy { MeetingStore.get(this) }

    // --- survey call bot ---
    private val surveyPrefs by lazy { com.r1.launcher.survey.SurveyPrefs.get(this) }
    private val surveyStore by lazy { com.r1.launcher.survey.SurveyStore.get(this) }
    private var transcriberBinder: TranscriberRecordingService.LocalBinder? = null
    private var transcriberServiceBound: Boolean = false
    private var transcriberCurrentMeeting: Meeting? = null
    private var transcriberPlayer: MediaPlayer? = null
    private val transcriberExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val transcriberPollRunnable = object : Runnable {
        override fun run() {
            val b = transcriberBinder
            if (b != null && b.isRecording) {
                state.recordingActive = true
                state.recordingElapsedMs = b.elapsedMs
                state.recordingPeak = b.peakLevel
                ui.postDelayed(this, 200L)
            } else {
                state.recordingActive = false
            }
        }
    }
    private val transcriberServiceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            transcriberBinder = service as? TranscriberRecordingService.LocalBinder
            // If we bound to an active recording (e.g. activity recreate after
            // low-mem kill), surface it back into the panel state.
            if (transcriberBinder?.isRecording == true) {
                // Don't auto-jump panels on rebind; the user is wherever they
                // are, and the list page reflects active recording via the
                // pulsing record row.
                state.recordingActive = true
                ui.removeCallbacks(transcriberPollRunnable)
                ui.post(transcriberPollRunnable)
            }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            transcriberBinder = null
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    // Lazy so Locale.getDefault() resolves AFTER attachBaseContext. The locale
    // helper pins ASCII numerals via the `-u-nu-latn` Unicode extension — see
    // com.r1.launcher.locale.digitFriendlyLocale.
    private val hm by lazy { SimpleDateFormat("h:mm a", com.r1.launcher.locale.digitFriendlyLocale()) }
    private val dt by lazy { SimpleDateFormat("EEEE, MMMM d", com.r1.launcher.locale.digitFriendlyLocale()) }

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

    // adb-installable ElevenLabs key receiver:
    //   adb shell "am broadcast -a com.r1.launcher.SET_ELEVENLABS_KEY --es key sk_..."
    // Lets the user inject the API key without typing it on a 480x480 round
    // screen. Receiver is exported so adb (uid 2000) can reach it.
    private val voiceKeyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            val k = i?.getStringExtra("key")?.trim().orEmpty()
            when {
                k.isEmpty() -> toastFail("--es key missing")
                !isValidElevenKey(k) -> toastFail("not an elevenlabs key")
                else -> {
                    voicePrefs.elevenlabsKey = k
                    refreshVoiceKeyState()
                    toastSuccess("voice key saved")
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
        // Kill the Android system "click" beep that View.playSoundEffect(CLICK)
        // fires on every Compose Modifier.clickable activation. We ship our own
        // UI click cue (SoundPrefs / playUiClickSound) and it stacks on top of
        // the system one, producing a double-tick. Disable on the decor view —
        // propagates to the AndroidComposeView and every clickable inside.
        window.decorView.isSoundEffectsEnabled = false

        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ensurePhonePerm()

        // UI click cues live on STREAM_SYSTEM so the "speaker" slider (which
        // governs STREAM_MUSIC for TTS / media) doesn't bleed into them. The
        // "ui sound" slider is a software gain on soundPool.play(), so we want
        // STREAM_SYSTEM held at max — otherwise we'd be attenuating twice.
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_SYSTEM, 100)
        }.getOrNull()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        runCatching {
            audioManager?.let { am ->
                am.setStreamVolume(
                    AudioManager.STREAM_SYSTEM,
                    am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM),
                    0,
                )
            }
        }
        runCatching {
            val afd = assets.openFd("moving.mp3")
            movingSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }
        runCatching {
            val afd = assets.openFd("UIClick-Very_short_wooden_UI-Elevenlabs.mp3")
            uiClickSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }
        runCatching {
            val afd = assets.openFd("UIClick-very_subtle_button_p-Elevenlabs.mp3")
            selectSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }
        runCatching {
            val afd = assets.openFd("record.mp3")
            recordStartSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }
        runCatching {
            val afd = assets.openFd("release-record.mp3")
            recordStopSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
        }

        // OTA: silent boot check (no toast on "up to date"), wired through carroot
        // for the post-install reboot. The Settings → "check for updates" row
        // calls back in with forcePrompt = true.
        OTAUpdater.executeRootCommand = { cmd -> sendToCarroot(cmd) }
        OTAUpdater.checkForUpdates(this, state, forcePrompt = false) { msg ->
            toast(msg)
        }

        // Hydrate transcriber prefs cache for the UI thread.
        refreshTranscriberPrefsCache()
        // Pre-bind to the recording service so a recovery from low-mem-kill
        // (FGS survives, activity doesn't) immediately sees the active recording.
        bindTranscriberService()

        state.openClawHideChat = openClawPrefs.hideChat
        state.chatFontSize = openClawPrefs.chatFontSize
        // Hydrate global voice state from prefs.
        state.voiceEnabled = voicePrefs.enabled
        state.voiceId = voicePrefs.voiceId
        state.voiceCustomId = voicePrefs.customVoiceId.orEmpty()
        state.voiceModel = voicePrefs.model
        state.voiceStability = voicePrefs.stability
        state.voiceSimilarity = voicePrefs.similarity
        state.voiceStyle = voicePrefs.style
        state.voiceSpeed = voicePrefs.speed
        state.voiceSpeakerBoost = voicePrefs.speakerBoost
        refreshVoiceKeyState()
        state.wifiShareSsid = wifiSharePrefs.ssid
        state.wifiSharePassword = wifiSharePrefs.password
        state.wifiShareTimerMinutes = wifiSharePrefs.timerMinutes
        loadApps()

        // Default the remote panel and Bluetooth to off on every cold start —
        // user opts in via the Network panel.
        toggleBluetooth(false)

        // Make sure the alpine bootstrap helpers are present at /data/local/tmp/.
        // Bundling them as launcher assets means a fresh device (post-`fastboot
        // -w`) boots with the launcher already able to install Claude Code on
        // demand — no adb push, no shell intervention. Background thread so
        // we never block the UI on first launch.
        Thread { runCatching { deployClaudeScripts() } }.start()
        // Also probe Claude's auth state in the background so the on-device
        // tile knows whether to show the QR-redirect (logged out) or drop
        // straight into chat (logged in). Cheap — single carroot test calls.
        refreshClaudeAuthFlag()

        if (!com.r1.launcher.onboarding.OnboardingPrefs.isDone(this)) {
            state.openOnboarding()
            // If the user already picked a language (typically on the previous
            // run before recreate(), or when launching after factory-fresh prefs),
            // skip the language picker and start at welcome.
            if (com.r1.launcher.locale.LocalePrefs.get(this).picked) {
                state.onboardingStep = 1
            }
        }

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
        state.apps.add(AppEntry.Terminal)
        state.apps.add(AppEntry.Claude)
        state.apps.add(AppEntry.Meetings)
        state.apps.add(AppEntry.Survey)
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

        val keyFilter = IntentFilter("com.r1.launcher.SET_ELEVENLABS_KEY")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceKeyRx, keyFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(voiceKeyRx, keyFilter)
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
        // Cancel any in-flight push-to-talk recording — the activity is
        // singleTask so backgrounding doesn't tear down the AudioCapture
        // automatically, which would otherwise complete and send after
        // the user has left the app.
        if (state.chatRecording) runCatching { openClawRecordStop() }
        ui.removeCallbacks(tick)
        runCatching { unregisterReceiver(netRx) }
        runCatching { unregisterReceiver(batteryRx) }
        runCatching { unregisterReceiver(packageRx) }
        runCatching { unregisterReceiver(voiceKeyRx) }
        runCatching { unregisterReceiver(smsLocalRx) }
        runCatching { unregisterReceiver(webToggleRx) }
        runCatching { telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { openClawCloseSessionInternal() }
        runCatching { voiceCapture?.close() }
        voiceCapture = null
        runCatching { voiceSession?.cancel() }
        voiceSession = null
        runCatching { webServer?.stopServer() }
        webServer = null
        // Clean up UI handlers
        runCatching { ui.removeCallbacksAndMessages(null) }
        runCatching { tone?.release() }
        tone = null
        runCatching { soundPool?.release() }
        soundPool = null
        runCatching { openClawTtsCall?.cancel() }
        openClawTtsCall = null
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        // Stop transcriber playback but DO NOT stop the FGS — if a meeting is
        // recording when the user kills the launcher, we want it to keep going
        // until they explicitly stop it. Just unbind from our side.
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        unbindTranscriberService()
        runCatching { transcriberExecutor.shutdownNow() }
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
                    refreshVoiceKeyState()
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
            AppEntry.Terminal -> {
                selectTone()
                state.openTerminal()
            }
            AppEntry.Claude -> {
                selectTone()
                state.openClaude()
                // Don't auto-start the remote panel — that's the user's
                // explicit choice via Settings → Network → "remote panel".
                // The Claude redirect-to-web-companion screen now only
                // shows when the user has already turned remote panel on,
                // so we never present a dead QR.
                // Re-probe auth on every entry — the user may have just
                // logged in from the web companion in another tab without
                // hitting any of the auth-action callbacks. Cheap; runs on
                // a background thread.
                refreshClaudeAuthFlag()
            }
            AppEntry.Meetings -> {
                selectTone()
                transcriberOpen()
            }
            AppEntry.Survey -> {
                selectTone()
                surveyOpen()
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
        // Each callback compares the captured `session` against the current
        // `openClawSession` — late events from a session the user has since
        // closed or replaced must not mutate UI state.
        session.onState = { st ->
            ui.post {
                if (openClawSession !== session) return@post
                state.chatStatus = when (st) {
                    GatewaySession.State.Idle -> "idle"
                    GatewaySession.State.Connecting -> "connecting"
                    is GatewaySession.State.Live -> "live"
                    is GatewaySession.State.Switching -> "switching"
                    is GatewaySession.State.Error -> "error: ${st.message}"
                    is GatewaySession.State.AuthExpired -> "error: ${st.message}"
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
                // Only structured AuthExpired (emitted on connect-time auth
                // failures with known token codes) wipes pairing. Generic
                // method-level "unauthorized" errors no longer force re-pair.
                if (st is GatewaySession.State.AuthExpired) {
                    state.chatBusy = false
                    openClawPrefs.bootstrapToken = null
                    openClawPrefs.deviceToken = null
                    openClawPrefs.sharedToken = null
                    runCatching { openClawCloseSessionInternal() }
                    if (state.panel == Panel.OPENCLAW_CHAT) {
                        state.qrError = st.message
                        state.openOpenClawQr()
                    }
                } else if (st is GatewaySession.State.Error) {
                    state.chatBusy = false
                }
            }
        }
        session.onHistory = { msgs ->
            ui.post {
                if (openClawSession !== session) return@post
                applyOpenClawHistory(msgs)
                state.chatScrollIndex = 0
                speakLatestAssistantIfNeeded()
            }
        }
        session.onChatDelta = { runId, text ->
            ui.post {
                if (openClawSession !== session) return@post
                // Only show streaming preview for runs *we* initiated. Other
                // operators talking to the same agent shouldn't bleed into our
                // local UI (matches official client ChatController.kt:347).
                if (runId == null || state.chatPendingRunIds.contains(runId)) {
                    state.chatStreamingText = text
                    // Slice off any newly-completed sentences and start TTS
                    // before the gateway is done writing — first-audio latency
                    // drops from "stream end + history round-trip" to "first
                    // sentence boundary".
                    maybeEmitStreamingTtsChunk()
                }
            }
        }
        session.onChatTerminal = { runId, evState, errMsg ->
            ui.post {
                if (openClawSession !== session) return@post
                if (runId != null) state.chatPendingRunIds.remove(runId)
                // Don't clear chatStreamingText here — refreshHistory() takes a
                // network round-trip, and clearing now would leave a flicker
                // gap where the assistant bubble vanishes before its persisted
                // copy lands. applyOpenClawHistory() clears it once the new
                // messages are in chatMessages, in the same frame.
                state.chatBusy = false
                // Speak any residue past the last sentence boundary — covers
                // one-line replies, code-blocks, lists with no terminator.
                flushStreamingTtsTail()
                openClawSession?.refreshHistory()
            }
        }
        session.onMainSessionKey = { key ->
            ui.post {
                if (openClawSession !== session) return@post
                state.mainSessionKey = key
                openClawPrefs.lastMainSessionKey = key
            }
        }
        session.onSessions = { list ->
            ui.post {
                if (openClawSession !== session) return@post
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
     * long/tool-heavy runs. Preserve the local prefix only while a run is in
     * flight — once the run terminates and history refreshes, trust the
     * server. Otherwise legitimate server-side deletes/truncations would
     * leave stale messages on the R1 forever.
     */
    private fun applyOpenClawHistory(msgs: List<com.r1.launcher.openclaw.ChatMessage>) {
        val incoming = if (msgs.size > state.chatMessagesMax) {
            msgs.takeLast(state.chatMessagesMax)
        } else msgs
        val current = state.chatMessages.filterNot { it.streaming }
        val lastLocal = current.lastOrNull()?.text?.trim().orEmpty()
        val likelyCommandReset = lastLocal.startsWith("/") && lastLocal.length < 40
        val runInFlight = state.chatBusy || state.chatPendingRunIds.isNotEmpty()
        val next = if (runInFlight && current.isNotEmpty() && incoming.size < current.size && !likelyCommandReset) {
            android.util.Log.w(
                "OpenClaw",
                "chat.history returned fewer messages (${incoming.size}) than local UI (${current.size}); preserving local prefix (run in flight)",
            )
            current.take(current.size - incoming.size) + incoming
        } else {
            incoming
        }
        state.chatMessages.clear()
        state.chatMessages.addAll(next.takeLast(state.chatMessagesMax))
        // Drop the streaming preview now that the persisted messages have
        // landed — same-frame replacement so there's no flicker between the
        // streaming bubble disappearing and the final assistant bubble
        // appearing in chatMessages.
        state.chatStreamingText = ""
    }

    private fun speakLatestAssistantIfNeeded() {
        // No more "OPENCLAW_TALK panel" guard — the talk panel is gone, and
        // we now auto-speak whenever the user's enabled it globally and the
        // chat panel is the active surface (avoid speaking while scanning QR
        // or buried in settings).
        if (!state.voiceEnabled || state.panel != Panel.OPENCLAW_CHAT || !openClawSpeakNextAssistant) return
        val msg = state.chatMessages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return
        val key = "${msg.timestamp}:${msg.text.hashCode()}"
        if (key == openClawLastSpokenKey) return
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toastFail("voice: set elevenlabs key in settings → voice")
            openClawSpeakNextAssistant = false
            return
        }
        openClawLastSpokenKey = key
        openClawSpeakNextAssistant = false
        // Cancel any prior in-flight TTS download / playback before starting a
        // new one — back-to-back assistant messages shouldn't stack audio.
        cancelOpenClawSpeech()
        val cleanText = stripMarkdownForTts(msg.text)
        if (cleanText.isBlank()) return
        val outFile = File(File(cacheDir, "openclaw-voice").apply { mkdirs() }, "assistant.mp3")
        openClawTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanText,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { mp3Bytes, err ->
            openClawTtsCall = null
            if (err == "canceled") return@synthesize  // user-initiated, silent
            if (err != null || mp3Bytes == null) {
                toastFail("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playOpenClawSpeech(mp3Bytes)
        }
    }

    /** Cancel any in-flight TTS download AND stop current playback. Safe to
     *  call when nothing is happening. Used for: back-to-back assistant
     *  messages (stack-prevent), session close, and the interrupt-on-record
     *  path in startVoiceCapture (so press-to-talk over a playing reply
     *  immediately silences it instead of waiting for the audio to finish).
     *  Also tears down the streaming-TTS pipeline (chunk calls + slot map +
     *  per-turn offsets) so a new turn can start clean. */
    private fun cancelOpenClawSpeech() {
        runCatching { openClawTtsCall?.cancel() }
        openClawTtsCall = null
        runCatching { openClawSpeechPlayer?.stop() }
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        runCatching { openClawSpeechCurrentFile?.delete() }
        openClawSpeechCurrentFile = null

        // Streaming-TTS teardown: bumping turnId invalidates any in-flight
        // chunk callbacks that race past the cancel(). Cancel HTTP, drop
        // queued slots, delete leftover chunk MP3s.
        openClawStreamingTtsTurnId++
        openClawTtsChunkCalls.forEach { runCatching { it.cancel() } }
        openClawTtsChunkCalls.clear()
        openClawSpeechSlots.values.forEach { f -> f?.let { runCatching { it.delete() } } }
        openClawSpeechSlots.clear()
        // Belt-and-suspenders: any stream-*.mp3 still in cache (e.g., from a
        // prior process kill where onDestroy didn't run) gets reaped here.
        runCatching {
            File(cacheDir, "openclaw-voice")
                .listFiles { f -> f.name.startsWith("stream-") }
                ?.forEach { runCatching { it.delete() } }
        }
        openClawSpeechIssuedSeq = 0
        openClawSpeechNextToPlay = 1
        openClawSpeechPlaying = false
        openClawStreamingSpokenOffset = 0
        openClawStreamingTtsActive = false
    }

    /** Strip Markdown markers so ElevenLabs reads natural prose, not
     *  "asterisk asterisk bold". Conservative — keeps content, drops
     *  formatting glyphs. Order matters: more specific patterns first. */
    private fun stripMarkdownForTts(input: String): String {
        var s = input
        // Code fences ```lang ... ``` — keep contents, drop fences.
        s = s.replace(Regex("```[a-zA-Z0-9_+-]*\\n?"), "")
        s = s.replace("```", "")
        // Inline code `foo` → foo
        s = s.replace(Regex("`([^`\\n]+)`"), "$1")
        // Image ![alt](url) → alt
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // Link [text](url) → text
        s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // Bold **foo** / __foo__ → foo
        s = s.replace(Regex("\\*\\*([^*\\n]+?)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_\\n]+?)__"), "$1")
        // Italic *foo* / _foo_ — guarded so bullet "* item" at line start is left
        // alone; the bullet rule below removes the marker.
        s = s.replace(Regex("(?<![*\\s])\\*([^*\\n]+?)\\*(?!\\*)"), "$1")
        s = s.replace(Regex("(?<![_\\w])_([^_\\n]+?)_(?!\\w)"), "$1")
        // Strikethrough ~~foo~~ → foo
        s = s.replace(Regex("~~([^~\\n]+)~~"), "$1")
        // ATX headings "# Foo" → "Foo"
        s = s.replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        // Blockquote "> foo" → "foo"
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        // List bullets "- foo" / "* foo" / "+ foo" → "foo"
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        // Collapse runaway whitespace introduced by stripped markers.
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        s = s.replace(Regex(" {2,}"), " ")
        return s.trim()
    }

    /** Find a sentence-boundary split point in `tail`. Returns the index
     *  AFTER the boundary (so substring(0, idx) is ready to speak), or 0
     *  if nothing is ready.
     *
     *  When `firstChunk == true` (no chunks emitted for this run yet),
     *  return the FIRST qualifying boundary with a lower minChunk — gets
     *  audio playing sooner. Otherwise return the LAST qualifying boundary
     *  with a higher minChunk — maximizes prosody quality on subsequent
     *  chunks.
     *
     *  Boundaries: `.`, `!`, `?` followed by whitespace AND preceded by a
     *  letter (skips numbered-list markers `1.`, `2.`, decimals `3.14`);
     *  any `\n\n`. Force-flush at MAX_TAIL on last whitespace if no
     *  qualifying boundary exists yet (code, lists, abbrev-heavy prose). */
    private fun findStreamingSplitPoint(tail: String, firstChunk: Boolean): Int {
        if (tail.isEmpty()) return 0
        val minChunk = if (firstChunk) 16 else 32
        val maxTail = if (firstChunk) 160 else 240
        var firstBoundary = 0
        var lastBoundary = 0
        var i = 0
        while (i < tail.length) {
            val c = tail[i]
            var here = 0
            if ((c == '.' || c == '!' || c == '?') && i + 1 < tail.length) {
                val prev = if (i > 0) tail[i - 1] else ' '
                if (tail[i + 1].isWhitespace() && prev.isLetter() && i + 1 >= minChunk) {
                    here = i + 1
                }
            } else if (c == '\n' && i + 1 < tail.length && tail[i + 1] == '\n' && i + 2 >= minChunk) {
                here = i + 2
            }
            if (here > 0) {
                if (firstBoundary == 0) firstBoundary = here
                lastBoundary = here
                if (firstChunk) return here  // emit ASAP for the opening
            }
            i++
        }
        if (lastBoundary > 0) return lastBoundary
        if (tail.length >= maxTail) {
            val cap = minOf(tail.length, maxTail)
            val ws = tail.substring(0, cap).lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
            if (ws >= minChunk) return ws + 1
        }
        return 0
    }

    /** Look at the cumulative `chatStreamingText`, slice off any newly-
     *  completed sentences past `openClawStreamingSpokenOffset`, and
     *  enqueue them for ElevenLabs synth. Idempotent and turn-scoped.
     *  Stays a no-op when ElevenLabs key is missing — the post-stream
     *  fallback then surfaces the toast (don't double-error). */
    private fun maybeEmitStreamingTtsChunk() {
        if (!openClawStreamingTtsActive && !openClawSpeakNextAssistant) {
            android.util.Log.d("StreamingTts", "skip: active=$openClawStreamingTtsActive arm=$openClawSpeakNextAssistant")
            return
        }
        if (state.panel != Panel.OPENCLAW_CHAT || !state.voiceEnabled) {
            android.util.Log.d("StreamingTts", "skip: panel=${state.panel} voiceEnabled=${state.voiceEnabled}")
            return
        }
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) {
            android.util.Log.d("StreamingTts", "skip: elevenlabs key missing")
            return
        }
        val full = state.chatStreamingText
        if (full.length <= openClawStreamingSpokenOffset) return
        val tail = full.substring(openClawStreamingSpokenOffset)
        // First chunk uses a faster, more permissive splitter so audio
        // starts as soon as possible. Subsequent chunks pick the last
        // boundary in the tail for smoother prosody.
        val firstChunk = openClawSpeechIssuedSeq == 0
        val split = findStreamingSplitPoint(tail, firstChunk)
        if (split <= 0) {
            android.util.Log.d("StreamingTts", "no boundary in tail (len=${tail.length}, first=$firstChunk)")
            return
        }
        val chunk = tail.substring(0, split).trim()
        openClawStreamingSpokenOffset += split
        if (chunk.isEmpty()) return
        openClawStreamingTtsActive = true
        openClawSpeakNextAssistant = false
        android.util.Log.i("StreamingTts", "emit chunk: '${chunk.take(60)}...' (len=${chunk.length})")
        enqueueStreamingTtsChunk(chunk)
    }

    /** Flush any non-empty residue past `openClawStreamingSpokenOffset` —
     *  for replies that ended without a sentence terminator (one-line
     *  numeric answers, raw lists, model truncation). Called from
     *  onChatTerminal, after the last delta has landed. Only runs if
     *  streaming TTS already claimed this run (otherwise the post-stream
     *  one-shot will speak the full message). */
    private fun flushStreamingTtsTail() {
        if (state.panel != Panel.OPENCLAW_CHAT || !state.voiceEnabled) return
        if (!openClawStreamingTtsActive) return  // streaming never started for this run
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) return
        val full = state.chatStreamingText
        if (full.length <= openClawStreamingSpokenOffset) return
        val tail = full.substring(openClawStreamingSpokenOffset).trim()
        openClawStreamingSpokenOffset = full.length
        if (tail.isEmpty()) return
        enqueueStreamingTtsChunk(tail)
    }

    private fun enqueueStreamingTtsChunk(chunk: String) {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) return
        val cleanChunk = stripMarkdownForTts(chunk)
        if (cleanChunk.isBlank()) return  // chunk was pure markdown decoration
        val turnId = openClawStreamingTtsTurnId
        val seq = ++openClawSpeechIssuedSeq
        val outFile = File(
            File(cacheDir, "openclaw-voice").apply { mkdirs() },
            "stream-$turnId-$seq.mp3",
        )
        val call = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanChunk,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { _, err ->
            // synthesize callbacks come back on main via its internal Handler.
            if (turnId != openClawStreamingTtsTurnId) {
                android.util.Log.d("StreamingTts", "stale chunk seq=$seq (turn $turnId vs ${openClawStreamingTtsTurnId})")
                runCatching { outFile.delete() }
                return@synthesize
            }
            if (err != null) {
                android.util.Log.w("StreamingTts", "chunk seq=$seq err=$err")
                openClawSpeechSlots[seq] = null
                runCatching { outFile.delete() }
            } else {
                android.util.Log.i("StreamingTts", "chunk seq=$seq ready (${outFile.length()} bytes)")
                openClawSpeechSlots[seq] = outFile
            }
            drainStreamingSpeechQueue()
        }
        if (call != null) {
            if (turnId != openClawStreamingTtsTurnId) {
                runCatching { call.cancel() }
            } else {
                openClawTtsChunkCalls.add(call)
            }
        }
    }

    /** If the next-to-play slot is filled and nothing is currently playing,
     *  start playback. Skips errored slots. Re-fires from each MediaPlayer's
     *  onCompletion to chain the queue. Main thread only. */
    private fun drainStreamingSpeechQueue() {
        while (true) {
            if (openClawSpeechPlaying) return
            val next = openClawSpeechNextToPlay
            if (!openClawSpeechSlots.containsKey(next)) return
            val file = openClawSpeechSlots.remove(next)
            openClawSpeechNextToPlay = next + 1
            if (file == null) continue  // errored slot — skip
            playStreamingSpeechFile(file)
            return  // playStreamingSpeechFile sets playing = true
        }
    }

    private fun playStreamingSpeechFile(file: File) {
        android.util.Log.i("StreamingTts", "play ${file.name} (${file.length()} bytes)")
        runCatching { openClawSpeechPlayer?.stop() }
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        // If cancel runs mid-playback, this file isn't in the slots map
        // anymore — track it so cancelOpenClawSpeech can delete it.
        runCatching { openClawSpeechCurrentFile?.delete() }
        openClawSpeechCurrentFile = file
        val turnId = openClawStreamingTtsTurnId
        runCatching {
            openClawSpeechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    runCatching { file.delete() }
                    if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
                    if (turnId != openClawStreamingTtsTurnId) {
                        openClawSpeechPlaying = false
                        return@setOnCompletionListener
                    }
                    openClawSpeechPlaying = false
                    drainStreamingSpeechQueue()
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    runCatching { file.delete() }
                    if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
                    openClawSpeechPlaying = false
                    if (turnId == openClawStreamingTtsTurnId) drainStreamingSpeechQueue()
                    true
                }
                prepare()
                start()
            }
            openClawSpeechPlaying = true
        }.onFailure {
            openClawSpeechPlaying = false
            runCatching { file.delete() }
            if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
        }
    }

    /** Play TTS audio bytes (MP3 from ElevenLabs Flash v2.5) via MediaPlayer.
     *  MediaPlayer handles MP3 natively — no WAV header normalization needed
     *  like the prior Whisper/Sherpa pipelines. */
    private fun playOpenClawSpeech(audioBytes: ByteArray) {
        runCatching {
            // Don't force MEDIA volume to max — that ignored the user's volume
            // setting and blasted every TTS reply. Playback still routes through
            // USAGE_MEDIA → STREAM_MUSIC, so the existing Settings → Sound →
            // Volume slider directly controls reply loudness.
            val dir = File(cacheDir, "openclaw-voice").apply { mkdirs() }
            val out = File(dir, "assistant.mp3")
            out.writeBytes(audioBytes)
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
                    toastFail("voice playback failed")
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            toastFail("voice playback: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun openClawCloseSessionInternal() {
        runCatching { voiceCapture?.close() }
        voiceCapture = null
        runCatching { voiceSession?.cancel() }
        voiceSession = null
        voiceSink = null
        // Tears down both the legacy one-shot TTS and the streaming-TTS
        // pipeline (chunk HTTP calls + queued MP3 files + per-turn offsets).
        cancelOpenClawSpeech()
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
        // UI feedback volume comes from our own pref — independent of STREAM_MUSIC.
        state.uiVolumeMax = com.r1.launcher.sound.SoundPrefs.MAX_UI_LEVEL
        state.uiVolumeLevel = soundPrefs.uiVolumeLevel
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

    override fun setUiVolume(level: Int) {
        val clamped = level.coerceIn(0, com.r1.launcher.sound.SoundPrefs.MAX_UI_LEVEL)
        state.uiVolumeLevel = clamped
        soundPrefs.uiVolumeLevel = clamped
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

    override fun onOnboardingDone() {
        com.r1.launcher.onboarding.OnboardingPrefs.markDone(this)
        state.isOnboarding = false
        state.goHome()
    }

    override fun setLanguage(code: String) {
        val prefs = com.r1.launcher.locale.LocalePrefs.get(this)
        if (prefs.language == code && prefs.picked) return
        prefs.language = code
        prefs.picked = true
        // recreate() rebuilds attachBaseContext → applyLocale → all stringResource
        // lookups + LocalLayoutDirection re-evaluate against the new locale.
        recreate()
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
        toastFail("Settings UI unavailable")
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
        toastFail("Date settings unavailable")
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
                    toastFail("Wi-Fi toggle failed")
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
                    toastFail("Cellular toggle failed")
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
        if (state.isOnboarding) {
            state.advanceOnboarding()
        } else {
            state.back()
        }
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
                    toastFail((if (enable) "Hotspot failed: " else "Stop failed: ") + reason.take(80))
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
            toastFail("name can't be empty")
            return
        }
        if (target == WifiShareEditTarget.PASSWORD && input.length < 8) {
            toastFail("password needs 8+ chars")
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

    override fun rebootDevice() {
        toast("Restarting…")
        Thread {
            // `svc power reboot` is the userspace API; falls back to the binary
            // `reboot` (toybox) which carroot can invoke as root.
            if (!sendToCarroot("svc power reboot null")) {
                Thread.sleep(200)
                if (!sendToCarroot("reboot")) {
                    ui.post { toast("Reboot failed: no root shell") }
                }
            }
        }.start()
    }

    override fun powerOffDevice() {
        toast("Powering off…")
        Thread {
            // `svc power shutdown` triggers the orderly Android shutdown
            // sequence; `reboot -p` is the busybox/toybox fallback.
            if (!sendToCarroot("svc power shutdown")) {
                Thread.sleep(200)
                if (!sendToCarroot("reboot -p")) {
                    ui.post { toast("Power off failed: no root shell") }
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

    // --- LauncherHost: terminal panel ---

    private var terminalActiveThread: Thread? = null

    /**
     * One-shot streaming variant of [sendToCarroot]. Each carroot connection
     * gets a fresh `sh`, so cwd doesn't survive between calls — we wrap the
     * user command in `cd <cwd> ; (cmd) ; pwd > <pwdFile> ; printf SENTINEL`
     * and read the new cwd from disk after the stream ends.
     *
     * `onLine` fires on a background thread for every newline-terminated chunk
     * the shell emits. `onDone(exitCode, newCwd)` fires once after EOF.
     */
    private fun sendToCarrootStreaming(
        userCmd: String,
        cwd: String,
        onLine: (String) -> Unit,
        onDone: (exitCode: Int, newCwd: String) -> Unit,
    ): Thread {
        val t = Thread {
            val pwdFile = "/data/local/tmp/r1_term_cwd"
            val safeCwd = cwd.replace("\"", "\\\"")
            // ; not && so the user's command runs even if `cd` fails (e.g.
            // first launch where cwd doesn't exist yet). The trailing pwd
            // captures whatever directory the shell ended up in — that's how
            // `cd X` from the user persists into the next command.
            val script = "cd \"$safeCwd\" 2>/dev/null; $userCmd; ec=\$?; " +
                "pwd > $pwdFile 2>/dev/null; printf '\\n__R1_END__%s__\\n' \"\$ec\""
            var exitCode = -1
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                    val out = s.getOutputStream()
                    out.write((script + "\n").toByteArray())
                    out.flush()
                    s.shutdownOutput()  // signal EOF so `sh` reads the script and exits
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val sentinel = Regex("__R1_END__(-?\\d+)__").find(line)
                        if (sentinel != null) {
                            exitCode = sentinel.groupValues[1].toIntOrNull() ?: -1
                            break
                        }
                        onLine(line)
                    }
                }
            } catch (e: Exception) {
                onLine("[r1-terminal] socket: ${e.message ?: e.javaClass.simpleName}")
            }
            val newCwd = runCatching { File(pwdFile).readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: cwd
            onDone(exitCode, newCwd)
        }
        t.start()
        return t
    }

    private fun appendTerminalLine(line: String) {
        state.terminalOutput.add(line)
        while (state.terminalOutput.size > state.terminalOutputMax) {
            state.terminalOutput.removeAt(0)
        }
        // Fan out to any connected web clients so the Terminal tab mirrors
        // the on-device buffer in real time.
        runCatching { webServer?.broadcastTerminalOutput(line, state.terminalCwd) }
    }

    override fun terminalRun(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        if (state.terminalBusy) {
            toast("busy")
            return
        }
        // Echo prompt + command into the buffer so the scrollback is readable.
        appendTerminalLine("${promptLabel(state.terminalCwd)} $trimmed")
        state.terminalInput = ""
        state.terminalScrollIndex = 0

        // Client-side `clear` shortcut: don't burn a carroot round-trip on it.
        if (trimmed == "clear" || trimmed == "cls") {
            state.terminalOutput.clear()
            return
        }

        // Auto-route Alpine-only commands (npm, node, python, …) through the
        // chroot wrapper so the user can type `npm install foo` directly
        // instead of `sh /data/local/tmp/r1-alpine "npm install foo"`. Use
        // `alpine: <anything>` to force-route any other command.
        val resolved = resolveAlpineWrapping(trimmed)

        state.terminalBusy = true
        terminalActiveThread = sendToCarrootStreaming(
            userCmd = resolved,
            cwd = state.terminalCwd,
            onLine = { line -> ui.post { appendTerminalLine(line) } },
            onDone = { exitCode, newCwd ->
                ui.post {
                    if (newCwd != state.terminalCwd) state.terminalCwd = newCwd
                    if (exitCode != 0 && exitCode != -1) {
                        appendTerminalLine("[exit $exitCode]")
                    }
                    state.terminalBusy = false
                    terminalActiveThread = null
                }
            },
        )
    }

    override fun terminalClear() {
        state.terminalOutput.clear()
        state.terminalScrollIndex = 0
    }

    override fun setWebTerminalEnabled(enable: Boolean) {
        state.webTerminalEnabled = enable
        toast(if (enable) "remote terminal: on (root over LAN)" else "remote terminal: off")
    }

    /**
     * Open an ElevenLabs Realtime STT session and start streaming mic PCM
     * into it. Single shared client across the three voice-input panels —
     * different sinks for the final transcript:
     *   CHAT:     auto-send via openClawSendText (release-to-send UX)
     *   TERMINAL: paste into terminalInput (don't auto-execute)
     *   CLAUDE:   paste into claudeInput (don't auto-execute)
     *
     * Does the audio-perm check, voice-key check, and recording-state setup;
     * also wires partial transcripts to the right state field for the UI to
     * show "listening: <live text>" while the user speaks.
     */
    private fun startVoiceCapture(sink: VoiceSink) {
        if (!ensureAudioPerm()) return
        // Mic source is single-consumer: if the meetings FGS is recording, abort.
        // Without this we'd silently get garbage frames or have the FGS lose
        // its audio path mid-meeting.
        if (transcriberBinder?.isRecording == true) {
            toastFail("stop recording first")
            return
        }
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            toast("voice: set elevenlabs key in settings → voice")
            return
        }
        if (voiceCapture != null) return // already recording
        // Interrupt any TTS reply that's mid-download or mid-playback. Two
        // wins: (1) the user can cut the assistant off to interject without
        // waiting for it to finish speaking, (2) cancelling the OkHttp call
        // mid-stream stops further bytes from arriving (saves bandwidth, may
        // also save credits depending on ElevenLabs' refund behavior for
        // cancelled streams).
        cancelOpenClawSpeech()
        voiceSink = sink
        when (sink) {
            VoiceSink.CHAT -> {
                state.chatRecording = true
                state.chatPartialText = ""
                state.chatInputLevel = 0
            }
            VoiceSink.TERMINAL -> {
                state.terminalRecording = true
                state.terminalPartial = ""
            }
            VoiceSink.CLAUDE -> {
                state.claudeRecording = true
                state.claudePartial = ""
            }
        }
        playRecordStartTone()

        val session = com.r1.launcher.voice.ElevenLabsRealtimeClient.open(
            apiKey = key,
            onPartial = { text ->
                ui.post {
                    when (sink) {
                        VoiceSink.CHAT -> state.chatPartialText = text
                        VoiceSink.TERMINAL -> state.terminalPartial = text
                        VoiceSink.CLAUDE -> state.claudePartial = text
                    }
                }
            },
            onCommitted = { text ->
                ui.post { handleCommittedTranscript(sink, text) }
            },
            onError = { msg ->
                ui.post {
                    toast("voice: $msg")
                    cancelVoiceCapture()
                }
            },
        )
        voiceSession = session

        val cap = com.r1.launcher.voice.StreamingAudioCapture()
        voiceCapture = cap
        // Delay mic open by ~200ms so the recording cue (record.mp3 via SoundPool
        // on USAGE_MEDIA) plays through. AudioRecord on VOICE_RECOGNITION steals
        // the audio path and silences MEDIA mid-sample, so without the delay
        // the cue is inaudible. Re-check voiceCapture inside the post — the
        // user may have released before the delay fired (very-short tap).
        ui.postDelayed({
            if (voiceCapture !== cap) return@postDelayed
            cap.start(object : com.r1.launcher.voice.StreamingAudioCapture.Callback {
                override fun onPcm(chunk: ByteArray) {
                    voiceSession?.sendPcm(chunk)
                }
                override fun onLevel(levelPct: Int) {
                    if (sink == VoiceSink.CHAT) state.chatInputLevel = levelPct
                }
                override fun onError(msg: String) {
                    ui.post {
                        toast("mic: $msg")
                        cancelVoiceCapture()
                    }
                }
            })
        }, 200L)
    }

    /** Stop streaming and ask the server for a final transcript via VAD/commit.
     *  The committed_transcript callback will deliver the result, then we
     *  finalize through handleCommittedTranscript. */
    private fun stopVoiceCapture() {
        voiceCapture?.close()
        voiceCapture = null
        voiceSession?.finish()
        // voiceSession nulled inside handleCommittedTranscript or onError.
        when (voiceSink) {
            VoiceSink.CHAT -> {
                state.chatRecording = false
                state.chatInputLevel = 0
            }
            VoiceSink.TERMINAL -> state.terminalRecording = false
            VoiceSink.CLAUDE -> state.claudeRecording = false
            null -> {}
        }
        playRecordStopTone()
    }

    /** Hard-cancel: drop session, no transcript expected. */
    private fun cancelVoiceCapture() {
        voiceCapture?.close()
        voiceCapture = null
        voiceSession?.cancel()
        voiceSession = null
        when (voiceSink) {
            VoiceSink.CHAT -> {
                state.chatRecording = false
                state.chatInputLevel = 0
                state.chatPartialText = ""
            }
            VoiceSink.TERMINAL -> {
                state.terminalRecording = false
                state.terminalPartial = ""
            }
            VoiceSink.CLAUDE -> {
                state.claudeRecording = false
                state.claudePartial = ""
            }
            null -> {}
        }
        voiceSink = null
    }

    private fun handleCommittedTranscript(sink: VoiceSink, text: String) {
        val clean = text.trim()
        when (sink) {
            VoiceSink.CHAT -> {
                state.chatPartialText = ""
                if (clean.isNotEmpty()) openClawSendText(clean)
            }
            VoiceSink.TERMINAL -> {
                state.terminalPartial = ""
                if (clean.isNotEmpty()) {
                    state.terminalInput = if (state.terminalInput.isBlank()) clean
                        else state.terminalInput.trimEnd() + " " + clean
                }
            }
            VoiceSink.CLAUDE -> {
                state.claudePartial = ""
                if (clean.isNotEmpty()) {
                    state.claudeInput = if (state.claudeInput.isBlank()) clean
                        else state.claudeInput.trimEnd() + " " + clean
                }
            }
        }
        voiceSession = null
        voiceSink = null
    }

    override fun terminalRecordStart() = startVoiceCapture(VoiceSink.TERMINAL)
    override fun terminalRecordStop()  = stopVoiceCapture()

    override fun terminalPasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        // Append (with a leading space if the field is non-empty) so prior
        // typing isn't clobbered. User can wheel-press to run.
        state.terminalInput = if (state.terminalInput.isBlank()) raw
            else state.terminalInput.trimEnd() + " " + raw
    }

    // --- LauncherHost: claude code app (Panel.CLAUDE) ---

    private var claudeActiveThread: Thread? = null

    private fun appendClaudeMessage(msg: com.r1.launcher.claude.ClaudeMessage) {
        state.claudeMessages.add(msg)
        while (state.claudeMessages.size > state.claudeMessagesMax) {
            state.claudeMessages.removeAt(0)
        }
        // Mirror to the web companion's claude tab (no-op when no clients).
        runCatching { webServer?.broadcastClaudeMessage(msg.role, msg.text, msg.error) }
    }

    override fun claudeSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (state.claudeBusy) {
            toast("busy — wait for reply")
            return
        }
        appendClaudeMessage(com.r1.launcher.claude.ClaudeMessage(role = "user", text = trimmed))
        state.claudeInput = ""
        state.claudeScrollIndex = 0
        state.claudeBusy = true
        state.claudeStreamingText = ""
        runCatching { webServer?.broadcastClaudeBusy(true) }

        // Encode user text via base64 so any quotes / special chars / newlines
        // round-trip cleanly through the carroot socket → ash → chroot → ash
        // → claude pipeline without any escaping headaches. base64 is in
        // busybox on Android and present in alpine.
        val b64 = android.util.Base64.encodeToString(
            trimmed.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        // First turn omits `--continue` (no prior session); subsequent turns
        // use `-c` to continue the most recent session, giving claude full
        // memory of prior turns. Reset on "clear chat" pill.
        val resumeFlag = if (state.claudeFirstTurn) "" else "-c"
        // Pipe decoded prompt through claude --print as stdin; --output-format
        // text gives us plain UTF-8 with no JSON wrapping. claude streams
        // tokens to stdout line-by-line (well, mostly; we accept whatever
        // arrives). Each line goes into state.claudeStreamingText until done.
        val script = "echo '$b64' | base64 -d | " +
            "sh /data/local/tmp/r1-alpine 'claude --print $resumeFlag --output-format text 2>&1'"

        claudeActiveThread = sendToCarrootStreaming(
            userCmd = script,
            cwd = "/sdcard",
            onLine = { line -> ui.post {
                // Accumulate streamed lines into the live preview cell.
                val sep = if (state.claudeStreamingText.isEmpty()) "" else "\n"
                state.claudeStreamingText = state.claudeStreamingText + sep + line
                runCatching { webServer?.broadcastClaudeStreaming(state.claudeStreamingText) }
            } },
            onDone = { exitCode, _ ->
                ui.post {
                    val body = state.claudeStreamingText
                    state.claudeStreamingText = ""
                    if (body.isNotBlank()) {
                        appendClaudeMessage(
                            com.r1.launcher.claude.ClaudeMessage(
                                role = "assistant",
                                text = body,
                                error = exitCode != 0 && exitCode != -1,
                            ),
                        )
                    } else if (exitCode != 0 && exitCode != -1) {
                        appendClaudeMessage(
                            com.r1.launcher.claude.ClaudeMessage(
                                role = "assistant",
                                text = getString(R.string.claude_exit_no_output, exitCode),
                                error = true,
                            ),
                        )
                    }
                    state.claudeBusy = false
                    state.claudeFirstTurn = false
                    claudeActiveThread = null
                    runCatching {
                        webServer?.broadcastClaudeStreaming("")
                        webServer?.broadcastClaudeBusy(false)
                    }
                }
            },
        )
    }

    override fun claudeClear() {
        state.claudeMessages.clear()
        state.claudeStreamingText = ""
        state.claudeScrollIndex = 0
        // Next send starts a fresh session (no `-c`) so the cleared UI
        // matches a cleared conversation context on the claude side too.
        state.claudeFirstTurn = true
        runCatching {
            webServer?.broadcastClaudeStreaming("")
            webServer?.broadcastClaudeCleared()
        }
    }

    override fun claudePasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        state.claudeInput = if (state.claudeInput.isBlank()) raw
            else state.claudeInput.trimEnd() + " " + raw
    }

    override fun claudeRecordStart() = startVoiceCapture(VoiceSink.CLAUDE)
    override fun claudeRecordStop()  = stopVoiceCapture()

    // --- LauncherHost: claude bootstrap (alpine + claude binary deploy) ---

    /**
     * Names of every script we ship as a launcher asset. Each lives at
     * `assets/scripts/<name>` and gets copied to `/data/local/tmp/<name>`
     * via carroot on first run. Order doesn't matter — they're independent
     * files. The bootstrap chain only runs `bootstrap-alpine.sh` →
     * `setup-claude-agent.sh` → `setup-claude-user.sh` (see [claudeSetupRun]).
     */
    private val claudeAssetScripts = listOf(
        "bootstrap-alpine.sh",
        "setup-claude-agent.sh",
        "setup-claude-user.sh",
        "claude-auth-start.sh",
        "claude-auth-finish.sh",
        "r1-alpine",
    )

    /**
     * Stage every helper script under `/data/local/tmp/` if it isn't already
     * there, or if the bytes don't match the asset (so a launcher upgrade
     * picks up newer script versions). Runs as `shell` for the asset → app
     * dir copy, then carroots a `cp` to land the file in `/data/local/tmp/`
     * (which `shell` cannot write to).
     */
    private fun deployClaudeScripts() {
        val stagingDir = java.io.File(filesDir, "scripts").apply { mkdirs() }
        claudeAssetScripts.forEach { name ->
            val staged = java.io.File(stagingDir, name)
            val bytes = runCatching {
                assets.open("scripts/$name").use { it.readBytes() }
            }.getOrNull() ?: return@forEach

            // Only rewrite when content drifts — keeps the writeText cheap on
            // boot if the launcher version didn't change the script.
            val current = runCatching { staged.readBytes() }.getOrNull()
            if (current == null || !current.contentEquals(bytes)) {
                staged.writeBytes(bytes)
            }
        }
        // Bridge from the launcher's private dir to /data/local/tmp/ via
        // carroot. carroot is root, so it can read /data/data/<pkg>/files/
        // (mode 700, owner u0_a*) and write to /data/local/tmp/. Single
        // carroot call to amortise the socket overhead.
        val cmd = buildString {
            append("mkdir -p /data/local/tmp && ")
            claudeAssetScripts.forEach { name ->
                append("cp -f ${stagingDir.absolutePath}/$name /data/local/tmp/$name && ")
            }
            append("chmod 755 /data/local/tmp/*.sh /data/local/tmp/r1-alpine 2>/dev/null && ")
            append("echo SCRIPTS_DEPLOYED")
        }
        val log = runCarrootBlocking(cmd, timeoutMs = 8_000)
        if (!log.contains("SCRIPTS_DEPLOYED")) {
            android.util.Log.w(
                "LauncherActivity",
                "deployClaudeScripts: carroot copy did not confirm — log: ${log.take(400)}",
            )
        }
    }

    // --- LauncherHost: claude OAuth + API-key auth ---

    /**
     * Run a one-shot script via carroot, capturing all stdout+stderr until
     * the shell EOFs or [timeoutMs] elapses. Used for the auth helper scripts
     * which print a few lines and exit. Blocks the calling thread; callers
     * MUST be on a background thread (web RPC handler is fine).
     */
    private fun runCarrootBlocking(cmd: String, timeoutMs: Long = 15_000): String {
        val sb = StringBuilder()
        val done = java.util.concurrent.CountDownLatch(1)
        val script = "$cmd ; printf '\\n__R1_END__\\n'"
        val t = Thread {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                    s.soTimeout = timeoutMs.toInt()
                    val out = s.getOutputStream()
                    out.write((script + "\n").toByteArray())
                    out.flush()
                    s.shutdownOutput()
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.contains("__R1_END__")) break
                        sb.appendLine(line)
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("[carroot-error] ${e.message ?: e.javaClass.simpleName}")
            } finally {
                done.countDown()
            }
        }
        t.start()
        if (!done.await(timeoutMs + 2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            sb.appendLine("[carroot-error] timeout")
        }
        return sb.toString()
    }

    /**
     * Pull the first OAuth URL from a script log. The Anthropic flow prints
     * URLs like https://claude.ai/oauth/authorize?... — we accept anything
     * that starts with https:// to be lenient with future format changes.
     */
    private fun parseOAuthUrl(log: String): String {
        val rx = Regex("""https://[^\s'"<>]+""")
        return rx.findAll(log)
            .map { it.value }
            .firstOrNull { it.contains("oauth") || it.contains("claude.ai") || it.contains("anthropic") }
            ?: rx.find(log)?.value
            ?: ""
    }

    private fun chrootReady(): Boolean {
        // "Ready" means: (1) the wrapper is staged at /data/local/tmp/r1-alpine,
        // (2) alpine is extracted (alpine/usr exists), AND (3) the claude binary
        // is installed (its symlink at /root/.local/bin/claude resolves). The
        // third check is critical — without it, the auth scripts fail the
        // moment they try to launch `claude auth login`. Mid-bootstrap we want
        // chrootReady=false so the web UI keeps showing the "installing"
        // state instead of prematurely flipping to the login form.
        val log = runCarrootBlocking(
            "test -x /data/local/tmp/r1-alpine && " +
                "test -d /data/local/tmp/alpine/usr && " +
                "{ test -e /data/local/tmp/alpine/root/.local/bin/claude || test -L /data/local/tmp/alpine/root/.local/bin/claude; } && " +
                "echo READY || echo NOT_READY",
            timeoutMs = 5_000,
        )
        return log.contains("READY") && !log.contains("NOT_READY")
    }

    override fun claudeAuthStatus(): com.r1.launcher.claude.ClaudeAuthStatus {
        // OAuth surface is `claude auth login --claudeai` (FIFO flow, see
        // claude-auth-{start,finish}.sh). It writes credentials.json under
        // the chroot's /root/.claude/ then we sync to /home/claude/.claude/
        // for the unprivileged claude user. Either path being non-empty is
        // proof of an OAuth login.
        val log = runCarrootBlocking(
            """
            CHROOT=NO; OAUTH=NO; KEY=NO
            test -x /data/local/tmp/r1-alpine && \
                test -d /data/local/tmp/alpine/usr && \
                { test -e /data/local/tmp/alpine/root/.local/bin/claude || test -L /data/local/tmp/alpine/root/.local/bin/claude; } && CHROOT=YES
            test -s /data/local/tmp/alpine/home/claude/.claude/.credentials.json && OAUTH=YES
            test -s /data/local/tmp/alpine/root/.claude/.credentials.json && OAUTH=YES
            test -s /data/local/tmp/.anthropic_key && KEY=YES
            echo CHROOT=${'$'}CHROOT OAUTH=${'$'}OAUTH KEY=${'$'}KEY
            """.trimIndent(),
            timeoutMs = 5_000,
        )
        return com.r1.launcher.claude.ClaudeAuthStatus(
            hasOAuth = log.contains("OAUTH=YES"),
            hasApiKey = log.contains("KEY=YES"),
            chrootReady = log.contains("CHROOT=YES"),
        )
    }

    /**
     * Background-thread refresh of [LauncherState.claudeAuthed]. Drives the
     * "show QR redirect vs. drop into chat" decision in the Claude tile.
     * Runs cheap carroot test calls; safe to fire from onCreate, after
     * bootstrap completion, and after every auth-action handler.
     */
    private fun refreshClaudeAuthFlag() {
        Thread {
            runCatching {
                val s = claudeAuthStatus()
                runOnUiThread { state.claudeAuthed = s.hasOAuth || s.hasApiKey }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun claudeAuthStart(): com.r1.launcher.claude.ClaudeAuthStartResult {
        if (!chrootReady()) {
            return com.r1.launcher.claude.ClaudeAuthStartResult(
                url = "", log = "",
                error = "alpine chroot not bootstrapped on this device",
            )
        }
        // The script blocks for ~4s after kicking off the daemon then cats
        // the log it has captured so far. 12s gives ample slack for slow
        // first-token TLS handshakes to claude.ai.
        val log = runCarrootBlocking(
            "sh /data/local/tmp/claude-auth-start.sh",
            timeoutMs = 12_000,
        )
        val url = parseOAuthUrl(log)
        return com.r1.launcher.claude.ClaudeAuthStartResult(
            url = url,
            log = log,
            error = if (url.isEmpty()) {
                "no OAuth URL printed — check log (claude binary may be missing)"
            } else null,
        )
    }

    override fun claudeAuthFinish(code: String): com.r1.launcher.claude.ClaudeAuthFinishResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return com.r1.launcher.claude.ClaudeAuthFinishResult(
                ok = false, log = "", error = "empty code",
            )
        }
        // Single-quote the code so the '#' it contains doesn't start a shell
        // comment. The script itself uses double quotes around $1 so single
        // quotes round-trip safely.
        val safe = trimmed.replace("'", "'\\''")
        val log = runCarrootBlocking(
            "sh /data/local/tmp/claude-auth-finish.sh '$safe'",
            // Script polls up to 20s for /root/.claude/.credentials.json
            // to land *and* runs a `claude --print` probe as the
            // unprivileged user to verify the credentials actually
            // authenticate. 50s gives both phases plenty of room without
            // dropping the socket mid-verify on a slow uplink.
            timeoutMs = 50_000,
        )
        // Two-tier success: the file must land AND the verify probe inside
        // finish.sh must not surface "Not logged in". File-only success was
        // the source of the "I logged in but it still says not logged in"
        // bug — a code-reuse / expired-token attempt writes a partial
        // .credentials.json (> 0 bytes) but `claude --print` rejects it.
        val status = claudeAuthStatus()
        val probeFailed = log.contains("Not logged in", ignoreCase = true) ||
            log.contains("Please run /login", ignoreCase = true) ||
            log.contains("CREDS_READABLE=NO")
        val ok = status.hasOAuth && !probeFailed
        runOnUiThread { state.claudeAuthed = status.hasOAuth || status.hasApiKey }
        return com.r1.launcher.claude.ClaudeAuthFinishResult(
            ok = ok,
            log = log,
            error = when {
                !status.hasOAuth -> "no .credentials.json after finish — code may be expired or already used"
                probeFailed -> "credentials saved but claude rejected them — use 'reset credentials' and retry with a fresh url"
                else -> null
            },
        )
    }

    override fun claudeAuthVerify(): com.r1.launcher.claude.ClaudeAuthVerifyResult {
        // Light end-to-end probe: launches `claude --print 'pong'` as the
        // unprivileged `claude` user (the one the chat panel actually uses)
        // and reports back whether the auth succeeded. Used by the web
        // companion's "test login" action to disambiguate
        // "credentials file present" from "credentials actually work".
        if (!chrootReady()) {
            return com.r1.launcher.claude.ClaudeAuthVerifyResult(
                ok = false, log = "", error = "alpine chroot not bootstrapped",
            )
        }
        // Just route through the r1-alpine wrapper — it handles bind-mount
        // restoration, auth env-var assembly (skipping empties so OAuth
        // precedence isn't poisoned), and the drop to the unprivileged
        // claude user. Asking it to run `claude auth status` first gives a
        // structured JSON we can parse for the actual loggedIn state, then
        // a real `--print` round-trip confirms inference works end-to-end.
        val cmd = "sh /data/local/tmp/r1-alpine \"claude auth status 2>&1; " +
            "echo --probe--; claude --print 'reply only with PONG' 2>&1 | head -5\""
        val log = runCarrootBlocking(cmd, timeoutMs = 30_000)
        val notLoggedIn = log.contains("Not logged in", ignoreCase = true) ||
            log.contains("Please run /login", ignoreCase = true)
        return com.r1.launcher.claude.ClaudeAuthVerifyResult(
            ok = !notLoggedIn && log.isNotBlank(),
            log = log,
            error = if (notLoggedIn) "claude says it's not logged in" else null,
        )
    }

    @Volatile private var claudeSetupThread: Thread? = null

    override fun claudeSetupRunning(): Boolean = claudeSetupThread?.isAlive == true

    override fun claudeSetupStart(): Boolean {
        if (claudeSetupRunning()) return false
        // Run sequentially: bootstrap-alpine (downloads + extracts ~84 MB) →
        // setup-claude-agent (writes r1-root + CLAUDE.md, installs claude
        // binary side-effect via the install.sh fetch in step 1's chroot —
        // here we explicitly fetch it as a separate stage so the progress UI
        // can show distinct phases) → setup-claude-user (creates `claude`
        // user, syncs creds dir, fixes inet gid + resolv.conf perms).
        // Each phase wraps in `( … ) 2>&1` so stderr lands in the carroot
        // socket → progress event stream. Without this, apk lock errors
        // and missing-bash failures during the install go to /dev/null and
        // the user sees a silent stall in the log pane.
        //
        // Phase 2 uses `/bin/ash` (not bash) to launch the installer because
        // a freshly-bootstrapped alpine minirootfs ships only ash; bash gets
        // installed by phase 1 (apk add bash). The installer's `#!/bin/bash`
        // shebang resolves at exec time *inside* the curl|bash pipe, where
        // bash IS now present.
        // Phase 2 is gated on phase 1's chroot existing — if we just blast the
        // chroot command, a missing /bin/ash either no-ops the export line or
        // silently uses host bins (curl/bash not found is what the user saw).
        // The leading `test -x` makes the failure mode loud and short-circuits
        // the install step before it spews confusing "command not found" noise.
        val phases = listOf(
            "[1/4] alpine rootfs"   to "( sh /data/local/tmp/bootstrap-alpine.sh ) 2>&1",
            "[2/4] claude binary"   to "( test -x /data/local/tmp/alpine/bin/ash || " +
                "{ echo '[FAIL] alpine /bin/ash missing — phase 1 did not complete'; exit 1; }; " +
                "chroot /data/local/tmp/alpine /bin/ash -c " +
                "'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "export HOME=/root; cd /root && curl -fsSL https://claude.ai/install.sh | bash' ) 2>&1",
            "[3/4] r1-root bridge"  to "( sh /data/local/tmp/setup-claude-agent.sh ) 2>&1",
            "[4/4] claude user"     to "( sh /data/local/tmp/setup-claude-user.sh ) 2>&1",
        )
        val t = Thread {
            try {
                webServer?.broadcastClaudeSetupProgress("starting bootstrap...")
                // Sentinel-driven short-circuit: any phase that prints a line
                // beginning with "[FAIL]" aborts the loop. The scripts emit
                // this on validated-failure conditions (network down, corrupt
                // tarball, missing chroot prereq), so we can stop before
                // chaining noise from later phases.
                var aborted = false
                for ((label, cmd) in phases) {
                    if (aborted) {
                        webServer?.broadcastClaudeSetupProgress("--- $label SKIPPED (previous phase failed) ---")
                        continue
                    }
                    webServer?.broadcastClaudeSetupProgress("--- $label ---")
                    streamCarroot(cmd) { line ->
                        if (line.startsWith("[FAIL]")) aborted = true
                        webServer?.broadcastClaudeSetupProgress(line)
                    }
                }
                val status = claudeAuthStatus()
                val finalMsg = if (status.chrootReady) {
                    "DONE — claude code is ready, log in next"
                } else {
                    "ERROR — chroot still missing after bootstrap (check log above)"
                }
                webServer?.broadcastClaudeSetupProgress(finalMsg)
                webServer?.broadcastClaudeSetupDone(status.chrootReady)
                runOnUiThread { state.claudeAuthed = status.hasOAuth || status.hasApiKey }
            } catch (e: Exception) {
                webServer?.broadcastClaudeSetupProgress("[error] ${e.message ?: e.javaClass.simpleName}")
                webServer?.broadcastClaudeSetupDone(false)
            } finally {
                claudeSetupThread = null
            }
        }
        claudeSetupThread = t
        t.isDaemon = true
        t.start()
        return true
    }

    /**
     * Streaming carroot helper used by [claudeSetupStart]. Same shape as
     * [sendToCarrootStreaming] but without the cwd / sentinel / pwdFile
     * scaffolding, since these scripts don't care about cwd persistence and
     * each one ends with an explicit `echo DONE` we don't need to gate on.
     */
    private fun streamCarroot(cmd: String, onLine: (String) -> Unit) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                s.soTimeout = 600_000  // 10 min ceiling per phase — alpine extract is slow
                val out = s.getOutputStream()
                out.write((cmd + "\n").toByteArray())
                out.flush()
                s.shutdownOutput()
                val reader = s.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } catch (e: Exception) {
            onLine("[carroot] ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun claudeAuthReset(): Boolean {
        // Wipe every credential surface so the next start.sh / api-key paste
        // begins from a clean slate: both .credentials.json paths (root +
        // claude user), the cached projects dir (which can pin a stale
        // identity), the auth FIFO/log, and the API key file. Also kills
        // any half-finished `claude auth login` daemon — its FIFO writer
        // would otherwise block start.sh from creating a fresh pipe. The
        // `|| true` tail keeps the reset idempotent for users who never got
        // past the chroot bootstrap.
        val log = runCarrootBlocking(
            """
            ALPINE=/data/local/tmp/alpine
            for p in 'claude auth login' 'sleep 86400'; do
                pids=${'$'}(ps -ef 2>/dev/null | grep "${'$'}p" | grep -v grep | awk '{print ${'$'}2}')
                [ -n "${'$'}pids" ] && kill -9 ${'$'}pids 2>/dev/null || true
            done
            rm -f /data/local/tmp/.anthropic_key 2>/dev/null || true
            rm -f "${'$'}ALPINE/root/.claude/.credentials.json" 2>/dev/null || true
            rm -f "${'$'}ALPINE/home/claude/.claude/.credentials.json" 2>/dev/null || true
            rm -rf "${'$'}ALPINE/root/.claude/projects" 2>/dev/null || true
            rm -rf "${'$'}ALPINE/home/claude/.claude/projects" 2>/dev/null || true
            rm -f "${'$'}ALPINE/tmp/claude-auth.log" 2>/dev/null || true
            rm -f "${'$'}ALPINE/tmp/claude-auth.pipe" 2>/dev/null || true
            echo RESET_OK
            """.trimIndent(),
            timeoutMs = 8_000,
        )
        val ok = log.contains("RESET_OK")
        if (ok) runOnUiThread { state.claudeAuthed = false }
        return ok
    }

    override fun claudeSaveApiKey(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        // Light validation: real keys start with `sk-ant-`. Don't be strict
        // about exact length (Anthropic has rotated formats). Reject anything
        // with a newline or quote — we shell-interpolate this.
        if (k.contains('\n') || k.contains('\'') || k.contains('"')) return false
        // Write via carroot so the file is owned by root and readable by the
        // r1-alpine wrapper (which sources it as ANTHROPIC_API_KEY).
        val log = runCarrootBlocking(
            "printf '%s\\n' '$k' > /data/local/tmp/.anthropic_key && " +
                "chmod 600 /data/local/tmp/.anthropic_key && echo OK",
            timeoutMs = 5_000,
        )
        val ok = log.contains("OK")
        if (ok) runOnUiThread { state.claudeAuthed = true }
        return ok
    }

    override fun copyToClipboard(text: String, label: String) {
        if (text.isEmpty()) {
            toast("nothing to copy")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (cm == null) {
            toast("clipboard unavailable")
            return
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast("copied (${text.length} chars)")
    }

    private fun promptLabel(cwd: String): String {
        // Compact home-style indicator: /sdcard/foo → ~/foo when on the
        // user-data root, otherwise the absolute path.
        val short = if (cwd == "/sdcard") "~"
            else if (cwd.startsWith("/sdcard/")) "~" + cwd.removePrefix("/sdcard")
            else cwd
        return "$short \$"
    }

    /** Commands that only exist inside the Alpine chroot. Typing one of these
     *  in the terminal panel transparently re-routes through the r1-alpine
     *  wrapper so the user doesn't need the `sh /data/local/tmp/r1-alpine "…"`
     *  prefix every time. */
    private val alpineCommands = setOf(
        "npm", "node", "npx", "yarn", "pnpm",
        "python", "python3", "pip", "pip3",
        "apk", "openclaw", "claude",
    )

    private fun resolveAlpineWrapping(cmd: String): String {
        // Explicit override: `alpine: <anything>` always routes through chroot.
        if (cmd.startsWith("alpine:")) {
            return wrapAlpine(cmd.removePrefix("alpine:").trim())
        }
        // Auto-route on the first whitespace-separated token.
        val firstToken = cmd.split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (firstToken in alpineCommands) wrapAlpine(cmd) else cmd
    }

    private fun wrapAlpine(cmd: String): String {
        // Escape backslashes first, then double quotes, so the shell sees
        // the original command verbatim inside the wrapper's "$*" arg.
        val esc = cmd.replace("\\", "\\\\").replace("\"", "\\\"")
        return "sh /data/local/tmp/r1-alpine \"$esc\""
    }

    // --- LauncherHost: openclaw ---

    override fun openClawScanned(raw: String) {
        if (state.qrScanMode == QrScanMode.OPENAI_KEY) {
            // Note: enum name kept for back-compat; this branch now scans the
            // ElevenLabs voice key from QR.
            val k = raw.trim()
            if (!isValidElevenKey(k)) {
                state.qrError = "Not an elevenlabs key"
                return
            }
            voicePrefs.elevenlabsKey = k
            refreshVoiceKeyState()
            state.qrError = null
            selectTone()
            toast("voice key saved via QR")
            // Bounce back to the Settings → Voice panel where the user came from.
            state.qrScanMode = QrScanMode.GATEWAY_PAIRING
            state.openSettingsVoice()
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
        // Drop any selected/main session key carried over from a previous
        // gateway — those keys are scoped to that gateway and would 404
        // (or join an unrelated thread) on the new one.
        openClawPrefs.selectedSessionKey = null
        openClawPrefs.lastMainSessionKey = null
        state.qrError = null
        selectTone()
        openClawStartSession()
        state.chatMessages.clear()
        state.openOpenClawChat()
    }

    override fun openClawToggleRecord() {
        if (state.chatRecording) openClawRecordStop() else openClawRecordStart()
    }

    override fun openClawRecordStart() {
        // Don't even open the mic if there's no live OpenClaw session — the
        // committed transcript would have nowhere to send. (Original Whisper
        // flow checked the same thing.)
        openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        startVoiceCapture(VoiceSink.CHAT)
    }

    override fun openClawRecordStop() = stopVoiceCapture()

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
            // Arm the auto-speak gate when the user has voice enabled and is
            // sitting in the chat panel; streaming TTS will start mid-reply
            // as sentence boundaries arrive in chatStreamingText.
            if (state.voiceEnabled && state.panel == Panel.OPENCLAW_CHAT) {
                // cancelOpenClawSpeech() bumps turnId, drops slots, stops any
                // prior playback, and resets spokenOffset — so a fresh turn
                // starts clean even if the prior reply was still speaking.
                cancelOpenClawSpeech()
                openClawSpeakNextAssistant = true
                // Pre-warm api.elevenlabs.io: fires a tiny GET /v1/voices in
                // parallel with session.send. By the time the model finishes
                // its ~2s warm-up and the first chunk's POST /stream goes
                // out, OkHttp's pool already has a live TLS socket — saves
                // ~150ms on first audio. Rate-limited internally to ≤1/min.
                voicePrefs.elevenlabsKey?.takeIf { it.isNotBlank() }?.let { key ->
                    com.r1.launcher.voice.ElevenLabsTtsClient.warmConnection(key)
                }
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

    override fun openClawScrollUp() {
        state.chatScrollIndex++
    }

    override fun openClawScrollDown() {
        state.chatScrollIndex--
    }

    override fun openClawCloseSession() {
        openClawCloseSessionInternal()
    }

    private fun isValidElevenKey(raw: String): Boolean {
        // ElevenLabs keys: either "sk_<29 hex>" prefix form (~32 char) or a
        // bare 32-char hex string. Lower-case hex is the canonical form.
        if (raw.startsWith("sk_") && raw.length >= 20) return true
        if (raw.length == 32 && raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return true
        return false
    }

    override fun voiceToggleEnabled() {
        val next = !state.voiceEnabled
        state.voiceEnabled = next
        voicePrefs.enabled = next
        if (!next) runCatching { openClawSpeechPlayer?.stop() }
        popTone()
    }

    override fun voiceCycleVoiceId() {
        val voices = com.r1.launcher.voice.VoicePrefs.VOICES
        val curIdx = voices.indexOfFirst { it.second == state.voiceId }
        val next = voices[(curIdx + 1).coerceAtLeast(0) % voices.size]
        state.voiceId = next.second
        voicePrefs.voiceId = next.second
        toast("voice: ${next.first}")
        popTone()
    }

    override fun voiceSaveKey(key: String) {
        val k = key.trim()
        when {
            k.isEmpty() -> { toast("key is empty"); return }
            !isValidElevenKey(k) -> { toast("not an elevenlabs key"); return }
            else -> {
                voicePrefs.elevenlabsKey = k
                refreshVoiceKeyState()
                toast("voice key saved")
                state.back()
            }
        }
    }

    override fun voiceClearKey() {
        voicePrefs.elevenlabsKey = null
        refreshVoiceKeyState()
        toast("voice key cleared")
    }

    override fun voicePasteKeyFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty"); return
        }
        if (!isValidElevenKey(raw)) {
            toast("not an elevenlabs key"); return
        }
        voicePrefs.elevenlabsKey = raw
        refreshVoiceKeyState()
        toast("voice key saved")
    }

    override fun voiceSettingsRowActivate(idx: Int) {
        // Row layout matches SettingsVoicePanel:
        //   0  back
        //   1  voice on/off
        //   2  elevenlabs key  (handled inline by the panel's keyboard overlay)
        //   3  scan key from qr
        //   4  subscription (tap = force-refresh balance)
        //   5  voice picker (cycle catalog)
        //   6  custom voice id (handled inline by the panel's keyboard overlay)
        //   7  test voice
        //   8  tuning (opens SETTINGS_VOICE_TUNING)
        //   9  clear key
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> voiceToggleEnabled()
            // 2 handled by the panel's keyboard overlay
            3 -> {
                ensureCameraPerm()
                state.openOpenAiKeyQr() // QR scan path is reused — see openClawScanned
                selectTone()
            }
            4 -> {
                state.openSettingsVoiceSubscription()
                // Auto-fetch on entry; honors the 60s cache so re-entering
                // the page in quick succession doesn't re-hit the API.
                voiceFetchSubscription(force = false)
                selectTone()
            }
            5 -> voiceCycleVoiceId()
            // 6 handled by the panel's keyboard overlay
            7 -> voiceTestSynthesize()
            8 -> { state.openSettingsVoiceTuning(); selectTone() }
            9 -> voiceClearKey()
        }
    }

    override fun voiceSaveCustomVoiceId(id: String) {
        val v = id.trim()
        if (v.isEmpty()) { toast("voice id is empty"); return }
        voicePrefs.customVoiceId = v
        state.voiceCustomId = v
        toast("custom voice id saved")
        popTone()
    }

    override fun voiceClearCustomVoiceId() {
        voicePrefs.customVoiceId = null
        state.voiceCustomId = ""
        toast("custom voice id cleared")
        popTone()
    }

    override fun voicePasteCustomVoiceIdFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { toast("clipboard empty"); return }
        voicePrefs.customVoiceId = raw
        state.voiceCustomId = raw
        toast("custom voice id saved")
    }

    override fun voiceCycleModel() {
        val models = com.r1.launcher.voice.VoicePrefs.MODELS
        val curIdx = models.indexOfFirst { it.second == state.voiceModel }
        val next = models[(curIdx + 1).coerceAtLeast(0) % models.size]
        state.voiceModel = next.second
        voicePrefs.model = next.second
        toast("model: ${next.first}")
        popTone()
    }

    override fun voiceSetStability(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceStability = v
        voicePrefs.stability = v
    }

    override fun voiceSetSimilarity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceSimilarity = v
        voicePrefs.similarity = v
    }

    override fun voiceSetStyle(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceStyle = v
        voicePrefs.style = v
    }

    override fun voiceSetSpeed(value: Float) {
        val v = value.coerceIn(
            com.r1.launcher.voice.VoicePrefs.MIN_SPEED,
            com.r1.launcher.voice.VoicePrefs.MAX_SPEED,
        )
        state.voiceSpeed = v
        voicePrefs.speed = v
    }

    override fun voiceToggleSpeakerBoost() {
        val next = !state.voiceSpeakerBoost
        state.voiceSpeakerBoost = next
        voicePrefs.speakerBoost = next
        popTone()
    }

    override fun voiceResetTuning() {
        voicePrefs.resetTuning()
        state.voiceModel = voicePrefs.model
        state.voiceStability = voicePrefs.stability
        state.voiceSimilarity = voicePrefs.similarity
        state.voiceStyle = voicePrefs.style
        state.voiceSpeed = voicePrefs.speed
        state.voiceSpeakerBoost = voicePrefs.speakerBoost
        toast("tuning reset")
        popTone()
    }

    private val voiceSubExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var voiceSubInFlight: Boolean = false

    override fun voiceFetchSubscription(force: Boolean) {
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            state.voiceSubError = "no key"
            state.voiceSubLoading = false
            return
        }
        // 60s in-memory cache so wheel-spamming the row doesn't hammer the API.
        // Force = true (manual tap) bypasses the cache.
        val ageMs = System.currentTimeMillis() - state.voiceSubFetchedAtMs
        if (!force && state.voiceSubFetchedAtMs > 0L && ageMs < 60_000L) return
        if (voiceSubInFlight) return
        voiceSubInFlight = true
        state.voiceSubLoading = true
        state.voiceSubError = null
        voiceSubExecutor.submit {
            val client = com.r1.launcher.voice.ElevenLabsSubscriptionClient(key)
            when (val r = client.fetch()) {
                is com.r1.launcher.voice.ElevenLabsSubscriptionClient.Result.Success -> {
                    ui.post {
                        state.voiceSubData = r.data
                        state.voiceSubFetchedAtMs = System.currentTimeMillis()
                        state.voiceSubLoading = false
                        state.voiceSubError = null
                    }
                }
                is com.r1.launcher.voice.ElevenLabsSubscriptionClient.Result.Failure -> {
                    ui.post {
                        state.voiceSubLoading = false
                        state.voiceSubError = if (r.httpCode == 401) "invalid key"
                            else "${r.httpCode}: ${r.message.take(80)}"
                    }
                }
            }
            voiceSubInFlight = false
        }
    }

    override fun voiceTestSynthesize() {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toast("voice: set elevenlabs key first")
            return
        }
        if (state.voiceTestBusy) return
        state.voiceTestBusy = true
        // Stop any in-flight TTS (chat readback, prior test) before starting.
        cancelOpenClawSpeech()
        val sample = getString(R.string.voice_test_phrase)
        val outFile = File(File(cacheDir, "openclaw-voice").apply { mkdirs() }, "test.mp3")
        openClawTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = sample,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { mp3Bytes, err ->
            openClawTtsCall = null
            state.voiceTestBusy = false
            if (err == "canceled") return@synthesize
            if (err != null || mp3Bytes == null) {
                toastFail("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playOpenClawSpeech(mp3Bytes)
        }
    }

    override fun voiceTuningRowActivate(idx: Int) {
        // Row layout matches SettingsVoiceTuningPanel:
        //   0 back
        //   1 model picker (cycle)
        //   2..5 sliders (handled inline by the panel's +/- pills)
        //   6 speaker boost toggle
        //   7 test voice
        //   8 reset to defaults
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> voiceCycleModel()
            // 2..5 handled inline by the panel's slider pills
            6 -> voiceToggleSpeakerBoost()
            7 -> voiceTestSynthesize()
            8 -> voiceResetTuning()
        }
    }

    override fun openClawSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                // Convenience inline toggle for the global voice-enabled flag.
                // The canonical config still lives in Settings → Voice; this row
                // just flips the same VoicePrefs.enabled, so it stays in sync.
                voiceToggleEnabled()
                popTone()
            }
            2 -> {
                val newHide = !state.openClawHideChat
                state.openClawHideChat = newHide
                openClawPrefs.hideChat = newHide
                popTone()
            }
            // 3 (font size) is handled by +/- buttons in the UI
            4 -> { openClawClearHistory(); popTone() }
            5 -> { openClawDisconnect(); popTone() }
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

    override fun openClawCompactSession() {
        val session = openClawSession ?: run { toastFail("not connected"); return
        }
        val key = state.selectedSessionKey
        if (key.isBlank()) { toastFail("no session"); return }
        toast("compacting…")
        session.compactSession(key) { ok, err ->
            ui.post {
                if (ok) toastSuccess("compacted")
                else toastFail(err ?: "compact failed")
            }
        }
    }

    override fun openClawClearContext() {
        val session = openClawSession ?: run { toastFail("not connected"); return }
        val key = state.selectedSessionKey
        if (key.isBlank()) { toastFail("no session"); return }
        // Optimistic local clear so the UI flips empty immediately. The
        // gateway round-trip refreshes via onHistory if it succeeds, or
        // we'll see a toast if it fails (server still has the messages).
        state.chatMessages.clear()
        state.chatStreamingText = ""
        state.chatScrollIndex = 0
        toast("clearing context…")
        session.resetSession(key) { ok, err ->
            ui.post {
                if (ok) toastSuccess("context cleared")
                else toastFail(err ?: "clear failed")
            }
        }
    }

    override fun openClawSessionsRowActivate(idx: Int) {
        // Mirrors OpenClawSessionsPanel row order:
        //   0             "< back"
        //   1             "+ new thread"
        //   2..choices+1  switch to that thread
        //   choices+2     "refresh"
        if (idx == 0) {
            state.back(); backTone(); return
        }
        if (idx == 1) {
            // Fresh thread — pick a key the gateway hasn't seen before.
            // chat.subscribe lazy-creates server-side, so a unique timestamp
            // key is enough. Drop back to chat after dispatch.
            val newKey = "thread-${System.currentTimeMillis()}"
            openClawSwitchSession(newKey)
            popTone()
            state.back()
            return
        }
        val choices = com.r1.launcher.openclaw.resolveSessionChoices(
            currentSessionKey = state.selectedSessionKey,
            sessions = state.chatSessions.toList(),
            mainSessionKey = state.mainSessionKey,
        )
        val refreshIdx = 2 + choices.size.coerceAtLeast(1)
        if (idx == refreshIdx) {
            openClawRefreshSessions()
            popTone()
            return
        }
        val choice = choices.getOrNull(idx - 2) ?: return
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
            val fastList = runCatching {
                com.r1.launcher.messages.SmsLoader.loadConversationsFast(this)
            }.getOrElse { emptyList() }
            ui.post {
                state.smsConversations.clear()
                state.smsConversations.addAll(fastList)
            }

            val fullList = runCatching {
                com.r1.launcher.messages.SmsLoader.loadConversations(this)
            }.getOrElse { fastList }
            ui.post {
                state.smsConversations.clear()
                state.smsConversations.addAll(fullList)
                state.smsLoading = false
                if (fullList.isEmpty() && state.smsError == null) state.smsError = "no messages"
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
                state.smsThreadLoading = false
            }
        }.start()
    }

    private fun refreshVoiceKeyState() {
        val k = voicePrefs.elevenlabsKey
        if (k.isNullOrBlank()) {
            state.hasVoiceKey = false
            state.voiceKeyTail = ""
        } else {
            state.hasVoiceKey = true
            state.voiceKeyTail = k.takeLast(4)
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

    /** API 33+ runtime grant for the persistent recording-cue notification.
     *  Denial doesn't block recording — a microphone-typed FGS still runs.
     *  Returns true when the perm is granted (or pre-API-33), false otherwise. */
    private fun ensureNotifPerm(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF_PERM)
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
        when {
            uiClickSoundId != 0 -> playUiClickSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP, 18)
        }
    }
    
    override fun selectTone() {
        when {
            selectSoundId != 0  -> playSelectSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP, 30)
        }
    }
    
    override fun popTone() = playMovingSound()

    /** Recording-start cue — record.mp3 at full volume, plus a ToneGenerator
     *  beep as a guaranteed-audible fallback. SoundPool on MTK silently drops
     *  some plays (especially when the audio path is in flux from another
     *  stream just being released), so the ToneGenerator beep makes sure the
     *  user always gets audible feedback that the mic opened. Falls back to
     *  moving.mp3 if the dedicated cue isn't loaded yet. */
    private fun playRecordStartTone() {
        val sid = if (recordStartSoundId != 0) recordStartSoundId else movingSoundId
        val g = uiSoundGain()
        if (sid != 0 && g > 0f) {
            soundPool?.play(sid, g, g, 0, 0, 1f)
        }
        if (g > 0f) {
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
        }
    }

    /** Recording-stop cue — release-record.mp3, mirrored shape of start. Same
     *  fallback chain. Slightly different ToneGenerator type (PROMPT vs BEEP)
     *  so even when the mp3 drops the user hears a distinctly different cue
     *  for "released / sending" versus "recording started". */
    private fun playRecordStopTone() {
        val sid = if (recordStopSoundId != 0) recordStopSoundId else movingSoundId
        val g = uiSoundGain()
        if (sid != 0 && g > 0f) {
            soundPool?.play(sid, g, g, 0, 0, 1f)
        }
        if (g > 0f) {
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 60) }
        }
    }
    
    override fun backTone() {
        // Was a hardcoded ToneGenerator DTMF beep — that's the synthesized
        // "Android system" tick you hear when wheel-scrolling past the top of
        // a list. Mirror the navTone/selectTone fallback chain so back uses a
        // real mp3; pick the subtle button so back still sounds distinct from
        // forward nav (wooden). DTMF only as last resort.
        when {
            selectSoundId != 0 -> playSelectSound()
            movingSoundId != 0 -> playMovingSound()
            else               -> playTone(ToneGenerator.TONE_PROP_PROMPT, 35)
        }
    }
    
    private fun launchTone() {
        // Same fix as backTone: prefer a real mp3 over the DTMF "system" beep
        // that fires on every wheel-press app launch.
        when {
            uiClickSoundId != 0 -> playUiClickSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP2, 80)
        }
    }

    private fun uiSoundGain(): Float {
        val max = state.uiVolumeMax.coerceAtLeast(1).toFloat()
        return (state.uiVolumeLevel.toFloat() / max).coerceIn(0f, 1f)
    }

    private fun playMovingSound() {
        if (movingSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(movingSoundId, g, g, 0, 0, 1f)
    }

    private fun playUiClickSound() {
        if (uiClickSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(uiClickSoundId, g, g, 0, 0, 1f)
    }

    private fun playSelectSound() {
        if (selectSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(selectSoundId, g, g, 0, 0, 1f)
    }

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

        if (state.panel == Panel.OPENCLAW_CHAT && isOpenClawPttKey(code)) {
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
                        } else if (state.panel == Panel.TERMINAL) {
                            // Push-to-talk dictation. Stop fires on the matching UP
                            // (handled below in the sideLongFired branch).
                            terminalRecordStart()
                        } else if (state.panel == Panel.CLAUDE) {
                            claudeRecordStart()
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (sideLongFired) {
                        // Long-press already handled at DOWN-repeat. Mirror the
                        // start side-effect on UP for the terminal PTT path so
                        // recording stops when the user releases.
                        if (state.panel == Panel.TERMINAL) terminalRecordStop()
                        else if (state.panel == Panel.CLAUDE) claudeRecordStop()
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
                            when (state.panel) {
                                Panel.HOME -> lockScreen()
                                // Recording panel uses tap-to-toggle instead of
                                // PTT hold — long meetings make hold-to-record
                                // impractical. Tap also stops a running record.
                                Panel.TRANSCRIBER_RECORDING -> transcriberToggleRecording()
                                else -> state.activate(this)
                            }
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

    /** Generic toast — gray edge. Use [toastSuccess] / [toastFail] for outcomes. */
    private fun toast(msg: String) {
        ui.post { state.showToast(msg, ToastKind.INFO) }
    }

    /** Outcome toast — orange edge. */
    private fun toastSuccess(msg: String) {
        ui.post { state.showToast(msg, ToastKind.SUCCESS) }
    }

    /** Outcome toast — red edge. */
    private fun toastFail(msg: String) {
        ui.post { state.showToast(msg, ToastKind.FAIL) }
    }

    // ============================================================
    // Meetings (transcriber) — host implementations
    // ============================================================

    private fun bindTranscriberService() {
        if (transcriberServiceBound) return
        val intent = Intent(this, TranscriberRecordingService::class.java)
        transcriberServiceBound = bindService(intent, transcriberServiceConn, Context.BIND_AUTO_CREATE)
    }

    private fun unbindTranscriberService() {
        if (!transcriberServiceBound) return
        runCatching { unbindService(transcriberServiceConn) }
        transcriberServiceBound = false
        transcriberBinder = null
    }

    private fun refreshTranscriberPrefsCache() {
        state.hasSmtp = transcriberPrefs.hasSmtp()
        state.smtpHostDisplay = transcriberPrefs.smtpHost
        state.smtpPortDisplay = transcriberPrefs.smtpPort
        state.smtpUserDisplay = transcriberPrefs.smtpUser ?: ""
        state.defaultRecipientDisplay = transcriberPrefs.defaultRecipient
    }

    private fun reloadMeetings() {
        val list = meetingStore.listMeetings()
        state.meetings.clear()
        state.meetings.addAll(list)
        // Layout: 0=back, 1=settings, 2=record, 3..N+2=meetings — last valid
        // index is 2 + N. Snap focus back to back-pill if it would point past
        // the end after a deletion.
        val maxIdx = 2 + list.size
        if (state.transcriberListFocus > maxIdx) {
            state.transcriberListFocus = 0
        }
    }

    override fun transcriberOpen() {
        refreshTranscriberPrefsCache()
        reloadMeetings()
        bindTranscriberService()
        state.openTranscriberList()
    }

    override fun transcriberStartRecording() {
        if (!ensureAudioPerm()) return
        ensureNotifPerm() // best-effort — denial doesn't block recording
        // Conflict guard: STT capture for chat/terminal/claude already holds
        // the mic. Refuse rather than silently corrupt both.
        if (voiceCapture != null) {
            toastFail("voice capture active")
            return
        }
        if (transcriberBinder?.isRecording == true) {
            // Already recording — just open the panel.
            state.openTranscriberRecording()
            return
        }
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            toastFail("set elevenlabs key in settings → voice")
            return
        }

        val uuid = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val title = "Meeting " + SimpleDateFormat("yyyy-MM-dd HH:mm", com.r1.launcher.locale.digitFriendlyLocale()).format(Date(now))
        val audioPath = meetingStore.audioFile(uuid).absolutePath
        val meeting = Meeting(
            uuid = uuid,
            title = title,
            createdAtMs = now,
            audioPath = audioPath,
            status = MeetingStatus.RECORDING,
            // Snapshot the API key so a mid-recording Settings → Voice → "clear key"
            // doesn't break the upload after stop.
            apiKeySnapshot = key,
        )
        meetingStore.save(meeting)
        transcriberCurrentMeeting = meeting

        // Kick the FGS via startForegroundService (must call startForeground()
        // within 10s — the service does that synchronously in onStartCommand).
        val intent = TranscriberRecordingService.startIntent(this, audioPath, title)
        ContextCompat.startForegroundService(this, intent)
        bindTranscriberService()

        state.recordingActive = true
        state.recordingElapsedMs = 0L
        state.recordingPeak = 0
        ui.removeCallbacks(transcriberPollRunnable)
        ui.post(transcriberPollRunnable)
        playRecordStartTone()
        reloadMeetings()
        state.openTranscriberRecording()
    }

    override fun transcriberStopRecording() {
        val meeting = transcriberCurrentMeeting
        val binder = transcriberBinder
        if (binder == null || !binder.isRecording) {
            // Nothing recording — defensive cleanup of any stale state.
            state.recordingActive = false
            state.recordingElapsedMs = 0L
            transcriberCurrentMeeting = null
            return
        }
        val elapsed = binder.elapsedMs
        startService(TranscriberRecordingService.stopIntent(this))
        ui.removeCallbacks(transcriberPollRunnable)
        state.recordingActive = false
        state.recordingElapsedMs = 0L
        state.recordingPeak = 0
        playRecordStopTone()

        if (meeting == null) return
        // MediaRecorder.stop() finalizes the moov atom; give it ~500ms then
        // kick off the upload. The FGS's onDestroy/STOP path is synchronous
        // but the file system flush isn't guaranteed instant.
        ui.postDelayed({
            meeting.durationMs = elapsed
            meeting.status = MeetingStatus.QUEUED
            meetingStore.save(meeting)
            reloadMeetings()
            kickTranscription(meeting)
        }, 500L)
        transcriberCurrentMeeting = null
        // Drop the user back onto the list panel so the new entry is visible.
        if (state.panel == Panel.TRANSCRIBER_RECORDING) {
            state.openTranscriberList()
        }
    }

    override fun transcriberToggleRecording() {
        if (transcriberBinder?.isRecording == true) transcriberStopRecording()
        else transcriberStartRecording()
    }

    private fun kickTranscription(meeting: Meeting) {
        val key = meeting.apiKeySnapshot ?: voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            meeting.status = MeetingStatus.FAILED
            meeting.errorMessage = "no api key"
            meetingStore.save(meeting)
            ui.post {
                reloadMeetings()
                toastFail("no elevenlabs key")
            }
            return
        }
        meeting.status = MeetingStatus.TRANSCRIBING
        meetingStore.save(meeting)
        ui.post {
            reloadMeetings()
            state.transcribeBusy = true
        }
        transcriberExecutor.submit {
            val client = ScribeClient(key)
            val audio = java.io.File(meeting.audioPath)
            val result = client.transcribe(audio)
            ui.post { state.transcribeBusy = false }
            when (result) {
                is ScribeClient.Result.Success -> {
                    val text = TranscriptFormatter.render(result.response, meeting.speakerNames)
                    meeting.transcriptJson = result.rawJson
                    meeting.transcriptText = text
                    meeting.languageCode = result.response.language_code
                    meeting.speakerCount = TranscriptFormatter.distinctSpeakerCount(result.response)
                    meeting.status = MeetingStatus.TRANSCRIBED
                    meeting.errorMessage = null
                    // Strip the API-key snapshot now that the upload succeeded.
                    meeting.apiKeySnapshot = null
                    meetingStore.save(meeting)
                    ui.post {
                        reloadMeetings()
                        toastSuccess("transcribed: ${meeting.speakerCount} speaker${if (meeting.speakerCount == 1) "" else "s"}")
                    }
                }
                is ScribeClient.Result.Failure -> {
                    meeting.status = MeetingStatus.FAILED
                    meeting.errorMessage = "[HTTP ${result.httpCode}] ${result.message}"
                    meetingStore.save(meeting)
                    ui.post {
                        reloadMeetings()
                        toastFail("transcribe: ${result.message}".take(80))
                    }
                }
            }
        }
    }

    override fun transcriberOpenDetail(uuid: String) {
        bindTranscriberService()
        state.openTranscriberDetail(uuid)
    }

    override fun transcriberRetryTranscribe(uuid: String) {
        val m = meetingStore.loadMeeting(uuid) ?: return
        if (m.apiKeySnapshot.isNullOrBlank()) m.apiKeySnapshot = voicePrefs.elevenlabsKey
        kickTranscription(m)
    }

    override fun transcriberDelete(uuid: String) {
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        state.detailPlaying = false
        meetingStore.delete(uuid)
        if (state.currentMeetingUuid == uuid) state.currentMeetingUuid = null
        reloadMeetings()
        if (state.panel == Panel.TRANSCRIBER_DETAIL) state.openTranscriberList()
    }

    override fun transcriberPlayAudio(uuid: String) {
        val audio = meetingStore.audioFile(uuid)
        if (!audio.exists() || audio.length() == 0L) {
            toastFail("audio missing")
            return
        }
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(audio.absolutePath)
            setOnCompletionListener {
                state.detailPlaying = false
                runCatching { it.release() }
                if (transcriberPlayer === it) transcriberPlayer = null
            }
            setOnErrorListener { mp, _, _ ->
                state.detailPlaying = false
                runCatching { mp.release() }
                if (transcriberPlayer === mp) transcriberPlayer = null
                true
            }
            prepare()
            start()
        }
        state.detailPlaying = true
    }

    override fun transcriberStopAudio() {
        runCatching { transcriberPlayer?.stop() }
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        state.detailPlaying = false
    }

    override fun transcriberShareEmail(uuid: String, recipient: String) {
        if (!transcriberPrefs.hasSmtp()) {
            toastFail("set smtp creds in meetings → settings")
            return
        }
        val recipientFinal = recipient.ifBlank { transcriberPrefs.defaultRecipient }
        if (recipientFinal.isBlank()) {
            toastFail("recipient empty")
            return
        }
        val meeting = meetingStore.loadMeeting(uuid) ?: run {
            toastFail("meeting missing")
            return
        }
        val transcript = meeting.transcriptText
            ?: meeting.errorMessage?.let { "(transcription failed: $it)" }
            ?: ""
        state.detailStatus = "sending..."
        transcriberExecutor.submit {
            val sender = SmtpSender(transcriberPrefs)
            val result = sender.send(meeting, recipientFinal, java.io.File(meeting.audioPath), transcript)
            ui.post {
                when (result) {
                    is SmtpSender.Result.Success -> {
                        state.detailStatus = "sent to ${recipientFinal.take(28)}"
                        toastSuccess("email sent")
                    }
                    is SmtpSender.Result.Failure -> {
                        state.detailStatus = "send failed: ${result.message.take(40)}"
                        toastFail("send failed: ${result.message.take(60)}")
                    }
                }
            }
        }
    }

    override fun transcriberOpenSettings() {
        refreshTranscriberPrefsCache()
        state.openTranscriberSettings()
    }

    override fun transcriberSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> openTranscriberKeyboard("host", transcriberPrefs.smtpHost)
            2 -> openTranscriberKeyboard("port", transcriberPrefs.smtpPort.toString())
            3 -> openTranscriberKeyboard("user", transcriberPrefs.smtpUser ?: "")
            4 -> openTranscriberKeyboard("password", "")
            5 -> openTranscriberKeyboard("recipient", transcriberPrefs.defaultRecipient)
            6 -> { transcriberClearSmtp(); toastSuccess("smtp cleared") }
        }
    }

    private fun openTranscriberKeyboard(field: String, current: String) {
        state.transcriberSettingsEditField = field
        state.transcriberSettingsEditInput = current
    }

    override fun transcriberSaveSmtpField(field: String, value: String) {
        when (field) {
            "host" -> transcriberPrefs.smtpHost = value.ifBlank { TranscriberPrefs.DEFAULT_HOST }
            "port" -> transcriberPrefs.smtpPort = value.toIntOrNull()?.coerceIn(1, 65535) ?: TranscriberPrefs.DEFAULT_PORT
            "user" -> transcriberPrefs.smtpUser = value.ifBlank { null }
            "password" -> transcriberPrefs.smtpPassword = value.ifBlank { null }
            "recipient" -> transcriberPrefs.defaultRecipient = value
        }
        refreshTranscriberPrefsCache()
        state.transcriberSettingsEditField = ""
        state.transcriberSettingsEditInput = ""
    }

    override fun transcriberPasteSmtpField(field: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        state.transcriberSettingsEditInput = raw
    }

    override fun transcriberClearSmtp() {
        transcriberPrefs.clear()
        refreshTranscriberPrefsCache()
    }

    override fun transcriberPasteRecipient() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        state.transcriberRecipientInput = raw
    }

    override fun transcriberOpenDetailMenu() {
        val uuid = state.currentMeetingUuid ?: return
        val meeting = meetingStore.loadMeeting(uuid) ?: return
        val actions = mutableListOf<com.r1.launcher.transcriber.TranscriberDetailAction>()
        when (meeting.status) {
            com.r1.launcher.transcriber.MeetingStatus.TRANSCRIBED -> {
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.PLAY_TOGGLE
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.EMAIL
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
            com.r1.launcher.transcriber.MeetingStatus.FAILED -> {
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.RETRY
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
            else -> {
                // Recording / queued / transcribing — only safe action is delete
                // (which also stops the FGS via transcriberDelete()'s normal path).
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
        }
        actions += com.r1.launcher.transcriber.TranscriberDetailAction.CLOSE
        state.transcriberDetailMenuActions.clear()
        state.transcriberDetailMenuActions.addAll(actions)
        state.transcriberDetailMenuFocus = 0
        state.transcriberDetailMenuOpen = true
    }

    override fun transcriberDetailMenuActivate(
        action: com.r1.launcher.transcriber.TranscriberDetailAction,
    ) {
        val uuid = state.currentMeetingUuid
        when (action) {
            com.r1.launcher.transcriber.TranscriberDetailAction.PLAY_TOGGLE -> {
                if (state.detailPlaying) transcriberStopAudio()
                else uuid?.let { transcriberPlayAudio(it) }
                // Keep the menu open so the user can stop without re-tapping ⋮.
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.EMAIL -> {
                uuid?.let { transcriberShareEmail(it, state.transcriberRecipientInput) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.RETRY -> {
                uuid?.let { transcriberRetryTranscribe(it) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.DELETE -> {
                uuid?.let { transcriberDelete(it) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.CLOSE -> {
                state.transcriberDetailMenuOpen = false
            }
        }
    }

    // --- survey call bot ---

    private fun refreshSurveyDisplayState() {
        state.hasOpenAiKey = surveyPrefs.hasOpenAiKey()
        state.openAiKeyTail = surveyPrefs.openAiKey?.takeLast(4) ?: ""
        state.hasSipCreds = surveyPrefs.hasSipCreds()
        state.sipHostDisplay = surveyPrefs.sipHost.orEmpty()
        state.sipUserDisplay = surveyPrefs.sipUser.orEmpty()
        state.sipFromDisplay = surveyPrefs.sipFromNumber.orEmpty()
        state.realtimeVoiceDisplay = surveyPrefs.realtimeVoice
        state.summarizerModelDisplay = surveyPrefs.summarizerModel
        state.surveyConsentTextDisplay = surveyPrefs.consentText
        state.surveyEmailRecipientDisplay = surveyPrefs.emailRecipient.orEmpty()
    }

    private fun reloadSurveysAndCampaigns() {
        val s = surveyStore.listSurveys()
        state.surveys.clear(); state.surveys.addAll(s)
        val c = surveyStore.listCampaigns()
        state.campaigns.clear(); state.campaigns.addAll(c)
        val r = surveyStore.listCallRecords()
        state.callRecords.clear(); state.callRecords.addAll(r)
    }

    override fun surveyOpen() {
        refreshSurveyDisplayState()
        reloadSurveysAndCampaigns()
        state.openSurveyList()
    }

    override fun surveyOpenCampaign() {
        state.currentSurveyId = state.surveys.firstOrNull()?.id
        state.openSurveyCampaign()
    }

    override fun surveyConfirmCampaignAndDial() {
        val cid = state.currentCampaignId ?: run {
            toastFail("no campaign selected")
            return
        }
        surveyStartCampaignById(cid)
    }

    override fun surveyHangup() {
        val sess = surveyActiveSession
        if (sess != null) {
            sess.hangup()
        } else {
            state.surveyCallActive = false
            state.surveyCallStatus = "ended"
        }
        // Pause the campaign so the loop doesn't dial the next contact after
        // the user explicitly hung up.
        state.currentCampaignId?.let { cid ->
            surveyStore.loadCampaign(cid)?.let {
                if (it.status == com.r1.launcher.survey.CampaignStatus.RUNNING) {
                    surveyStore.saveCampaign(it.copy(
                        status = com.r1.launcher.survey.CampaignStatus.PAUSED,
                        updatedAtMs = System.currentTimeMillis(),
                    ))
                    reloadSurveysAndCampaigns()
                }
            }
        }
    }

    override fun surveyOpenDetail(callRecordId: String) {
        state.openSurveyDetail(callRecordId)
    }

    override fun surveyDeleteCallRecord(callRecordId: String) {
        surveyStore.deleteCallRecord(callRecordId)
        reloadSurveysAndCampaigns()
    }

    override fun surveyEmailCallRecord(callRecordId: String) {
        runSurveyPostCallPipeline(callRecordId, forceSummary = false, email = true)
    }

    override fun surveyRetrySummary(callRecordId: String) {
        runSurveyPostCallPipeline(callRecordId, forceSummary = true, email = false)
    }

    override fun surveyHandleCallComplete(callRecordId: String) {
        // Entry from the SIP / orchestrator path once the call ends. Auto-runs
        // summary + email so the user gets the artifact without touching the UI.
        runSurveyPostCallPipeline(callRecordId, forceSummary = false, email = true)
    }

    /**
     * Post-call pipeline. Runs on the shared [transcriberExecutor] (single
     * thread is fine — survey calls are sequential):
     *   1. Load the record.
     *   2. If [forceSummary] or no summary on file → call [SummaryGenerator],
     *      persist the result back to the record.
     *   3. If [email] → SMTP-send the call as an audio + transcript + answers
     *      attachment trio, using the survey-specific recipient when set,
     *      falling back to the transcriber default recipient.
     *
     * All toasts are dispatched back to the UI thread; the executor itself
     * never touches Compose state directly.
     */
    private fun runSurveyPostCallPipeline(
        recordId: String,
        forceSummary: Boolean,
        email: Boolean,
    ) {
        val rec0 = surveyStore.loadCallRecord(recordId) ?: run {
            toastFail("call record missing")
            return
        }
        val recipient = surveyPrefs.emailRecipient
            ?.takeIf { it.isNotBlank() }
            ?: transcriberPrefs.defaultRecipient.takeIf { it.isNotBlank() }
            .orEmpty()
        if (email && !transcriberPrefs.hasSmtp()) {
            toastFail("set smtp creds in meetings → settings")
            return
        }
        if (email && recipient.isBlank()) {
            toastFail("set survey email recipient first")
            return
        }
        if (email) state.showToast("emailing survey…", com.r1.launcher.ToastKind.INFO)
        else state.showToast("summarizing…", com.r1.launcher.ToastKind.INFO)
        transcriberExecutor.submit {
            // ---- summary ----
            val rec = if (forceSummary || rec0.summary.isNullOrBlank()) {
                runSurveySummary(rec0)
            } else rec0

            // ---- email ----
            if (!email) {
                ui.post {
                    if (rec.summary.isNullOrBlank()) toastFail("summary failed")
                    else state.showToast("summary updated", com.r1.launcher.ToastKind.SUCCESS)
                }
                return@submit
            }
            val survey = surveyStore.loadSurvey(rec.surveyId)
            val payload = buildSurveyEmailPayload(rec, survey, recipient)
            val sender = SmtpSender(transcriberPrefs)
            val result = sender.sendGeneric(payload)
            ui.post {
                when (result) {
                    is SmtpSender.Result.Success ->
                        state.showToast(
                            "emailed survey to ${recipient.take(28)}",
                            com.r1.launcher.ToastKind.SUCCESS,
                        )
                    is SmtpSender.Result.Failure ->
                        toastFail("email failed: ${result.message.take(60)}")
                }
            }
        }
    }

    /** Runs [SummaryGenerator] for one [CallRecord] and writes the result back
     *  to the store. Returns the record (updated if the summary succeeded,
     *  original on any failure — the email still goes out either way). */
    private fun runSurveySummary(rec: com.r1.launcher.survey.CallRecord): com.r1.launcher.survey.CallRecord {
        val model = surveyPrefs.summarizerModel
        val key = when {
            model.startsWith("claude") -> surveyPrefs.claudeKey
            model.startsWith("gpt") -> surveyPrefs.openAiKey
            else -> null
        }?.takeIf { it.isNotBlank() } ?: run {
            android.util.Log.w("SurveyPostProc", "no api key for $model; skipping summary")
            return rec
        }
        val survey = surveyStore.loadSurvey(rec.surveyId)
        val gen = com.r1.launcher.survey.SummaryGenerator.forModel(model, key)
        return when (val r = gen.generate(rec, survey)) {
            is com.r1.launcher.survey.SummaryGenerator.Result.Success -> {
                val updated = rec.copy(
                    summary = r.data.summary,
                    sentiment = r.data.sentiment,
                    completeness = r.data.completeness,
                )
                surveyStore.saveCallRecord(updated)
                ui.post {
                    // If the user is currently viewing this record, reload it.
                    if (state.currentCallRecordId == updated.id) {
                        state.currentCallRecordId = null
                        state.currentCallRecordId = updated.id
                    }
                }
                updated
            }
            is com.r1.launcher.survey.SummaryGenerator.Result.Failure -> {
                android.util.Log.w("SurveyPostProc", "summary failed: ${r.message}")
                ui.post { toastFail("summary failed: ${r.message.take(60)}") }
                rec
            }
        }
    }

    /** Assemble the SMTP payload for one call record: human-readable body,
     *  WAV audio (if present), transcript .txt, and answers .json. */
    private fun buildSurveyEmailPayload(
        rec: com.r1.launcher.survey.CallRecord,
        survey: com.r1.launcher.survey.Survey?,
        recipient: String,
    ): SmtpSender.EmailPayload {
        val durationS = rec.durationMs / 1000
        val mins = durationS / 60
        val secs = durationS % 60
        val surveyName = survey?.name ?: "(missing survey)"
        val displayContact = rec.contact.name.ifBlank { rec.contact.phone }
        val body = buildString {
            appendLine("Survey call from your R1.")
            appendLine()
            appendLine("Contact:  $displayContact")
            appendLine("Phone:    ${rec.contact.phone}")
            appendLine("Survey:   $surveyName")
            appendLine("Status:   ${rec.status.name.lowercase()}")
            appendLine("Ended:    ${rec.endReason ?: "—"}")
            appendLine("Duration: ${"%d:%02d".format(mins, secs)}")
            rec.sentiment?.let { appendLine("Sentiment: $it") }
            appendLine("Completeness: ${(rec.completeness * 100).toInt()}%")
            appendLine()
            rec.summary?.takeIf { it.isNotBlank() }?.let {
                appendLine("--- Summary ---")
                appendLine(it)
                appendLine()
            }
            if (rec.structuredAnswers.isNotEmpty()) {
                appendLine("--- Answers ---")
                rec.structuredAnswers.forEach { (qid, ans) ->
                    val qprompt = survey?.questions?.firstOrNull { it.id == qid }?.prompt
                    if (qprompt != null) appendLine("$qprompt")
                    appendLine("  → $ans")
                }
                appendLine()
            }
            appendLine("Audio recording + full transcript + JSON answers attached.")
        }

        val atts = buildList<SmtpSender.Attachment> {
            val audio = java.io.File(rec.audioPath)
            if (audio.exists() && audio.length() > 0) {
                add(SmtpSender.Attachment.FileAttachment(
                    filename = "${rec.id}.wav",
                    file = audio,
                    contentType = "audio/wav",
                ))
            }
            val transcript = rec.transcript.orEmpty()
            if (transcript.isNotBlank()) {
                add(SmtpSender.Attachment.TextAttachment(
                    filename = "${rec.id}.txt",
                    text = transcript,
                ))
            }
            add(SmtpSender.Attachment.TextAttachment(
                filename = "${rec.id}.json",
                text = encodeAnswersJson(rec),
                mime = "application/json; charset=utf-8",
            ))
        }
        return SmtpSender.EmailPayload(
            recipient = recipient,
            subject = "Survey call — $displayContact — $surveyName",
            body = body,
            attachments = atts,
        )
    }

    private fun encodeAnswersJson(rec: com.r1.launcher.survey.CallRecord): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("record_id", rec.id)
            put("campaign_id", rec.campaignId)
            put("survey_id", rec.surveyId)
            put("contact_name", rec.contact.name)
            put("contact_phone", rec.contact.phone)
            put("status", rec.status.name.lowercase())
            put("end_reason", rec.endReason)
            put("duration_ms", rec.durationMs)
            put("sentiment", rec.sentiment)
            put("completeness", rec.completeness)
            put("summary", rec.summary)
            put("answers", kotlinx.serialization.json.buildJsonObject {
                rec.structuredAnswers.forEach { (k, v) -> put(k, v) }
            })
        }
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            obj,
        )
    }

    override fun surveyPlayAudio(callRecordId: String) {
        state.showToast("playback pending (task #11 polish)", com.r1.launcher.ToastKind.INFO)
    }

    override fun surveyStopAudio() {
        // No-op until playback lands.
    }

    override fun surveyOpenSettings() {
        refreshSurveyDisplayState()
        state.openSurveySettings()
    }

    override fun surveySettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                // openai key — keyboard entry lands with task #11 polish.
                state.showToast("paste openai key via web companion for now", com.r1.launcher.ToastKind.INFO)
            }
            2, 3, 4, 5 -> {
                state.showToast("sip creds via web companion (task #12)", com.r1.launcher.ToastKind.INFO)
            }
            6 -> {
                state.showToast("edit consent in web companion", com.r1.launcher.ToastKind.INFO)
            }
            7 -> surveyCycleVoice()
            8 -> surveyCycleSummarizerModel()
            9 -> {
                state.showToast("email recipient via web companion", com.r1.launcher.ToastKind.INFO)
            }
            10 -> {
                surveyPrefs.clearAll()
                refreshSurveyDisplayState()
                state.showToast("survey settings cleared", com.r1.launcher.ToastKind.SUCCESS)
            }
        }
    }

    override fun surveyCampaignRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                state.showToast("pick survey via web companion", com.r1.launcher.ToastKind.INFO)
            }
            2 -> {
                state.showToast("pick contacts via web companion", com.r1.launcher.ToastKind.INFO)
            }
            3 -> {
                val sid = state.currentSurveyId
                if (sid == null) {
                    state.showToast("no survey selected", com.r1.launcher.ToastKind.FAIL)
                } else {
                    state.showToast("start campaign pending (task #10)", com.r1.launcher.ToastKind.INFO)
                }
            }
        }
    }

    override fun surveyListRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> surveyOpenSettings()
            2 -> surveyOpenCampaign()
            else -> {
                val campaignIdx = idx - 3
                val c = state.campaigns.getOrNull(campaignIdx) ?: return
                when (c.status) {
                    com.r1.launcher.survey.CampaignStatus.PENDING,
                    com.r1.launcher.survey.CampaignStatus.PAUSED ->
                        surveyStartCampaignById(c.id)
                    com.r1.launcher.survey.CampaignStatus.RUNNING -> {
                        state.currentCampaignId = c.id
                        state.openSurveyLive()
                    }
                    com.r1.launcher.survey.CampaignStatus.COMPLETED,
                    com.r1.launcher.survey.CampaignStatus.CANCELLED -> {
                        val recent = surveyStore.listCallRecordsForCampaign(c.id).firstOrNull()
                        if (recent != null) surveyOpenDetail(recent.id)
                        else state.showToast("no call records yet for this campaign", com.r1.launcher.ToastKind.INFO)
                    }
                }
            }
        }
    }

    override fun surveySaveOpenAiKey(key: String) {
        surveyPrefs.openAiKey = key.trim().ifBlank { null }
        refreshSurveyDisplayState()
    }

    override fun surveyPasteOpenAiKey() {
        val cb = getSystemService(android.content.ClipboardManager::class.java)
        val txt = cb?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (txt.isNullOrBlank()) {
            state.showToast("clipboard empty", com.r1.launcher.ToastKind.FAIL)
            return
        }
        surveySaveOpenAiKey(txt)
        state.showToast("openai key saved", com.r1.launcher.ToastKind.SUCCESS)
    }

    override fun surveyClearOpenAiKey() {
        surveyPrefs.openAiKey = null
        refreshSurveyDisplayState()
    }

    override fun surveyCycleVoice() {
        val voices = com.r1.launcher.survey.SurveyPrefs.REALTIME_VOICES
        val curIdx = voices.indexOf(surveyPrefs.realtimeVoice).coerceAtLeast(0)
        val next = voices[(curIdx + 1) % voices.size]
        surveyPrefs.realtimeVoice = next
        refreshSurveyDisplayState()
    }

    override fun surveyCycleSummarizerModel() {
        val models = com.r1.launcher.survey.SurveyPrefs.SUMMARIZER_MODELS.map { it.second }
        val curIdx = models.indexOf(surveyPrefs.summarizerModel).coerceAtLeast(0)
        val next = models[(curIdx + 1) % models.size]
        surveyPrefs.summarizerModel = next
        refreshSurveyDisplayState()
    }

    // ---- Web companion CRUD ----

    override fun surveyUpsertSurvey(survey: com.r1.launcher.survey.Survey): String {
        val now = System.currentTimeMillis()
        val sid = survey.id.ifBlank { "s_" + java.util.UUID.randomUUID().toString().take(8) }
        val existing = if (survey.id.isBlank()) null else surveyStore.loadSurvey(survey.id)
        val created = existing?.createdAtMs ?: now
        val toSave = survey.copy(id = sid, createdAtMs = created, updatedAtMs = now)
        surveyStore.saveSurvey(toSave)
        reloadSurveysAndCampaigns()
        return sid
    }

    override fun surveyDeleteSurveyById(surveyId: String) {
        surveyStore.deleteSurvey(surveyId)
        if (state.currentSurveyId == surveyId) state.currentSurveyId = null
        reloadSurveysAndCampaigns()
    }

    override fun surveyCreateCampaign(
        surveyId: String,
        contacts: List<com.r1.launcher.survey.Contact>,
    ): String {
        val now = System.currentTimeMillis()
        val cid = "c_" + java.util.UUID.randomUUID().toString().take(8)
        val campaign = com.r1.launcher.survey.Campaign(
            id = cid,
            surveyId = surveyId,
            contacts = contacts,
            status = com.r1.launcher.survey.CampaignStatus.PENDING,
            nextContactIdx = 0,
            createdAtMs = now,
            updatedAtMs = now,
        )
        surveyStore.saveCampaign(campaign)
        reloadSurveysAndCampaigns()
        return cid
    }

    override fun surveyCancelCampaignById(campaignId: String) {
        val c = surveyStore.loadCampaign(campaignId) ?: return
        surveyStore.saveCampaign(c.copy(
            status = com.r1.launcher.survey.CampaignStatus.CANCELLED,
            updatedAtMs = System.currentTimeMillis(),
        ))
        reloadSurveysAndCampaigns()
    }

    override fun surveyStartCampaignById(campaignId: String) {
        if (!surveyPrefs.hasOpenAiKey()) { toastFail("set openai key first"); return }
        if (!surveyPrefs.hasSipCreds()) { toastFail("set sip credentials first"); return }
        val c = surveyStore.loadCampaign(campaignId) ?: run {
            toastFail("campaign not found"); return
        }
        if (c.contacts.isEmpty()) { toastFail("campaign has no contacts"); return }
        if (surveyActiveSession != null) { toastFail("a call is already live"); return }
        val survey = surveyStore.loadSurvey(c.surveyId) ?: run {
            toastFail("survey missing: ${c.surveyId}"); return
        }
        // Mark running so progress is visible in lists.
        val running = c.copy(
            status = com.r1.launcher.survey.CampaignStatus.RUNNING,
            updatedAtMs = System.currentTimeMillis(),
        )
        surveyStore.saveCampaign(running)
        reloadSurveysAndCampaigns()
        state.currentCampaignId = campaignId
        state.openSurveyLive()
        runSurveyNextContact(running, survey)
    }

    /** A reference to the live session — non-null while a call is in flight.
     *  Reset to null in onCompleted so [surveyHangup] and the campaign loop
     *  can tell whether anything is up. */
    @Volatile private var surveyActiveSession: com.r1.launcher.survey.SurveyCallSession? = null

    /** Dial the contact at [Campaign.nextContactIdx]. Builds the [CallRecord],
     *  bumps the campaign index, kicks the session, and lets its Listener
     *  drive the rest. */
    private fun runSurveyNextContact(
        c: com.r1.launcher.survey.Campaign,
        survey: com.r1.launcher.survey.Survey,
    ) {
        val idx = c.nextContactIdx
        if (idx >= c.contacts.size) {
            // Campaign complete.
            surveyStore.saveCampaign(c.copy(
                status = com.r1.launcher.survey.CampaignStatus.COMPLETED,
                updatedAtMs = System.currentTimeMillis(),
            ))
            reloadSurveysAndCampaigns()
            state.surveyCallActive = false
            state.surveyCallStatus = "campaign done"
            state.showToast("campaign complete", com.r1.launcher.ToastKind.SUCCESS)
            return
        }
        if (c.status != com.r1.launcher.survey.CampaignStatus.RUNNING) {
            // Paused / cancelled — bail out without dialing the next contact.
            return
        }
        val contact = c.contacts[idx]
        val recordId = "r_" + java.util.UUID.randomUUID().toString().take(8)
        val audioFile = surveyStore.audioFile(c.id, recordId)
        val now = System.currentTimeMillis()
        val rec = com.r1.launcher.survey.CallRecord(
            id = recordId,
            campaignId = c.id,
            surveyId = c.surveyId,
            contact = contact,
            createdAtMs = now,
            status = com.r1.launcher.survey.CallRecordStatus.DIALING,
            audioPath = audioFile.absolutePath,
        )
        surveyStore.saveCallRecord(rec)

        // Wire up live UI state.
        state.surveyCallActive = true
        state.currentCallRecordId = recordId
        state.surveyCallContactName = contact.name.ifBlank { contact.phone }
        state.surveyCallStatus = "starting"
        state.surveyCallElapsedMs = 0L
        state.surveyCallCurrentQuestion = ""

        val creds = com.r1.launcher.survey.SurveyCallSession.SipCreds(
            host = surveyPrefs.sipHost!!,
            port = 5060,
            user = surveyPrefs.sipUser!!,
            password = surveyPrefs.sipPassword!!,
            fromNumber = surveyPrefs.sipFromNumber,
        )
        val session = com.r1.launcher.survey.SurveyCallSession(
            openAiKey = surveyPrefs.openAiKey!!,
            voice = surveyPrefs.realtimeVoice,
            survey = survey,
            contact = contact,
            consentText = survey.consentText?.takeIf { it.isNotBlank() } ?: surveyPrefs.consentText,
            sipPrefs = creds,
            wavFile = audioFile,
            listener = SurveyCallListener(c, survey, recordId),
        )
        surveyActiveSession = session
        session.start()
    }

    /** Listener that the live SurveyCallSession invokes — wires events back
     *  to [state], the web companion, the call-record store, and the campaign
     *  loop. */
    private inner class SurveyCallListener(
        private val campaign: com.r1.launcher.survey.Campaign,
        private val survey: com.r1.launcher.survey.Survey,
        private val recordId: String,
    ) : com.r1.launcher.survey.SurveyCallSession.Listener {

        private val tickRunnable = object : Runnable {
            override fun run() {
                if (state.surveyCallActive && callStartMs > 0) {
                    state.surveyCallElapsedMs = System.currentTimeMillis() - callStartMs
                    broadcastSurveyLive()
                    ui.postDelayed(this, 500L)
                }
            }
        }
        private var callStartMs: Long = 0L

        override fun onStatus(status: String) {
            state.surveyCallStatus = status
            broadcastSurveyLive()
        }
        override fun onCallEstablished() {
            callStartMs = System.currentTimeMillis()
            state.surveyCallStatus = "live"
            updateCallRecordStatus(com.r1.launcher.survey.CallRecordStatus.LIVE)
            ui.post(tickRunnable)
        }
        override fun onLiveStateChanged(s: com.r1.launcher.survey.SurveyOrchestrator.LiveState) {
            state.surveyCallCurrentQuestion = s.currentQuestionPrompt
            broadcastSurveyLive()
        }
        override fun onAssistantTextDelta(text: String) {
            webServer?.broadcastSurveyTranscriptDelta("bot", text)
        }
        override fun onUserTextFinal(text: String) {
            webServer?.broadcastSurveyTranscriptDelta("user", text)
        }
        override fun onCompleted(
            reason: String,
            consentGranted: Boolean,
            transcript: String,
            answers: Map<String, String>,
            durationMs: Long,
        ) {
            ui.removeCallbacks(tickRunnable)
            state.surveyCallActive = false
            state.surveyCallStatus = "ended: $reason"
            surveyActiveSession = null

            // Persist final CallRecord with whatever we captured.
            val existing = surveyStore.loadCallRecord(recordId) ?: return
            val finalStatus = when {
                reason == "consent_denied" -> com.r1.launcher.survey.CallRecordStatus.CONSENT_DENIED
                reason == "register_failed" -> com.r1.launcher.survey.CallRecordStatus.FAILED
                reason.startsWith("INVITE 486") -> com.r1.launcher.survey.CallRecordStatus.BUSY
                reason.startsWith("INVITE 487")
                    || reason.startsWith("INVITE 408") -> com.r1.launcher.survey.CallRecordStatus.NO_ANSWER
                reason.startsWith("INVITE ") -> com.r1.launcher.survey.CallRecordStatus.FAILED
                else -> com.r1.launcher.survey.CallRecordStatus.COMPLETED
            }
            val updated = existing.copy(
                status = finalStatus,
                durationMs = durationMs,
                transcript = transcript.takeIf { it.isNotBlank() },
                structuredAnswers = answers,
                endReason = reason,
            )
            surveyStore.saveCallRecord(updated)

            // Broadcast call.done.
            webServer?.broadcastSurveyCallDone(kotlinx.serialization.json.buildJsonObject {
                put("recordId", recordId)
                put("reason", reason)
                put("consentGranted", consentGranted)
                put("durationMs", durationMs)
            })

            // Auto-run summary + email (best-effort; failures toast but don't
            // halt the campaign).
            runSurveyPostCallPipeline(recordId, forceSummary = false, email = true)

            // Advance the campaign (bump nextContactIdx, pace).
            val freshCampaign = surveyStore.loadCampaign(campaign.id) ?: return
            if (freshCampaign.status != com.r1.launcher.survey.CampaignStatus.RUNNING) {
                reloadSurveysAndCampaigns()
                return
            }
            val nextIdx = freshCampaign.nextContactIdx + 1
            val bumped = freshCampaign.copy(
                nextContactIdx = nextIdx,
                callRecordIds = freshCampaign.callRecordIds + recordId,
                updatedAtMs = System.currentTimeMillis(),
            )
            surveyStore.saveCampaign(bumped)
            reloadSurveysAndCampaigns()
            val delay = surveyPrefs.betweenCallsDelayMs
            ui.postDelayed({
                val cur = surveyStore.loadCampaign(bumped.id) ?: return@postDelayed
                if (cur.status == com.r1.launcher.survey.CampaignStatus.RUNNING) {
                    runSurveyNextContact(cur, survey)
                }
            }, delay)
        }
        override fun onError(message: String) {
            android.util.Log.w("SurveyCall", "session error: $message")
            state.surveyCallStatus = "error: ${message.take(40)}"
            broadcastSurveyLive()
        }

        private fun updateCallRecordStatus(s: com.r1.launcher.survey.CallRecordStatus) {
            val rec = surveyStore.loadCallRecord(recordId) ?: return
            surveyStore.saveCallRecord(rec.copy(status = s))
        }

        private fun broadcastSurveyLive() {
            webServer?.broadcastSurveyCallState(kotlinx.serialization.json.buildJsonObject {
                put("active", state.surveyCallActive)
                put("campaignId", state.currentCampaignId)
                put("recordId", state.currentCallRecordId)
                put("status", state.surveyCallStatus)
                put("contactName", state.surveyCallContactName)
                put("currentQuestion", state.surveyCallCurrentQuestion)
                put("elapsedMs", state.surveyCallElapsedMs)
            })
        }
    }

    override fun surveySaveSettingsField(field: String, value: String) {
        val trimmed = value.trim()
        when (field) {
            "openai_key"        -> surveyPrefs.openAiKey = trimmed.ifBlank { null }
            "claude_key"        -> surveyPrefs.claudeKey = trimmed.ifBlank { null }
            "sip_host"          -> surveyPrefs.sipHost = trimmed.ifBlank { null }
            "sip_user"          -> surveyPrefs.sipUser = trimmed.ifBlank { null }
            "sip_password"      -> surveyPrefs.sipPassword = trimmed.ifBlank { null }
            "sip_from"          -> surveyPrefs.sipFromNumber = trimmed.ifBlank { null }
            "consent_text"      -> surveyPrefs.consentText =
                trimmed.ifBlank { com.r1.launcher.survey.SurveyPrefs.DEFAULT_CONSENT_TEXT }
            "voice"             -> surveyPrefs.realtimeVoice =
                trimmed.ifBlank { com.r1.launcher.survey.SurveyPrefs.DEFAULT_REALTIME_VOICE }
            "summarizer_model"  -> surveyPrefs.summarizerModel =
                trimmed.ifBlank { com.r1.launcher.survey.SurveyPrefs.DEFAULT_SUMMARIZER_MODEL }
            "email_recipient"   -> surveyPrefs.emailRecipient = trimmed.ifBlank { null }
            else -> android.util.Log.w("SurveyPrefs", "unknown settings field: $field")
        }
        refreshSurveyDisplayState()
    }
}

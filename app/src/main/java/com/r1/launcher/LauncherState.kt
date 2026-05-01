package com.r1.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.r1.launcher.messages.SmsConversation
import com.r1.launcher.messages.SmsItem
import com.r1.launcher.openclaw.ChatMessage
import com.r1.launcher.openclaw.SessionEntry

enum class Panel { HOME, APPS, SETTINGS, NETWORK, WIFI_SCAN, WIFI_PASSWORD, WIFI_SHARE, WIFI_SHARE_EDIT, BRIGHTNESS, VOLUME, FACTORY_CONFIRM, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_TALK, OPENCLAW_CANVAS, OPENCLAW_CAMERA, OPENCLAW_SETTINGS, OPENCLAW_SESSIONS, MESSAGES, MESSAGES_THREAD }

enum class WifiShareEditTarget { SSID, PASSWORD }

/** What the OPENCLAW_QR scanner is currently looking for. */
enum class QrScanMode { GATEWAY_PAIRING, OPENAI_KEY }

/**
 * Single container for all UI state. Activity mutates; Compose reads.
 *
 * Uses mutableStateOf so Compose observes changes directly — no Flow collection,
 * no ViewModel ceremony. The Activity is single-instance with configChanges flags,
 * so we don't need ViewModel lifecycle survival.
 *
 * Panel state machine mirrors the old Java: panels stack conceptually but we only
 * show one at a time (plus scrim + topbar overlays). `back()` unwinds one level.
 */
class LauncherState {
    // --- panel ---
    var panel by mutableStateOf(Panel.HOME)
        private set

    // --- per-panel focus indices ---
    var appsFocus by mutableIntStateOf(0)
    /** Settings panel rows: 0=Brightness, 1=Volume, 2=Wi-Fi, 3=Airplane/Data. */
    var settingsFocus by mutableIntStateOf(0)
    var networkFocus by mutableIntStateOf(0)
    /** Factory-reset confirmation: 0=back/cancel, 1=confirm wipe. Defaults to 0 so accidental activate is a cancel. */
    var factoryConfirmFocus by mutableIntStateOf(0)
    var wifiScanFocus by mutableIntStateOf(0)
    var wifiConnectedSsid by mutableStateOf("")
    var wifiSelectedSsid by mutableStateOf("")
    var wifiPasswordInput by mutableStateOf("")
    val wifiScanResults = mutableStateListOf<String>()
    // --- wifi share (hotspot) ---
    var wifiShareFocus by mutableIntStateOf(0)
    var wifiShareEnabled by mutableStateOf(false)
    var wifiShareSsid by mutableStateOf("")
    var wifiSharePassword by mutableStateOf("")
    val wifiShareConnectedClients = mutableStateListOf<String>()
    /** 0 = off, otherwise minutes until auto-shutoff. Persisted via WifiSharePrefs. */
    var wifiShareTimerMinutes by mutableIntStateOf(0)
    /** Live countdown shown on the enable row while hotspot is on with a timer. */
    var wifiShareTimerRemainingSec by mutableIntStateOf(0)
    /** Which field the WIFI_SHARE_EDIT keyboard is editing. */
    var wifiShareEditTarget by mutableStateOf(WifiShareEditTarget.SSID)
    /** Buffer used by WIFI_SHARE_EDIT; copied into the targeted field on save. */
    var wifiShareEditInput by mutableStateOf("")
    /** Whether the connected-clients row in WIFI_SHARE is expanded to show MACs. */
    var wifiShareClientsExpanded by mutableStateOf(false)
    /** Live brightness 1..255 — pre-seeded from Settings.System on openSettings(). */
    var brightnessLevel by mutableIntStateOf(128)
    /** Live STREAM_MUSIC volume 0..volumeMax. */
    var volumeLevel by mutableIntStateOf(0)
    var volumeMax by mutableIntStateOf(15)

    // --- clock / date ---
    var clockText by mutableStateOf("00:00")
    var dateText by mutableStateOf("—")

    // --- topbar & network toggles ---
    var wifiOn by mutableStateOf(false)
    var wifiEnabled by mutableStateOf(false)
    var cellularOn by mutableStateOf(false)
    var btOn by mutableStateOf(false)
    /** Topbar update spinner: 0=hidden, 1=half (checking), 2=full + rotate (downloading/installing). */
    var updateIconState by mutableIntStateOf(0)
    var batteryPct by mutableFloatStateOf(1f)
    /** True while a charger (USB/AC/wireless) is connected. Topbar uses this to tint the battery pill green. */
    var batteryCharging by mutableStateOf(false)
    var simPresent by mutableStateOf(false)
    var simOperator by mutableStateOf("")
    var networkType by mutableStateOf("")
    /** 0..4 */
    var signalLevel by mutableIntStateOf(0)

    // --- apps list ---
    val apps = mutableStateListOf<AppEntry>()
    var appsLoaded = false

    // --- openclaw chat panel ---
    val chatMessages = mutableStateListOf<ChatMessage>()
    /** Cap message list so very long sessions don't degrade UI responsiveness. */
    val chatMessagesMax = 500
    /** Live assistant streaming preview — shown as a single bubble at the bottom
     *  while a `delta` is in flight. Replaced by the canonical bubble after the
     *  next `chat.history` refresh on terminal events. */
    var chatStreamingText by mutableStateOf("")
    /** Run IDs initiated by us; used to filter stream events from other operators. */
    val chatPendingRunIds = mutableStateListOf<String>()
    var chatStatus by mutableStateOf("connecting")
    var chatRecording by mutableStateOf(false)
    var chatBusy by mutableStateOf(false)
    var chatScrollIndex by mutableIntStateOf(0)
    var canvasScrollIndex by mutableIntStateOf(0)
    /** Live mic peak 0..100, used by the talk-mode input ring. */
    var chatInputLevel by mutableIntStateOf(0)
    /** Running speech-to-text transcript while recording. Cleared on stop. */
    var chatPartialText by mutableStateOf("")
    /** Last QR-decode error to surface in the QR panel. Null = no error shown. */
    var qrError by mutableStateOf<String?>(null)
    /** Drives the QR scanner's behaviour on a successful decode. */
    var qrScanMode by mutableStateOf(QrScanMode.GATEWAY_PAIRING)
    /** OpenAI Whisper key state — true if a key is saved. Header pill reads this. */
    var chatHasOpenaiKey by mutableStateOf(false)
    /** Last 4 chars of saved Whisper key for visual confirmation. Empty if unset. */
    var chatOpenaiKeyTail by mutableStateOf("")
    /** True between "stop recording" and either transcript-back or error. */
    var chatTranscribing by mutableStateOf(false)
    /** Buffer for the openclaw settings input field. */
    var chatSettingsKeyInput by mutableStateOf("")
    /** Focus index for the openclaw settings menu. */
    var openClawSettingsFocus by mutableIntStateOf(0)
    /** Toggle to hide chat messages in the chat panel. */
    var openClawHideChat by mutableStateOf(false)
    /** Chat font size in sp. Adjustable from OpenClaw settings. */
    var chatFontSize by mutableIntStateOf(14)
    /** Auto-speak assistant replies via Android TextToSpeech when terminal events fire. */
    var chatTtsEnabled by mutableStateOf(false)
    /** Available threads from sessions.list. Driven by GatewaySession.onSessions. */
    val chatSessions = mutableStateListOf<SessionEntry>()
    /** Currently active thread key. Persisted across launches via OpenClawPrefs. */
    var selectedSessionKey by mutableStateOf("main")
    /** Server-snapshot main session key from connect response. */
    var mainSessionKey by mutableStateOf("main")
    /** True while a sessions.list refresh is in flight. */
    var sessionsLoading by mutableStateOf(false)
    /** Focus index for the OPENCLAW_SESSIONS panel rows. */
    var openClawSessionsFocus by mutableIntStateOf(0)
    /** Snap-and-ask camera prompt + captured JPEG payload for OpenClaw chat. */
    var openClawCameraPrompt by mutableStateOf("what do you see?")
    var openClawCameraJpegBase64 by mutableStateOf<String?>(null)
    var openClawCameraBusy by mutableStateOf(false)
    var openClawCameraError by mutableStateOf<String?>(null)
    /**
     * Current target angle for the camera stepper motor while the OpenClaw
     * camera panel is open. Range [0, 180]: 0 = FACE (lens at user), 90 = idle,
     * 180 = BACK (lens at scene). Defaults to BACK on panel entry; wheel
     * up/down nudges it in 15° steps so the user can re-aim the lens (e.g.
     * tilt down for a desk shot or up for selfie framing).
     */
    var openClawCameraMotor by mutableIntStateOf(180)

    // --- web companion panel ---
    var webServerEnabled by mutableStateOf(false)
    var webServerPort by mutableIntStateOf(8080)
    /** Best-effort local IP of the interface the panel is reachable on. */
    var webServerIp by mutableStateOf("")

    // --- messages (SMS) ---
    val smsConversations = mutableStateListOf<SmsConversation>()
    /** True while loadConversations() is running on a background thread. */
    var smsLoading by mutableStateOf(false)
    /** Set when READ_SMS is denied or content provider returned no rows. */
    var smsError by mutableStateOf<String?>(null)
    var messagesFocus by mutableIntStateOf(0)
    /** Open thread address; drives MESSAGES_THREAD title + body list. */
    var smsThreadAddress by mutableStateOf("")
    var smsThreadName by mutableStateOf("")
    val smsThreadMessages = mutableStateListOf<SmsItem>()
    var smsThreadFocus by mutableIntStateOf(0)

    // --- state transitions ---

    fun openApps() {
        appsFocus = 0
        panel = Panel.APPS
    }

    fun openSettings() {
        settingsFocus = 0
        panel = Panel.SETTINGS
    }

    fun openNetwork() {
        networkFocus = 0
        panel = Panel.NETWORK
    }

    fun openWifiScan() {
        wifiScanFocus = 0
        panel = Panel.WIFI_SCAN
    }

    fun openWifiPassword(ssid: String) {
        wifiSelectedSsid = ssid
        wifiPasswordInput = ""
        panel = Panel.WIFI_PASSWORD
    }

    fun openWifiShare() {
        wifiShareFocus = 0
        wifiShareClientsExpanded = false
        panel = Panel.WIFI_SHARE
    }

    fun openWifiShareEdit(target: WifiShareEditTarget) {
        wifiShareEditTarget = target
        wifiShareEditInput = when (target) {
            WifiShareEditTarget.SSID -> wifiShareSsid
            WifiShareEditTarget.PASSWORD -> wifiSharePassword
        }
        panel = Panel.WIFI_SHARE_EDIT
    }

    fun openBrightness() {
        panel = Panel.BRIGHTNESS
    }

    fun openVolume() {
        panel = Panel.VOLUME
    }

    fun openFactoryConfirm() {
        factoryConfirmFocus = 0
        panel = Panel.FACTORY_CONFIRM
    }

    fun openOpenClawQr() {
        // Don't clear qrError here — auto-recovery from a failed handshake
        // sets the error message and then opens this panel; clearing would
        // erase it before the user sees it. User-initiated entry from the
        // apps grid resets qrError explicitly.
        qrScanMode = QrScanMode.GATEWAY_PAIRING
        panel = Panel.OPENCLAW_QR
    }

    /** Open the same camera panel but treat the next decode as an OpenAI key. */
    fun openOpenAiKeyQr() {
        qrError = null
        qrScanMode = QrScanMode.OPENAI_KEY
        panel = Panel.OPENCLAW_QR
    }

    fun openOpenClawChat() {
        chatScrollIndex = 0
        chatStatus = "connecting"
        panel = Panel.OPENCLAW_CHAT
    }

    fun openOpenClawTalk() {
        chatScrollIndex = 0
        panel = Panel.OPENCLAW_TALK
    }

    fun openOpenClawCanvas() {
        canvasScrollIndex = 0
        panel = Panel.OPENCLAW_CANVAS
    }

    fun openOpenClawCamera() {
        openClawCameraPrompt = "what do you see?"
        openClawCameraJpegBase64 = null
        openClawCameraBusy = false
        openClawCameraError = null
        openClawCameraMotor = 180
        panel = Panel.OPENCLAW_CAMERA
    }

    fun openOpenClawSessions() {
        openClawSessionsFocus = 0
        panel = Panel.OPENCLAW_SESSIONS
    }

    fun openMessages() {
        messagesFocus = 0
        panel = Panel.MESSAGES
    }

    fun openMessagesThread(address: String, displayName: String) {
        smsThreadAddress = address
        smsThreadName = displayName
        smsThreadFocus = 0
        smsThreadMessages.clear()
        panel = Panel.MESSAGES_THREAD
    }

    fun openOpenClawSettings() {
        // Pre-fill input with the current key (masked rendering happens in UI).
        // Empty string when no key is set.
        chatSettingsKeyInput = ""
        openClawSettingsFocus = 0
        panel = Panel.OPENCLAW_SETTINGS
    }

    fun goHome() {
        panel = Panel.HOME
    }

    fun back() {
        panel = when (panel) {
            Panel.NETWORK, Panel.BRIGHTNESS, Panel.VOLUME, Panel.FACTORY_CONFIRM -> Panel.SETTINGS
            Panel.WIFI_SCAN -> Panel.NETWORK
            Panel.WIFI_PASSWORD -> Panel.WIFI_SCAN
            Panel.WIFI_SHARE -> Panel.NETWORK
            Panel.WIFI_SHARE_EDIT -> Panel.WIFI_SHARE
            Panel.SETTINGS -> Panel.APPS
            Panel.APPS -> Panel.HOME
            Panel.OPENCLAW_QR -> if (qrScanMode == QrScanMode.OPENAI_KEY) Panel.OPENCLAW_SETTINGS else Panel.APPS
            Panel.OPENCLAW_CHAT -> Panel.APPS
            Panel.OPENCLAW_TALK -> Panel.OPENCLAW_CHAT
            Panel.OPENCLAW_CANVAS -> Panel.OPENCLAW_CHAT
            Panel.OPENCLAW_CAMERA -> Panel.OPENCLAW_CHAT
            Panel.OPENCLAW_SETTINGS, Panel.OPENCLAW_SESSIONS -> Panel.OPENCLAW_CHAT
            Panel.MESSAGES -> Panel.APPS
            Panel.MESSAGES_THREAD -> Panel.MESSAGES
            Panel.HOME -> Panel.HOME
        }
    }
}

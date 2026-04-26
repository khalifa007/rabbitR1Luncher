package com.r1.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.r1.launcher.openclaw.ChatMessage

enum class Panel { HOME, SHEET, APPS, STORE, DETAIL, SETTINGS, NETWORK, WIFI_SCAN, WIFI_PASSWORD, BRIGHTNESS, VOLUME, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_SETTINGS, AUDIO_TEST }

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
    /** Home dock: 0=system, 1=store, 2=apps. */
    var homeFocus by mutableIntStateOf(0)
    var sheetFocus by mutableIntStateOf(0)
    var appsFocus by mutableIntStateOf(0)
    var storeFocus by mutableIntStateOf(0)
    /** Detail overlay: 0=back, 1=open, 2=uninstall. */
    var detailFocus by mutableIntStateOf(0)
    /** Settings panel rows: 0=Brightness, 1=Volume, 2=Wi-Fi, 3=Airplane/Data. */
    var settingsFocus by mutableIntStateOf(0)
    var networkFocus by mutableIntStateOf(0)
    var wifiScanFocus by mutableIntStateOf(0)
    var wifiConnectedSsid by mutableStateOf("")
    var wifiSelectedSsid by mutableStateOf("")
    var wifiPasswordInput by mutableStateOf("")
    val wifiScanResults = mutableStateListOf<String>()
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
    /** 0=hidden, 1=half (checking), 2=full + rotate (downloading/installing). */
    var updateIconState by mutableIntStateOf(0)
    var batteryPct by mutableFloatStateOf(1f)
    var simPresent by mutableStateOf(false)
    var simOperator by mutableStateOf("")
    var networkType by mutableStateOf("")
    /** 0..4 */
    var signalLevel by mutableIntStateOf(0)

    // --- apps list ---
    val apps = mutableStateListOf<AppEntry>()
    var appsLoaded = false

    // --- store ---
    val storeEntries = mutableStateListOf<AppStore.Entry>()
    var storeFetchedOnce = false
    var storeFetchedAt: Long = 0L
    var storeLoadError by mutableStateOf<String?>(null)
    var storeLoading by mutableStateOf(false)
    var downloadingSlug by mutableStateOf<String?>(null)
    var downloadingPct by mutableIntStateOf(0)
    var detailEntry by mutableStateOf<AppStore.Entry?>(null)

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
    /** Running speech-to-text transcript while recording. Cleared on stop. */
    var chatPartialText by mutableStateOf("")
    /** Last QR-decode error to surface in the QR panel. Null = no error shown. */
    var qrError by mutableStateOf<String?>(null)
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

    // --- audio test panel ---
    /** Index into AudioTester.Source.values() — wheel up/down cycles when idle. */
    var audioTestSourceIndex by mutableIntStateOf(0)
    /** "idle" | "recording" | "playing" | "done" | "error: ..." */
    var audioTestStatus by mutableStateOf("idle")
    /** Live RMS 0..100 — live during recording, snaps to 0 otherwise. */
    var audioTestLevel by mutableIntStateOf(0)
    /** Live peak 0..100 — live during recording. */
    var audioTestPeak by mutableIntStateOf(0)
    /** After a recording finishes: duration (ms), sample count, max peak seen. */
    var audioTestLastDurationMs by mutableIntStateOf(0)
    var audioTestLastSamples by mutableIntStateOf(0)
    var audioTestLastPeakOverall by mutableIntStateOf(0)
    var audioTestHasRecording by mutableStateOf(false)

    // --- debug key overlay ---
    var debugKeyText by mutableStateOf("")
    var debugKeyVisible by mutableStateOf(false)
    /** User-toggled in Settings: when false, key codes never light up the overlay. */
    var showDebugBar by mutableStateOf(true)

    // --- state transitions ---

    fun openSheet() {
        sheetFocus = 0
        panel = Panel.SHEET
    }

    fun openApps() {
        appsFocus = 0
        panel = Panel.APPS
    }

    fun openStore() {
        storeFocus = 0
        panel = Panel.STORE
    }

    fun openDetail(entry: AppStore.Entry) {
        detailEntry = entry
        detailFocus = 0
        panel = Panel.DETAIL
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

    fun openBrightness() {
        panel = Panel.BRIGHTNESS
    }

    fun openVolume() {
        panel = Panel.VOLUME
    }

    fun openOpenClawQr() {
        // Don't clear qrError here — auto-recovery from a failed handshake
        // sets the error message and then opens this panel; clearing would
        // erase it before the user sees it. User-initiated entry from the
        // apps grid resets qrError explicitly.
        panel = Panel.OPENCLAW_QR
    }

    fun openOpenClawChat() {
        chatScrollIndex = 0
        chatStatus = "connecting"
        panel = Panel.OPENCLAW_CHAT
    }

    fun openOpenClawSettings() {
        // Pre-fill input with the current key (masked rendering happens in UI).
        // Empty string when no key is set.
        chatSettingsKeyInput = ""
        openClawSettingsFocus = 0
        panel = Panel.OPENCLAW_SETTINGS
    }

    fun openAudioTest() {
        audioTestStatus = "idle"
        audioTestLevel = 0
        audioTestPeak = 0
        panel = Panel.AUDIO_TEST
    }

    fun goHome() {
        panel = Panel.HOME
        detailEntry = null
    }

    fun back() {
        panel = when (panel) {
            Panel.DETAIL -> Panel.STORE
            Panel.NETWORK, Panel.BRIGHTNESS, Panel.VOLUME -> Panel.SETTINGS
            Panel.WIFI_SCAN -> Panel.NETWORK
            Panel.WIFI_PASSWORD -> Panel.WIFI_SCAN
            Panel.SETTINGS -> Panel.APPS
            Panel.STORE, Panel.APPS, Panel.SHEET -> Panel.HOME
            Panel.OPENCLAW_QR, Panel.OPENCLAW_CHAT, Panel.AUDIO_TEST -> Panel.APPS
            Panel.OPENCLAW_SETTINGS -> Panel.OPENCLAW_CHAT
            Panel.HOME -> Panel.HOME
        }
        if (panel != Panel.DETAIL) detailEntry = null
    }
}

package com.r1.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.r1.launcher.claude.ClaudeMessage
import com.r1.launcher.messages.SmsConversation
import com.r1.launcher.messages.SmsItem
import com.r1.launcher.openclaw.ChatMessage
import com.r1.launcher.openclaw.SessionEntry
import com.r1.launcher.survey.CallRecordIndexEntry
import com.r1.launcher.survey.CampaignIndexEntry
import com.r1.launcher.survey.SurveyIndexEntry
import com.r1.launcher.transcriber.MeetingIndexEntry
import com.r1.launcher.transcriber.TranscriberDetailAction

enum class Panel { HOME, ONBOARDING, APPS, SETTINGS, SETTINGS_DISPLAY, SETTINGS_SOUND, SETTINGS_DEVICE, SETTINGS_ABOUT, SETTINGS_VOICE, SETTINGS_VOICE_TUNING, SETTINGS_VOICE_SUBSCRIPTION, SETTINGS_LANGUAGE, NETWORK, WIFI_SCAN, WIFI_PASSWORD, WIFI_SHARE, WIFI_SHARE_EDIT, BRIGHTNESS, VOLUME, UI_VOLUME, FACTORY_CONFIRM, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_CAMERA, OPENCLAW_SETTINGS, OPENCLAW_SESSIONS, MESSAGES, MESSAGES_THREAD, TERMINAL, CLAUDE, TRANSCRIBER_LIST, TRANSCRIBER_RECORDING, TRANSCRIBER_DETAIL, TRANSCRIBER_SETTINGS, SURVEY_LIST, SURVEY_CAMPAIGN, SURVEY_CONSENT, SURVEY_LIVE, SURVEY_DETAIL, SURVEY_SETTINGS }

enum class WifiShareEditTarget { SSID, PASSWORD }

/** What the OPENCLAW_QR scanner is currently looking for. */
enum class QrScanMode { GATEWAY_PAIRING, OPENAI_KEY }

/** Kind of toast — drives the edge color in [com.r1.launcher.ui.ToastOverlay]. */
enum class ToastKind { INFO, SUCCESS, FAIL }

/**
 * Single in-flight toast. [id] makes consecutive identical messages distinct
 * for `LaunchedEffect` re-keying; [expiresAtMs] is the wall-clock dismiss time
 * so the overlay can self-clear via `delay`.
 */
data class ToastEntry(
    val id: Long,
    val text: String,
    val kind: ToastKind,
    val expiresAtMs: Long,
)

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
    /**
     * Bumped by [activate] when launching from the apps panel via wheel/side
     * button. The focused AppCard observes this and runs the same press
     * animation that the touch path already fires via its `clickable` lambda,
     * so both entry points share one visual feedback.
     */
    var appsPressTrigger by mutableIntStateOf(0)
    /** Onboarding wizard: 0=welcome, 1=network, 2=updates, 3=done. */
    var onboardingStep by mutableIntStateOf(0)
    /** Focus within the current onboarding step's row list. */
    var onboardingFocus by mutableIntStateOf(0)
    /** True while the wizard is active — gates back-routing detours from sub-flows like wifi scan. */
    var isOnboarding by mutableStateOf(false)
    /** Top settings: 0=back, 1=network, 2=display, 3=sound, 4=device, 5=about. */
    var settingsFocus by mutableIntStateOf(0)
    /** Display category: 0=back, 1=brightness. */
    var settingsDisplayFocus by mutableIntStateOf(0)
    /** Sound category: 0=back, 1=ui sound, 2=speaker. */
    var settingsSoundFocus by mutableIntStateOf(0)
    /** Device category: 0=back, 1=updates, 2=factory reset. */
    var settingsDeviceFocus by mutableIntStateOf(0)
    /** About category: 0=back. (single info row). */
    var settingsAboutFocus by mutableIntStateOf(0)
    /** Language picker: 0=back, 1..N=LocalePrefs.SUPPORTED[N-1]. */
    var settingsLanguageFocus by mutableIntStateOf(0)
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
    /** UI-click feedback volume 0..uiVolumeMax. Drives SoundPool gain in
     *  playMovingSound / playUiClickSound; persisted via SoundPrefs. */
    var uiVolumeLevel by mutableIntStateOf(5)
    var uiVolumeMax by mutableIntStateOf(15)

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
    /** Live mic peak 0..100, used by the talk-mode input ring. */
    var chatInputLevel by mutableIntStateOf(0)
    /** Running speech-to-text transcript while recording. Cleared on stop. */
    var chatPartialText by mutableStateOf("")
    /** Last QR-decode error to surface in the QR panel. Null = no error shown. */
    var qrError by mutableStateOf<String?>(null)
    /** Drives the QR scanner's behaviour on a successful decode. */
    var qrScanMode by mutableStateOf(QrScanMode.GATEWAY_PAIRING)
    /** True between "stop recording" and either transcript-back or error. */
    var chatTranscribing by mutableStateOf(false)
    /** Buffer for the Settings → Voice keyboard input field (key entry). */
    var voiceKeyInput by mutableStateOf("")
    /** Focus index for the openclaw settings menu. */
    var openClawSettingsFocus by mutableIntStateOf(0)
    /** Toggle to hide chat messages in the chat panel. */
    var openClawHideChat by mutableStateOf(false)
    /** Chat font size in sp. Adjustable from OpenClaw settings. */
    var chatFontSize by mutableIntStateOf(14)
    // Voice config now lives globally in Settings → Voice (see VoicePrefs).
    // The fields below are populated from VoicePrefs at activity start and on
    // setting changes, so the UI can read them reactively.
    var voiceEnabled by mutableStateOf(false)
    var voiceId by mutableStateOf("21m00Tcm4TlvDq8ikWAM") // Rachel
    var hasVoiceKey by mutableStateOf(false)
    var voiceKeyTail by mutableStateOf("")
    var voiceFocus by mutableIntStateOf(0)
    /** Optional user-supplied voice id (cloned, professional, shared) overriding
     *  the catalog [voiceId] for synthesis. Empty string = unset. */
    var voiceCustomId by mutableStateOf("")
    /** TTS model id (Flash / Turbo / Multilingual). Hydrated from VoicePrefs. */
    var voiceModel by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_MODEL)
    /** voice_settings.stability — 0..1, hydrated from prefs. */
    var voiceStability by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_STABILITY)
    var voiceSimilarity by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SIMILARITY)
    var voiceStyle by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_STYLE)
    var voiceSpeed by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SPEED)
    var voiceSpeakerBoost by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SPEAKER_BOOST)
    /** Focus index for SETTINGS_VOICE_TUNING rows. */
    var voiceTuningFocus by mutableIntStateOf(0)
    /** True while a "test voice" synthesis is in flight (button stays warm). */
    var voiceTestBusy by mutableStateOf(false)
    // ElevenLabs subscription/balance — cached from /v1/user/subscription. The
    // single credit pool covers TTS chars + STT minutes (Meetings, chat STT,
    // terminal STT, claude STT, OpenClaw TTS) so this is the right home for the
    // balance display rather than putting it inside the Meetings panel.
    /** Full subscription snapshot. Null = never fetched. */
    var voiceSubData by mutableStateOf<com.r1.launcher.voice.SubscriptionData?>(null)
    /** True between fetch start and response. */
    var voiceSubLoading by mutableStateOf(false)
    /** When the cached values were fetched (epoch ms). 0 = never. */
    var voiceSubFetchedAtMs by mutableStateOf(0L)
    /** Last error message from the fetch, or null on success / never tried. */
    var voiceSubError by mutableStateOf<String?>(null)
    /** Focus index for the SETTINGS_VOICE_SUBSCRIPTION page rows. */
    var voiceSubFocus by mutableIntStateOf(0)
    // Live partial transcripts during STT recording. chatPartialText already
    // existed (line 126) and is reused for OpenClaw chat. The two below are
    // new for terminal/claude panels which gain dictation via ElevenLabs.
    var terminalPartial by mutableStateOf("")
    var claudePartial by mutableStateOf("")
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
    /** When false, web RPC `terminal.*` methods refuse with a "disabled" error.
     *  Off by default — the launcher's root shell over LAN is a real risk and
     *  the user must explicitly opt in via Settings → Network → "remote terminal". */
    var webTerminalEnabled by mutableStateOf(false)

    // --- terminal panel ---
    /** Current input buffer; submitted on wheel-press, edited via RetroKeyboard. */
    var terminalInput by mutableStateOf("")
    /** Working directory tracked client-side (parsed from `cd ...`); prepended
     *  to every command since each carroot connection gets a fresh shell. */
    var terminalCwd by mutableStateOf("/sdcard")
    /** Output scrollback. Capped at 500 lines (FIFO) to bound memory. */
    val terminalOutput = mutableStateListOf<String>()
    val terminalOutputMax = 500
    /** True between submit and command exit. Blocks concurrent submissions. */
    var terminalBusy by mutableStateOf(false)
    var terminalRecording by mutableStateOf(false)
    var terminalTranscribing by mutableStateOf(false)
    /** Wheel-driven scroll offset for the output area (0 = bottom/latest). */
    var terminalScrollIndex by mutableIntStateOf(0)
    /** When false, the on-screen RetroKeyboard collapses so the output area
     *  fills the screen — useful for reading long `npm install` logs. Toggled
     *  by the "kbd" header pill or the keyboard's own "hide" key. */
    var terminalKbVisible by mutableStateOf(true)

    // --- claude code app (Panel.CLAUDE) ---
    /** Chat scrollback for the Claude Code app — alternating user/assistant
     *  bubbles. Each turn is a single committed message (no streaming inside
     *  a bubble; live streaming text lives in [claudeStreamingText]). */
    val claudeMessages = mutableStateListOf<ClaudeMessage>()
    /** Cap so very long chats don't degrade UI responsiveness. FIFO drop. */
    val claudeMessagesMax = 200
    /** Current input buffer; submitted on wheel-press / send pill. */
    var claudeInput by mutableStateOf("")
    /** True from "send" until the claude --print invocation returns. Blocks
     *  concurrent submissions and drives the `...` status indicator. */
    var claudeBusy by mutableStateOf(false)
    /** Live partial assistant reply being streamed from claude --print's
     *  stdout. Rendered as a "live" bubble at the bottom of the scrollback;
     *  committed to [claudeMessages] when the invocation completes. */
    var claudeStreamingText by mutableStateOf("")
    /** Wheel-driven scroll offset (0 = bottom/latest). */
    var claudeScrollIndex by mutableIntStateOf(0)
    /** True between mic-press and transcript-back. */
    var claudeRecording by mutableStateOf(false)
    var claudeTranscribing by mutableStateOf(false)
    /** False on the very first send of a session (no `-c` flag — start fresh).
     *  Flips to true after first reply so subsequent sends use `--continue`
     *  to maintain context. Reset on "clear chat" pill. */
    var claudeFirstTurn by mutableStateOf(true)
    /** True until the user dismisses the "use the web companion" hint screen
     *  (the QR + IP redirect). Defaults true so first-time users land on the
     *  hint; "open anyway" flips it false for the rest of the session. The
     *  on-device chat is still functional — this just defers users to the
     *  bigger-screen experience by default since the R1 keyboard is rough.
     *  NOTE: this flag is only consulted when [claudeAuthed] is false — once
     *  the user is logged in, the redirect is skipped entirely (the QR's
     *  primary purpose is the OAuth flow, which is hard to do on-device). */
    var claudeShowWebHint by mutableStateOf(true)
    /** Set true once the launcher has confirmed Claude has working creds
     *  (subscription OAuth via .credentials.json OR an Anthropic API key).
     *  Updated from [claudeAuthStatus]'s background check on activity
     *  startup, after bootstrap completion, and after each auth action.
     *  When true, opening the Claude tile drops straight into chat — the
     *  QR redirect was a setup affordance, not a recurring detour. */
    var claudeAuthed by mutableStateOf(false)

    // --- meetings (transcriber) ---
    /** Index of saved meetings, newest first. Hydrated from MeetingStore on
     *  panel entry; updated in-place after start/stop/transcribe/delete. */
    val meetings = mutableStateListOf<MeetingIndexEntry>()
    /** Focus on TRANSCRIBER_LIST: 0=back pill, 1=settings gear (top-right),
     *  2="+ new recording", 3..N+2=meetings. */
    var transcriberListFocus by mutableIntStateOf(0)
    /** UUID of the currently-selected meeting in detail panel. */
    var currentMeetingUuid by mutableStateOf<String?>(null)
    /** Detail-panel focus when the ⋮ overlay is closed: 0=back pill,
     *  1=⋮ menu icon. While the overlay is open, [transcriberDetailMenuFocus]
     *  drives the wheel instead. */
    var transcriberDetailFocus by mutableIntStateOf(0)
    /** Settings panel focus: 0=back, 1=smtp host, 2=smtp port, 3=smtp user,
     *  4=smtp password, 5=default recipient, 6=clear smtp. */
    var transcriberSettingsFocus by mutableIntStateOf(0)
    /** Live recording state mirrored from the FGS binder. */
    var recordingActive by mutableStateOf(false)
    var recordingElapsedMs by mutableStateOf(0L)
    var recordingPeak by mutableIntStateOf(0)
    /** True between record-stop and Scribe response (or failure). */
    var transcribeBusy by mutableStateOf(false)
    /** Buffer for the recipient typed in the detail panel email keyboard. */
    var transcriberRecipientInput by mutableStateOf("")
    /** Which transcriber-settings field the keyboard is editing.
     *  Empty string = keyboard not visible. */
    var transcriberSettingsEditField by mutableStateOf("")
    var transcriberSettingsEditInput by mutableStateOf("")
    /** Has SMTP creds (cached from prefs to avoid repeated reads on the UI thread). */
    var hasSmtp by mutableStateOf(false)
    var smtpHostDisplay by mutableStateOf("")
    var smtpPortDisplay by mutableIntStateOf(0)
    var smtpUserDisplay by mutableStateOf("")
    var defaultRecipientDisplay by mutableStateOf("")
    /** True while audio is playing back from the detail panel. */
    var detailPlaying by mutableStateOf(false)
    /** When non-empty, shown on the detail panel (e.g. "sent to alice@…", "send failed: …"). */
    var detailStatus by mutableStateOf("")
    /** True while the ⋮ action overlay is open on the detail panel. While open,
     *  the wheel + activate routes through [transcriberDetailMenuFocus] and
     *  back/menu-press first closes the overlay before unwinding the panel. */
    var transcriberDetailMenuOpen by mutableStateOf(false)
    /** Focus index inside the action menu — 0..(N-1) over the dynamic action
     *  set computed from the meeting's status (play/email/retry/delete). */
    var transcriberDetailMenuFocus by mutableIntStateOf(0)
    /** The action set currently shown in the ⋮ menu. Host populates it on
     *  open based on the meeting's status; nav reads it for wheel max + dispatch. */
    val transcriberDetailMenuActions = mutableStateListOf<TranscriberDetailAction>()

    // --- survey call bot ---
    /** Index of saved surveys (templates). Hydrated from SurveyStore on entry. */
    val surveys = mutableStateListOf<SurveyIndexEntry>()
    /** Index of saved call records (one per completed call). */
    val callRecords = mutableStateListOf<CallRecordIndexEntry>()
    /** Index of campaigns (a survey + a contact list + status). */
    val campaigns = mutableStateListOf<CampaignIndexEntry>()
    /** Focus on SURVEY_LIST: 0=back, 1=settings gear, 2="+ new campaign",
     *  3..=campaigns rows. */
    var surveyListFocus by mutableIntStateOf(0)
    /** Focus on SURVEY_CAMPAIGN composer: 0=back, 1=pick survey, 2=pick contacts,
     *  3=start. Author the survey + contacts on the web companion. */
    var surveyCampaignFocus by mutableIntStateOf(0)
    /** Two-row pre-dial confirm: 0=cancel, 1=confirm + dial. */
    var surveyConsentFocus by mutableIntStateOf(0)
    /** Detail panel: 0=back, 1=⋮ (mirror transcriber detail). */
    var surveyDetailFocus by mutableIntStateOf(0)
    /** Settings rows: 0=back, 1=openai key, 2=sip host, 3=sip user,
     *  4=sip password, 5=sip from-number, 6=consent text, 7=voice cycle,
     *  8=summarizer model, 9=email recipient, 10=clear all. */
    var surveySettingsFocus by mutableIntStateOf(0)
    /** Currently selected campaign (for SURVEY_LIVE + post-call). */
    var currentCampaignId by mutableStateOf<String?>(null)
    /** Currently selected survey (for the campaign composer). */
    var currentSurveyId by mutableStateOf<String?>(null)
    /** Currently selected call record (for SURVEY_DETAIL). */
    var currentCallRecordId by mutableStateOf<String?>(null)
    /** Live state mirrored from SurveyOrchestrator while a call is in flight. */
    var surveyCallActive by mutableStateOf(false)
    var surveyCallElapsedMs by mutableStateOf(0L)
    var surveyCallPeak by mutableIntStateOf(0)
    var surveyCallContactName by mutableStateOf("")
    /** "dialing" / "ringing" / "live" / "wrapping up" / "ended" / "failed". */
    var surveyCallStatus by mutableStateOf("")
    var surveyCallCurrentQuestion by mutableStateOf("")
    /** Running answer count for the live UI. */
    var surveyCallAnswered by mutableIntStateOf(0)
    /** Total questions in the active survey (for the "x of N" indicator). */
    var surveyCallTotal by mutableIntStateOf(0)
    /** Cached display values for SURVEY_SETTINGS rows (no secrets in plain state). */
    var hasOpenAiKey by mutableStateOf(false)
    var openAiKeyTail by mutableStateOf("")
    var hasSipCreds by mutableStateOf(false)
    var sipHostDisplay by mutableStateOf("")
    var sipUserDisplay by mutableStateOf("")
    var sipFromDisplay by mutableStateOf("")
    var realtimeVoiceDisplay by mutableStateOf("alloy")
    var summarizerModelDisplay by mutableStateOf("claude-haiku-4-5-20251001")
    var surveyConsentTextDisplay by mutableStateOf("")
    var surveyEmailRecipientDisplay by mutableStateOf("")

    // --- toast overlay ---
    /** Currently visible toast, or null when hidden. Set via [showToast];
     *  cleared either by the overlay's auto-dismiss timer or by [showToast]
     *  replacing it with a newer entry. */
    var toast by mutableStateOf<ToastEntry?>(null)
    private var toastSeq = 0L

    /**
     * Push a toast onto the overlay. New calls preempt any in-flight toast
     * (we never queue — the latest message wins, matching how stock Android
     * Toast behaves with `LENGTH_SHORT`).
     */
    fun showToast(
        text: String,
        kind: ToastKind = ToastKind.INFO,
        durationMs: Long = 3000L,
    ) {
        toastSeq++
        toast = ToastEntry(
            id = toastSeq,
            text = text,
            kind = kind,
            expiresAtMs = System.currentTimeMillis() + durationMs,
        )
    }

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
    var smsThreadLoading by mutableStateOf(false)
    var smsThreadFocus by mutableIntStateOf(0)

    // --- state transitions ---

    fun openApps() {
        appsFocus = 0
        panel = Panel.APPS
    }

    fun openOnboarding() {
        onboardingStep = 0
        onboardingFocus = 0
        isOnboarding = true
        panel = Panel.ONBOARDING
    }

    fun advanceOnboarding() {
        onboardingStep++
        onboardingFocus = 0
        panel = Panel.ONBOARDING
    }

    fun openSettings() {
        settingsFocus = 0
        panel = Panel.SETTINGS
    }

    fun openSettingsDisplay() {
        settingsDisplayFocus = 0
        panel = Panel.SETTINGS_DISPLAY
    }

    fun openSettingsSound() {
        settingsSoundFocus = 0
        panel = Panel.SETTINGS_SOUND
    }

    fun openSettingsDevice() {
        settingsDeviceFocus = 0
        panel = Panel.SETTINGS_DEVICE
    }

    fun openSettingsAbout() {
        settingsAboutFocus = 0
        panel = Panel.SETTINGS_ABOUT
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

    fun openUiVolume() {
        panel = Panel.UI_VOLUME
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

    fun openSettingsVoice() {
        voiceFocus = 0
        panel = Panel.SETTINGS_VOICE
    }

    fun openSettingsVoiceTuning() {
        voiceTuningFocus = 0
        panel = Panel.SETTINGS_VOICE_TUNING
    }

    fun openSettingsVoiceSubscription() {
        voiceSubFocus = 0
        panel = Panel.SETTINGS_VOICE_SUBSCRIPTION
    }

    fun openSettingsLanguage() {
        settingsLanguageFocus = 0
        panel = Panel.SETTINGS_LANGUAGE
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

    fun openTerminal() {
        // Preserve scrollback and cwd so reopening feels session-like.
        terminalInput = ""
        terminalScrollIndex = 0
        terminalRecording = false
        terminalTranscribing = false
        panel = Panel.TERMINAL
    }

    fun openClaude() {
        // Preserve message history + first-turn flag so reopening continues
        // the same conversation. Only the input buffer + ephemeral indicators
        // get reset.
        claudeInput = ""
        claudeScrollIndex = 0
        claudeRecording = false
        claudeTranscribing = false
        // Show the web-companion redirect on every fresh entry. Once they hit
        // "open anyway" we don't keep nagging within the same session, but
        // navigating away and back resets it because that's almost always
        // someone showing the QR to a new collaborator.
        claudeShowWebHint = true
        panel = Panel.CLAUDE
    }

    fun openMessagesThread(address: String, displayName: String) {
        smsThreadAddress = address
        smsThreadName = displayName
        smsThreadFocus = 0
        smsThreadMessages.clear()
        smsThreadLoading = true
        panel = Panel.MESSAGES_THREAD
    }

    fun openOpenClawSettings() {
        openClawSettingsFocus = 0
        panel = Panel.OPENCLAW_SETTINGS
    }

    fun openTranscriberList() {
        transcriberListFocus = 0
        detailStatus = ""
        panel = Panel.TRANSCRIBER_LIST
    }

    fun openTranscriberRecording() {
        // No focus index — recording panel has a single stop pill driven by
        // the side-button toggle, plus a back row that wheel-up reaches.
        panel = Panel.TRANSCRIBER_RECORDING
    }

    fun openTranscriberDetail(uuid: String) {
        currentMeetingUuid = uuid
        transcriberDetailFocus = 0
        transcriberDetailMenuOpen = false
        transcriberDetailMenuFocus = 0
        transcriberRecipientInput = defaultRecipientDisplay
        detailStatus = ""
        panel = Panel.TRANSCRIBER_DETAIL
    }

    fun openTranscriberSettings() {
        transcriberSettingsFocus = 0
        transcriberSettingsEditField = ""
        transcriberSettingsEditInput = ""
        panel = Panel.TRANSCRIBER_SETTINGS
    }

    fun openSurveyList() {
        surveyListFocus = 0
        panel = Panel.SURVEY_LIST
    }

    fun openSurveyCampaign() {
        surveyCampaignFocus = 0
        panel = Panel.SURVEY_CAMPAIGN
    }

    fun openSurveyConsent(campaignId: String) {
        currentCampaignId = campaignId
        surveyConsentFocus = 0
        panel = Panel.SURVEY_CONSENT
    }

    fun openSurveyLive() {
        surveyCallActive = true
        surveyCallElapsedMs = 0L
        surveyCallPeak = 0
        surveyCallAnswered = 0
        surveyCallStatus = "dialing"
        surveyCallCurrentQuestion = ""
        panel = Panel.SURVEY_LIVE
    }

    fun openSurveyDetail(callRecordId: String) {
        currentCallRecordId = callRecordId
        surveyDetailFocus = 0
        panel = Panel.SURVEY_DETAIL
    }

    fun openSurveySettings() {
        surveySettingsFocus = 0
        panel = Panel.SURVEY_SETTINGS
    }

    fun goHome() {
        panel = Panel.HOME
    }

    fun back() {
        panel = when (panel) {
            Panel.BRIGHTNESS -> Panel.SETTINGS_DISPLAY
            Panel.VOLUME -> Panel.SETTINGS_SOUND
            Panel.UI_VOLUME -> Panel.SETTINGS_SOUND
            Panel.NETWORK -> if (isOnboarding) Panel.ONBOARDING else Panel.SETTINGS
            Panel.FACTORY_CONFIRM -> Panel.SETTINGS_DEVICE
            // Language was promoted out of the root in v3.32 — it now lives
            // under Device alongside reboot/power off/factory reset, so the
            // back arrow needs to drop back into SETTINGS_DEVICE.
            Panel.SETTINGS_LANGUAGE -> Panel.SETTINGS_DEVICE
            Panel.SETTINGS_DISPLAY, Panel.SETTINGS_SOUND, Panel.SETTINGS_DEVICE, Panel.SETTINGS_ABOUT, Panel.SETTINGS_VOICE -> Panel.SETTINGS
            Panel.SETTINGS_VOICE_TUNING -> Panel.SETTINGS_VOICE
            Panel.SETTINGS_VOICE_SUBSCRIPTION -> Panel.SETTINGS_VOICE
            Panel.WIFI_SCAN -> if (isOnboarding) Panel.ONBOARDING else Panel.NETWORK
            Panel.WIFI_PASSWORD -> Panel.WIFI_SCAN
            Panel.WIFI_SHARE -> Panel.NETWORK
            Panel.WIFI_SHARE_EDIT -> Panel.WIFI_SHARE
            Panel.ONBOARDING -> Panel.ONBOARDING
            Panel.SETTINGS -> Panel.APPS
            Panel.APPS -> Panel.HOME
            Panel.OPENCLAW_QR -> if (qrScanMode == QrScanMode.OPENAI_KEY) Panel.OPENCLAW_SETTINGS else Panel.APPS
            Panel.OPENCLAW_CHAT -> Panel.APPS
            Panel.OPENCLAW_CAMERA -> Panel.OPENCLAW_CHAT
            Panel.OPENCLAW_SETTINGS, Panel.OPENCLAW_SESSIONS -> Panel.OPENCLAW_CHAT
            Panel.MESSAGES -> Panel.APPS
            Panel.MESSAGES_THREAD -> Panel.MESSAGES
            Panel.TERMINAL -> Panel.APPS
            Panel.CLAUDE -> Panel.APPS
            Panel.TRANSCRIBER_LIST -> Panel.APPS
            // Recording-stop side-effect is fired by LauncherActivity's
            // backPressed handling; here we just unwind the panel.
            Panel.TRANSCRIBER_RECORDING -> Panel.TRANSCRIBER_LIST
            Panel.TRANSCRIBER_DETAIL -> Panel.TRANSCRIBER_LIST
            Panel.TRANSCRIBER_SETTINGS -> Panel.TRANSCRIBER_LIST
            Panel.SURVEY_LIST -> Panel.APPS
            Panel.SURVEY_CAMPAIGN -> Panel.SURVEY_LIST
            Panel.SURVEY_CONSENT -> Panel.SURVEY_CAMPAIGN
            Panel.SURVEY_LIVE -> Panel.SURVEY_LIST
            Panel.SURVEY_DETAIL -> Panel.SURVEY_LIST
            Panel.SURVEY_SETTINGS -> Panel.SURVEY_LIST
            Panel.HOME -> Panel.HOME
        }
    }
}

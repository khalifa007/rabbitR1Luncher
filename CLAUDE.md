# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Compose-based HOME launcher for the **Rabbit R1** (480×480 round, MT6765). Ships as a system app inside the user's LineageOS-based **CarrotOS** image, source tree at `/home/khalifa/lineage` (target: `lineage_r1-userdebug`). Successor to `../mylauncher/` (Gradle-less Java + XML). Same package (`com.r1.launcher`), platform-signed (see Release compatibility) so the launcher can hold signature-only perms like `ACCESS_MESSAGES_ON_ICC`. Current clean-baseline chain — see `app/build.gradle.kts` for live `versionCode`/`versionName`.

`../mylauncher/CLAUDE.md` has feature-level history; this file covers only the Compose rewrite + OpenClaw chat + OS-image integration.

## Bootstrap & build

`gradle-wrapper.jar` and `gradlew*` are not checked in. Generate once:

```bash
bash bootstrap.sh   # downloads Gradle 8.9 to .bootstrap/, runs `gradle wrapper`
```

Normal loop:

```bash
./gradlew assembleDebug                                    # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

- **`versionCode` in `app/build.gradle.kts` is pinned to `1000` locally for debug, but the file is `git update-index --skip-worktree`**, so `git status`/commits ignore the local value. HEAD tracks the real release version (`versionCode=5` / `versionName="1.1.0"` at time of writing). `adb install -r` at the same `versionCode` is allowed — you don't need to bump per install. The `/system/app/R1Launcher/` baked into CarrotOS is the floor; if it ever passes `1000`, re-pin higher. `INSTALL_FAILED_VERSION_DOWNGRADE` = system image was rebuilt with a higher number, raise the local pin. To cut a release: `git update-index --no-skip-worktree app/build.gradle.kts`, edit to the real release version, commit, `git tag vX.Y.Z && git push --tags` (CI fires on tags, not main pushes), then re-pin and re-skip.
- **`am start` won't replace a foreground launcher process.** "Activity not started, intent has been delivered..." means old code is still in memory. Force-stop via carroot first.

## Pinned versions — do not drift without testing

- **AGP 8.7.2** + **Gradle 8.9** — AGP 8.7 is the lowest that works with Kotlin 2.0 compose plugin.
- **Kotlin 2.0.21** + `kotlin.plugin.compose` + `kotlin.plugin.serialization` — Kotlin 2.0 requires the separate compose plugin; serialization is for OpenClaw JSON-RPC.
- **Compose BOM 2024.10.01** — pulls compose.ui/foundation/animation/material3 at matched versions.
- **OkHttp 4.12.0**, **kotlinx-serialization-json 1.7.3**, **security-crypto 1.1.0-alpha06**, **bcprov-jdk18on 1.78.1**, **zxing-android-embedded 4.3.0**.
- **minSdk 23**, **targetSdk 33**, **compileSdk 34**, **Java 17**. minSdk/targetSdk match the old project for clean OTA upgrade.

## Architecture

Single Activity (`LauncherActivity`) with one `setContent { R1Theme { LauncherRoot(state, appStore, host) } }`. No fragments, no nav component.

**State** (`LauncherState`): plain Kotlin class with `mutableStateOf`/`mutableIntStateOf`/`mutableStateListOf` fields. No ViewModel — Activity has `configChanges="keyboardHidden|orientation|screenSize|uiMode"` + `launchMode=singleTask`, so it never recreates.

**Panel state machine** on `state.panel`:
`HOME, ONBOARDING, APPS, SETTINGS, SETTINGS_DISPLAY, SETTINGS_SOUND, SETTINGS_DEVICE, SETTINGS_ABOUT, SETTINGS_VOICE, SETTINGS_VOICE_TUNING, SETTINGS_VOICE_SUBSCRIPTION, SETTINGS_LANGUAGE, NETWORK, WIFI_SCAN, WIFI_PASSWORD, WIFI_SHARE, WIFI_SHARE_EDIT, BRIGHTNESS, VOLUME, UI_VOLUME, FACTORY_CONFIRM, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_CAMERA, OPENCLAW_SETTINGS, OPENCLAW_SESSIONS, MESSAGES, MESSAGES_THREAD, TERMINAL, CLAUDE, HERMES_CHAT, HERMES_CONFIG, TRANSCRIBER_LIST, TRANSCRIBER_RECORDING, TRANSCRIBER_DETAIL, TRANSCRIBER_SETTINGS`

Each panel has its own focus int; brightness/volume use level fields. Home dock + system-tray sheet were removed in 3.16; wheel press on clock jumps straight to apps grid. `back()` unwind is intentional and asymmetric:

- `BRIGHTNESS → SETTINGS_DISPLAY → SETTINGS → APPS → HOME`, `VOLUME / UI_VOLUME → SETTINGS_SOUND → SETTINGS`, `FACTORY_CONFIRM → SETTINGS_DEVICE → SETTINGS`
- `NETWORK → SETTINGS` (or `ONBOARDING` if `isOnboarding`); `SETTINGS_LANGUAGE → SETTINGS_DEVICE`
- `WIFI_SCAN → NETWORK` (or `ONBOARDING`), `WIFI_PASSWORD → WIFI_SCAN`
- `WIFI_SHARE → NETWORK`, `WIFI_SHARE_EDIT → WIFI_SHARE`
- `OPENCLAW_QR → APPS` (or `OPENCLAW_SETTINGS` when `qrScanMode == OPENAI_KEY`)
- `OPENCLAW_CHAT → APPS`, `OPENCLAW_SETTINGS / OPENCLAW_SESSIONS → OPENCLAW_CHAT`, `OPENCLAW_CAMERA → OPENCLAW_CHAT`
- `MESSAGES → APPS`, `MESSAGES_THREAD → MESSAGES`, `TERMINAL → APPS`, `CLAUDE → APPS`
- `HERMES_CHAT → APPS`, `HERMES_CONFIG → HERMES_CHAT` (if `hermesConfigCameFromChat`) else `APPS`
- `SETTINGS_VOICE_TUNING / SETTINGS_VOICE_SUBSCRIPTION → SETTINGS_VOICE`
- `TRANSCRIBER_LIST → APPS`, `TRANSCRIBER_RECORDING / TRANSCRIBER_DETAIL / TRANSCRIBER_SETTINGS → TRANSCRIBER_LIST` (back from RECORDING also stops the FGS via `host.transcriberStopRecording`)

**Apps list is typed**: `state.apps : MutableList<AppEntry>`, sealed class with `Real(ResolveInfo)`, `Settings`, `OpenClaw`, `Messages`, `Terminal`, `Claude`, `Hermes`, `Meetings`. `LauncherActivity.loadApps()` appends synthetics after real apps in order: `Messages`, `OpenClaw`, `Terminal`, `Claude`, `Hermes`, `Meetings`, `Settings`. `launchApp(idx)` switches on type:

- `Real` — fires the launcher intent
- `Settings` — `WRITE_SETTINGS` grant check, then `state.openSettings()`
- `OpenClaw` — paired → `openClawStartSession()` + `openOpenClawChat()`; else camera-perm + `openOpenClawQr()`
- `Messages` — `READ_SMS` grant + `openMessages()` + `loadSmsConversations()`
- `Terminal` — `openTerminal()` (no perm; uses carroot)
- `Claude` — `openClaude()` (routes user text → `claude --print [-c]` via carroot+chroot)
- `Hermes` — `hydrateHermesStateFromPrefs()` + `openHermesChat()` if `hermesPrefs.hasConfig()`, else `openHermesConfig()` (no perm; pure HTTPS to a user-provided gateway)
- `Meetings` — `RECORD_AUDIO` grant + `transcriberOpen()` (binds the microphone-typed FGS, opens `TRANSCRIBER_LIST`)

Adding a synthetic: every `when` branch in `AppsPanel.kt` (key, painter, label, `appKey`, `appContentType`) needs the new case — sealed class enforces it but the icon switch is easy to miss.

**Navigation** lives in `LauncherNav.kt` as extension functions on `LauncherState`: `wheelUp(host)`, `wheelDown(host)`, `activate(host)`, `backPressed(host)`. Pure state mutations; side effects go through the `LauncherHost` interface implemented by `LauncherActivity` (app launch, system intents, brightness/volume, tones, OpenClaw, SMS, web-server, terminal, claude, clipboard).

**Compose tree** (z-stack inside one `Box` in `LauncherRoot.kt`): wallpaper + HomeScreen + every Panel composable, all gated on `state.panel == Panel.X`. Topbar overlays only on HOME. When adding a panel, register both the composable and its `onRowClick` dispatcher in `LauncherRoot.kt`.

**Key dispatch**: `Activity.dispatchKeyEvent` routes the same superset as the old launcher (volume, dpad, page up/down, headsethook, media_play_pause, call, assist, voice_assist) into `state.wheelUp/wheelDown/activate/backPressed`. The isHandled allowlist must stay in sync with the dispatcher. Debug overlay prints `key <code> sc <scan> NAME` on every keydown.

**Side button (BUTTON_1)**: keylayout (`device/rabbit/r1/keylayout/mtk-kpd.kl`) remaps physical KEY_POWER (116) → `BUTTON_1 WAKE` so the launcher sees raw DOWN/UP events instead of `onNewIntent`. State machine in `dispatchKeyEvent`:

- **Single tap on HOME** → `lockScreen()` (PowerService accessibility action)
- **Single tap elsewhere** → `state.activate(host)`
- **Double tap anywhere** → `state.goHome()` (window: `SIDE_DOUBLE_PRESS_MS = 350`)
- **Long press on HOME** → `PowerService.openPowerDialog()` (window: `SIDE_LONG_PRESS_MS = 500`)
- **Long press in OPENCLAW_CHAT** — push-to-talk; long-press fires `openClawRecordStart()`, UP fires `openClawRecordStop()`. Same dispatch model in TERMINAL and CLAUDE panels for dictation.

State: `sideDownAtMs`/`sideLastShortUpMs`/`sideLongFired`/`pendingSideSingle`. **Don't map to HOME or POWER** — HOME collapses DOWN/UP into one `onNewIntent` (kills timing); POWER is intercepted by `PhoneWindowManager`.

**Animation tokens** (`ui/Common.kt`): `ANIM_OPEN_MS = 220`, `ANIM_CLOSE_MS = 170`, `ANIM_FOCUS_MS = 140`, `FOCUS_SCALE = 1.04f`, `UNFOCUS_ALPHA = 0.55f`. `Modifier.focusAnim(focused)` applies scale+alpha via `animateFloatAsState`.

**Java interop**: `Updater.java`, `AppStore.java`, `PowerService.java`, `ApkProvider.java` are unchanged from the old project — Kotlin calls them directly.

## Topbar SIM/network

`refreshSim()` reads operator + radio type via direct `TelephonyManager`. Requires `READ_PHONE_STATE` (runtime grant via `ensurePhonePerm()`). Mapping: LTE→`"LTE"`, NR→`"5G"`, HSPA*/UMTS/EVDO*→`"3G"`, EDGE/GPRS/CDMA/1xRTT→`"2G"`. Logs `refreshSim: simState=N op='X' dataOn=B radio=N -> 'Y'`.

Topbar pill (`ui/Topbar.kt`) shows lowercase operator + orange `LTE`/`5G`/`3G`/`2G` pill when `simPresent && cellularOn && networkType.isNotEmpty()`. Pill hides immediately on cellular off because `toggleCellular(false)` pre-clears `state.networkType`.

`netRx` BroadcastReceiver also calls `refreshSim()` so topbar updates live on connectivity changes.

**Operator name + LTE pill is NOT proof mobile data works.** Both come from `telephony.registry`, which survives even when `com.android.phone` is dead. Real proof: `mDataConnectionState=2` and `ping 8.8.8.8` returns RTT.

## Connectivity toggles

Pattern: optimistic `state.X = enable` → framework API → verify at +400ms → fall through to carroot shell → re-verify at +1500ms and +4000ms. Carroot @ 127.0.0.1:1337.

| Toggle    | Direct API (silent no-op without sys perm) | Carroot fallback                              |
|-----------|--------------------------------------------|------------------------------------------------|
| Wi-Fi     | `WifiManager.setWifiEnabled`               | `cmd wifi set-wifi-enabled enabled\|disabled`  |
| Cellular  | `TelephonyManager.setDataEnabled`          | `settings put global mobile_data 1\|0`         |
| Bluetooth | `BluetoothAdapter.enable/disable`          | `cmd bluetooth_manager enable\|disable`        |

Each toggle logs `toggleX(B) direct applied=A` and `toggleX(B) ok=O (applied=A)`. **Never trust the framework return value** — `setDataEnabled` returns void, `setWifiEnabled` returns true even when rejected. Always re-read state.

`svc data` and `svc wifi` are dead shims on this build — return 0, no effect. Don't fall back to them.

## Factory reset

`Panel.FACTORY_CONFIRM` (`ui/FactoryConfirmPanel.kt`) is the two-row destructive confirm from Settings → "factory reset". `state.openFactoryConfirm()` resets `factoryConfirmFocus = 0` (back row) so a stray activate cancels rather than wipes. On confirm, `factoryReset()` sends `am broadcast -a android.intent.action.FACTORY_RESET --receiver-foreground -p android` via carroot, with `MASTER_CLEAR` legacy fallback. Both broadcasts are protected — must go through carroot.

## OS-image dependency (CarrotOS at /home/khalifa/lineage)

Two cross-tree integrations:

- **APN database**: `device/rabbit/r1/device.mk` PRODUCT_COPY_FILES includes `device/sample/etc/apns-full-conf.xml:system/etc/apns-conf.xml`. Without this, the carriers DB is empty on first boot and no SIM gets data.
- **carroot socket**: `device/rabbit/r1/rootdir/system/etc/init/carroot.rc` runs `nc -L -p 1337 sh` as root with `u:r:su:s0`. `sendToCarroot(cmd: String): Boolean` in `LauncherActivity.kt` is the single entry point; returns true on socket-write success, NOT command effect — re-verify state after every call.

Rebuild + flash sequence:

```bash
cd /home/khalifa/lineage && source build/envsetup.sh
lunch lineage_r1-userdebug
make systemimage -j$(nproc)
adb reboot bootloader
fastboot flash system out/target/product/r1/system.img
fastboot -w                       # wipes userdata; prevents ART cache mismatch
fastboot reboot
```

`fastboot -w` is load-bearing (see anti-pattern list).

## Wi-Fi sharing (hotspot)

Settings → Network → "wifi share" exposes toggle/SSID/password/connected/auto-off rows. Driven via carroot — `cmd wifi start-softap "<ssid>" wpa2 "<pass>"` (one-shot only; the `set-ssid` then `start-softap` two-step is rejected on this build). Verify by parsing `ip link show ap0` for `state UP` + `LOWER_UP`. **Do not** parse `dumpsys wifi` for SAP state — those strings come from `cmd wifi`'s event listener, not from dumpsys.

Connected-client polling: `ip neigh show dev ap0` every 3s → `state.wifiShareConnectedClients`. Auto-off via `Handler.postDelayed` chain in `armWifiShareTimer()`.

Persistence: `wifishare/WifiSharePrefs.kt` — SSID + timer in plain prefs, password in `EncryptedSharedPreferences("wifishare.secure")`. Defaults seeded on first read (random SSID `R1-XXXX`, random 10-char password).

## SMS / Messages

CarrotOS has **no default SMS app**, so the framework's `InboundSmsHandler` silently drops every incoming SMS (Android 4.4+ restriction — only the default app can write to `content://sms`). Workaround:

1. **`messages/SmsReceiver.kt`** — manifest-registered receiver for the legacy `android.provider.Telephony.SMS_RECEIVED` action (still fires for any holder of `RECEIVE_SMS`). Decodes PDUs via `Telephony.Sms.Intents.getMessagesFromIntent`, concatenates multi-part, persists.
2. **`messages/SmsCache.kt`** — append-only JSON log at `filesDir/sms-cache.json`, capped at 1000 entries.
3. **`messages/SmsLoader.kt`** — `loadConversations(ctx)` and `loadMessagesFor(ctx, addr)` merge three sources: `content://sms`, ICC SIM via reflection on `SmsManager.getAllMessagesFromIcc()` (hidden `@SystemApi`, see `invokeAllMessagesFromIcc()`), and `SmsCache`. Most carriers don't write to (1) or (2) anymore — (3) is the working path.

Live refresh: after each capture, `SmsReceiver` fires a local `com.r1.launcher.NEW_SMS_LOCAL` broadcast that the activity catches.

`MessagesPanel` shows one row per sender. `MessagesThreadPanel` shows the thread as alternating bubbles (incoming = grey left, outgoing = orange right).

## Web companion panel

`web/R1WebServer.kt` (NanoHTTPD + NanoWSD) hosts HTTP+WS on port 8080, serves SPA from `assets/web/`, JSON-RPC at `/api/rpc`:
```
req:   {type:"req",   id, method, params}
res:   {type:"res",   id, ok, payload, error}
event: {type:"event", event, payload}
```

**RPC methods** (`web/WebRpc.kt`) — thin shims over `LauncherHost`/`LauncherState`:
- `state.snapshot` — full live state, also auto-broadcast at 1 Hz
- `sms.list` / `sms.thread`
- `text.send` (target = `openai_key` or `openclaw_chat`)
- `wifi/cellular/bt/hotspot.toggle`, `brightness/volume.set`, `openai.set`
- `terminal.run` / `terminal.clear` / `terminal.history` — gated on `state.webTerminalEnabled`; return `{code:"disabled"}` when off
- `claude.send` / `claude.clear` / `claude.history` — always available

**Broadcast events**: `state.snapshot` (1 Hz), `terminal.output`, `claude.message`, `claude.streaming` (empty `text` = stream done), `claude.busy`, `claude.cleared`. Helpers in `R1WebServer.broadcast*`; `LauncherActivity` calls them from `terminalRun`/`claudeSend` callbacks so on-device + web stay in lockstep.

**Web UI** (`assets/web/`): single-page, mirrors device design (black bg, `#FF6A00`, Jersey 15, 2px-edge tiles, scanline+grain overlays). `index.html`: `view-home` (clock + grid) plus `<section class="view view-app">` per app, `<template id="tpl-app-header">` cloned for back-pill+title+status. `setView(name)` swaps active class; `<` or Esc returns home. `app.js` = WS-RPC client + view router + ~110-LOC markdown renderer (HTML-escapes first).

**Asset routing** (`R1WebServer.serveHttp`): `/`+`/index.html` → `web/index.html`; `/app.js`+`/style.css` → matching files; `/static/<x>` → `web/<x>` (MIME via `guessMime()`); `/api/state` → JSON; else 404. Font canonical at `res/font/jersey_15.ttf`; copy at `assets/web/` is what the server serves.

Server auto-starts in `onCreate`; toggle via Settings → Network → "remote panel" or `am broadcast -a com.r1.launcher.TOGGLE_WEB_SERVER --ez on true`.

**IP discovery** (`discoverLocalIp`): preferred order `ap0` > `wlan*` > `eth*` > else. Skip cellular (`ccmni*`/`rmnet*`/`ppp*`) — those are CGN, not LAN-routable. Without this `ccmni0`'s `10.x.x.x` wins by alphabetical sort.

**NanoHTTPD/NanoWSD gotchas:** default 5s socket timeout breaks WS — use `start(0, false)`. `WebSocket.send()` from main thread throws — route through a single-threaded `sendExecutor`.

## OpenClaw chat panel

Built-in client for an [openclaw](https://github.com/openclaw/openclaw) AI gateway. Pair via QR, chat with streaming replies. Voice input + assistant readback go through ElevenLabs (see **Voice (Settings → Voice)** section below). OpenClaw-specific files in `app/src/main/java/com/r1/launcher/openclaw/`:

| File | Role |
|---|---|
| `SetupCode.kt` | Decode URL-safe Base64 QR → `{url, bootstrapToken?, token?, password?}`. |
| `OpenClawPrefs.kt` | EncryptedSharedPreferences (`openclaw.secure`) for `gateway.url`, `gateway.bootstrap`, `gateway.deviceToken`, `gateway.token`. Plain prefs (`openclaw.plain`) for random `node.instanceId`, `chat.hide`, `chat.fontSize`. |
| `DeviceIdentityStore.kt` | Ed25519 keypair via Bouncy Castle lightweight; persisted at `filesDir/openclaw/identity/device.json`. Builds the pipe-delimited v3 auth payload. |
| `GatewaySession.kt` | OkHttp WebSocket + JSON-RPC. One client, one socket, `pending: Map<id, CompletableDeferred>`, `onEvent` callback. |
| `ChatMessage.kt` | `data class ChatMessage(role, text, streaming, timestamp)` + JsonArray flatteners. |

### Protocol gotchas

- **Role + scopes**: connect as `role: "operator"` with `scopes: ["operator.read", "operator.write", "operator.talk.secrets"]`. The `node` role can't access chat methods. `PAIRING_SETUP_BOOTSTRAP_PROFILE` only allows `operator.*`.
- **Server-issued nonce**: don't generate your own. Wait for the `connect.challenge` event after opening the socket; use that nonce in the signed payload + `device.nonce`.
- **`chat.subscribe` requires admin** unless wrapped in `node.event`: `request("node.event", {event: "chat.subscribe", payloadJSON: "..."})`. `chat.send` and `chat.history` work directly.
- **`client.id` is allowlisted** — use `"openclaw-android"`. Anything else gets rejected.
- **No URL port mangling** — gateway URL may be plain `wss://host`. OkHttp picks 443/80 from scheme. `withDefaultPort(18789)` from earlier broke `wss://claw.luma.om`.
- **Audio attachments are NOT auto-transcribed** by the gateway — forwarded to LLM as multimodal input. With a text-only LLM you get "I didn't receive any text". That's why we transcribe client-side before `chat.send`.

## Voice (Settings → Voice)

Single source of truth for ElevenLabs key + voice picker + auto-speak toggle. Used by **OpenClaw chat / Terminal / Claude** for STT, and by **OpenClaw chat** for assistant TTS readback. Files in `app/src/main/java/com/r1/launcher/voice/`:

| File | Role |
|---|---|
| `VoicePrefs.kt` | EncryptedSharedPreferences (`voice.secure`) for `elevenlabs.key`. Plain prefs (`voice.plain`) for `voice.enabled` (auto-speak toggle) + `voice.id` (Rachel default). 4-voice catalog hardcoded in companion. |
| `StreamingAudioCapture.kt` | Mic → live PCM frames (16 kHz mono PCM_16BIT, VOICE_RECOGNITION). Emits `onPcm(chunk)` per ~80ms. 60s session cap. |
| `ElevenLabsRealtimeClient.kt` | Scribe v2 Realtime WS: `wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&language_code=en&commit_strategy=vad`. `xi-api-key` header. PCM sent base64-in-JSON (`message_type=input_audio_chunk`). Server emits `partial_transcript` (live) and `committed_transcript` (final, on VAD silence or explicit commit). |
| `ElevenLabsTtsClient.kt` | Flash v2.5 REST `/stream`: POST `/v1/text-to-speech/{voice_id}/stream?output_format=mp3_22050_32&optimize_streaming_latency=4` with `{"text":..., "model_id":"eleven_flash_v2_5"}`. Streams chunked MP3 → `cacheDir/openclaw-voice/assistant.mp3` → MediaPlayer. Returns OkHttp `Call` for mid-flight `cancel()`. `mp3_22050_32` (~3 KB/s) is ~4× smaller than `mp3_44100_128`, intelligible for speech. Errors parsed from `{"detail":{"status":...,"message":...}}`. |

### Voice flow

`LauncherActivity.startVoiceCapture(sink)` is the single entry — opens an `ElevenLabsRealtimeClient`, drives a `StreamingAudioCapture` into it, and routes the committed transcript to one of three sinks:

| Sink | UI render | Action on committed transcript |
|---|---|---|
| `CHAT` | `state.chatPartialText` (live text overlay above input) | Auto-send via `openClawSendText` ("release to send") |
| `TERMINAL` | `state.terminalPartial` | Paste into `state.terminalInput` (don't auto-execute) |
| `CLAUDE` | `state.claudePartial` | Paste into `state.claudeInput` (don't auto-execute) |

Side button long-press in any of those panels invokes the relevant `*RecordStart()` host method, which delegates to `startVoiceCapture(sink)`. Release calls `stopVoiceCapture()` which sends `commit:true` and waits for `committed_transcript`.

**Recording cue & mic-open delay**: `startVoiceCapture` plays `playRecordingTone()` (moving.mp3 + `ToneGenerator.TONE_PROP_BEEP` fallback for MTK SoundPool drops during audio-routing transitions), then delays AudioRecord open by 200 ms so the cue plays through (opening `VOICE_RECOGNITION` reroutes the audio path and silences in-flight MEDIA samples).

**Pending bubble UX (chat sink)**: `state.chatPartialText` renders as a gray user-side bubble at the bottom of the chat list (`reverseLayout=true`). Render gate is `chatPartialText.isNotBlank()` — *not* `chatRecording` — so the bubble persists across release → `committed_transcript`. `handleCommittedTranscript` clears the partial AND adds the orange persisted bubble in the same frame (clean color flip, not flicker). Same pattern for streaming assistant bubble: `chatStreamingText` is cleared inside `applyOpenClawHistory` after the persisted message lands, not in `onChatTerminal`.

**TTS auto-speak**: `speakLatestAssistantIfNeeded()` fires on assistant message arrival. Gated on `voiceEnabled && panel == OPENCLAW_CHAT && openClawSpeakNextAssistant` (gate set in `openClawSendText`). Tracks OkHttp `Call` in `openClawTtsCall`. `cancelOpenClawSpeech()` aborts both in-flight HTTP and MediaPlayer; called from `startVoiceCapture` (PTT over playing reply silences it), `speakLatestAssistantIfNeeded` (no stacking), `openClawCloseSessionInternal`, `onDestroy`. ElevenLabs locks character billing at request submission, so cancel = bandwidth + UX win, not guaranteed credit refund.

**TTS volume**: `USAGE_MEDIA` → `STREAM_MUSIC`, controlled by Settings → Sound → Volume. Don't force-set MAX before playback.

**OpenClaw settings inline "speak replies" toggle** at row index 1 flips the same `voicePrefs.enabled` flag as Settings → Voice. `openClawSettingsRowActivate` uses indices 0..5 — careful when reshuffling.

### ElevenLabs key — five ways to set it

1. **Settings → Voice** → "elevenlabs key" row → RetroKeyboard
2. **adb broadcast**: `adb shell "am broadcast -a com.r1.launcher.SET_ELEVENLABS_KEY --es key 'sk_...'"` (receiver: `LauncherActivity.voiceKeyRx`)
3. **Clipboard paste** — "paste" pill in the Settings → Voice keyboard overlay
4. **QR scan** — Settings → Voice → "scan key from qr" sets `qrScanMode = OPENAI_KEY` (enum name kept for back-compat); `LauncherActivity.openClawScanned` validates and saves to `voicePrefs.elevenlabsKey`
5. **Web companion** — paste into "send text" tab with target = `voice_key`

Validation: accepts either `sk_<29+ chars>` prefix form OR a 32-char hex string (lowercase or upper).

### Voice catalog (Settings → Voice → "voice: <name>" row cycles through)

| name | voice_id |
|---|---|
| rachel (default) | `21m00Tcm4TlvDq8ikWAM` |
| adam | `pNInz6obpgDQGcFmaJgB` |
| aria | `9BWtsMINqrJLrRacOk9x` |
| sarah | `EXAVITQu4vr4xnSDxMaL` |

## Terminal panel (Panel.TERMINAL)

Synthetic app + Compose panel that turns the launcher into a usable shell on the round screen — replaces shipping a separate Termux. Source: `ui/TerminalPanel.kt`.

Layout: back pill + cwd + `hide`/`kbd` toggle + status (`idle`/`rec`/`stt`/`...`); scrollable `LazyColumn` (auto-scrolls unless wheel-scrolled up); input row with `$` prompt + `paste`/`run`/`clr` pills; collapsible `RetroKeyboard` (`state.terminalKbVisible`).

**Execution model**: each command is one carroot connection. cwd tracked client-side in `state.terminalCwd`, prepended as `cd <cwd> && (<cmd>) ; pwd > <pwdFile> ; printf SENTINEL` so `cd /system` then `pwd` works across fresh `sh` instances. Streaming helper: `sendToCarrootStreaming(userCmd, cwd, onLine, onDone)` opens socket, writes wrapped script, `socket.shutdownOutput()`, reads stdout line-by-line on a background thread until EOF or sentinel. Lines flow into `state.terminalOutput` (cap 500, FIFO) AND `webServer?.broadcastTerminalOutput(line, cwd)`.

**Auto-routing**: `npm install foo` is rewritten to `sh /data/local/tmp/r1-alpine "npm install foo"`. Set in `LauncherActivity.alpineCommands`: `npm`, `node`, `npx`, `yarn`, `pnpm`, `python`, `python3`, `pip`, `pip3`, `apk`, `openclaw`, `claude`. `alpine: <anything>` is the explicit force-route prefix. `clear`/`cls` intercepted client-side.

**Voice dictation**: long-press side button while terminal is open → `terminalRecordStart()` → `startVoiceCapture(VoiceSink.TERMINAL)` (ElevenLabs Realtime) → committed transcript appends to `state.terminalInput`. Doesn't auto-submit. Live partial transcripts appear in `state.terminalPartial`. Single ElevenLabs key shared with chat + claude panels — see Voice section.

**Web terminal tab**: companion panel mirrors via `terminal.output` events. Gated on `state.webTerminalEnabled` (Settings → Network → "remote terminal", default off — exposes a root shell over LAN). RPC methods return `{code:"disabled"}` when off.

## Claude Code app (Panel.CLAUDE)

Synthetic app + chat panel that turns the launcher into a Claude Code chat client. Source: `ui/ClaudePanel.kt`, `claude/ClaudeMessage.kt`.

**Why a separate app, not the terminal panel**: tried socat-PTY chat mode (`scripts/install-socat.sh`, AnsiStripper) and it failed. claude's TUI uses Ink, which (a) needs arrow-keys for picker prompts the RetroKeyboard can't send, and (b) shows a "trust this directory" prompt that **cannot be skipped in interactive mode** (only `--print` skips it). PTY approach scrubbed in v3.26.0.

**Execution model**: each user turn fires `echo '<base64>' | base64 -d | sh /data/local/tmp/r1-alpine 'claude --print [-c] --output-format text 2>&1'` via `sendToCarrootStreaming`. base64 sidesteps every escaping issue (carroot → ash → chroot → ash → claude). First turn omits `-c`; turn 2+ uses `-c` to continue the most recent session. The `clr` pill resets `claudeMessages` AND flips `claudeFirstTurn = true`. claude's stdout streams into `state.claudeStreamingText` (live preview bubble at tail), commits to `claudeMessages` on completion.

**UX**: orange-right user / gray-left assistant bubbles, capped at 200 FIFO. Header: back / clear / kbd-toggle / status. Input row with `>` prompt + paste pill + send pill, collapsible RetroKeyboard. Long-press side → Whisper dictation. Wheel-press to send; wheel-up/down to scroll. Side back → APPS.

**Limitations**: `claude --print` uses subscription rate limits per turn. No tool-use UI (output folded into text). No per-token streaming animation (chunky). For full feature set (slash commands, `/resume`, plan mode), drop to terminal and run `claude --print "..."` directly.

## Hermes Agent app (Panel.HERMES_CHAT / HERMES_CONFIG)

See memory `project_hermes_agent_app.md` for the full implementation map. One-line recap: synthetic app that talks to a self-hosted **Hermes Agent** (`github.com/NousResearch/hermes-agent`) via its OpenAI-compatible HTTP gateway (`POST /v1/chat/completions?stream=true`, SSE deltas, `Authorization: Bearer` + `X-Hermes-Session-Id` headers). Files: `hermes/HermesPrefs.kt` / `HermesMessage.kt` / `HermesClient.kt`, panels at `ui/HermesChatPanel.kt` / `ui/HermesConfigPanel.kt`. Voice integration adds `VoiceSink.HERMES_CHAT` + `speakLatestHermesAssistantIfNeeded()` + `cancelHermesSpeech()`, parallel to the OpenClaw plumbing. Wire is **REST+SSE only**, no WebSocket — don't try to reuse `GatewaySession`. Config rows accept typed URL + bearer token; no QR pairing this round. Theme color `AppThemes.Hermes = #FFB300` (amber).

## Alpine arm64 chroot + Claude Code agent

See memory `project_alpine_chroot.md` and `project_claude_agent_on_r1.md` for full setup. Quick recap:

- Alpine 3.20 rootfs at `/data/local/tmp/alpine`; wrapper `/data/local/tmp/r1-alpine` re-binds `/proc`/`/sys`/`/dev` per invocation and `chroot`s with PATH `/root/.local/bin:...:/bin` + `ANTHROPIC_API_KEY` from `/data/local/tmp/.anthropic_key`. No proot — `chroot` + `mount --bind` are in `/system/bin` and carroot is root.
- Each `r1-alpine "<cmd>"` is a fresh `ash -c` — no cwd/env persistence, no PTY (REPLs unusable).
- `claude` user (uid 1000) created by `scripts/setup-claude-user.sh` because Claude Code refuses `bypassPermissions` when uid==0. Wrapper detects `claude ...` as first token and uses `su -l claude -s /bin/ash -c "$*"`. `/root/.local/{lib,share,state}` chmod 755 so the symlinked claude binary is reachable.
- Agent chain: terminal panel → carroot → alpine → `claude --print` → `r1-root <cmd>` (3-line `nc 127.0.0.1 1337`) → carroot → Android root. `/home/claude/CLAUDE.md` is device context + guardrails. OAuth via `scripts/claude-auth-{start,finish}.sh` (FIFO at `/tmp/claude-auth.pipe`); PKCE binds `code_challenge` to one login attempt — don't reuse URLs.
- Each `claude --print` = one task, one agent loop, no state between invocations.

## Resources

Copied from `../mylauncher/res/` except layouts:

- `drawable/` — vectors loaded via `painterResource(R.drawable.ic_xxx)`. Synthetic apps reuse: Settings → `ic_settings`, OpenClaw → `ic_wifi_arc`, Messages → `ic_messages`.
- `assets/web/` — `index.html` + `app.js` + `style.css`. Vanilla JS, no build step.
- `font/jersey_15.ttf` — wired into `LocalR1Type.appCard` (24sp), `clock`/`date` styles in `Theme.kt`.
- `values/colors.xml`, `values/strings.xml`, `xml/accessibility_service.xml`, `xml/network_security_config.xml` — unchanged.
- `values/themes.xml` — declares `@style/Theme.R1Launcher` for windowing (fullscreen, no action bar). Compose draws everything else.

## Release compatibility

- `applicationId = "com.r1.launcher"` — same as old project.
- **Signed with the LineageOS platform key** — `platform.keystore` (PKCS12, password `android`, alias `platform`) is converted from `~/lineage/build/make/target/product/security/platform.{pk8,x509.pem}`. Both `debug` and `release` build types use it. The prebuilt at `~/lineage/device/rabbit/r1/prebuilt/app/R1Launcher/Android.mk` declares `LOCAL_CERTIFICATE := platform` so Soong re-signs at OS-build time with the same key — sigs match, `adb install -r app-debug.apk` works in-place over the system app. Required because `ACCESS_MESSAGES_ON_ICC` (signature-only) gates `SmsManager.getAllMessagesFromIcc()`. SHA-256 cert fingerprint `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
- **One-time migration cost from `debug.keystore` (pre-v3.28.1) to platform key (v3.28.1+):** existing `/data/app/` installs signed with `debug.keystore` will reject upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. After flashing the new system image, either `fastboot -w` (cleanest) or `adb uninstall com.r1.launcher` first, then reinstall.
- **Release cuts on tag push, not main push.** `.github/workflows/release.yml` triggers on `v*` tags; building the APK uses whatever `versionCode`/`versionName` is at HEAD. Workflow: unset skip-worktree on `app/build.gradle.kts`, bump both fields, commit, `git tag vX.Y.Z && git push origin main --tags`. Re-pin debug and re-skip after.
- **`versionCode` chain** restarted at `1` for the v1.0.x clean baseline (last legacy was 213+). Increment both fields together for every release; same `versionCode` + new `versionName` won't allow OTA upgrade.

## Do not waste time re-attempting

- **Going Gradle-less.** Old pipeline doesn't know Kotlin/Compose/compose-compiler-plugin. Keep Gradle.
- **Running without bootstrap.** `gradle-wrapper.jar` isn't checked in; `bootstrap.sh` populates it. `build.sh` auto-invokes on first run.
- **AGP < 8.6 with Kotlin 2.0.** AGP 8.5- doesn't recognize `kotlin.plugin.compose`. Keep AGP ≥ 8.7.
- **Checking in `gradle-wrapper.jar`.** Bootstrap takes ~8s; jar changes with Gradle upgrades. Keep out of tree.
- **Material3 components with default styling.** R1 look is custom — Material3 is in only because `MaterialTheme` populates CompositionLocals. Use raw `Box`/`Row`/`Column`/`Text`, not `Button`/`Card`.
- **Skipping `state.back()` after `host.*` side-effect calls.** Self-closing panels expect the state machine to unwind.
- **Trusting framework toggle APIs.** `setWifiEnabled`/`setDataEnabled`/`Bluetooth.enable` silently no-op without system perm. Route through carroot. Brightness/volume *do* work programmatically; brightness needs `WRITE_SETTINGS` grant.
- **`svc data` / `svc wifi`.** Dead shims on this build.
- **Stripping `com.android.phone`/`TeleService.apk`/`com.android.providers.telephony`/`mediatek-telephony-*`.** `DataNetworkController` lives in `com.android.phone`. Removing leaves operator+pill working (from `telephony.registry`) while `mDataConnectionState=-1`. Keep them.
- **Flashing without `fastboot -w`** when telephony boot jars changed. `oat_primary/` mismatch ANRs `com.android.phone` ("Boot image chunk count mismatch"). Recover: `rm -rf /data/user_de/0/com.android.phone/cache/oat_primary/ /data/dalvik-cache/*phone*` + reboot. Sanity: `service list | grep -E "phone|iphonesubinfo|isub"` shows all three.
- **`am start` to "relaunch" after install.** Only foregrounds existing process. Force-stop via carroot first.
- **`AudioTrack` raw PCM for short clips.** Silently dropped. Wrap in WAV + `MediaPlayer.setDataSource(path)`.
- **`MediaPlayer.setDataSource(FileInputStream(file).use { it.fd })`.** `.use` closes FD before `prepare()`. Pass the path string.
- **Force-setting `STREAM_MUSIC` to MAX before TTS.** Overrides user's volume slider. TTS uses `USAGE_MEDIA`, controlled by Settings → Sound → Volume.
- **Opening `AudioRecord` (esp. `VOICE_RECOGNITION`) immediately after a UI sound.** AudioRecord steals the audio path; in-flight samples get clipped. Delay mic open ~200 ms after any cue.
- **`SoundPool.play(...)` with `rate != 1.0f` on MTK.** Silently dropped. Keep `rate=1f`. For "must beep" layer `ToneGenerator.startTone(...)` on top.
- **ElevenLabs `committed_transcript` not arriving while `partial_transcript` works.** Error frame slipped past `message_type` switch. Server emits `quota_exceeded`/`auth_error`/`unauthorized` etc. — catch-all in `onMessage` routes any frame with `error`/`exceed` to `onError`.
- **Buffering full TTS MP3 before playback.** Use `/stream?output_format=mp3_22050_32&optimize_streaming_latency=4`, hold OkHttp `Call` for `cancel()`. ElevenLabs locks billing at submission — cancel = bandwidth + UX, not guaranteed refund.
- **Clearing streaming bubble on `onChatTerminal`.** Fires before `chat.history` round-trips → flicker gap. Clear inside `applyOpenClawHistory` after persisted messages land.
- **Gating partial-transcript bubble on `chatRecording`.** Flips false on button release, ~200-500 ms before `committed_transcript`. Gate on `chatPartialText.isNotBlank()` alone.
- **Auto-transcribing audio attachments via `chat.send`.** Gateway forwards as multimodal, no STT. Transcribe client-side first.
- **OpenClaw role `node` for chat / direct `chat.subscribe` / self-generated connect nonce / `:18789` port suffix.** Use `operator` scopes; wrap subscribe in `node.event`; wait for `connect.challenge`; let OkHttp pick port from scheme.
- **`adb shell cmd clipboard set`.** Not implemented. Use `com.r1.launcher.SET_OPENAI_KEY` broadcast.
- **Bumping `versionCode` per debug install.** File is skip-worktree pinned to `1000` locally; `adb install -r` at same version is allowed. Only bump if `/system/app/R1Launcher/` floor passes the pin.
- **Committing local `versionCode` bumps.** File is skip-worktree — `git add app/build.gradle.kts` is a no-op until you `--no-skip-worktree` first. Don't `git checkout app/build.gradle.kts` either; it'll clobber the local pin.
- **Pushing to main expecting a release.** Workflow trigger is `v*` tags only. `git tag vX.Y.Z && git push --tags` cuts the release; plain `git push` does not.
- **Mapping side button to HOME or POWER.** HOME collapses DOWN/UP into one `onNewIntent`; POWER is intercepted. Use `BUTTON_1` — tradeoff: dead inside third-party apps.
- **Delivering SMS via `content://sms` without being default SMS app.** Use legacy `SMS_RECEIVED` + own JSON cache.
- **`SmsManager.getAllMessagesFromIcc()` direct call.** Hidden `@SystemApi`; reflect + `runCatching`.
- **`cmd wifi soft-ap-set-ssid` + `start-softap` two-step.** Only one-shot `cmd wifi start-softap "<ssid>" wpa2 "<pass>"` works.
- **Verifying softap via `dumpsys wifi`.** Strings come from `cmd wifi`'s listener, not dumpsys. Use `ip link show ap0` `state UP` + `LOWER_UP`. Retry ~6s (softap drops STA first on MTK).
- **Polling softap clients via `iw dev wlan1 station dump`.** Interface is `ap0`. Use `ip neigh show dev ap0`.
- **NanoHTTPD default 5s socket-read timeout.** Tears down WS. Use `start(0, false)`.
- **`WebSocket.send()` from main thread.** Route through `sendExecutor`.
- **First non-loopback interface for panel IP.** `ccmni0` (10.x CGN) sorts before `wlan0`. Use `discoverLocalIp()`.
- **`*/` inside Kotlin KDoc.** Closes the block.
- **Trusting `am broadcast` results.** `result=0` only means `am` exited cleanly. Verify via `dumpsys activity broadcasts`.
- **Bumping `multiplatform-markdown-renderer-m3`.** Every version calls `DrawScope.drawLine-NGM6Ib0$default(...)` missing from compose.ui 1.7.x — hard-crash on `>` blockquote. Workaround: strip `^[ \t]*>[ \t]?` per line before `Markdown(...)`. Real fix needs Compose BOM 2025.x.
- **`pgrep -af "<pattern>"`.** Returns nothing (toybox quirk). Use `ps -ef | grep -E "..." | grep -v grep`.
- **`setsid` + `nohup` + `chroot` from outside the chroot.** Kills cascade across the chroot syscall boundary when nc closes. Background `&` from inside chroot's ash; init reparents.

## Testing loop

Emulator loop from `../mylauncher/CLAUDE.md` — same AVD (`R1Emu`, 480×480 round, API 33):

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd R1Emu -no-snapshot -gpu host &
bash deploy_emu.sh
adb exec-out screencap -p > emu.png
python3 -c "from PIL import Image; print(Image.open(r'emu.png').getpixel((x,y)))"
```

For OpenClaw work: real R1 + running openclaw gateway on a reachable host. After install:

```bash
adb shell pm grant com.r1.launcher android.permission.CAMERA
adb shell pm grant com.r1.launcher android.permission.RECORD_AUDIO
adb shell "am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity"
adb logcat -s GatewaySession R1Motor AudioTrack MediaPlayer
```

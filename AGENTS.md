# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this is

Custom Compose-based HOME launcher for the **Rabbit R1** (480×480 round, MT6765, currently running CipherOS 7.0 ALHENA / Android 16). Successor to `../mylauncher/` (the Gradle-less Java + XML launcher). Same package (`com.r1.launcher`), same signing key (`debug.keystore` copied from the old project), so OTA upgrades flow naturally from the old APK to this one **as long as `versionCode` keeps climbing past 24** (this project starts at 100; current is in the 120s).

Read `../AGENTS.md` first for device/network/CipherOS state and `../mylauncher/AGENTS.md` for feature-level history — this file covers only what's different in the Compose rewrite plus the OpenClaw chat integration.

## First-time bootstrap

The repo doesn't check in `gradle-wrapper.jar` or `gradlew{,bat}`. Generate them once:

```bash
bash bootstrap.sh
```

This downloads Gradle 8.9, extracts it to `.bootstrap/`, and calls `gradle wrapper` to populate `gradle/wrapper/gradle-wrapper.jar` + `gradlew*`. 7-Zip at `/c/Program Files/7-Zip/7z.exe` is used on Windows (no `unzip` in Git-Bash by default). Safe to re-run.

After that, the normal commands work:

```bash
bash build.sh            # ./gradlew assembleDebug → build/r1launcher.apk
bash deploy.sh           # build + adb install -r + relaunch (USB)
bash deploy_vps.sh       # build + scp to rabbit.luma.om + update latest.json
bash deploy_emu.sh       # build + adb install on R1Emu AVD
```

## Pinned versions — do not drift without testing

- **AGP 8.7.2** + **Gradle 8.9** — compatible with Android Studio JBR 21; AGP 8.7 is the lowest that plays nicely with Kotlin 2.0 compose plugin.
- **Kotlin 2.0.21** + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.kotlin.plugin.serialization` — Kotlin 2.0 *requires* the separate compose plugin; serialization plugin is needed for the OpenClaw JSON-RPC.
- **Compose BOM 2024.10.01** — pulls in `compose.ui`, `compose.foundation`, `compose.animation`, `compose.material3` at matched versions.
- **OkHttp 4.12.0**, **kotlinx-serialization-json 1.7.3**, **security-crypto 1.1.0-alpha06**, **bcprov-jdk18on 1.78.1**, **zxing-android-embedded 4.3.0**, **vosk-android 0.3.47** + **JNA 5.13.0** — for the OpenClaw chat panel. Vosk is no longer wired in (see "Whisper STT path" below); the dep + 130 MB asset model are still in the tree as dead weight pending cleanup.
- **minSdk 23**, **targetSdk 33**, **compileSdk 34**. These match the old project so the OTA upgrade path is clean.
- **Java 17 source/target** — AGP 8.7 defaults; system JDK isn't used, `build.sh` exports `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`.

## Architecture

Single Activity (`LauncherActivity`), one `setContent { R1Theme { LauncherRoot(state, appStore, host) } }`. No fragments, no nav component.

**State**: `LauncherState` — a plain Kotlin class holding `mutableStateOf` / `mutableIntStateOf` / `mutableStateListOf` fields. Compose observes directly. No ViewModel — the Activity has `android:configChanges="keyboardHidden|orientation|screenSize|uiMode"` and `launchMode=singleTask`, so it never recreates, and ViewModel survival ceremony would be pure cost.

**Panel state machine**: `enum Panel { HOME, SHEET, APPS, STORE, DETAIL, SETTINGS, BRIGHTNESS, VOLUME, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_SETTINGS, AUDIO_TEST }` on `state.panel`. Transitions via `openSheet()`, `openApps()`, `openStore()`, `openDetail(entry)`, `openSettings()`, `openBrightness()`, `openVolume()`, `openOpenClawQr()`, `openOpenClawChat()`, `openOpenClawSettings()`, `openAudioTest()`, `back()`. Each panel has its own focus int (`homeFocus`, `sheetFocus`, etc.); brightness/volume use level fields instead. The `back()` unwind is intentional and asymmetric:

- `BRIGHTNESS|VOLUME → SETTINGS → APPS → HOME` (Settings exits back into the apps grid since it's an in-grid app)
- `OPENCLAW_QR|OPENCLAW_CHAT|AUDIO_TEST → APPS` (same reason — they're in-grid synthetic apps)
- `OPENCLAW_SETTINGS → OPENCLAW_CHAT` (settings is a child of chat, not of the grid)

**Apps list is typed**: `state.apps` is `MutableList<AppEntry>` — a sealed class: `Real(ResolveInfo)`, `Settings`, `OpenClaw`, `AudioTest`. `LauncherActivity.loadApps()` appends the synthetics in this order: real apps, then `OpenClaw`, then `AudioTest`, then `Settings`. `launchApp(idx)` switches on the type:
- `Real` — fires the launcher intent
- `Settings` — `WRITE_SETTINGS` grant check then `state.openSettings()`
- `OpenClaw` — if `openClawPrefs.hasPairing()` → `openClawStartSession()` + `state.openOpenClawChat()`; else → `state.openOpenClawQr()` (after camera-perm request)
- `AudioTest` — mic-perm request + `state.openAudioTest()` (diagnostic, see "Audio test panel")

When adding a new synthetic app, every `when` branch in `AppsPanel.kt` (key, painter, label) needs the new case or you get a compile error.

**Navigation actions** live in `LauncherNav.kt` as extension functions on `LauncherState`: `wheelUp(host)`, `wheelDown(host)`, `activate(host)`, `backPressed(host)`. They're pure state mutations; side effects (starting intents, tones, rebooting, brightness/volume writes, OpenClaw record, audio test) go through the `LauncherHost` interface which `LauncherActivity` implements. The host has grown to ~25 methods covering: app launch, system intents, store, detail, brightness/volume, tones, OpenClaw (`openClawScanned`, `openClawToggleRecord`, `openClawSendText`, `openClawScrollUp/Down`, `openClawCloseSession`, `openClawPasteOpenaiKey`, `openClawClearOpenaiKey`, `openClawSaveOpenaiKey`), and audio test (`audioTestActivate`, `audioTestCycleSource`, `audioTestStop`).

**Compose tree** (z-stack inside a single `Box` in `LauncherRoot.kt`):

```
wallpaper
  + HomeScreen          (always composed; covered by higher panels)
  + AppsPanel           (panel == APPS)
  + StorePanel          (panel == STORE)
  + SystemSheet         (panel == SHEET; full-scrim + card)
  + StoreDetail         (panel == DETAIL; full-scrim + card)
  + SettingsPanel       (panel == SETTINGS)
  + BrightnessPanel     (panel == BRIGHTNESS; wheel slider)
  + VolumePanel         (panel == VOLUME; wheel slider)
  + OpenClawQrPanel     (panel == OPENCLAW_QR; ZXing camera)
  + OpenClawChatPanel   (panel == OPENCLAW_CHAT)
  + OpenClawSettingsPanel (panel == OPENCLAW_SETTINGS; key entry + RetroKeyboard)
  + AudioTestPanel      (panel == AUDIO_TEST; mic capture + playback)
  + Topbar              (HOME || SHEET || DETAIL only)
  + debug key overlay
```

**Key dispatch**: `Activity.dispatchKeyEvent` routes the same superset of wheel/PTT candidate keycodes as the old launcher (volume, dpad, page up/down, headsethook, media_play_pause, call, assist, voice_assist, power) into `state.wheelUp/wheelDown/activate/backPressed`. The isHandled allowlist must stay in sync with the dispatcher or you'll see UP-event fallthrough. Debug overlay prints `key <code> sc <scan> NAME` on every keydown — gated on `state.showDebugBar` (toggle exposed in the Settings panel; default true).

**Animation tokens** in `ui/Common.kt`:
- `ANIM_OPEN_MS = 220`, `ANIM_CLOSE_MS = 170`, `ANIM_FOCUS_MS = 140` — same as the old XML timings
- `FOCUS_SCALE = 1.04f`, `UNFOCUS_ALPHA = 0.55f` — same visual focus emphasis
- `Modifier.focusAnim(focused)` applies scale+alpha via `animateFloatAsState`

**Java interop**: `Updater.java`, `AppStore.java`, `PowerService.java`, `ApkProvider.java` are unchanged from the old project. Kotlin calls into them directly. No need to port these unless you want idiomatic Kotlin — they work as-is.

## OpenClaw chat panel

A built-in client for an [openclaw](https://github.com/openclaw/openclaw) AI gateway. Pair via QR, chat with streaming replies, voice input via OpenAI Whisper. All in `app/src/main/java/com/r1/launcher/openclaw/`:

| File | Role |
|---|---|
| `SetupCode.kt` | Decode the URL-safe Base64 QR → `{url, bootstrapToken?, token?, password?}`. |
| `OpenClawPrefs.kt` | EncryptedSharedPreferences wrapper (`openclaw.secure`) holding `gateway.url`, `gateway.bootstrap`, `gateway.deviceToken`, `gateway.token`, `openai.key`. Plain prefs (`openclaw.plain`) for the random `node.instanceId`. |
| `DeviceIdentityStore.kt` | Ed25519 keypair via Bouncy Castle lightweight API; persisted at `filesDir/openclaw/identity/device.json`. Builds the pipe-delimited v3 auth payload string. |
| `GatewaySession.kt` | OkHttp WebSocket + JSON-RPC over `{type:"req"\|"res"\|"event"}`. Owns one client, one socket, a `pending: Map<id, CompletableDeferred>` for responses, and an `onEvent` callback for server pushes. |
| `ChatMessage.kt` | `data class ChatMessage(role, text, streaming, timestamp)` + JsonArray flatteners. |
| `AudioCapture.kt` | Pure mic → WAV (16 kHz mono PCM_16BIT, capped at 30 s). Used for voice input; replaced the on-device Vosk path. |
| `WhisperClient.kt` | OkHttp multipart POST to `https://api.openai.com/v1/audio/transcriptions` with `language=en` locked. |
| `VoiceRecognizer.kt` | **Dead code** — the old Vosk recognizer. Not referenced by `LauncherActivity` anymore. Slated for removal alongside the Vosk dep + assets. |

### Protocol gotchas (learned the hard way — leaving here so they don't get re-discovered)

- **Role + scopes**: connect as `role: "operator"` with `scopes: ["operator.read", "operator.write", "operator.talk.secrets"]`. The `node` role exists but doesn't have access to chat methods. The `PAIRING_SETUP_BOOTSTRAP_PROFILE` only allows `operator.*` scopes — `node.invoke` will be rejected.
- **Server-issued nonce**: don't generate your own. After opening the WebSocket, wait for the `connect.challenge` event; use *that* nonce in both the signed payload and `device.nonce` in the connect call. Server-supplied or it will fail with `device nonce mismatch`.
- **`chat.subscribe` requires admin** — unless wrapped in `node.event`. The official client invokes it as `request("node.event", {event: "chat.subscribe", payloadJSON: "..."})`. `chat.send` and `chat.history` work directly.
- **`client.id` is allowlisted** — use `"openclaw-android"`. `"r1-launcher"` (or any other) gets rejected with "must be equal to one of allowed values".
- **No URL port mangling** — the gateway URL from the QR may be a plain `wss://host` (no port). Don't append a default port; OkHttp picks 443 for `wss://` and 80 for `ws://` automatically. The `withDefaultPort(18789)` helper from earlier broke `wss://claw.luma.om`.
- **Audio attachments are NOT auto-transcribed** by the gateway. They're forwarded to the LLM as multimodal input — only useful if your LLM is multimodal (GPT-4o-audio etc.). With a text-only LLM you get back "I didn't receive any text in your message". This is why we transcribe client-side via Whisper before calling `chat.send`.

### Whisper STT path

Voice flow in `LauncherActivity.openClawToggleRecord`: tap wheel → `AudioCapture.start()` records PCM until next tap → on stop, build a 44-byte WAV header + PCM bytes → `WhisperClient.transcribe(wav, apiKey)` POSTs to OpenAI with `language=en` → on success, append optimistic user bubble + `session.send(text=transcript)`. UI shows `rec` (red) → `stt` (yellow) → `...` (busy) in the chat header. Empty/short clips are filtered (`< 300ms` or `< 2%` peak) with a toast.

The OpenAI key lives in `OpenClawPrefs.openaiKey` (encrypted). Three ways to set it:

1. **Settings page in the chat** — tap the `set` pill in the chat header → enter via the on-screen RetroKeyboard or paste from clipboard.
2. **adb broadcast** (cleanest for development):
   ```
   adb shell "am broadcast -a com.r1.launcher.SET_OPENAI_KEY --es key 'sk-...'"
   ```
   Receiver in `LauncherActivity.openaiKeyRx`; registered in `onResume` with `RECEIVER_EXPORTED` on SDK ≥ 33.
3. **Clipboard paste** — the `+key` orange pill (when no key set) reads `ClipboardManager.primaryClip` and saves if it starts with `sk-`.

If the LLM still replies in the wrong language despite Whisper being locked to English, that's a server-side system-prompt issue in your openclaw agent config — not something the launcher can fix.

## Audio test panel

Diagnostic-only app entry in the apps grid (alongside the synthetic Settings/OpenClaw cards). Lives in `app/src/main/java/com/r1/launcher/audio/AudioTester.kt` + `ui/AudioTestPanel.kt`.

Use case: when voice STT misbehaves, this isolates *where* the failure is. Wheel-press starts an `AudioRecord` (16 kHz mono PCM_16BIT) and shows a live RMS+peak meter. Wheel-press again stops, wraps the PCM as WAV, writes it to `cacheDir/audio-test/rec.wav`, and plays it back via `MediaPlayer` (with `AudioAttributes(USAGE_MEDIA)` + auto-bumped media volume to max). Wheel-up/down cycles `AudioSource` (`VOICE_RECOGNITION`, `MIC`, `CAMCORDER`, `DEFAULT`) when idle.

**Do not use raw `AudioTrack` PCM playback for short clips on the R1** — even with `MODE_STREAM` + `Builder` + explicit `USAGE_MEDIA`, the output is silently dropped. `MediaPlayer` reading a WAV file works fine. This is why `AudioTester.runPlayback` writes a real WAV file and pipes it through `MediaPlayer` instead of using `AudioTrack.write(buf)`.

## Resources

Copied from `../mylauncher/res/` except layouts:

- `drawable/` — all 32 vector/shape drawables preserved. Vector icons load via `painterResource(R.drawable.ic_xxx)`. Shape/selector drawables that were tied to View state (`tile_bg`, `dock_btn_state`) are *not* used by Compose — focus styling is redone with `Modifier.background(tileFocus, RoundedCornerShape(8.dp))`. The synthetic apps reuse existing icons: Settings → `ic_settings`, OpenClaw → `ic_wifi_arc`, AudioTest → `ic_signal_bars`.
- `font/jersey_15.ttf` — wired into `LocalR1Type.appCard` (24sp, used for apps-grid and settings-grid card labels) and `clock`/`date` styles in `Theme.kt`.
- `values/colors.xml`, `values/strings.xml` — unchanged (Compose reads strings via `stringResource`).
- `xml/accessibility_service.xml`, `xml/network_security_config.xml` — unchanged.
- `values/themes.xml` — declares `@style/Theme.R1Launcher` because the manifest still references an Android theme for windowing (fullscreen, no action bar, dark sys-bar colors). Compose draws the UI itself.
- `assets/model-en-us/` — **130 MB Vosk lgraph model**, currently dead weight. Will be removed when the Vosk dep is ripped out.

## Release compatibility

- `applicationId = "com.r1.launcher"` — same as old project, so the OTA install intent replaces the old APK in place (no uninstall required).
- **Signed with `debug.keystore`** — the copy at `mylauncher-compose/debug.keystore` is identical to `mylauncher/debug.keystore`. Don't regenerate or re-sign — every device with the old APK installed will refuse the upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and need `pm uninstall com.r1.launcher` + re-bootstrap of accessibility grant + home-activity selection + install-packages appop.
- **`versionCode`** starts at 100 (last Java release was 24); current is in the 120s. Bump both `versionCode` and `versionName` in `app/build.gradle.kts` before each release.

## Do not waste time re-attempting

- **Going back to Gradle-less.** The old pipeline doesn't know about Kotlin, Compose, or the Compose compiler plugin. Keep Gradle.
- **Running without bootstrap.** `./gradlew assembleDebug` fails if `gradle-wrapper.jar` isn't present — and it isn't checked in. `build.sh` auto-invokes `bootstrap.sh` on first run; respect that flow.
- **Kotlin stdlib + AGP < 8.6 with Kotlin 2.0.** AGP 8.5 and below don't recognize `org.jetbrains.kotlin.plugin.compose`. Keep AGP ≥ 8.7.
- **Checking in `gradle-wrapper.jar`.** Plenty of projects do this, but on this machine the bootstrap takes ~8s and the jar changes with Gradle upgrades. Keep it out of the tree; run `bootstrap.sh`.
- **Leaving Material3 components styled with defaults.** The R1 look is custom (tile selectors, back pill, dock button) — Material3 is pulled in only because `MaterialTheme` populates CompositionLocals that some primitives assume. Use raw `Box`/`Row`/`Column`/`Text` for everything, not `Button`/`Card`/etc., or the ripple + elevation will break the vibe.
- **Dropping `state.back()` after `host.*` side-effect calls.** Panels that self-close on activation (sheet rows → Wi-Fi, reboot, etc.) expect the state machine to unwind; miss a `back()` and the panel stays up behind the launched intent.
- **Programmatic Wi-Fi or airplane-mode toggle.** `WifiManager.setWifiEnabled` is a no-op for `targetSdk ≥ 29`; airplane mode requires `WRITE_SECURE_SETTINGS` (system-signed only). Settings rows for these fall through to system Settings activities — don't try to flip them in-app. Brightness and stream volume *do* work programmatically (`Settings.System.SCREEN_BRIGHTNESS` + `AudioManager.STREAM_MUSIC`); brightness needs a one-time `WRITE_SETTINGS` grant the first time the Settings card is opened.
- **`AudioTrack` raw PCM playback for short clips on the R1.** Even with `Builder` + `USAGE_MEDIA` + `MODE_STREAM` + max volume, output is silently dropped. Wrap PCM in WAV and use `MediaPlayer.setDataSource(path)`. ToneGenerator/SoundPool work fine through `AudioTrack` because they use compressed/long-lived streams.
- **`MediaPlayer.setDataSource(FileInputStream(file).use { it.fd })`.** The `.use` block closes the FD before `prepare()` runs, leaving `MediaPlayer` with a dead descriptor. Pass the path string, not an FD from a closed stream.
- **Auto-transcribing audio attachments via `chat.send`.** The openclaw gateway forwards them to the LLM as multimodal input but doesn't run STT. Either run a multimodal LLM, or transcribe client-side first (current approach via WhisperClient).
- **OpenClaw role `node` for chat methods.** Use `operator` with the operator scopes — node can't access `chat.send`/`chat.history`/`chat.subscribe`. The first attempt was rejected with "unauthorized role: node".
- **Calling `chat.subscribe` directly.** It defaults to admin scope. Wrap in `node.event` per the official client (`request("node.event", {event:"chat.subscribe", payloadJSON:"..."})`).
- **Generating your own connect nonce.** Server sends a `connect.challenge` event with the nonce you must use. Wait for that event, then connect.
- **Appending a default port to OpenClaw gateway URLs.** The QR may give `wss://host` with no port; OkHttp uses scheme defaults. Forcing `:18789` breaks `wss://`.
- **`adb shell cmd clipboard set`.** The CipherOS shell doesn't have a clipboard CLI implementation. To inject the OpenAI key from a host machine, use the `com.r1.launcher.SET_OPENAI_KEY` broadcast intent instead.

## Testing loop

Use the emulator loop from `../mylauncher/AGENTS.md` — same AVD (`R1Emu`, 480×480 round, API 33). Boot once:

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd R1Emu -no-snapshot -gpu host &
```

Then `bash deploy_emu.sh` rebuilds + reinstalls + relaunches. Screenshot via `adb exec-out screencap -p > emu.png`; pixel-sample with `python3 -c "from PIL import Image; print(Image.open(r'emu.png').getpixel((x,y)))"` for focus/alpha regressions.

For OpenClaw work specifically, you'll want a real R1 (camera + mic + WebSocket actually exercised) and a running openclaw gateway on a reachable host. After install on the R1:

```bash
adb shell pm grant com.r1.launcher android.permission.CAMERA
adb shell pm grant com.r1.launcher android.permission.RECORD_AUDIO
adb shell "am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity"
```

Logs for the OpenClaw / audio paths:
```
adb logcat -s GatewaySession AudioTester AudioTrack MediaPlayer
```

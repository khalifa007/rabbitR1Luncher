# CLAUDE.md — mylauncher-compose

Successor to `../mylauncher/` (the Gradle-less Java + XML launcher). Same package (`com.r1.launcher`), same signing key (`debug.keystore` copied from the old project), so OTA upgrades flow naturally from the old APK to this one **as long as `versionCode` keeps climbing past 24** (this project starts at 100).

Read `../CLAUDE.md` first for device/network state and `../mylauncher/CLAUDE.md` for feature-level history — this file covers only what's different in the Compose rewrite.

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
- **Kotlin 2.0.21** + `org.jetbrains.kotlin.plugin.compose` — Kotlin 2.0 *requires* the separate compose plugin (replaces the compose compiler extension version pin in AGP 7.x-style setups).
- **Compose BOM 2024.10.01** — pulls in `compose.ui`, `compose.foundation`, `compose.animation`, `compose.material3` at matched versions.
- **minSdk 23**, **targetSdk 33**, **compileSdk 34**. These match the old project so the OTA upgrade path is clean.
- **Java 17 source/target** — AGP 8.7 defaults; system JDK isn't used, `build.sh` exports `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`.

## Architecture

Single Activity (`LauncherActivity`), one `setContent { R1Theme { LauncherRoot(state, appStore, host) } }`. No fragments, no nav component.

**State**: `LauncherState` — a plain Kotlin class holding `mutableStateOf` / `mutableIntStateOf` / `mutableStateListOf` fields. Compose observes directly. No ViewModel — the Activity has `android:configChanges="keyboardHidden|orientation|screenSize|uiMode"` and `launchMode=singleTask`, so it never recreates, and ViewModel survival ceremony would be pure cost.

**Panel state machine**: `enum Panel { HOME, SHEET, APPS, STORE, DETAIL, SETTINGS, BRIGHTNESS, VOLUME }` on `state.panel`. Transitions via `openSheet()`, `openApps()`, `openStore()`, `openDetail(entry)`, `openSettings()`, `openBrightness()`, `openVolume()`, `back()`. Each panel has its own focus int (`homeFocus`, `sheetFocus`, `appsFocus`, `storeFocus`, `detailFocus`, `settingsFocus`); brightness/volume use level fields (`brightnessLevel` 1..255, `volumeLevel` 0..`volumeMax`) instead. The `back()` unwind for the settings micro-app is intentional: `BRIGHTNESS|VOLUME → SETTINGS → APPS → HOME` (Settings exits back into the apps grid, not home, since it's modeled as an in-grid app).

**Apps list is typed**: `state.apps` is `MutableList<AppEntry>` — a sealed class with `Real(ResolveInfo)` + `Settings`. `LauncherActivity.loadApps()` appends `AppEntry.Settings` after the real entries, so the synthetic *settings* card is always the last apps-grid item. `LauncherActivity.launchApp(idx)` switches on the type: `Real` fires the launcher intent; `Settings` runs the `WRITE_SETTINGS` grant check (`Settings.System.canWrite` / `ACTION_MANAGE_WRITE_SETTINGS`) before calling `state.openSettings()`. To pin Settings elsewhere in the grid (e.g. top), change the `add` index in `loadApps()`.

**Navigation actions** live in `LauncherNav.kt` as extension functions on `LauncherState`: `wheelUp(host)`, `wheelDown(host)`, `activate(host)`, `backPressed(host)`. They're pure state mutations; side effects (starting intents, tones, rebooting, brightness/volume writes, etc.) go through the `LauncherHost` interface which `LauncherActivity` implements. Beyond the original launch/wifi/reboot/store/detail methods, the host now exposes `setBrightness(level)`, `setVolume(level)`, and `openAirplaneSettings()`.

**Compose tree** (z-stack inside a single `Box` in `LauncherRoot.kt`):

```
wallpaper
  + HomeScreen     (always composed; covered by higher panels)
  + AppsPanel      (AnimatedVisibility on panel == APPS)
  + StorePanel     (AnimatedVisibility on panel == STORE)
  + SystemSheet    (AnimatedVisibility on panel == SHEET; full-scrim + card)
  + StoreDetail    (AnimatedVisibility on panel == DETAIL; full-scrim + card)
  + SettingsPanel  (AnimatedVisibility on panel == SETTINGS; apps-card-style list)
  + BrightnessPanel(AnimatedVisibility on panel == BRIGHTNESS; wheel slider)
  + VolumePanel    (AnimatedVisibility on panel == VOLUME; wheel slider)
  + Topbar         (AnimatedVisibility on HOME || SHEET || DETAIL)
  + debug key overlay
```

**Key dispatch**: `Activity.dispatchKeyEvent` routes the same superset of wheel/PTT candidate keycodes as the old launcher (volume, dpad, page up/down, headsethook, media_play_pause, call, assist, voice_assist, power) into `state.wheelUp/wheelDown/activate/backPressed`. The isHandled allowlist must stay in sync with the dispatcher or you'll see UP-event fallthrough. Debug overlay prints `key <code> sc <scan> NAME` on every keydown — gated on `state.showDebugBar` (toggle exposed in the Settings panel; default true).

**Animation tokens** in `ui/Common.kt`:
- `ANIM_OPEN_MS = 220`, `ANIM_CLOSE_MS = 170`, `ANIM_FOCUS_MS = 140` — same as the old XML timings
- `FOCUS_SCALE = 1.04f`, `UNFOCUS_ALPHA = 0.55f` — same visual focus emphasis
- `Modifier.focusAnim(focused)` applies scale+alpha via `animateFloatAsState`

**Java interop**: `Updater.java`, `AppStore.java`, `PowerService.java`, `ApkProvider.java` are unchanged from the old project. Kotlin calls into them directly. No need to port these unless you want idiomatic Kotlin — they work as-is.

## Resources

Copied from `../mylauncher/res/` except layouts:

- `drawable/` — all 32 vector/shape drawables preserved. Vector icons load via `painterResource(R.drawable.ic_xxx)`. Shape/selector drawables that were tied to View state (`tile_bg`, `dock_btn_state`) are *not* used by Compose — focus styling is redone with `Modifier.background(tileFocus, RoundedCornerShape(8.dp))`.
- `font/jersey_15.ttf` — wired into `LocalR1Type.appCard` (24sp, used for apps-grid and settings-grid card labels) and `clock`/`date` styles in `Theme.kt`.
- `values/colors.xml`, `values/strings.xml` — unchanged (Compose reads strings via `stringResource`).
- `xml/accessibility_service.xml`, `xml/network_security_config.xml` — unchanged.
- `values/themes.xml` — **new**. Declares `@style/Theme.R1Launcher` because the manifest still references an Android theme for windowing (fullscreen, no action bar, dark sys-bar colors). Compose draws the UI itself.

## Release compatibility

- `applicationId = "com.r1.launcher"` — same as old project, so the OTA install intent replaces the old APK in place (no uninstall required).
- **Signed with `debug.keystore`** — the copy at `mylauncher-compose/debug.keystore` is identical to `mylauncher/debug.keystore`. Don't regenerate or re-sign — every device with the old APK installed will refuse the upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and need `pm uninstall com.r1.launcher` + re-bootstrap of accessibility grant + home-activity selection + install-packages appop.
- **`versionCode`** starts at 100 (last Java release was 24), so the first `deploy_vps.sh` run publishes cleanly over the Java install. Bump both `versionCode` and `versionName` in `app/build.gradle.kts` before each release.

## Do not waste time re-attempting

- **Going back to Gradle-less.** The old pipeline doesn't know about Kotlin, Compose, or the Compose compiler plugin. Keep Gradle.
- **Running without bootstrap.** `./gradlew assembleDebug` fails if `gradle-wrapper.jar` isn't present — and it isn't checked in. `build.sh` auto-invokes `bootstrap.sh` on first run; respect that flow.
- **Kotlin stdlib + AGP < 8.6 with Kotlin 2.0.** AGP 8.5 and below don't recognize `org.jetbrains.kotlin.plugin.compose`. Keep AGP ≥ 8.7.
- **Checking in `gradle-wrapper.jar`.** Plenty of projects do this, but on this machine the bootstrap takes ~8s and the jar changes with Gradle upgrades. Keep it out of the tree; run `bootstrap.sh`.
- **Leaving Material3 components styled with defaults.** The R1 look is custom (tile selectors, back pill, dock button) — Material3 is pulled in only because `MaterialTheme` populates CompositionLocals that some primitives assume. Use raw `Box`/`Row`/`Column`/`Text` for everything, not `Button`/`Card`/etc., or the ripple + elevation will break the vibe.
- **Dropping `state.back()` after `host.*` side-effect calls.** Panels that self-close on activation (sheet rows → Wi-Fi, reboot, etc.) expect the state machine to unwind; miss a `back()` and the panel stays up behind the launched intent.
- **Programmatic Wi-Fi or airplane-mode toggle.** `WifiManager.setWifiEnabled` is a no-op for `targetSdk ≥ 29`; airplane mode requires `WRITE_SECURE_SETTINGS` (system-signed only). Settings rows for these fall through to system Settings activities — don't try to flip them in-app. Brightness and stream volume *do* work programmatically (`Settings.System.SCREEN_BRIGHTNESS` + `AudioManager.STREAM_MUSIC`); brightness needs a one-time `WRITE_SETTINGS` grant the first time the Settings card is opened.

## Testing loop

Use the emulator loop from `../mylauncher/CLAUDE.md` — same AVD (`R1Emu`, 480×480 round, API 33). Boot once:

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd R1Emu -no-snapshot -gpu host &
```

Then `bash deploy_emu.sh` rebuilds + reinstalls + relaunches. Screenshot via `adb exec-out screencap -p > emu.png`; pixel-sample with `python3 -c "from PIL import Image; print(Image.open(r'emu.png').getpixel((x,y)))"` for focus/alpha regressions.

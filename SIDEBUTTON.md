# SIDEBUTTON.md

Everything about the Rabbit R1's physical side button on **CipherOS 7.0 ALHENA**: how it actually works at the kernel level, how to remap it, the persistence path that survives reboots, the wake-from-sleep gotcha, and the current production setup (single press = home, double press = open apps).

If you're flashing back to stock rabbitOS or to CipherOS 6.0 ALTAIR, none of this applies — the kernel-level behavior is different. See `JAILBREAK.md` for stock-era notes and `cipheros-install/` for 6.0.

---

## TL;DR

```bash
# Map side button to HOME (single press goes home, also wakes from screen-off-but-not-suspend)
adb shell "mkdir -p /data/system/devices/keylayout && \
  printf 'key 116   HOME      WAKE\nkey 114   VOLUME_DOWN\n' > \
  /data/system/devices/keylayout/mtk-kpd.kl"
adb shell "stop; sleep 2; start"   # framework reload, ~20s
```

That's it. Persists across reboots. To revert: delete the file and `stop; start` again.

---

## How the side button works on this device

### Hardware → kernel

The R1's side button is wired to **two** input paths:

| Path | Device node | When it fires | Status |
|---|---|---|---|
| AP keypad controller | `/dev/input/event0` (`mtk-kpd`, IRQ 25) | SoC awake | ✅ Works on CipherOS 7.0 |
| PMIC keys | `/dev/input/event1` (`mtk-pmic-keys`, IRQs 325/327) | SoC suspended (wake source) | ❌ Stuck at 0 IRQs — device-tree mapping bug |

When you press the button while the screen is on, the AP keypad controller's IRQ fires and `mtk-kpd` emits `KEY_POWER` (Linux keycode 116) on `/dev/input/event0`. Android's input subsystem reads that, applies the keylayout, dispatches to PhoneWindowManager.

When the SoC is in deep suspend, the AP-side IRQ is masked; only PMIC-side IRQs can wake it. The PMIC-keys driver is loaded but its IRQ counters never increment when the side button is pressed — the device-tree mapping for the GPIO/PMIC key register is incomplete or wrong. **Net effect: the side button cannot wake the device from suspend.** This was true on CipherOS 6.0 (per TurboTheTurtle's `cipheros-findings.md`) and **is still true on 7.0** despite 7.0 having fixed the AP-side path. See "Wake-from-sleep limitation" below for workarounds.

### Kernel → Android

Once `KEY_POWER` reaches Android's `EventHub`, it is mapped to an Android `KEYCODE_*` via a **keylayout file** (`.kl`). Android searches these directories in order:

1. `/odm/usr/keylayout/`
2. `/vendor/usr/keylayout/`
3. `/system/usr/keylayout/`
4. **`/data/system/devices/keylayout/`** ← the one we use

For each input device, Android first looks for a name-specific file (`<device-name>.kl`, e.g. `mtk-kpd.kl`), and falls back to `Generic.kl` if no name-specific file is found in **any** of the search paths. **A name-specific file in `/data/system/devices/keylayout/` wins over `Generic.kl` in `/system/usr/keylayout/`** — that's the trick we exploit.

Verify what keylayout file Android is currently using for the side button:

```bash
adb shell "dumpsys input | sed -n '/mtk-kpd/,/Identifier:/p'" | grep KeyLayoutFile
# KeyLayoutFile: /data/system/devices/keylayout/mtk-kpd.kl       ← custom (our setup)
# KeyLayoutFile: /system/usr/keylayout/Generic.kl                ← stock fallback
```

### Android → behavior

Once mapped to a `KEYCODE_*`, several layers can act on it:

- **`KEYCODE_POWER`** is intercepted by `PhoneWindowManager.interceptKeyBeforeQueueing` *before* it reaches userland apps. Short press → screen lock; long press → power menu. Apps' `dispatchKeyEvent` never sees it.
- **`KEYCODE_HOME`** is also intercepted at PhoneWindowManager level — pressing it brings the home activity to front. If the home activity is already foregrounded, its `onNewIntent` is called instead of `onCreate`.
- Most other keycodes (`KEYCODE_MENU`, `KEYCODE_ASSIST`, `KEYCODE_NOTIFICATION`, custom unhandled codes) reach the foreground app's `dispatchKeyEvent` and can be acted on per-app.

This is why the `WAKE` flag in a `.kl` only matters for keys that Android already has wake-handling for — it makes those keycodes wake the screen if delivered while the screen is off but the SoC is still awake (e.g. immediately after the screen-off timeout, before deep suspend kicks in). It does **not** make the kernel deliver an event during suspend — if no IRQ fires, no event, no wake.

---

## File format: a keylayout `.kl`

Plain text. One mapping per line: `key <linux-keycode> <ANDROID_KEYCODE> [FLAG ...]`.

For our side button:

```
key 116   HOME      WAKE
key 114   VOLUME_DOWN
```

- `116` is `KEY_POWER` in Linux's `<linux/input-event-codes.h>`. We override it.
- `114` is `KEY_VOLUMEDOWN`. The `mtk-kpd` device advertises both, so we pass volume-down through unchanged. (Whether anything is actually wired to the volume-down event at the GPIO level on the R1 is a separate question — leaving the mapping in is harmless either way.)
- Flags after the keycode: `WAKE` (wakes the screen if Android sees the event with the screen off), `VIRTUAL`, `FUNCTION`, `GESTURE`. `WAKE` is the only one we use here.

Don't add a mapping for any keycode the device doesn't actually emit — Android logs a warning and skips it, but it clutters logcat.

---

## Recipes

### Set the mapping

Pick one of these for `<KEYCODE>`:

| Keycode | Effect | Notes |
|---|---|---|
| `POWER` | Stock — short=lock, long=power menu | Default behavior, no override needed |
| `HOME WAKE` | Returns to launcher from any app | What we use today |
| `APP_SWITCH` | Stock Android: opens recents/overview | **No-op on this build** — CipherOS R1 has no recents/overview implementation |
| `ASSIST` | Triggers the digital assistant | Configure assistant in Settings → Apps → Default apps |
| `NOTIFICATION` | Opens the notification shade | Untested on this device |
| `MENU` | Foreground app's menu key | Most modern apps ignore it; useful if you want your launcher to handle it via `dispatchKeyEvent` |
| `CAMERA` | Launches the camera app | |
| `SEARCH` | Triggers system search | |

Apply:

```bash
adb shell "mkdir -p /data/system/devices/keylayout && \
  printf 'key 116   <KEYCODE>\nkey 114   VOLUME_DOWN\n' > \
  /data/system/devices/keylayout/mtk-kpd.kl"

adb shell "stop; sleep 2; start"   # framework reloads keylayouts
# wait ~20s for SystemUI/launcher to come back up
```

Verify:

```bash
adb shell "cat /data/system/devices/keylayout/mtk-kpd.kl"
adb shell "dumpsys input | grep -A1 mtk-kpd | grep KeyLayout"
# expect: /data/system/devices/keylayout/mtk-kpd.kl
```

### Test the press

```bash
adb shell "logcat -c"
# press the side button
adb shell "dumpsys window | grep mCurrentFocus; dumpsys power | grep mWakefulness"
```

Or watch the raw kernel events:

```bash
adb shell getevent -l /dev/input/event0
# press the button — expect "KEY_POWER DOWN" then "KEY_POWER UP"
# Ctrl+C to stop. The kernel always emits KEY_POWER regardless of mapping —
# the .kl translates it to a different Android keycode downstream.
```

### Revert to stock

```bash
adb shell "rm /data/system/devices/keylayout/mtk-kpd.kl"
adb shell "stop; sleep 2; start"
```

After framework reload, `dumpsys input | grep KeyLayoutFile` for `mtk-kpd` should show `/system/usr/keylayout/Generic.kl`, and the button is back to short=lock / long=power-menu.

### Diagnose "button does nothing"

In order:

1. **Kernel sees the press?**
   ```bash
   adb shell "grep mtk-kpd /proc/interrupts"   # snapshot
   # press the button 3 times
   adb shell "grep mtk-kpd /proc/interrupts"   # should be ~6 higher
   ```
   - **No change** → the screen was off / SoC suspended when you pressed (PMIC bug — see below). Wake the device first (`adb shell input keyevent WAKEUP`, USB attach, or notification).
   - **Change** → kernel is fine, problem is Android-side. Continue.

2. **Android using the keylayout you expect?**
   ```bash
   adb shell "dumpsys input | grep -A1 mtk-kpd | grep KeyLayout"
   ```
   If it points at `/data/...` when you wanted stock, or `/system/...` when you wanted the override — fix the file (or its absence), `stop; start`.

3. **Mapping correct in the file?**
   ```bash
   adb shell "cat /data/system/devices/keylayout/mtk-kpd.kl"
   ```
   Watch out for stray characters / wrong line endings. The file must use plain `LF` newlines.

4. **Foreground app eating the event?**
   `KEYCODE_HOME` / `KEYCODE_POWER` are intercepted by PhoneWindowManager *before* apps. But if you mapped to `KEYCODE_MENU` (or similar app-pass-through) and the foreground app consumes it, you'll see no visible effect. Map to `HOME` if you want a system-level guarantee.

---

## Wake-from-sleep limitation

The side button **cannot wake the device once the SoC is in deep suspend**, regardless of `.kl` mapping or `WAKE` flag. The `mtk-pmic-keys` driver — which is what's supposed to be the wake source — never sees the press. This is a kernel/device-tree-level bug carried over from CipherOS 6.0.

`double_tap_to_wake=1` and `wake_gesture_enabled=1` are set in CipherOS Settings, but the touch driver doesn't honor them in suspend either — empirically, double-tapping the screen during `mWakefulness=Asleep` does nothing.

**Workarounds:**

```bash
# Keep the screen alive whenever plugged in (covers most charging-station use)
adb shell "settings put global stay_on_while_plugged_in 7"

# Long screen-off timeout so it rarely sleeps
adb shell "settings put system screen_off_timeout 1800000"   # 30 min

# Wake the device on demand from PC
adb shell "input keyevent WAKEUP"
```

Plugging/unplugging USB also reliably wakes the device. Notifications wake it too (the modem and various services hold wake-source wakelocks that fire in suspend).

A real fix would require a kernel patch to the device-tree binding for `mtk-pmic-keys` — out of scope for this doc.

---

## Current production setup (as of 2026-04-25)

**Side button**: `key 116 HOME WAKE` in `/data/system/devices/keylayout/mtk-kpd.kl`. Single press from any app returns to the launcher. (Long-press has no special handler — Android's long-press logic for `KEYCODE_HOME` is launcher-specific and not wired.)

**Launcher**: `mylauncher-compose/` (com.r1.launcher v3.0.1+). The activity is `singleTask`, so each side-button press fires `onNewIntent`. The activity tracks `lastHomePressMs` and detects two presses within `HOME_DOUBLE_PRESS_MS` (400 ms): double press toggles the apps panel — opens it from any state, or closes it back to home if it's already open. Single press just brings the launcher forward; if you're already on it nothing visibly happens (you're already home).

To change the double-press timing window: edit `LauncherActivity.kt`'s `HOME_DOUBLE_PRESS_MS` constant. To change what double-press does: replace the `state.openApps()` call inside `onNewIntent`.

To change the keymap target without touching the launcher: just rewrite `/data/system/devices/keylayout/mtk-kpd.kl` (per "Set the mapping" above). E.g. switching to `ASSIST` would make the button trigger the digital-assistant intent instead of going home — the launcher's double-press detection only fires on `HOME` (because it's tied to `onNewIntent`, which only fires on `CATEGORY_HOME` activity launches), so changing the keycode also disables the double-press feature.

---

## What's NOT going to work — don't waste time

- **Mapping `KEYCODE_POWER` to a behavior other than lock/power-menu via Settings.** Android 16's "Power button" settings only expose `power_button_long_press` (default 1 = power menu) and an unsupported `power_button_short_press`. There's no "remap power button to launch X app" path in stock Settings UI.
- **Catching `KEYCODE_POWER` in a foreground app's `dispatchKeyEvent`.** PhoneWindowManager intercepts it before any app sees it. Go through the keylayout instead.
- **Editing `/system/usr/keylayout/Generic.kl` in place.** All writable partitions on this device report `statvfs Available: 0` (free blocks all in ext4 reserved/GDT pool). Even with `mount -o rw,remount /` succeeding, file writes fail with `ENOSPC` despite `df` showing free space. Use `/data/system/devices/keylayout/` — same end effect, no writes to `/system`.
- **Adding the keylayout file before `/data` is decrypted on boot.** Inputflinger re-scans `/data/system/devices/keylayout/` for built-in input devices once `/data` becomes available — empirically it picks up the file there, so you don't need an early-boot path. Confirmed via `dumpsys input` after a real reboot.
- **Expecting the side button to work as a wake source via the `WAKE` flag alone.** The flag does what it says (Android wakes on the keycode if delivered with screen off), but the kernel doesn't deliver the event during suspend. See "Wake-from-sleep limitation" above. A `WAKE` flag is still useful for the brief screen-off-but-not-suspended window after timeout.
- **Mapping to `APP_SWITCH` and expecting recents/overview.** This CipherOS build has no Quickstep / Launcher3 / Recents implementation — only `com.r1.launcher` plus a bare SystemUI. `APP_SWITCH` is a silent no-op.

---

## References

- `dumpsys input` — authoritative on which keylayout file Android is currently using for each input device. Always check this when something's off.
- `getevent -l /dev/input/event0` — raw kernel events. Use to confirm the kernel sees a press at all.
- `/proc/interrupts` (line `mtk-kpd`) — IRQ counter. Use to detect button presses without `getevent`.
- TurboTheTurtle's `rabbit-r1-firmware/docs/cipheros-findings.md` — original investigation on CipherOS 6.0 (where the kpd path was also broken; 7.0 fixed kpd but not pmic).
- AOSP `frameworks/native/services/inputflinger/reader/EventHub.cpp` (`getInputDeviceConfigurationFilePathByName`) — the search-path order quoted above.
- Persistent memory log of this work: `~/.claude/projects/.../memory/project_r1_sidebutton.md`.

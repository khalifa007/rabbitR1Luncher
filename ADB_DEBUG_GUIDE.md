# ADB Debugging Guide — Rabbit R1 Launcher

Quick reference for capturing screenshots, recording video, reading logs, and other
debugging tasks via ADB on the Rabbit R1 (or any Android device).

> [!IMPORTANT]
> All commands use **`;`** to chain (not `&&`), so they work in **PowerShell 5+**.
> If you're in Git Bash or WSL, both `;` and `&&` work fine.

---

## 📸 Screenshots

### Capture + pull in one go
```bash
adb shell screencap -p /sdcard/screenshot.png ; adb pull /sdcard/screenshot.png .
```

### Capture only (stays on device)
```bash
adb shell screencap -p /sdcard/screenshot.png
```

### Pull to a specific folder
```bash
adb pull /sdcard/screenshot.png "C:\Users\new97\Desktop\screenshot.png"
```

### Capture directly to PC (no file on device)
```bash
adb exec-out screencap -p > screenshot.png
```

> [!TIP]
> Use `adb exec-out` to pipe binary data directly — avoids leftover files on the device.

---

## 🎬 Screen Recording

### Record (stops on Ctrl+C or after 3 min max)
```bash
adb shell screenrecord /sdcard/video.mp4
```
Press **Ctrl+C** when done, then pull:
```bash
adb pull /sdcard/video.mp4 .
```

### Record with time limit (seconds)
```bash
adb shell screenrecord --time-limit 15 /sdcard/video.mp4
```

### Record with custom resolution & bitrate
```bash
adb shell screenrecord --size 480x480 --bit-rate 4000000 --time-limit 30 /sdcard/video.mp4
```

### All screenrecord flags

| Flag | Default | Description |
|------|---------|-------------|
| `--time-limit N` | 180 (3 min) | Max recording duration in seconds |
| `--size WxH` | device native | Force output resolution |
| `--bit-rate N` | 4000000 (4 Mbps) | Video bitrate in bits/sec |
| `--verbose` | off | Show more info during recording |
| `--bugreport` | off | Overlay timestamp + framerate |

> [!NOTE]
> The R1 screen is 480×480 round. `screenrecord` captures the full square buffer;
> the round mask is applied by the display hardware, so your video will be square.

---

## 📋 Logcat (Reading Logs)

### Follow all logs (live tail)
```bash
adb logcat
```

### Filter by tag (most useful)
```bash
adb logcat -s LauncherActivity
adb logcat -s OpenClaw
adb logcat -s GatewaySession
adb logcat -s R1Motor
```

### Multiple tags
```bash
adb logcat -s LauncherActivity:V OpenClaw:V GatewaySession:V
```

### Filter with grep (Windows PowerShell)
```powershell
adb logcat | Select-String "BUTTON_1|toggleRecord|RecordStart"
```

### Filter with grep (Git Bash / WSL)
```bash
adb logcat | grep -E "BUTTON_1|toggleRecord|RecordStart"
```

### Save logs to file
```bash
adb logcat -d > logcat.txt
```
(`-d` dumps current buffer and exits; without `-d` it streams continuously)

### Clear log buffer
```bash
adb logcat -c
```

### Log priority levels
| Letter | Level |
|--------|-------|
| V | Verbose |
| D | Debug |
| I | Info |
| W | Warning |
| E | Error |

Example — show only warnings and above:
```bash
adb logcat *:W
```

---

## 🔧 Common Debugging Commands

### Force-stop and relaunch the launcher
```bash
adb shell "am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity"
```

### Install APK (replace existing)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Grant permissions (camera, mic)
```bash
adb shell pm grant com.r1.launcher android.permission.CAMERA
adb shell pm grant com.r1.launcher android.permission.RECORD_AUDIO
```

### Set OpenAI key via broadcast
```bash
adb shell "am broadcast -a com.r1.launcher.SET_OPENAI_KEY --es key 'sk-...'"
```

### Send a key event (simulate button press)
```bash
adb shell input keyevent KEYCODE_DPAD_CENTER   # wheel click
adb shell input keyevent KEYCODE_DPAD_UP        # wheel up
adb shell input keyevent KEYCODE_DPAD_DOWN      # wheel down
adb shell input keyevent KEYCODE_BACK           # back
adb shell input keyevent KEYCODE_HOME           # home
```

### Check what's on screen (dump UI hierarchy)
```bash
adb shell dumpsys activity top | head -20
```

### List installed packages
```bash
adb shell pm list packages | sort
```

### Check if device is connected
```bash
adb devices
```

---

## 🌐 ADB over Wi-Fi (Wireless Debugging)

### Method 1: Any Android version (via USB first)

```bash
# Step 1: Connect device via USB, then enable TCP mode
adb tcpip 5555

# Step 2: Find device IP (look for wlan0 inet addr)
adb shell ip addr show wlan0

# Step 3: Disconnect USB cable, then connect wirelessly
adb connect 192.168.100.68:5555
```

### Verify wireless connection
```bash
adb devices
# Should show: 192.168.1.100:5555    device
```

### Deploy launcher wirelessly
```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell "am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity"
```

### Disconnect
```bash
adb disconnect 192.168.1.100:5555
```

### Switch back to USB mode
```bash
adb usb
```

> [!TIP]
> The device IP can change if it reconnects to Wi-Fi. If `adb connect` fails,
> re-check the IP on the device: **Settings → About Phone → IP Address**, or
> `adb shell ip addr show wlan0` (while still on USB).

> [!WARNING]
> ADB over Wi-Fi has **no authentication** — anyone on the same network can connect.
> Only use on trusted networks. Disable wireless debugging when not in use.

---

## 📶 Wi-Fi via ADB

### Connect to a Wi-Fi network (WPA2)
```bash
adb shell cmd wifi connect-network "MySSID" wpa2 "MyPassword"
```

### Connect to an open network (no password)
```bash
adb shell cmd wifi connect-network "MySSID" open
```

### Disconnect from current network
```bash
adb shell cmd wifi forget-network "MySSID"
```

### Enable / disable Wi-Fi
```bash
adb shell svc wifi enable
adb shell svc wifi disable
```

### Check Wi-Fi status
```bash
adb shell cmd wifi status
```

### Scan for nearby networks
```bash
adb shell cmd wifi start-scan
```
Wait a couple seconds, then list results:
```bash
adb shell cmd wifi list-scan-results
```

### Show saved networks
```bash
adb shell cmd wifi list-networks
```

### Check current connection (IP, SSID, etc.)
```bash
adb shell dumpsys wifi | Select-String "mWifiInfo"
```

> [!NOTE]
> `cmd wifi` commands require **Android 11+** (API 30). On older devices, use
> `adb shell svc wifi enable/disable` for toggling, and the Settings UI for connecting.
> On the R1 with root (carroot), the launcher uses `cmd wifi` directly.

---

## 🐛 Advanced Debugging

### Get a bug report (full system dump)
```bash
adb bugreport bugreport.zip
```

### Watch for ANRs and crashes
```bash
adb logcat -s ActivityManager:E AndroidRuntime:E
```

### Monitor memory usage
```bash
adb shell dumpsys meminfo com.r1.launcher
```

### Check battery info
```bash
adb shell dumpsys battery
```

### Network info
```bash
adb shell dumpsys connectivity
adb shell dumpsys wifi
```

### Pixel-sample a screenshot (verify focus/alpha)
```bash
adb exec-out screencap -p > emu.png
python -c "from PIL import Image; print(Image.open('emu.png').getpixel((240,240)))"
```

---

## 🔄 Typical Debug Workflow

```bash
# 1. Clear logs
adb logcat -c

# 2. Reproduce the bug on device

# 3. Capture screenshot
adb exec-out screencap -p > bug_screenshot.png

# 4. Dump logs
adb logcat -d -s LauncherActivity:V OpenClaw:V > bug_logs.txt

# 5. Or record a video of the issue
adb shell screenrecord --time-limit 30 /sdcard/bug.mp4
# ... reproduce bug, then Ctrl+C ...
adb pull /sdcard/bug.mp4 .
```

---

## 📱 R1-Specific Notes

- **Round display**: The R1 has a 480×480 round screen. Screenshots/videos capture the full square; corners appear black on the device but filled in captures.
- **Side button**: Mapped to `KEYCODE_BUTTON_1` via keylayout. Use `adb logcat -s LauncherActivity | grep BUTTON_1` to trace presses.
- **Root shell**: The launcher talks to `carroot` on `127.0.0.1:1337` for root commands. Not available via adb directly.
- **Wi-Fi status**: `adb shell cmd wifi status` (requires shell or root).

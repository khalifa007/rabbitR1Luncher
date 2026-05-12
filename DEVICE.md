# DEVICE.md — Rabbit R1 hardware & software facts

## Hardware

- **Chipset**: MediaTek MT6765 (Helio P35 / G36 family)
- **ABI**: `arm64-v8a` (only — `abilist` confirms no 32-bit fallback)
- **Display**: ~2.88" touchscreen, density 320 dpi (xhdpi bucket), physical density also 320
- **Input**: touchscreen, scroll wheel, PTT (side button)
- **Networking**: WiFi 2.4/5 GHz, LTE (Spreadtrum modem — separate SoC, shows as `VID_18D1&PID_4EE7` when DIAG ports expose)

## Software

- **OS**: rabbitOS (AOSP-13 base + Rabbit launcher)
- **Model prop**: `ro.product.model = r1`
- **Android version**: `ro.build.version.release = 13`
- **Bootloader**: flash-unlocked (user already unlocked, OEM unlock permitted)
- **SELinux**: enforcing, but carroot runs under context `u:r:rootkit:s0` (kernel-level, bypasses normal restrictions)

## USB composite modes observed

| PID (hex) | Mode | Who picks it | Notes |
|---|---|---|---|
| `0E8D:2000` | MediaTek PreLoader (BROM) | powered-on while carroot fires | ~3 s window. WebUSB entry point for carroot |
| `0E8D:2304` | rabbitOS default `cua` | stock boot, `sys.usb.state=cua` | HID keyboard + HID mouse + WebUSB Data Channel. No ADB |
| `0E8D:2303` | HID-only `cua` | boot with altered `persist.sys.usb.config` | 2× HID interfaces. Still no ADB. `webusbd` re-forces this |
| `0E8D:201C` | MediaTek Android ADB Interface | should appear with `sys.usb.config=adb` | Never seen on stock rabbitOS in our session — webusbd blocks |
| `18D1:0FFF` | Android Bootloader + ADB (fastbootd) | booted into bootloader | Google VID. Use `fastboot devices` here |
| `18D1:4EE7` | Spreadtrum DIAG / modem composite | modem debug, if ever | Not used for ADB |

## Processes of interest (from `ps -A`)

| PID range (varies) | Process | Role |
|---|---|---|
| `tech.rabbit.judy` | Rabbit's watchdog — re-disables AOSP components, enforces stock behavior. **Kill and disable.** |
| `tech.rabbit.r1launcher.r1` | Stock launcher. Safe to swap via `cmd package set-home-activity`; do not uninstall |
| `tech.rabbit.r1systemupdater` | OTA updater. Disable when modifying things, otherwise an update may revert work |
| `tech.rabbit.r1browser` | WebView-based browser, stock |
| `webusbd` | **Custom Rabbit daemon** — manages the `cua` USB composite. Fights any attempt to change `sys.usb.config` to `adb`. system user |
| `android.hardware.usb@1.2-service-mediatekv2` | MTK USB HIDL. Load-bearing. **Do not kill.** |

## Packages disabled by default on stock rabbitOS

Confirmed disabled via `pm list packages -d`:
- `com.android.settings` (and individual activities are also component-disabled — re-enabling the package alone is not enough)
- `com.android.providers.settings`
- `com.android.providers.media.module`
- `com.android.providers.contacts`
- `com.android.providers.telephony`
- `com.mediatek.MtkSettingsResOverlay`
- `com.mediatek.SettingsProviderResOverlay`

We re-enabled the non-overlay ones via `pm enable`. Overlays left alone.

## Networking defaults

- DHCP from whatever AP the user joined via rabbitOS setup
- `wlan0` is the primary interface
- IPv6 gets a SLAAC global + privacy temp; `adbd` binds IPv6 only when on TCP (see `CLAUDE.md` failed paths)

## Reference — known community resources

- **carroot** (tethered root via MTK BROM): https://retr0.id/stuff/r1_jailbreak/
- **Boot notes / WebUSB scripts**: https://github.com/DavidBuchanan314/rabbit_r1_boot_notes
- **Blog context**: https://www.da.vidbuchanan.co.uk/blog/r1-jailbreak.html
- **r1_escape** (fastboot-flashed AOSP-13 replacement): https://github.com/RabbitHoleEscapeR1/r1_escape
- **CipherOS** / Android 16 ROM (via rabbitmods): https://www.rabbitmods.net/flashing/
- **awesome-rabbit-r1** (community resource index): https://github.com/sayhiben/awesome-rabbit-r1
- **Bootloader unlock tutorial (XDA)**: https://xdaforums.com/t/how-to-unlock-rabbit-r1-bootloader-tutorial.4676024/

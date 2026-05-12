# rabbitR1Luncher

Compose-based HOME launcher for the **Rabbit R1** (480×480 round, MT6765). Single Activity, no fragments, no nav component. Package `com.r1.launcher`.

[![ROM: CarrotOS](https://img.shields.io/badge/rom-CarrotOS-FF6A00?style=flat-square&logo=android&logoColor=white)](https://github.com/khalifa007/carrotOS)

---

## Ships in CarrotOS

This launcher is the single home app baked into **[CarrotOS](https://github.com/khalifa007/carrotOS)** — a custom LineageOS 21 GSI (Android 14) for the R1. It replaces Launcher3 at `/system/app/R1Launcher/` and is the only chrome on the device: no status bar, no nav bar, no lock screen.

To flash CarrotOS on your R1, head to **[CarrotOS Releases](https://github.com/khalifa007/carrotOS/releases/latest)**.

---

## Build

```bash
bash bootstrap.sh           # one-time: fetch Gradle 8.9, generate wrapper
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

R8-minified, platform-key signed, single dex (~3.5 MB).

Bump `versionCode` in `app/build.gradle.kts` before every install — the system-app copy at `/system/app/R1Launcher/` is the floor; user installs at `/data/app/` only override when strictly higher. `INSTALL_FAILED_VERSION_DOWNGRADE` means you forgot to bump.

---

## Sideload onto an existing CarrotOS device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

---

## Stack

- AGP 8.7.2 + Gradle 8.9
- Kotlin 2.0.21 (`kotlin.plugin.compose`, `kotlin.plugin.serialization`)
- Compose BOM 2024.10.01
- minSdk 23 / targetSdk 33 / compileSdk 34, Java 17

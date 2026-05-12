# CipherOS R1 Custom ROM Build Guide

## Goal
Build a custom CipherOS ROM for the Rabbit R1 with:
- Our custom launcher (com.r1.launcher) as the ONLY launcher
- All bloat apps removed (Jelly, Eleven, Etar, Email, Recorder, etc.)
- R1 hardware support (camera motor, scroll wheel, etc.)
- Distributable ZIP that other R1 users can flash

---

## Source Repos

| Component | Repo | Branch |
|-----------|------|--------|
| CipherOS Manifest | https://github.com/CipherOS/android_manifest | sixteen-qpr2 |
| CipherOS Vendor | https://github.com/CipherOS/android_vendor_cipher | sixteen-qpr2 |
| R1 Device Tree | https://github.com/techyminati/android_device_rabbit_r1 | sixteen-qpr2 |
| R1 Kernel | https://github.com/techyminati/alps-4.19 | alps-mp-t0.mp1.tc16sp-pr1 |
| R1 Vendor Blobs | https://github.com/techyminati/proprietary_vendor_rabbit_r1 | sixteen-qpr2 |
| Our Launcher | c:\Users\new97\Desktop\r1\mylauncher-compose | main |


R1 Maintainer: **techyminati** (Aryan Sinha)

---

## Build Environment

- **OS**: WSL2 Ubuntu 24.04 on Windows (stored on F:\WSL\Ubuntu)
- **RAM**: 32 GB
- **Disk**: F: drive, ~450 GB free
- **Source tree**: ~/cipher

---

## Step-by-Step Commands

### STEP 1: Install dependencies
```bash
sudo apt update
sudo apt install -y \
    bc bison build-essential ccache curl flex \
    g++-multilib gcc-multilib git git-lfs gnupg \
    gperf imagemagick lib32readline-dev lib32z1-dev \
    libelf-dev liblz4-tool libsdl1.2-dev libssl-dev \
    libxml2 libxml2-utils lzop pngcrush rsync \
    schedtool squashfs-tools xsltproc zip zlib1g-dev \
    python3 python3-pip openjdk-17-jdk \
    libncurses-dev repo fontconfig \
    python-is-python3 wget unzip
```

### STEP 2: Configure git & ccache
```bash
git config --global user.name "R1 Builder"
git config --global user.email "r1builder@localhost"
export USE_CCACHE=1
export CCACHE_EXEC=/usr/bin/ccache
ccache -M 50G
echo 'export USE_CCACHE=1' >> ~/.bashrc
echo 'export CCACHE_EXEC=/usr/bin/ccache' >> ~/.bashrc
```

### STEP 3: Sync CipherOS source (3-6 hours, can game during this)
```bash
cd ~/cipher
repo init -u https://github.com/CipherOS/android_manifest -b sixteen-qpr2
repo sync -c -j4 --force-sync --no-clone-bundle --no-tags
```

Note: Branch was changed from `fifteen` to `sixteen-qpr2` because
CipherOS deleted the fifteen branch. sixteen-qpr2 = CipherOS 7.0 ALHENA (Android 16).

### STEP 4: Clone R1 device tree + kernel
```bash
cd ~/cipher

# Device tree
git clone https://github.com/techyminati/android_device_rabbit_r1 \
    device/rabbit/r1 -b sixteen-qpr2

# Kernel
git clone https://github.com/techyminati/alps-4.19 \
    kernel/mediatek/alps-4.19 -b alps-mp-t0.mp1.tc16sp-pr1

# Vendor blobs
git clone https://github.com/techyminati/proprietary_vendor_rabbit_r1 \
    vendor/rabbit/r1 -b sixteen-qpr2
```

### STEP 5: Add our launcher APK as prebuilt
```bash
# Create directory
mkdir -p device/rabbit/r1/prebuilt/app/R1Launcher

# Copy APK from Windows
cp /mnt/c/Users/new97/Desktop/r1/mylauncher-compose/app/build/outputs/apk/debug/app-debug.apk \
    device/rabbit/r1/prebuilt/app/R1Launcher/R1Launcher.apk
```

Create file `device/rabbit/r1/prebuilt/app/R1Launcher/Android.mk`:
```makefile
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := R1Launcher
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := R1Launcher.apk
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRIVILEGED_MODULE := true
LOCAL_OVERRIDES_PACKAGES := TrebuchetQuickStep TrebuchetQuickStepGo Launcher3 Launcher3QuickStep

include $(BUILD_PREBUILT)
```

Add to `device/rabbit/r1/cipher_r1.mk`:
```makefile
# R1 Custom Launcher
PRODUCT_PACKAGES += \
    R1Launcher
```

### STEP 6: Strip bloat apps
```bash
# Comment out unwanted apps from vendor config
sed -i 's/^\(.*\bEmail\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bExchange2\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bBackgrounds\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bEleven\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bEtar\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bJelly\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bOmniStyle\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*\bAudioFX\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*TrebuchetQuickStep\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk
sed -i 's/^\(.*TrebuchetQuickStepGo\b\)/# REMOVED: \1/' vendor/cipher/config/common_mobile.mk

# Remove Recorder
sed -i 's/^\(.*\bRecorder\b\)/# REMOVED: \1/' vendor/cipher/config/common_full.mk
```

### STEP 7: Build the ROM (4-8 hours, run before bed!)
```bash
cd ~/cipher
source build/envsetup.sh
lunch cipher_r1-userdebug
mka bacon -j6 2>&1 | tee build.log
```

### STEP 8: Get the output
```bash
# Find the ZIP
ls -lh out/target/product/r1/CipherOS-*.zip

# Copy to Windows Desktop
cp out/target/product/r1/CipherOS-*.zip /mnt/c/Users/new97/Desktop/
```

### STEP 9: Flash on R1
```bash
# Boot R1 into fastboot mode
adb reboot fastboot

# Flash (from Windows)
fastboot update CipherOS-*.zip -w
```

---

## Scripts on Windows

Two scripts saved at `C:\Users\new97\Desktop\r1\`:

### build_cipheros.sh (Step 1 - Sync & Setup)
Does steps 1-6 automatically. Run with:
```bash
cp /mnt/c/Users/new97/Desktop/r1/build_cipheros.sh ~/build_cipheros.sh
chmod +x ~/build_cipheros.sh
~/build_cipheros.sh
```

### build_rom.sh (Step 2 - Build)
Does step 7. Run when ready:
```bash
cp /mnt/c/Users/new97/Desktop/r1/build_rom.sh ~/build_rom.sh
chmod +x ~/build_rom.sh
~/build_rom.sh
```

---

## Troubleshooting

### "Unable to locate package libncurses5"
Ubuntu 24.04 removed ncurses5. Use `libncurses-dev` instead.

### "Unable to remote fetch project platform_frameworks_base"
The `fifteen` branch was deleted from CipherOS. Re-init with `sixteen-qpr2`:
```bash
cd ~/cipher
repo init -u https://github.com/CipherOS/android_manifest -b sixteen-qpr2
repo sync -c -j4 --force-sync --no-clone-bundle --no-tags
```

### "Your local changes would be overwritten by checkout"
```bash
cd ~/cipher/.repo/manifests
git checkout -- .
cd ~/cipher
repo init -u https://github.com/CipherOS/android_manifest -b sixteen-qpr2
repo sync -c -j4 --force-sync --no-clone-bundle --no-tags
```

### Build gets killed (SIGKILL / OOM)
Reduce threads: `mka bacon -j4` or even `-j2`

### PC crashes during build
Re-enable pagefile (admin PowerShell on Windows):
```powershell
$cs = Get-CimInstance Win32_ComputerSystem
$cs | Set-CimInstance -Property @{AutomaticManagedPagefile=$true}
```

---

## Key Facts
- Package ID: com.r1.launcher
- Signed with: debug.keystore (PRESIGNED in build)
- R1 SoC: MediaTek MT6765 (Helio P35)
- CipherOS version: 7.0 ALHENA
- Android version: 16 (branch sixteen-qpr2)
- Build target: cipher_r1-userdebug
- R1 maintainer: techyminati (Aryan Sinha)

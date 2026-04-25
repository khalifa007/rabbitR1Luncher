package com.r1.launcher

/**
 * Wheel / activate routing. Lives outside LauncherState so the state container
 * stays focused on "what to render"; this file handles "what button does what".
 *
 * `host` provides the side-effecting pieces (tone feedback, launching apps,
 * triggering updater/store, opening settings, rebooting). The state machine
 * itself is pure: panel + focus indices only.
 */
interface LauncherHost {
    fun launchApp(idx: Int)
    fun openWifiSettings()
    fun requestReboot(powerOff: Boolean)
    fun checkForUpdate()
    fun storeActivate(entry: AppStore.Entry)
    fun detailOpen()
    fun detailUninstall()
    fun setBrightness(level: Int)
    fun setVolume(level: Int)
    fun openAirplaneSettings()
    fun openDateSettings()
    fun lockScreen()
    fun navTone()
    fun selectTone()
    fun backTone()
    fun popTone()
    fun openClawScanned(raw: String)
    fun openClawToggleRecord()
    fun openClawSendText(text: String)
    fun openClawScrollUp()
    fun openClawScrollDown()
    fun openClawCloseSession()
    fun audioTestActivate()
    fun audioTestCycleSource(delta: Int)
    fun audioTestStop()
}

fun LauncherState.wheelUp(host: LauncherHost) {
    when (panel) {
        Panel.DETAIL -> {
            val prev = detailFocus
            detailFocus = (detailFocus - 1).coerceAtLeast(0)
            if (prev != detailFocus) host.navTone()
        }
        Panel.STORE -> {
            if (storeFocus <= 0) {
                back(); host.backTone()
            } else {
                storeFocus--; host.navTone()
            }
        }
        Panel.APPS -> {
            if (appsFocus <= 0) {
                back(); host.backTone()
            } else {
                appsFocus--; host.navTone()
            }
        }
        Panel.SHEET -> {
            val prev = sheetFocus
            sheetFocus = (sheetFocus - 1).coerceAtLeast(0)
            if (prev != sheetFocus) host.navTone()
        }
        Panel.SETTINGS -> {
            if (settingsFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsFocus--; host.navTone()
            }
        }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel - 16).coerceAtLeast(1)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel - 1).coerceAtLeast(0)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollUp(); host.navTone() }
        Panel.AUDIO_TEST -> { host.audioTestCycleSource(-1); host.navTone() }
        Panel.HOME -> {
            val prev = homeFocus
            homeFocus = (homeFocus - 1).coerceAtLeast(0)
            if (prev != homeFocus) host.navTone()
        }
    }
}

fun LauncherState.wheelDown(host: LauncherHost) {
    when (panel) {
        Panel.DETAIL -> {
            val prev = detailFocus
            detailFocus = (detailFocus + 1).coerceAtMost(2)
            if (prev != detailFocus) host.navTone()
        }
        Panel.STORE -> {
            val max = (storeEntries.size - 1).coerceAtLeast(0)
            val prev = storeFocus
            storeFocus = (storeFocus + 1).coerceAtMost(max)
            if (prev != storeFocus) host.navTone()
        }
        Panel.APPS -> {
            val max = (apps.size - 1).coerceAtLeast(0)
            val prev = appsFocus
            appsFocus = (appsFocus + 1).coerceAtMost(max)
            if (prev != appsFocus) host.navTone()
        }
        Panel.SHEET -> {
            val prev = sheetFocus
            sheetFocus = (sheetFocus + 1).coerceAtMost(3)
            if (prev != sheetFocus) host.navTone()
        }
        Panel.SETTINGS -> {
            val prev = settingsFocus
            settingsFocus = (settingsFocus + 1).coerceAtMost(3)
            if (prev != settingsFocus) host.navTone()
        }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel + 16).coerceAtMost(255)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel + 1).coerceAtMost(volumeMax)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollDown(); host.navTone() }
        Panel.AUDIO_TEST -> { host.audioTestCycleSource(1); host.navTone() }
        Panel.HOME -> {
            openApps()
            host.selectTone()
        }
    }
}

fun LauncherState.activate(host: LauncherHost) {
    when (panel) {
        Panel.DETAIL -> when (detailFocus) {
            0 -> { back(); host.backTone() }
            1 -> host.detailOpen()
            2 -> host.detailUninstall()
        }
        Panel.STORE -> {
            val entry = storeEntries.getOrNull(storeFocus) ?: return
            host.storeActivate(entry)
        }
        Panel.APPS -> host.launchApp(appsFocus)
        Panel.SHEET -> when (sheetFocus) {
            0 -> host.openWifiSettings()
            1 -> host.requestReboot(powerOff = false)
            2 -> host.requestReboot(powerOff = true)
            3 -> host.checkForUpdate()
        }
        Panel.SETTINGS -> when (settingsFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openBrightness(); host.selectTone() }
            2 -> { openVolume(); host.selectTone() }
            3 -> { showDebugBar = !showDebugBar; host.popTone() }
        }
        Panel.BRIGHTNESS, Panel.VOLUME -> { back(); host.selectTone() }
        Panel.OPENCLAW_QR -> { /* camera scan auto-completes; activate is no-op */ }
        Panel.OPENCLAW_CHAT -> { host.openClawToggleRecord(); host.popTone() }
        Panel.AUDIO_TEST -> { host.audioTestActivate(); host.popTone() }
        Panel.HOME -> when (homeFocus) {
            0 -> { openSheet(); host.selectTone() }
            1 -> { openStore(); host.selectTone() }
            2 -> { openApps(); host.selectTone() }
        }
    }
}

fun LauncherState.backPressed(host: LauncherHost) {
    if (panel == Panel.OPENCLAW_CHAT || panel == Panel.OPENCLAW_QR) {
        host.openClawCloseSession()
        back(); host.backTone()
        return
    }
    if (panel == Panel.AUDIO_TEST) {
        host.audioTestStop()
        back(); host.backTone()
        return
    }
    if (panel != Panel.HOME) {
        back(); host.backTone()
    }
}

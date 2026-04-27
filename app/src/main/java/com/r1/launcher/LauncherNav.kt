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
    fun toggleWifi(enable: Boolean)
    fun toggleCellular(enable: Boolean)
    fun startWifiScan()
    fun connectToWifi(ssid: String, pass: String)
    fun openAirplaneSettings()
    fun openDateSettings()
    fun lockScreen()
    fun navTone()
    fun selectTone()
    fun backTone()
    fun popTone()
    fun openClawScanned(raw: String)
    fun openClawToggleRecord()
    fun openClawRecordStart()
    fun openClawRecordStop()
    fun openClawSendText(text: String)
    fun openClawOpenTalk()
    fun openClawSetSpeaker(enabled: Boolean)
    fun openClawScrollUp()
    fun openClawScrollDown()
    fun openClawCloseSession()
    fun openClawPasteOpenaiKey()
    fun openClawClearOpenaiKey()
    fun openClawSaveOpenaiKey(key: String)
    fun openClawSettingsRowActivate(idx: Int)
    fun openClawClearHistory()
    fun openClawDisconnect()
    fun openClawSetFontSize(size: Int)
    fun openClawSwitchSession(key: String)
    fun openClawRefreshSessions()
    fun openClawSessionsRowActivate(idx: Int)
    fun openClawOpenCameraAsk()
    fun openClawCameraCaptured(jpegBytes: ByteArray)
    fun openClawCameraRetake()
    fun openClawCameraSend(prompt: String)
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
        Panel.NETWORK -> {
            if (networkFocus <= 0) {
                back(); host.backTone()
            } else {
                networkFocus--; host.navTone()
            }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiScanFocus--; host.navTone()
            }
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
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
        Panel.OPENCLAW_TALK -> { host.openClawSetSpeaker(!chatTtsEnabled); host.navTone() }
        Panel.OPENCLAW_CANVAS -> { canvasScrollIndex++; host.navTone() }
        Panel.OPENCLAW_CAMERA -> { /* camera panel owns touch/keyboard controls */ }
        Panel.OPENCLAW_SETTINGS -> {
            if (openClawSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSettingsFocus--; host.navTone()
            }
        }
        Panel.OPENCLAW_SESSIONS -> {
            if (openClawSessionsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSessionsFocus--; host.navTone()
            }
        }
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
            settingsFocus = (settingsFocus + 1).coerceAtMost(5)
            if (prev != settingsFocus) host.navTone()
        }
        Panel.NETWORK -> {
            val prev = networkFocus
            networkFocus = (networkFocus + 1).coerceAtMost(3) // 4 rows: back, wifi, cellular, connect
            if (prev != networkFocus) host.navTone()
        }
        Panel.WIFI_SCAN -> {
            val max = (wifiScanResults.size).coerceAtLeast(0) // back + N items
            val prev = wifiScanFocus
            wifiScanFocus = (wifiScanFocus + 1).coerceAtMost(max)
            if (prev != wifiScanFocus) host.navTone()
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
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
        Panel.OPENCLAW_TALK -> { host.openClawSetSpeaker(!chatTtsEnabled); host.navTone() }
        Panel.OPENCLAW_CANVAS -> { canvasScrollIndex--; host.navTone() }
        Panel.OPENCLAW_CAMERA -> { /* camera panel owns touch/keyboard controls */ }
        Panel.OPENCLAW_SETTINGS -> {
            val prev = openClawSettingsFocus
            openClawSettingsFocus = (openClawSettingsFocus + 1).coerceAtMost(5)
            if (prev != openClawSettingsFocus) host.navTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            // Row layout matches OpenClawSessionsPanel:
            //   0           "< back"
            //   1..choices  one row per resolveSessionChoices entry
            //              (or a single placeholder row when choices is empty)
            //   choices+1   "refresh"
            val choiceCount = com.r1.launcher.openclaw.resolveSessionChoices(
                currentSessionKey = selectedSessionKey,
                sessions = chatSessions.toList(),
                mainSessionKey = mainSessionKey,
            ).size.coerceAtLeast(1)
            val max = 1 + choiceCount // back + choices + refresh = 2+choices, last index = 1+choices
            val prev = openClawSessionsFocus
            openClawSessionsFocus = (openClawSessionsFocus + 1).coerceAtMost(max)
            if (prev != openClawSessionsFocus) host.navTone()
        }
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
            1 -> { openNetwork(); host.selectTone() }
            2 -> { openBrightness(); host.selectTone() }
            3 -> { openVolume(); host.selectTone() }
            4 -> { host.checkForUpdate(); host.selectTone() }
            5 -> { /* Info row */ }
        }
        Panel.NETWORK -> when (networkFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifi(!wifiEnabled); host.popTone() }
            2 -> { host.toggleCellular(!cellularOn); host.popTone() }
            3 -> { host.startWifiScan(); openWifiScan(); host.selectTone() }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus == 0) {
                back(); host.backTone()
            } else {
                val ssid = wifiScanResults.getOrNull(wifiScanFocus - 1)
                if (ssid != null) {
                    openWifiPassword(ssid)
                    host.selectTone()
                }
            }
        }
        Panel.WIFI_PASSWORD -> { /* RetroKeyboard handles input */ }
        Panel.BRIGHTNESS, Panel.VOLUME -> { back(); host.selectTone() }
        Panel.OPENCLAW_QR -> { /* camera scan auto-completes; activate is no-op */ }
        Panel.OPENCLAW_CHAT -> { host.openClawToggleRecord(); host.popTone() }
        Panel.OPENCLAW_TALK -> { host.openClawToggleRecord(); host.popTone() }
        Panel.OPENCLAW_CANVAS -> { back(); host.backTone() }
        Panel.OPENCLAW_CAMERA -> { /* touch-first capture/ask surface */ }
        Panel.OPENCLAW_SETTINGS -> {
            host.openClawSettingsRowActivate(openClawSettingsFocus)
            host.selectTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            host.openClawSessionsRowActivate(openClawSessionsFocus)
            host.selectTone()
        }
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
    if (panel == Panel.OPENCLAW_TALK || panel == Panel.OPENCLAW_CANVAS || panel == Panel.OPENCLAW_CAMERA) {
        back(); host.backTone()
        return
    }
    if (panel != Panel.HOME) {
        back(); host.backTone()
    }
}

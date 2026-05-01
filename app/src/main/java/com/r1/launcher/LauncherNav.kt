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
    fun checkForUpdate()
    fun setBrightness(level: Int)
    fun setVolume(level: Int)
    fun toggleWifi(enable: Boolean)
    fun toggleCellular(enable: Boolean)
    fun toggleBluetooth(enable: Boolean)
    fun factoryReset()
    fun startWifiScan()
    fun connectToWifi(ssid: String, pass: String)
    fun toggleWifiShare(enable: Boolean)
    fun wifiShareSaveEdit()
    fun wifiShareCycleTimer()
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
    fun openClawCameraMotorNudge(delta: Int)
    fun loadSmsConversations()
    fun openSmsThread(address: String, displayName: String)
    fun toggleWebServer(enable: Boolean)
}

fun LauncherState.wheelUp(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            if (appsFocus <= 0) {
                back(); host.backTone()
            } else {
                appsFocus--; host.navTone()
            }
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
        Panel.FACTORY_CONFIRM -> {
            if (factoryConfirmFocus <= 0) {
                back(); host.backTone()
            } else {
                factoryConfirmFocus--; host.navTone()
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
        Panel.WIFI_SHARE -> {
            if (wifiShareFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiShareFocus--; host.navTone()
            }
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
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
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(-15) }
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
        Panel.MESSAGES -> {
            if (messagesFocus <= 0) {
                back(); host.backTone()
            } else {
                messagesFocus--; host.navTone()
            }
        }
        Panel.MESSAGES_THREAD -> {
            if (smsThreadFocus <= 0) {
                back(); host.backTone()
            } else {
                smsThreadFocus--; host.navTone()
            }
        }
        Panel.HOME -> { /* clock screen — no list to scroll */ }
    }
}

fun LauncherState.wheelDown(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            val max = (apps.size - 1).coerceAtLeast(0)
            val prev = appsFocus
            appsFocus = (appsFocus + 1).coerceAtMost(max)
            if (prev != appsFocus) host.navTone()
        }
        Panel.SETTINGS -> {
            val prev = settingsFocus
            settingsFocus = (settingsFocus + 1).coerceAtMost(6) // back, network, brightness, volume, updates, factory reset, about
            if (prev != settingsFocus) host.navTone()
        }
        Panel.NETWORK -> {
            val prev = networkFocus
            networkFocus = (networkFocus + 1).coerceAtMost(6) // back, wifi, cellular, bluetooth, share, remote, scan
            if (prev != networkFocus) host.navTone()
        }
        Panel.FACTORY_CONFIRM -> {
            val prev = factoryConfirmFocus
            factoryConfirmFocus = (factoryConfirmFocus + 1).coerceAtMost(1)
            if (prev != factoryConfirmFocus) host.navTone()
        }
        Panel.WIFI_SCAN -> {
            val max = (wifiScanResults.size).coerceAtLeast(0) // back + N items
            val prev = wifiScanFocus
            wifiScanFocus = (wifiScanFocus + 1).coerceAtMost(max)
            if (prev != wifiScanFocus) host.navTone()
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
        Panel.WIFI_SHARE -> {
            val prev = wifiShareFocus
            wifiShareFocus = (wifiShareFocus + 1).coerceAtMost(5) // back, enable, name, password, connected, auto-off
            if (prev != wifiShareFocus) host.navTone()
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
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
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(+15) }
        Panel.OPENCLAW_SETTINGS -> {
            val prev = openClawSettingsFocus
            openClawSettingsFocus = (openClawSettingsFocus + 1).coerceAtMost(6)
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
        Panel.MESSAGES -> {
            // Row 0 = back, then one row per conversation. Empty list still has
            // a single "no messages" row, but it isn't selectable — keep focus on back.
            val maxRow = smsConversations.size // back + N convs; last index = N
            val prev = messagesFocus
            messagesFocus = (messagesFocus + 1).coerceAtMost(maxRow)
            if (prev != messagesFocus) host.navTone()
        }
        Panel.MESSAGES_THREAD -> {
            val maxRow = smsThreadMessages.size // back + N items; last index = N
            val prev = smsThreadFocus
            smsThreadFocus = (smsThreadFocus + 1).coerceAtMost(maxRow)
            if (prev != smsThreadFocus) host.navTone()
        }
        Panel.HOME -> {
            openApps()
            host.selectTone()
        }
    }
}

fun LauncherState.activate(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> host.launchApp(appsFocus)
        Panel.SETTINGS -> when (settingsFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openNetwork(); host.selectTone() }
            2 -> { openBrightness(); host.selectTone() }
            3 -> { openVolume(); host.selectTone() }
            4 -> { host.checkForUpdate(); host.selectTone() }
            5 -> { openFactoryConfirm(); host.selectTone() }
            6 -> { /* Info row */ }
        }
        Panel.NETWORK -> when (networkFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifi(!wifiEnabled); host.popTone() }
            2 -> { host.toggleCellular(!cellularOn); host.popTone() }
            3 -> { host.toggleBluetooth(!btOn); host.popTone() }
            4 -> { openWifiShare(); host.selectTone() }
            5 -> { host.toggleWebServer(!webServerEnabled); host.popTone() }
            6 -> { host.startWifiScan(); openWifiScan(); host.selectTone() }
        }
        Panel.WIFI_SHARE -> when (wifiShareFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifiShare(!wifiShareEnabled); host.popTone() }
            2 -> { openWifiShareEdit(WifiShareEditTarget.SSID); host.selectTone() }
            3 -> { openWifiShareEdit(WifiShareEditTarget.PASSWORD); host.selectTone() }
            4 -> {
                if (wifiShareConnectedClients.isNotEmpty()) {
                    wifiShareClientsExpanded = !wifiShareClientsExpanded
                    host.popTone()
                }
            }
            5 -> { host.wifiShareCycleTimer(); host.popTone() }
        }
        Panel.WIFI_SHARE_EDIT -> { /* RetroKeyboard handles input */ }
        Panel.FACTORY_CONFIRM -> when (factoryConfirmFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.factoryReset(); host.selectTone() }
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
        Panel.MESSAGES -> {
            if (messagesFocus == 0) {
                back(); host.backTone()
            } else {
                val conv = smsConversations.getOrNull(messagesFocus - 1)
                if (conv != null) {
                    host.openSmsThread(conv.address, conv.displayName)
                    host.selectTone()
                }
            }
        }
        Panel.MESSAGES_THREAD -> {
            // Only the back row at idx 0 is actionable; bubbles are read-only.
            if (smsThreadFocus == 0) { back(); host.backTone() }
        }
        // Wheel press on the clock screen jumps straight to the apps grid.
        Panel.HOME -> { openApps(); host.selectTone() }
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

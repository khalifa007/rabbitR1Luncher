package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.locale.LocalePrefs

/**
 * Root composable — FrameLayout-style z-stack:
 *   wallpaper + panel-under-focus → topbar → debug key
 *
 * Topbar hides while apps panel is open (it draws its own header).
 */
@Composable
fun LauncherRoot(
    state: LauncherState,
    host: LauncherHost,
) {
    val colors = LocalR1Colors.current
    val ctx = LocalContext.current
    val direction = if (LocalePrefs.isRtl(LocalePrefs.get(ctx).language)) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Base: home screen. Visible whenever nothing else is stacked over it.
        HomeScreen(state = state)

        OnboardingPanel(
            state = state,
            onRowClick = { idx ->
                when (state.onboardingStep) {
                    0 -> {
                        // Language picker: idx is the index into LocalePrefs.SUPPORTED
                        val lang = LocalePrefs.SUPPORTED.getOrNull(idx)
                        if (lang != null) {
                            host.setLanguage(lang.code) // recreates activity; onCreate jumps to step 1
                            host.selectTone()
                        }
                    }
                    1 -> { state.advanceOnboarding(); host.selectTone() }
                    2 -> when (idx) {
                        0 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
                        1 -> if (state.simPresent) { state.advanceOnboarding(); host.selectTone() } else host.backTone()
                        2 -> { state.advanceOnboarding(); host.selectTone() }
                    }
                    3 -> when (idx) {
                        0 -> { host.checkForUpdate(); host.selectTone() }
                        1 -> { state.advanceOnboarding(); host.selectTone() }
                        2 -> { state.advanceOnboarding(); host.selectTone() }
                    }
                    else -> { host.onOnboardingDone(); host.selectTone() }
                }
            },
        )

        AppsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onAppClick = { idx -> host.launchApp(idx) },
        )

        SettingsPanel(
            state = state,
            onRowClick = { idx ->
                // Order must match the rows list in SettingsPanel.kt:
                //   network, display, sound, voice, device, about
                // (language was promoted into device in v3.32)
                // and the wheel-activate dispatcher in LauncherNav.kt.
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openNetwork(); host.selectTone() }
                    2 -> { state.openSettingsDisplay(); host.selectTone() }
                    3 -> { state.openSettingsSound(); host.selectTone() }
                    4 -> { state.openSettingsVoice(); host.selectTone() }
                    5 -> { state.openSettingsDevice(); host.selectTone() }
                    6 -> { state.openSettingsAbout(); host.selectTone() }
                }
            },
        )

        SettingsDisplayPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openBrightness(); host.selectTone() }
                }
            },
        )

        SettingsSoundPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openUiVolume(); host.selectTone() }
                    2 -> { state.openVolume(); host.selectTone() }
                }
            },
        )

        SettingsDevicePanel(
            state = state,
            onRowClick = { idx ->
                // Order must match SettingsDevicePanel rows + LauncherNav dispatcher:
                //   updates, language, restart, power off, factory reset
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.checkForUpdate(); host.selectTone() }
                    2 -> { state.openSettingsLanguage(); host.selectTone() }
                    3 -> { host.rebootDevice(); host.selectTone() }
                    4 -> { host.powerOffDevice(); host.selectTone() }
                    5 -> { state.openFactoryConfirm(); host.selectTone() }
                }
            },
        )

        SettingsAboutPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                }
            },
        )

        SettingsVoicePanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSaveKey = { k -> host.voiceSaveKey(k) },
            onPasteKey = { host.voicePasteKeyFromClipboard() },
            onClear = { host.voiceClearKey() },
            onRowClick = { idx -> host.voiceSettingsRowActivate(idx) },
        )

        SettingsLanguagePanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val lang = LocalePrefs.SUPPORTED.getOrNull(idx - 1)
                    if (lang != null) {
                        host.setLanguage(lang.code) // recreates activity
                        host.selectTone()
                    }
                }
            },
        )

        NetworkPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleWifi(!state.wifiEnabled); host.popTone() }
                    2 -> { host.toggleCellular(!state.cellularOn); host.popTone() }
                    3 -> { host.toggleBluetooth(!state.btOn); host.popTone() }
                    4 -> { state.openWifiShare(); host.selectTone() }
                    5 -> { host.toggleWebServer(!state.webServerEnabled); host.popTone() }
                    6 -> { host.setWebTerminalEnabled(!state.webTerminalEnabled); host.popTone() }
                    7 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
                }
            },
        )

        FactoryConfirmPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.factoryReset(); host.selectTone() }
                }
            },
        )

        WifiScanPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val ssid = state.wifiScanResults.getOrNull(idx - 1)
                    if (ssid != null) {
                        state.openWifiPassword(ssid)
                        host.selectTone()
                    }
                }
            },
        )

        WifiPasswordPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSubmit = {
                host.connectToWifi(state.wifiSelectedSsid, state.wifiPasswordInput)
            }
        )

        WifiSharePanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleWifiShare(!state.wifiShareEnabled); host.popTone() }
                    2 -> { state.openWifiShareEdit(com.r1.launcher.WifiShareEditTarget.SSID); host.selectTone() }
                    3 -> { state.openWifiShareEdit(com.r1.launcher.WifiShareEditTarget.PASSWORD); host.selectTone() }
                    4 -> {
                        if (state.wifiShareConnectedClients.isNotEmpty()) {
                            state.wifiShareClientsExpanded = !state.wifiShareClientsExpanded
                            host.popTone()
                        }
                    }
                    5 -> { host.wifiShareCycleTimer(); host.popTone() }
                }
            },
        )

        WifiShareEditPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSubmit = { host.wifiShareSaveEdit() },
        )

        BrightnessPanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        VolumePanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        UiVolumePanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        OpenClawQrPanel(
            state = state,
            onScanned = { raw -> host.openClawScanned(raw) },
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
        )

        OpenClawChatPanel(
            state = state,
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
            onSend = { text -> host.openClawSendText(text) },
            onOpenSettings = { state.openOpenClawSettings(); host.selectTone() },
            onSwitchSession = { key -> host.openClawSwitchSession(key); host.selectTone() },
            onOpenSessions = {
                state.openOpenClawSessions()
                host.openClawRefreshSessions() // best-effort fresh fetch on entry
                host.selectTone()
            },
            onOpenCamera = {
                host.openClawOpenCameraAsk()
                host.selectTone()
            },
            onCopyCode = { code -> host.copyToClipboard(code, "openclaw-code"); host.popTone() },
        )

        OpenClawCameraPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onCaptured = { bytes -> host.openClawCameraCaptured(bytes) },
            onRetake = { host.openClawCameraRetake() },
            onSend = { prompt -> host.openClawCameraSend(prompt) },
        )

        OpenClawSessionsPanel(
            state = state,
            onRowClick = { idx -> host.openClawSessionsRowActivate(idx) },
        )

        OpenClawSettingsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onRowClick = { idx -> host.openClawSettingsRowActivate(idx) },
            onFontSizeChange = { size -> host.openClawSetFontSize(size) },
        )

        MessagesPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val conv = state.smsConversations.getOrNull(idx - 1)
                    if (conv != null) {
                        host.openSmsThread(conv.address, conv.displayName)
                        host.selectTone()
                    }
                }
            },
        )

        MessagesThreadPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
        )

        TerminalPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onKeyPress = { ch -> state.terminalInput += ch },
            onBackspace = {
                if (state.terminalInput.isNotEmpty()) {
                    state.terminalInput = state.terminalInput.dropLast(1)
                }
            },
            onSubmit = {
                val cmd = state.terminalInput.trim()
                if (cmd.isNotEmpty()) {
                    host.terminalRun(cmd)
                    host.popTone()
                }
            },
            onClear = { host.terminalClear(); host.popTone() },
            onToggleKb = {
                state.terminalKbVisible = !state.terminalKbVisible
                host.popTone()
            },
            onPaste = { host.terminalPasteFromClipboard(); host.popTone() },
        )

        ClaudePanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSend = { text ->
                if (text.isNotEmpty()) {
                    host.claudeSend(text)
                    host.popTone()
                }
            },
            onClear = { host.claudeClear(); host.popTone() },
            onPaste = { host.claudePasteFromClipboard(); host.popTone() },
        )

        // Topbar overlay — only on the clock screen; every other panel
        // either draws its own header or is a full-screen takeover.
        val topbarVisible = state.panel == Panel.HOME
        AnimatedVisibility(
            visible = topbarVisible,
            enter = fadeIn(tween(ANIM_OPEN_MS, easing = EnterEasing)),
            exit = fadeOut(tween(ANIM_CLOSE_MS, easing = ExitEasing)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Topbar(state = state)
        }

    }
    }
}

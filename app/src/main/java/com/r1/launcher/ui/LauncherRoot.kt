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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Base: home screen. Visible whenever nothing else is stacked over it.
        HomeScreen(state = state)

        AppsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onAppClick = { idx -> host.launchApp(idx) },
        )

        SettingsPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openNetwork(); host.selectTone() }
                    2 -> { state.openBrightness(); host.selectTone() }
                    3 -> { state.openVolume(); host.selectTone() }
                    4 -> { host.checkForUpdate(); host.selectTone() }
                    5 -> { state.openFactoryConfirm(); host.selectTone() }
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
                    6 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
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

        OpenClawQrPanel(
            state = state,
            onScanned = { raw -> host.openClawScanned(raw) },
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
        )

        OpenClawChatPanel(
            state = state,
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
            onSend = { text -> host.openClawSendText(text) },
            onPasteKey = { host.openClawPasteOpenaiKey() },
            onClearKey = { host.openClawClearOpenaiKey() },
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
            onOpenTalk = {
                host.openClawOpenTalk()
                host.selectTone()
            },
            onOpenCanvas = {
                state.openOpenClawCanvas()
                host.selectTone()
            },
        )

        OpenClawTalkPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onToggleRecord = { host.openClawToggleRecord() },
            onSpeakerChange = { enabled -> host.openClawSetSpeaker(enabled) },
        )

        OpenClawCanvasPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
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
            onSave = { key -> host.openClawSaveOpenaiKey(key) },
            onPasteFromClipboard = { host.openClawPasteOpenaiKey() },
            onClear = { host.openClawClearOpenaiKey() },
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

        // Topbar overlay — only on the clock screen; every other panel
        // either draws its own header or is a full-screen takeover.
        val topbarVisible = state.panel == Panel.HOME
        AnimatedVisibility(
            visible = topbarVisible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Topbar(state = state)
        }

    }
}

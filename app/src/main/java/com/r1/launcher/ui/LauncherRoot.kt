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
import com.r1.launcher.AppStore
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

/**
 * Root composable — FrameLayout-style z-stack:
 *   wallpaper + panel-under-focus → system sheet → store detail → topbar → debug key
 *
 * Topbar hides while apps/store are open (old launcher did the same), since those
 * panels draw their own header.
 */
@Composable
fun LauncherRoot(
    state: LauncherState,
    appStore: AppStore,
    host: LauncherHost,
) {
    val colors = LocalR1Colors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Base: home screen. Visible whenever nothing else is stacked over it.
        HomeScreen(
            state = state,
            onDockClick = { idx ->
                state.homeFocus = idx
                when (idx) {
                    0 -> { state.openSheet(); host.selectTone() }
                    1 -> { state.openStore(); host.selectTone() }
                    2 -> { state.openApps(); host.selectTone() }
                }
            },
        )

        AppsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onAppClick = { idx -> host.launchApp(idx) },
        )

        StorePanel(
            state = state,
            appStore = appStore,
            onBack = { state.back(); host.backTone() },
            onRowClick = { entry -> host.storeActivate(entry) },
        )

        SystemSheet(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> host.openWifiSettings()
                    1 -> host.requestReboot(false)
                    2 -> host.requestReboot(true)
                    3 -> host.checkForUpdate()
                }
            },
            onScrimClick = { state.back(); host.backTone() },
        )

        StoreDetail(
            state = state,
            onBack = { state.back(); host.backTone() },
            onOpen = { host.detailOpen() },
            onUninstall = { host.detailUninstall() },
        )

        SettingsPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openBrightness(); host.selectTone() }
                    2 -> { state.openVolume(); host.selectTone() }
                    3 -> { state.showDebugBar = !state.showDebugBar; host.selectTone() }
                    4 -> { state.openNetwork(); host.selectTone() }
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
                    3 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
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
        )

        OpenClawSettingsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSave = { key -> host.openClawSaveOpenaiKey(key) },
            onPasteFromClipboard = { host.openClawPasteOpenaiKey() },
            onClear = { host.openClawClearOpenaiKey() },
        )

        AudioTestPanel(
            state = state,
            onBack = { host.audioTestStop(); state.back(); host.backTone() },
        )

        // Topbar overlay — hidden while apps or store panel (which have their own
        // header) are fully visible. Stays up for the sheet and detail overlays.
        val topbarVisible = state.panel == Panel.HOME ||
            state.panel == Panel.SHEET ||
            state.panel == Panel.DETAIL
        AnimatedVisibility(
            visible = topbarVisible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Topbar(state = state)
        }

        // Debug keycode overlay.
        if (state.debugKeyVisible) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .wrapContentSize(),
            ) {
                Text(
                    state.debugKeyText,
                    style = LocalR1Type.current.mono,
                    color = colors.accent,
                )
            }
        }
    }
}

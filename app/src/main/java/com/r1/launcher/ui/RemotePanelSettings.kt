package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Dedicated settings page for the embedded HTTP/WS web panel and its related
 * capture behavior. Sits one level below NETWORK so the parent stays focused
 * on radios and the user finds everything web-companion in one place.
 *
 * Row layout (focus indices):
 *   0  header (back)
 *   1  server on/off          — toggleWebServer
 *   2  passcode: XXXX         — opens PANEL_PASSCODE numeric keypad
 *   3  include mic            — setCaptureMicEnabled
 *   4  include playback audio — setCapturePlaybackEnabled
 *   5  remote terminal        — setWebTerminalEnabled
 *
 * Don't reorder without bumping the focus map in [com.r1.launcher.LauncherNav]
 * (REMOTE_PANEL activate) and [com.r1.launcher.ui.LauncherRoot] (the
 * RemotePanelSettings onRowClick dispatcher) together — they must stay in sync.
 */
@Composable
fun RemotePanelSettings(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.REMOTE_PANEL,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val items = listOf(
            SettingsItem.Standard("__header__"),
            SettingsItem.Toggle(
                "server",
                state.webServerEnabled,
                subtitle = if (state.webServerEnabled) {
                    "http://${state.webServerIp.ifEmpty { "?" }}:${state.webServerPort}"
                } else "off",
            ),
            SettingsItem.Standard(
                "passcode: ${state.panelPasscode}",
                subtitle = "tap to change",
            ),
            SettingsItem.Toggle(
                "record mic",
                state.captureMicEnabled,
                subtitle = if (state.captureMicEnabled)
                    "your voice in screen recordings"
                else "skip mic — silent for the user",
            ),
            SettingsItem.Toggle(
                "record system audio",
                state.capturePlaybackEnabled,
                subtitle = if (state.capturePlaybackEnabled)
                    "WARNING: mutes device speakers while recording"
                else "off — speakers work normally",
            ),
            SettingsItem.Toggle(
                "remote terminal",
                state.webTerminalEnabled,
                subtitle = when {
                    !state.webTerminalEnabled -> "off"
                    !state.webServerEnabled -> "needs server on"
                    else -> "ROOT SHELL OVER LAN"
                },
            ),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.remotePanelFocus) {
            listState.animateScrollToItem(
                state.remotePanelFocus.coerceIn(0, items.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = items,
                    key = { idx, item -> if (idx == 0) "header" else item.label },
                ) { idx, item ->
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_network,
                            title = "remote panel",
                            backFocused = state.remotePanelFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.remotePanelFocus,
                            toggleChecked = (item as? SettingsItem.Toggle)?.checked,
                            subtitle = (item as? SettingsItem.Toggle)?.subtitle
                                ?: (item as? SettingsItem.Standard)?.subtitle
                                ?: "",
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}

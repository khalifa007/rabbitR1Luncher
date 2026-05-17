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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

@Composable
fun NetworkPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.NETWORK,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val items = listOf(
            // Placeholder for header — item idx 0 is rendered as the page
            // header below, not a row. Keep it in the list so focus indices
            // stay stable with existing nav code (0=back, 1..=rows).
            SettingsItem.Standard("__header__"),
            SettingsItem.Toggle(stringResource(R.string.network_row_wifi), state.wifiEnabled, subtitle = if (state.wifiEnabled) state.wifiConnectedSsid else ""),
            SettingsItem.Toggle(stringResource(R.string.network_row_cellular), state.cellularOn),
            SettingsItem.Toggle(stringResource(R.string.network_row_bluetooth), state.btOn),
            SettingsItem.Toggle(
                stringResource(R.string.network_row_share),
                state.wifiShareEnabled,
                subtitle = if (state.wifiShareEnabled) stringResource(R.string.network_share_clients, state.wifiShareConnectedClients.size) else "",
            ),
            SettingsItem.Toggle(
                stringResource(R.string.network_row_remote),
                state.webServerEnabled,
                subtitle = if (state.webServerEnabled) "http://${state.webServerIp.ifEmpty { "?" }}:${state.webServerPort}" else "",
            ),
            SettingsItem.Toggle(
                stringResource(R.string.network_row_terminal),
                state.webTerminalEnabled,
                subtitle = when {
                    !state.webTerminalEnabled -> ""
                    !state.webServerEnabled -> stringResource(R.string.network_terminal_needs_panel)
                    else -> stringResource(R.string.network_terminal_warn_lan)
                },
            ),
            // ntfy.sh — outbound long-poll subscriber. Subtitle shows live
            // status when on, the topic when off but configured.
            SettingsItem.Toggle(
                "ntfy.sh",
                state.ntfySubscriberEnabled,
                subtitle = when {
                    state.ntfySubscriberEnabled -> state.ntfyStatus
                    state.ntfyTopic.isNotBlank() -> "topic …${state.ntfyTopic.takeLast(10)}"
                    else -> "set a topic"
                },
            ),
            SettingsItem.Standard(stringResource(R.string.network_row_scan)),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.networkFocus) {
            listState.animateScrollToItem(
                state.networkFocus.coerceIn(0, items.lastIndex)
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
                            title = "network",
                            backFocused = state.networkFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.networkFocus,
                            toggleChecked = (item as? SettingsItem.Toggle)?.checked,
                            subtitle = (item as? SettingsItem.Toggle)?.subtitle ?: "",
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}

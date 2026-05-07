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
            SettingsItem.Standard(stringResource(R.string.back_short)),
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
                    start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { idx, item ->
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

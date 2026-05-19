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
 * Single Bluetooth page: shows on/off toggle + a live device list.
 *
 * Index map for focus / clicks:
 *   0 = back (header)
 *   1 = on/off toggle
 *   2..N+1 = devices (paired with connection state, then discovered)
 *
 * The device list is rendered only when BT is on. When off, the toggle is the
 * only interactive row.
 */
@Composable
fun BluetoothScanPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.BT_SCAN,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        // Build a flat row list parallel to the focus indices.
        // Each entry is either a SettingsItem.Toggle (bt on/off) or
        // SettingsItem.Standard (a device row).
        val items = mutableListOf<SettingsItem>()
        items.add(SettingsItem.Standard("__header__"))
        items.add(SettingsItem.Toggle(
            label = "bluetooth",
            checked = state.btOn,
            subtitle = if (state.btOn) {
                if (state.btScanning) "scanning…" else "tap a device to pair"
            } else "off",
        ))
        if (state.btOn) {
            state.btDevices.forEach { dev ->
                val subtitle = when {
                    dev.connected -> "● connected — tap to disconnect"
                    dev.bonded -> "paired — tap to connect"
                    else -> "tap to pair"
                }
                items.add(SettingsItem.Standard(dev.name, subtitle = subtitle))
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.btScanFocus) {
            listState.animateScrollToItem(
                state.btScanFocus.coerceIn(0, items.lastIndex)
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
                    key = { idx, item -> if (idx == 0) "header" else "$idx-${item.label}" },
                ) { idx, item ->
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_network,
                            title = "bluetooth",
                            backFocused = state.btScanFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.btScanFocus,
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

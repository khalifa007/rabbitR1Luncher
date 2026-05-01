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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun WifiSharePanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.WIFI_SHARE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val ssid = state.wifiShareSsid.ifBlank { "—" }
        val passDisplay = state.wifiSharePassword.ifEmpty { "—" }
        val timerLabel = when (state.wifiShareTimerMinutes) {
            0 -> "off"
            15 -> "15 min"
            30 -> "30 min"
            60 -> "1 hour"
            120 -> "2 hours"
            else -> "${state.wifiShareTimerMinutes} min"
        }
        val enableSubtitle = when {
            state.wifiShareEnabled && state.wifiShareTimerMinutes > 0 ->
                formatRemaining(state.wifiShareTimerRemainingSec) + " left"
            state.wifiShareEnabled -> "on"
            else -> ""
        }
        val items = listOf(
            ShareRow.Plain("< back"),
            ShareRow.Toggle("enable", state.wifiShareEnabled, enableSubtitle),
            ShareRow.Plain("name", ssid),
            ShareRow.Plain("password", passDisplay),
            ShareRow.Plain("connected", "${state.wifiShareConnectedClients.size}"),
            ShareRow.Plain("auto-off", timerLabel),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.wifiShareFocus) {
            listState.animateScrollToItem(
                state.wifiShareFocus.coerceIn(0, items.lastIndex)
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
                    when (item) {
                        is ShareRow.Toggle -> SettingsRow(
                            label = item.label,
                            focused = idx == state.wifiShareFocus,
                            toggleChecked = item.checked,
                            subtitle = item.subtitle,
                            onClick = { onRowClick(idx) },
                        )
                        is ShareRow.Plain -> SettingsRow(
                            label = item.label,
                            focused = idx == state.wifiShareFocus,
                            toggleChecked = null,
                            subtitle = item.subtitle,
                            onClick = { onRowClick(idx) },
                        )
                    }
                    if (idx == 4 && state.wifiShareClientsExpanded && state.wifiShareConnectedClients.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                        ) {
                            state.wifiShareConnectedClients.forEach { mac ->
                                Text(
                                    text = mac,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontFamily = type.appCard.fontFamily,
                                    fontWeight = FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                // Non-focusable hint: where to point a phone browser to reach
                // the companion panel. Only shown when the server is up.
                if (state.webServerEnabled && state.webServerIp.isNotBlank()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "panel",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                            Text(
                                text = "http://${state.webServerIp}:${state.webServerPort}",
                                color = Color(0xFFFF6B00),
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class ShareRow(val label: String, val subtitle: String) {
    class Plain(label: String, subtitle: String = "") : ShareRow(label, subtitle)
    class Toggle(label: String, val checked: Boolean, subtitle: String = "") : ShareRow(label, subtitle)
}

private fun formatRemaining(totalSec: Int): String {
    if (totalSec <= 0) return "0s"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
        else -> "${s}s"
    }
}

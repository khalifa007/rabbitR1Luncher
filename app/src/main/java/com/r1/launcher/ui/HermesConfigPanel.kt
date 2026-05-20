package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.hermes.HermesPrefs

@Composable
fun HermesConfigPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CONFIG,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)
        val accent = AppThemes.Hermes

        val connections = state.hermesConnections.toList()
        val canAdd = connections.size < HermesPrefs.MAX_CONNECTIONS

        // Row layout:
        //   0                                          back header
        //   1..connections.size                        connection rows
        //   connections.size + 1                       "add new" (hidden if !canAdd)
        //   + 1 (or 0 when hidden)                     "scan from qr"
        //   + 1                                        "speak replies" toggle
        //   + 1                                        "hide text input" toggle
        //   + 1                                        "test active connection"
        val addRowIdx = if (canAdd) connections.size + 1 else -1
        val scanRowIdx = (if (canAdd) connections.size + 2 else connections.size + 1)
        val speakRowIdx = scanRowIdx + 1
        val hideRowIdx = scanRowIdx + 2
        val testRowIdx = scanRowIdx + 3
        val totalRows = testRowIdx + 1

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConfigFocus, totalRows) {
            listState.animateScrollToItem(state.hermesConfigFocus.coerceIn(0, totalRows - 1))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(List(totalRows) { it }) { idx, _ ->
                    when (idx) {
                        0 -> AppPageHeader(
                            titleIconRes = R.drawable.ic_hermes,
                            title = "hermes",
                            backFocused = state.hermesConfigFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = accent,
                        )
                        in 1..connections.size -> {
                            val conn = connections[idx - 1]
                            val isActive = state.hermesActiveId == conn.id
                            ConnectionRow(
                                label = conn.hostLabel,
                                subtitle = conn.subtitle,
                                isActive = isActive,
                                focused = state.hermesConfigFocus == idx,
                                accent = accent,
                                onClick = { onRowClick(idx) },
                            )
                        }
                        addRowIdx -> SettingsRow(
                            label = "+ add new connection",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                        scanRowIdx -> SettingsRow(
                            label = "scan config from qr",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                        speakRowIdx -> SettingsRow(
                            label = "speak replies",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = state.voiceEnabled,
                            onClick = { onRowClick(idx) },
                        )
                        hideRowIdx -> SettingsRow(
                            label = "hide text input",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = state.hermesHideChat,
                            onClick = { onRowClick(idx) },
                        )
                        testRowIdx -> SettingsRow(
                            label = "test connection",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = statusLine(state.hermesStatus),
                            subtitleColor = when {
                                state.hermesStatus == "live" -> ok
                                state.hermesStatus.startsWith("error") -> Color(0xFFE53935)
                                else -> dim
                            },
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    label: String,
    subtitle: String,
    isActive: Boolean,
    focused: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val dim = Color(0xFFAAAAAA)
    val bgColor = if (focused) Color(0xFFFF4500) else Color.Transparent
    val textColor = if (focused) Color.Black else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 24.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = dim,
                fontSize = 12.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier.padding(top = 36.dp),
            )
        }
    }
}

private fun statusLine(status: String): String = when {
    status == "live" -> "ok"
    status == "streaming" -> "streaming…"
    status == "connecting" -> "checking…"
    status.startsWith("error") -> status
    else -> "tap to test"
}

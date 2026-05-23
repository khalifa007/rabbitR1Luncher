package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.hermes.HermesPrefs

// One renderable row inside the Hermes config LazyColumn.
//   Header   — non-focusable section divider (uppercase amber label)
//   Focusable — carries a wheel-nav focus index that maps 1:1 to the
//               idx passed back into LauncherActivity.hermesConfigRowActivate.
//
// Section headers don't take a focus index, so the flat focus space stays
// identical to the pre-refactor layout — LauncherNav.kt and the host
// dispatch don't need to change.
private sealed class HermesRow {
    data class Header(val label: String) : HermesRow()
    data class Focusable(val focusIdx: Int, val render: @Composable () -> Unit) : HermesRow()
}

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
        val ok = Color(0xFF35D26F)
        val err = Color(0xFFE53935)
        val dim = Color(0xFFAAAAAA)
        val accent = AppThemes.Hermes

        val connections = state.hermesConnections.toList()
        val canAdd = connections.size < HermesPrefs.MAX_CONNECTIONS

        // Flat focus index space — kept in lockstep with
        //   LauncherActivity.hermesConfigRowActivate(idx)
        // and wheelUp/wheelDown handling in LauncherNav.kt.
        //   0           back
        //   1..N        connections (N = connections.size)
        //   N+1         + add new (only when canAdd)
        //   N+1 or N+2  scan from qr
        //   +1          speak replies
        //   +1          hide text input
        //   +1          test connection
        val addRowIdx = if (canAdd) connections.size + 1 else -1
        val scanRowIdx = if (canAdd) connections.size + 2 else connections.size + 1
        val speakRowIdx = scanRowIdx + 1
        val hideRowIdx = scanRowIdx + 2
        val testRowIdx = scanRowIdx + 3

        val rows = buildList<HermesRow> {
            // Page header doubles as focus index 0 (back pill lives inside it).
            add(HermesRow.Focusable(0) {
                AppPageHeader(
                    titleIconRes = R.drawable.ic_hermes,
                    title = "hermes",
                    backFocused = state.hermesConfigFocus == 0,
                    onBack = { onRowClick(0) },
                    themeColor = accent,
                )
            })

            add(HermesRow.Header("connections"))
            connections.forEachIndexed { i, conn ->
                val focusIdx = i + 1
                add(HermesRow.Focusable(focusIdx) {
                    val focused = state.hermesConfigFocus == focusIdx
                    val isActive = state.hermesActiveId == conn.id
                    SettingsRow(
                        label = conn.hostLabel,
                        focused = focused,
                        subtitle = conn.subtitle,
                        subtitleColor = dim,
                        leadingIcon = R.drawable.ic_network,
                        trailing = {
                            ActiveChip(active = isActive, focused = focused, accent = accent)
                        },
                        onClick = { onRowClick(focusIdx) },
                    )
                })
            }
            if (canAdd) {
                add(HermesRow.Focusable(addRowIdx) {
                    SettingsRow(
                        label = "+ add new connection",
                        focused = state.hermesConfigFocus == addRowIdx,
                        leadingIcon = R.drawable.ic_settings,
                        onClick = { onRowClick(addRowIdx) },
                    )
                })
            }
            add(HermesRow.Focusable(scanRowIdx) {
                SettingsRow(
                    label = "scan config from qr",
                    focused = state.hermesConfigFocus == scanRowIdx,
                    leadingIcon = R.drawable.ic_dots_9,
                    onClick = { onRowClick(scanRowIdx) },
                )
            })

            add(HermesRow.Header("chat"))
            add(HermesRow.Focusable(speakRowIdx) {
                SettingsRow(
                    label = "speak replies",
                    focused = state.hermesConfigFocus == speakRowIdx,
                    toggleChecked = state.voiceEnabled,
                    leadingIcon = R.drawable.ic_voice,
                    onClick = { onRowClick(speakRowIdx) },
                )
            })
            add(HermesRow.Focusable(hideRowIdx) {
                SettingsRow(
                    label = "hide text input",
                    focused = state.hermesConfigFocus == hideRowIdx,
                    toggleChecked = state.hermesHideChat,
                    leadingIcon = R.drawable.ic_messages,
                    onClick = { onRowClick(hideRowIdx) },
                )
            })

            add(HermesRow.Header("diagnostic"))
            add(HermesRow.Focusable(testRowIdx) {
                SettingsRow(
                    label = "test connection",
                    focused = state.hermesConfigFocus == testRowIdx,
                    subtitle = statusLine(state.hermesStatus),
                    subtitleColor = when {
                        state.hermesStatus == "live" -> ok
                        state.hermesStatus.startsWith("error") -> err
                        else -> dim
                    },
                    leadingIcon = R.drawable.ic_about,
                    onClick = { onRowClick(testRowIdx) },
                )
            })
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConfigFocus, rows.size) {
            val itemIdx = rows.indexOfFirst {
                it is HermesRow.Focusable && it.focusIdx == state.hermesConfigFocus
            }
            if (itemIdx >= 0) listState.animateScrollToItem(itemIdx)
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows) { _, row ->
                    when (row) {
                        is HermesRow.Header -> SectionHeader(row.label, themeColor = accent)
                        is HermesRow.Focusable -> row.render()
                    }
                }
            }
        }
    }
}

/**
 * "This is the active connection" chip. Visual grammar matches [MinimalSwitch]
 * so connection rows + toggles read as one pixel-art-adjacent family:
 *   - 14×14dp square frame, 2dp border
 *   - Active = amber border + inner 6dp amber square (radio-button look)
 *   - Inactive = dim border, empty interior
 *   - Focused row flips the border to black and fills the frame so the chip
 *     stays legible against the orange highlight.
 */
@Composable
private fun ActiveChip(active: Boolean, focused: Boolean, accent: Color) {
    val border = when {
        focused -> Color.Black
        active -> accent
        else -> Color(0xFF3A3A3A)
    }
    val fill = if (focused) Color.Black else Color.Transparent
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(fill)
            .border(2.dp, border, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(accent, RoundedCornerShape(1.dp)),
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

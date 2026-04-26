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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.openclaw.SessionEntry
import com.r1.launcher.openclaw.friendlySessionName
import com.r1.launcher.openclaw.resolveSessionChoices

/**
 * Wheel-driven thread picker. Row layout:
 *   0      "< back"
 *   1..N   one row per resolveSessionChoices entry; selected thread shows " *"
 *   N+1    "refresh"  (fetches sessions.list again)
 *
 * `host.openClawSessionsRowActivate(idx)` does the dispatch — see LauncherActivity.
 */
@Composable
fun OpenClawSessionsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_SESSIONS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        // Snapshot the choices for this composition so row indices stay stable
        // for the wheel-focus + activate dispatch.
        val choices: List<SessionEntry> = remember(state.chatSessions.toList(), state.selectedSessionKey, state.mainSessionKey) {
            resolveSessionChoices(
                currentSessionKey = state.selectedSessionKey,
                sessions = state.chatSessions.toList(),
                mainSessionKey = state.mainSessionKey,
            )
        }

        // Each row carries (label, subtitle). Subtitle is the raw session key so
        // multiple threads sharing the same displayName ("R1 Launcher" — our own
        // client name, echoed back by the gateway) stay distinguishable.
        data class Row(val label: String, val subtitle: String)
        val items = buildList {
            add(Row("< back", ""))
            if (choices.isEmpty()) {
                add(Row(if (state.sessionsLoading) "loading…" else "no threads", ""))
            } else {
                for (entry in choices) {
                    val friendly = friendlySessionName(entry.key)
                    val marker = if (entry.key == state.selectedSessionKey) "  *" else ""
                    add(Row("$friendly$marker", entry.key))
                }
            }
            add(Row(if (state.sessionsLoading) "refresh…" else "refresh", ""))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.openClawSessionsFocus) {
            listState.animateScrollToItem(
                state.openClawSessionsFocus.coerceIn(0, items.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { idx, row ->
                    SettingsRow(
                        label = row.label,
                        focused = idx == state.openClawSessionsFocus,
                        toggleChecked = null,
                        subtitle = row.subtitle,
                        onClick = { onRowClick(idx) },
                    )
                }
            }
        }
    }
}

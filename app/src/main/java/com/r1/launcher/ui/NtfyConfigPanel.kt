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
 * Settings → Network → ntfy.sh. Outbound long-poll subscriber for a single
 * topic on the public ntfy.sh instance. When enabled + configured, the
 * launcher holds an HTTPS connection out to ntfy.sh, and anyone on the
 * internet can `curl -d "msg" https://ntfy.sh/<topic>` to fire a notification
 * on the R1 — no NAT / port forwarding needed.
 *
 * The keyboard for the topic field is the same overlay used in
 * SettingsCredentialsPanel; we just set state.credentialsEditField to
 * "ntfy_topic" and let that panel's keyboard surface render. That means
 * users typing a topic from here get the same paste / clear / save pills
 * without a duplicate keyboard implementation.
 *
 * Row layout (kept in sync with [com.r1.launcher.LauncherActivity.ntfyConfigRowActivate]):
 *   0  < back
 *   1  enable toggle  (refuses on if topic is blank)
 *   2  topic          (status + tail; click → keyboard via credentials overlay)
 *   3  status         (info-only: live / connecting / retry… / disabled)
 */
@Composable
fun NtfyConfigPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.NTFY_CONFIG,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val topicSub = if (state.ntfyTopic.isBlank()) "not set"
            else "…${state.ntfyTopic.takeLast(12)}"
        val topicOk = state.ntfyTopic.isNotBlank()
        val statusSub = state.ntfyStatus
        val items = listOf(
            SettingsItem.Standard("__header__"),
            SettingsItem.Toggle("enabled", state.ntfySubscriberEnabled),
            SettingsItem.Standard("topic"),
            SettingsItem.Standard("status"),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.ntfyConfigFocus) {
            listState.animateScrollToItem(state.ntfyConfigFocus.coerceIn(0, items.lastIndex))
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
                            titleIconRes = R.drawable.ic_notifications,
                            title = "ntfy.sh",
                            backFocused = state.ntfyConfigFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        val (sub, subOk) = when (idx) {
                            2 -> topicSub to topicOk
                            3 -> statusSub to (statusSub == "live")
                            else -> "" to false
                        }
                        val subColor = if (subOk) Color(0xFF35D26F) else Color(0xFFFF4500)
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.ntfyConfigFocus,
                            toggleChecked = (item as? SettingsItem.Toggle)?.checked,
                            subtitle = sub,
                            subtitleColor = subColor,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}

package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.notifications.NotifSource

/** Per-source accent — also drives the dot color on each card so a glance is
 *  enough to tell openclaw from hermes from a generic webhook. */
private fun sourceColor(s: NotifSource): Color = when (s) {
    NotifSource.OPENCLAW -> AppThemes.OpenClaw
    NotifSource.HERMES -> AppThemes.Hermes
    NotifSource.WEBHOOK -> Color(0xFFFF6B00) // accent orange
    NotifSource.NTFY -> Color(0xFF35D26F)    // green — outbound relay path
    NotifSource.LOCAL -> Color(0xFFAAAAAA)
}

private fun sourceLabel(s: NotifSource): String = when (s) {
    NotifSource.OPENCLAW -> "openclaw"
    NotifSource.HERMES -> "hermes"
    NotifSource.WEBHOOK -> "webhook"
    NotifSource.NTFY -> "ntfy.sh"
    NotifSource.LOCAL -> "local"
}

@Composable
fun NotificationsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.NOTIFICATIONS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        // NotificationStore is append-only oldest-first; reverse so newest is on top.
        val items = state.notifications.asReversed()
        // Focus indices: 0=back, 1=header-clear (only when items.isNotEmpty()),
        // 2..N+1=items. No bottom "clear all" row — clear lives in the header
        // so it's always reachable without scrolling through a long list.
        val listState = rememberLazyListState()
        LaunchedEffect(state.notificationsFocus) {
            // LazyColumn rows are [header(0), item(1)..item(N)]. Map header
            // focus (back / clear) to row 0 and item focus (2..N+1) to its
            // LazyColumn row (focus - 1). Clamp at N to avoid scrolling
            // past the last item when items.isEmpty.
            val maxLazyRow = items.size
            val target = (state.notificationsFocus - 1).coerceIn(0, maxLazyRow)
            listState.animateScrollToItem(target)
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
                item(key = "header") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_notifications,
                        title = "notifs",
                        backFocused = state.notificationsFocus == 0,
                        onBack = { onRowClick(0) },
                        clearFocused = state.notificationsFocus == 1,
                        onClear = if (items.isNotEmpty()) ({ onRowClick(1) }) else null,
                        themeColor = Color(0xFFFF6B00),
                    )
                }
                if (items.isEmpty()) {
                    item {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp)) {
                            Text(
                                text = "no notifications yet",
                                color = Color(0xFFAAAAAA),
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = items,
                        key = { _, n -> n.id },
                    ) { idx, n ->
                        val rowIdx = idx + 2
                        NotificationCard(
                            source = n.sourceEnum,
                            title = n.title,
                            body = n.body,
                            timestampMs = n.timestamp,
                            unread = !n.read,
                            focused = rowIdx == state.notificationsFocus,
                            onClick = { onRowClick(rowIdx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    source: NotifSource,
    title: String,
    body: String,
    timestampMs: Long,
    unread: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val accent = sourceColor(source)
    val cardBg = if (focused) Color(0x33FF6A00) else Color(0xFF131315)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .focusAnim(focused)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(
                width = if (unread) 1.dp else 0.dp,
                color = if (unread) accent.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Source dot — colored chip on the left. Slight elevation tweak for unread.
        Box(
            modifier = Modifier
                .size(if (unread) 10.dp else 8.dp)
                .background(accent, CircleShape)
                .padding(top = 4.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sourceLabel(source),
                    color = accent,
                    fontSize = 11.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = relativeTime(timestampMs),
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
            if (title.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    color = if (unread) Color.White else Color(0xFFBBBBBB),
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    color = if (unread) Color(0xFFDDDDDD) else Color(0xFF888888),
                    fontSize = 12.sp,
                    fontFamily = type.appCard.fontFamily,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "now", "5m", "2h", "3d" — coarse buckets are easier to read than full timestamps
 *  on a small round display, and the list is implicitly newest-first so absolute
 *  values rarely matter. */
private fun relativeTime(ms: Long): String {
    val diffSec = ((System.currentTimeMillis() - ms) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60 -> "now"
        diffSec < 3600 -> "${diffSec / 60}m"
        diffSec < 86_400 -> "${diffSec / 3600}h"
        else -> "${diffSec / 86_400}d"
    }
}

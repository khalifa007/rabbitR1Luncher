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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MESSAGES,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val total = state.smsConversations.size
        // Row 0 = back, then one row per conversation (or a single placeholder).
        val rowCount = 1 + total.coerceAtLeast(1)

        val listState = rememberLazyListState()
        LaunchedEffect(state.messagesFocus) {
            listState.animateScrollToItem(
                state.messagesFocus.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
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
                item(key = "header") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_messages,
                        title = "messages",
                        backFocused = state.messagesFocus == 0,
                        onBack = { onRowClick(0) },
                        themeColor = AppThemes.Messages,
                    )
                }
                if (total == 0) {
                    item {
                        val loadingTxt = stringResource(R.string.common_loading)
                        val emptyTxt = stringResource(R.string.messages_empty)
                        val msg = if (state.smsLoading) loadingTxt
                            else state.smsError ?: emptyTxt
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 24.dp)
                        ) {
                            Text(
                                text = msg,
                                color = Color(0xFFAAAAAA),
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = state.smsConversations,
                        key = { _, conv -> conv.address },
                    ) { idx, conv ->
                        val rowIdx = idx + 1
                        val title = conv.displayName.ifBlank { conv.address }
                        val labelText = if (conv.unreadCount > 0)
                            "$title  •${conv.unreadCount}" else title
                        val subtitle = buildString {
                            append(formatDate(conv.latestTimestampMs))
                            append(" — ")
                            append(conv.latestBody.replace('\n', ' ').take(48))
                        }
                        SettingsRow(
                            label = labelText,
                            focused = rowIdx == state.messagesFocus,
                            toggleChecked = null,
                            subtitle = subtitle,
                            subtitleColor = Color(0xFFCCCCCC),
                            onClick = { onRowClick(rowIdx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessagesThreadPanel(
    state: LauncherState,
    onBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MESSAGES_THREAD,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val items = state.smsThreadMessages
        // Row 0 = back, then chronological message bubbles.
        val rowCount = 1 + items.size.coerceAtLeast(1)
        val listState = rememberLazyListState()
        LaunchedEffect(state.smsThreadFocus, items.size) {
            // Drift focus to bottom on first load so newest is visible.
            val target = if (state.smsThreadFocus == 0 && items.isNotEmpty())
                items.size else state.smsThreadFocus
            listState.animateScrollToItem(
                target.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
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
                item(key = "header") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_messages,
                        title = state.smsThreadName.ifBlank { state.smsThreadAddress },
                        backFocused = state.smsThreadFocus == 0,
                        onBack = onBack,
                        themeColor = AppThemes.Messages,
                    )
                }
                if (items.isEmpty()) {
                    item {
                        val loadingTxt = stringResource(R.string.common_loading)
                        val emptyTxt = stringResource(R.string.messages_empty)
                        val msg = if (state.smsThreadLoading) loadingTxt else emptyTxt
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp)) {
                            Text(
                                text = msg,
                                color = Color(0xFFAAAAAA),
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.id },
                    ) { idx, item ->
                        val align = if (item.incoming) Alignment.Start else Alignment.End
                        val bubbleBg = if (item.incoming) Color(0xFF1F1F1F) else Color(0xFFFF4500)
                        val textColor = if (item.incoming) Color.White else Color.Black
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = align.let {
                                if (item.incoming) Alignment.CenterStart else Alignment.CenterEnd
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        bubbleBg,
                                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = item.body,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontFamily = type.appCard.fontFamily,
                                    fontWeight = FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Lazy so Locale.getDefault() resolves AFTER attachBaseContext flips it; uses
// digitFriendlyLocale() to pin Latin digits even in Arabic locale.
private val timeFmt by lazy {
    SimpleDateFormat("MMM d HH:mm", com.r1.launcher.locale.digitFriendlyLocale())
}
private fun formatDate(ms: Long): String =
    runCatching { timeFmt.format(Date(ms)) }.getOrDefault("")

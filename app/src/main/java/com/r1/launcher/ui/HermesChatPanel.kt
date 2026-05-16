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
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.hermes.HermesMessage

/**
 * Chat surface for the Hermes Agent app. Stateless server-side — this panel just
 * renders [LauncherState.hermesMessages] / [LauncherState.hermesStreamingText] /
 * [LauncherState.hermesPartialText] and routes input through the host callbacks.
 *
 * Visual idiom is a deliberate near-clone of OpenClawChatPanel so users moving
 * between the two AI apps don't need to relearn anything; the theme color is the
 * one place where they diverge (Hermes uses AppThemes.Hermes — a warm gold).
 */
@Composable
fun HermesChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CHAT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        var menuOpen by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {

                val listState = rememberLazyListState()
                var lastTick by remember { mutableStateOf(state.hermesScrollIndex) }
                var lastSize by remember { mutableStateOf(state.hermesMessages.size) }
                LaunchedEffect(state.hermesScrollIndex, state.hermesMessages.size) {
                    val sizeGrew = state.hermesMessages.size > lastSize
                    lastSize = state.hermesMessages.size
                    if (state.hermesScrollIndex == 0 && (lastTick != 0 || sizeGrew)) {
                        runCatching { listState.animateScrollToItem(0) }
                        lastTick = 0
                    } else if (state.hermesScrollIndex != lastTick) {
                        val diff = state.hermesScrollIndex - lastTick
                        lastTick = state.hermesScrollIndex
                        runCatching { listState.animateScrollBy(diff.toFloat() * 300f) }
                    }
                }

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 50.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.hermesBusy && state.hermesStreamingText.isBlank()) {
                        item("typing") {
                            HermesBubble(
                                HermesMessage(role = "assistant", text = "thinking…", streaming = true),
                                fontSize = state.hermesFontSize,
                            )
                        }
                    }
                    if (state.hermesPartialText.isNotBlank()) {
                        item("partial") {
                            HermesBubble(
                                HermesMessage(role = "user", text = state.hermesPartialText),
                                fontSize = state.hermesFontSize,
                                pending = true,
                            )
                        }
                    }
                    if (state.hermesStreamingText.isNotBlank()) {
                        item("streaming") {
                            HermesBubble(
                                HermesMessage(
                                    role = "assistant",
                                    text = state.hermesStreamingText,
                                    streaming = true,
                                ),
                                fontSize = state.hermesFontSize,
                            )
                        }
                    }
                    if (state.hermesMessages.isEmpty() && state.hermesStreamingText.isBlank()) {
                        item {
                            HermesEmptyHint(state.hermesStatus)
                        }
                    } else {
                        val reversed = state.hermesMessages.asReversed()
                        itemsIndexed(items = reversed, key = { _, m -> m.id }) { _, msg ->
                            HermesBubble(msg, fontSize = state.hermesFontSize)
                        }
                    }
                }

                var inputText by remember { mutableStateOf("") }
                var showKeyboard by remember { mutableStateOf(false) }

                if (!state.hermesHideChat) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, AppThemes.Hermes, RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .clickable { showKeyboard = !showKeyboard }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (inputText.isEmpty()) "type here…"
                                else inputText + if (showKeyboard) "_" else "",
                            style = type.appCard,
                            color = if (inputText.isEmpty()) Color.Gray else Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "send",
                            style = type.appCard,
                            color = AppThemes.Hermes,
                            modifier = Modifier.clickable {
                                if (inputText.isNotBlank()) {
                                    onSend(inputText)
                                    inputText = ""
                                    showKeyboard = false
                                }
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = showKeyboard,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    ) {
                        RetroKeyboard(
                            onKeyPress = { char -> inputText += char },
                            onBackspace = { if (inputText.isNotEmpty()) inputText = inputText.dropLast(1) },
                            onDismiss = { showKeyboard = false },
                        )
                    }
                }
            }

            AppPageHeader(
                backFocused = false,
                onBack = onBack,
                themeColor = AppThemes.Hermes,
                compact = true,
                floating = true,
                modifier = Modifier.align(Alignment.TopCenter),
                trailingContent = {
                    HermesStatusDot(state.hermesStatus)
                    when {
                        state.hermesRecording -> {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "rec",
                                style = type.appCard.copy(fontSize = 13.sp),
                                color = AppThemes.Hermes,
                            )
                        }
                        state.hermesBusy -> {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "…",
                                style = type.appCard.copy(fontSize = 13.sp),
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    MenuDot(
                        themeColor = AppThemes.Hermes,
                        focused = menuOpen,
                        onClick = { menuOpen = !menuOpen },
                    )
                },
            )

            HermesDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onNewChat = { menuOpen = false; onClear() },
                onSettings = { menuOpen = false; onOpenConfig() },
            )
        }
    }
}

@Composable
private fun HermesDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    if (expanded) {
        // Invisible scrim — tap anywhere outside the menu to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { -it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 2 },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp, end = 12.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, AppThemes.Hermes.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                HermesMenuItem(label = "new chat", onClick = onNewChat)
                HermesMenuItem(label = "settings", onClick = onSettings)
            }
        }
    }
}

@Composable
private fun HermesMenuItem(
    label: String,
    onClick: () -> Unit,
    color: Color = AppThemes.Hermes,
) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 15.sp),
            color = color,
        )
    }
}

@Composable
private fun HermesStatusDot(status: String) {
    val color = when {
        status == "live" -> Color(0xFF35D26F)
        status == "streaming" -> Color(0xFFFFC107)
        status == "connecting" -> Color(0xFFFFC107)
        status.startsWith("error") -> Color(0xFFE53935)
        else -> Color(0xFF777777)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun HermesEmptyHint(status: String) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        val text = when {
            status.startsWith("error") -> status
            status == "live" -> "ask hermes anything"
            else -> "press wheel or hold side button to talk"
        }
        Text(
            text = text,
            style = type.appCard.copy(textAlign = TextAlign.Center),
            color = AppThemes.Hermes,
        )
    }
}

@Composable
private fun HermesBubble(
    msg: HermesMessage,
    fontSize: Int = 14,
    pending: Boolean = false,
) {
    val type = LocalR1Type.current
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val bg = when {
        pending -> Color(0xFF555555)
        isError -> Color(0xFF3A1414)
        isUser -> AppThemes.Hermes
        else -> Color(0xFF1F1F1F)
    }
    val textColor = when {
        pending -> Color.White
        isError -> Color(0xFFFF6B6B)
        isUser -> Color.Black
        else -> Color.White
    }
    val chatStyle = type.appCard.copy(fontSize = fontSize.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = msg.text.ifEmpty { if (msg.streaming) "…" else "" },
                style = chatStyle,
                color = textColor,
            )
        }
    }
}

package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.openclaw.ChatMessage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.mutableStateOf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor

@Composable
fun OpenClawChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onPasteKey: () -> Unit = {},
    onClearKey: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_CHAT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val colors = LocalR1Colors.current
        val type = LocalR1Type.current
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            // header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            ) {
                BackPill(label = "home", onClick = onBack)
                Spacer(Modifier.weight(1f))
                StatusDot(state.chatStatus)
                when {
                    state.chatRecording -> {
                        Spacer(Modifier.width(6.dp))
                        Text("rec", style = type.appCard, color = Color(0xFFFF4500))
                    }
                    state.chatTranscribing -> {
                        Spacer(Modifier.width(6.dp))
                        Text("stt", style = type.appCard, color = Color(0xFFFFC107))
                    }
                    state.chatBusy -> {
                        Spacer(Modifier.width(6.dp))
                        Text("...", style = type.appCard, color = Color.White)
                    }
                }
                Spacer(Modifier.width(8.dp))
                SettingsPill(
                    keySet = state.chatHasOpenaiKey,
                    onClick = onOpenSettings,
                )
            }

            val listState = rememberLazyListState()
            var lastTick by remember { mutableStateOf(state.chatScrollIndex) }

            // chatScrollIndex is now a relative scroll tick. 0 means "reset to bottom".
            LaunchedEffect(state.chatScrollIndex, state.chatMessages.size) {
                if (state.chatScrollIndex == 0 && lastTick != 0) {
                    runCatching { listState.animateScrollToItem(0) }
                    lastTick = 0
                } else if (state.chatScrollIndex != lastTick) {
                    val diff = state.chatScrollIndex - lastTick
                    lastTick = state.chatScrollIndex
                    runCatching {
                        listState.animateScrollBy(diff.toFloat() * 300f)
                    }
                }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.chatMessages.isEmpty()) {
                    item {
                        EmptyHint(state.chatStatus)
                    }
                } else {
                    val reversed = state.chatMessages.asReversed()
                    itemsIndexed(reversed) { _, msg ->
                        Bubble(msg)
                    }
                }
            }

            // live transcript (only while recording)
            if (state.chatRecording && state.chatPartialText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.chatPartialText,
                        style = type.appCard,
                        color = Color(0xFFFF4500),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }

            var inputText by remember { mutableStateOf("") }
            var showKeyboard by remember { mutableStateOf(false) }

            if (!state.openClawHideChat) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color(0xFFFF4500), RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .clickable { showKeyboard = !showKeyboard }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (inputText.isEmpty()) "type here..." else inputText + if (showKeyboard) "_" else "",
                        style = type.appCard,
                        color = if (inputText.isEmpty()) Color.Gray else Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "send",
                        style = type.appCard,
                        color = Color(0xFFFF4500),
                        modifier = Modifier.clickable {
                            if (inputText.isNotBlank()) {
                                onSend(inputText)
                                inputText = ""
                                showKeyboard = false
                            }
                        }
                    )
                }
                
                AnimatedVisibility(
                    visible = showKeyboard,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    RetroKeyboard(
                        onKeyPress = { char -> inputText += char },
                        onBackspace = { if (inputText.isNotEmpty()) inputText = inputText.dropLast(1) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when {
        status == "live" -> Color(0xFF35D26F)
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
private fun EmptyHint(status: String) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                status.startsWith("error") -> status
                status == "connecting" -> "connecting to gate…"
                status == "live" -> "no messages yet — press wheel to record"
                status == "idle" -> "device not approved yet —\nrun: openclaw devices approve <id>\non your gate, then reopen"
                else -> status
            },
            style = type.appCard.copy(textAlign = TextAlign.Center),
            color = Color(0xFFFF4500),
        )
    }
}

@Composable
private fun SettingsPill(keySet: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "settings",
            tint = Color(0xFFFF4500),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    val isUser = msg.role == "user"
    val bg = if (isUser) Color(0xFFFF4500) else colors.tile
    val align = if (isUser) Alignment.End else Alignment.Start
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
            Row(verticalAlignment = Alignment.Bottom) {
                if (isUser) {
                    Text(
                        text = msg.text.ifEmpty { if (msg.streaming) "…" else "" },
                        style = type.appCard,
                        color = Color.Black,
                    )
                } else {
                    Markdown(
                        content = msg.text.ifEmpty { if (msg.streaming) "…" else "" },
                        colors = markdownColor(
                            text = Color.White,
                            codeText = Color(0xFFFF4500),
                            codeBackground = Color.Transparent
                        ),
                        typography = markdownTypography(
                            text = type.appCard,
                            code = type.appCard,
                            paragraph = type.appCard,
                            quote = type.appCard,
                            h1 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            h2 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            h3 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            h4 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            h5 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            h6 = type.appCard.copy(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
                        )
                    )
                }
                if (msg.streaming) {
                    Spacer(Modifier.width(2.dp))
                    StreamingCaret()
                }
            }
        }
    }
}

@Composable
private fun StreamingCaret() {
    val colors = LocalR1Colors.current
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
    Box(
        modifier = Modifier
            .size(width = 6.dp, height = 14.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(colors.accent, RoundedCornerShape(1.dp)),
    )
}


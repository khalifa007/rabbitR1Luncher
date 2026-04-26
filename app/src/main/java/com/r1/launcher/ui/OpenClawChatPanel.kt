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
import androidx.compose.material.icons.automirrored.filled.List
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
import com.mikepenz.markdown.model.markdownAnnotator
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.mutableStateOf

import androidx.compose.foundation.clickable

@Composable
fun OpenClawChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onPasteKey: () -> Unit = {},
    onClearKey: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSwitchSession: (String) -> Unit = {},
    onOpenSessions: () -> Unit = {},
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
                ChatThreadsPill(onClick = onOpenSessions)
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
                // Typing indicator — shows while waiting for assistant to start
                // streaming. Sits at item 0 (bottom with reverseLayout=true).
                if (state.chatBusy && state.chatStreamingText.isBlank()) {
                    item("typing") {
                        Bubble(
                            ChatMessage(
                                role = "assistant",
                                text = "writing",
                                streaming = true,
                            ),
                            fontSize = state.chatFontSize,
                        )
                    }
                }
                // Live assistant streaming preview — sits at item 0 so with
                // reverseLayout=true it renders at the bottom of the chat,
                // beneath the most recent persisted bubble. Cleared when the
                // run reaches a terminal state and chat.history refreshes.
                if (state.chatStreamingText.isNotBlank()) {
                    item("streaming") {
                        Bubble(
                            ChatMessage(
                                role = "assistant",
                                text = state.chatStreamingText,
                                streaming = true,
                            ),
                            fontSize = state.chatFontSize,
                        )
                    }
                }
                if (state.chatMessages.isEmpty() && state.chatStreamingText.isBlank()) {
                    item {
                        EmptyHint(state.chatStatus)
                    }
                } else {
                    val reversed = state.chatMessages.asReversed()
                    itemsIndexed(reversed) { _, msg ->
                        Bubble(msg, fontSize = state.chatFontSize)
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
private fun ChatThreadsPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = "threads",
            tint = Color(0xFFFF4500),
            modifier = Modifier.size(24.dp),
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
private fun Bubble(msg: ChatMessage, fontSize: Int = 14) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    val isUser = msg.role == "user"
    val bg = if (isUser) Color(0xFFFF4500) else colors.tile
    val align = if (isUser) Alignment.End else Alignment.Start
    // Chat style driven by user-adjustable font size
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
            Row(verticalAlignment = Alignment.Bottom) {
                if (isUser) {
                    Text(
                        text = msg.text.ifEmpty { if (msg.streaming) "…" else "" },
                        style = chatStyle,
                        color = Color.Black,
                    )
                } else {
                    // Convert inline `code` → **code** so the library renders it
                    // as bold (Jersey 15) instead of monospace CODE_SPAN.
                    // Fenced code blocks are also stripped to plain text.
                    val rawContent = msg.text.ifEmpty { if (msg.streaming) "…" else "" }
                    val cleaned = rawContent
                        .replace(Regex("```[\\s\\S]*?```")) { m ->
                            val inner = m.value.removeSurrounding("```")
                            val nl = inner.indexOf('\n')
                            // Strip the first line only if it looks like a bare
                            // language tag (letters/digits/+-_), so "```js\nfoo"
                            // doesn't eat the "foo".
                            if (nl > 0 && inner.substring(0, nl).all {
                                    it.isLetterOrDigit() || it == '+' || it == '-' || it == '_'
                                }) inner.substring(nl + 1).trim('\n')
                            else inner.trim('\n')
                        }
                        .replace(Regex("`([^`\n]+)`"), "**$1**") // inline code → bold
                    Markdown(
                        content = cleaned,
                        colors = markdownColor(
                            text = Color.White,
                            codeText = Color(0xFFFF4500),
                            codeBackground = Color.Transparent
                        ),
                        typography = markdownTypography(
                            text = chatStyle,
                            code = chatStyle,
                            paragraph = chatStyle,
                            quote = chatStyle,
                            list = chatStyle,
                            ordered = chatStyle,
                            bullet = chatStyle,
                            h1 = chatStyle.copy(fontSize = (fontSize + 4).sp, fontWeight = FontWeight.Bold),
                            h2 = chatStyle.copy(fontSize = (fontSize + 3).sp, fontWeight = FontWeight.Bold),
                            h3 = chatStyle.copy(fontSize = (fontSize + 2).sp, fontWeight = FontWeight.Bold),
                            h4 = chatStyle.copy(fontSize = (fontSize + 1).sp, fontWeight = FontWeight.Bold),
                            h5 = chatStyle.copy(fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
                            h6 = chatStyle.copy(fontSize = (fontSize - 1).coerceAtLeast(8).sp, fontWeight = FontWeight.Bold)
                        ),
                        // Color bold/italic text orange — since we convert `code` to **code**.
                        // We consume the node (return true), strip the markdown markers
                        // ourselves, and pop the span so the color doesn't leak into
                        // following text.
                        annotator = markdownAnnotator { content, node ->
                            if (node.type.name == "STRONG" || node.type.name == "EMPH") {
                                val raw = content.substring(node.startOffset, node.endOffset)
                                val stripped = when {
                                    raw.startsWith("**") && raw.endsWith("**") -> raw.substring(2, raw.length - 2)
                                    raw.startsWith("__") && raw.endsWith("__") -> raw.substring(2, raw.length - 2)
                                    raw.startsWith("*") && raw.endsWith("*") -> raw.substring(1, raw.length - 1)
                                    raw.startsWith("_") && raw.endsWith("_") -> raw.substring(1, raw.length - 1)
                                    else -> raw
                                }
                                pushStyle(SpanStyle(color = Color(0xFFFF4500)))
                                append(stripped)
                                pop()
                                true
                            } else false
                        },
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


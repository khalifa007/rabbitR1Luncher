package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.R
import com.r1.launcher.Panel
import com.r1.launcher.hermes.HermesImageLoader
import com.r1.launcher.hermes.HermesMessage
import com.r1.launcher.hermes.HermesToolEvent
import androidx.compose.ui.text.font.FontStyle

/**
 * Chat surface for the Hermes Agent app. Stateless server-side — this panel just
 * renders [LauncherState.hermesActiveHistory] / [LauncherState.hermesStreamingText] /
 * [LauncherState.hermesPartialText] and routes input through the host callbacks.
 *
 * Visual idiom is a deliberate near-clone of OpenClawChatPanel so users moving
 * between the two AI apps don't need to relearn anything; the theme color is the
 * one place where they diverge (Hermes uses AppThemes.Hermes — a warm gold).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HermesChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    getClipboardText: () -> String = { "" },
    onCopyMessage: (String) -> Unit = {},
    onMicStart: () -> Unit = {},
    onMicStop: () -> Unit = {},
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
        var showKeyboard by remember { mutableStateOf(false) }
        var showPaste by remember { mutableStateOf(false) }
        var pasteText by remember { mutableStateOf("") }
        var copyMenuFor by remember { mutableStateOf<HermesMessage?>(null) }
        var selectingMessageId by remember { mutableStateOf<String?>(null) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {

                val listState = rememberLazyListState()
                val messages: List<HermesMessage> = state.hermesActiveHistory() ?: emptyList()
                var lastTick by remember { mutableStateOf(state.hermesScrollIndex) }
                var lastSize by remember { mutableStateOf(messages.size) }
                LaunchedEffect(state.hermesScrollIndex, messages.size) {
                    val sizeGrew = messages.size > lastSize
                    lastSize = messages.size
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
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                HermesReasoningCard(
                                    messageId = "streaming",
                                    reasoning = state.hermesReasoningText,
                                    tools = state.hermesToolEvents.toList(),
                                    isStreaming = true,
                                )
                                HermesBubble(
                                    HermesMessage(role = "assistant", text = "thinking…", streaming = true),
                                    fontSize = state.hermesFontSize,
                                )
                            }
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
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                HermesReasoningCard(
                                    messageId = "streaming",
                                    reasoning = state.hermesReasoningText,
                                    tools = state.hermesToolEvents.toList(),
                                    isStreaming = true,
                                )
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
                    }
                    if (messages.isEmpty() && state.hermesStreamingText.isBlank()) {
                        item {
                            HermesEmptyHint(state.hermesStatus)
                        }
                    } else {
                        val reversed = messages.asReversed()
                        itemsIndexed(items = reversed, key = { _, m -> m.id }) { _, msg ->
                            if (msg.role == "assistant") {
                                // Reasoning is derived once per message id: if the
                                // persisted message already carries `reasoning`,
                                // use it directly. Otherwise extract any inline
                                // <think> blocks from older `text` and use the
                                // stripped residue as the bubble text — old
                                // history instantly matches the new layout.
                                val (bubbleText, reasoning) = remember(msg.id, msg.text, msg.reasoning) {
                                    if (msg.reasoning != null) msg.text to msg.reasoning
                                    else extractInlineReasoning(msg.text)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HermesReasoningCard(
                                        messageId = msg.id,
                                        reasoning = reasoning,
                                        tools = msg.toolEvents,
                                        isStreaming = false,
                                    )
                                    HermesBubble(
                                        msg = if (bubbleText === msg.text) msg else msg.copy(text = bubbleText),
                                        fontSize = state.hermesFontSize,
                                        onLongPress = { copyMenuFor = msg },
                                        selecting = msg.id == selectingMessageId,
                                    )
                                }
                            } else {
                                HermesBubble(
                                    msg = msg,
                                    fontSize = state.hermesFontSize,
                                    onLongPress = { copyMenuFor = msg },
                                    selecting = msg.id == selectingMessageId,
                                )
                            }
                        }
                    }
                }

                if (!state.hermesHideChat) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, AppThemes.Hermes, RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .combinedClickable(
                                onClick = { showKeyboard = !showKeyboard },
                                onLongClick = {
                                    pasteText = getClipboardText()
                                    showPaste = true
                                },
                            )
                            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state.hermesInputText.isEmpty()) "type here…"
                                else state.hermesInputText + if (showKeyboard) "_" else "",
                            style = type.appCard,
                            color = if (state.hermesInputText.isEmpty()) Color.Gray else Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        HoldToTalkPill(
                            recording = state.hermesRecording,
                            themeColor = AppThemes.Hermes,
                            onStart = onMicStart,
                            onStop = onMicStop,
                            size = 28.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        SendIconButton(
                            tint = AppThemes.Hermes,
                            enabled = state.hermesInputText.isNotBlank(),
                            onClick = {
                                if (state.hermesInputText.isNotBlank()) {
                                    onSend(state.hermesInputText)
                                    state.hermesInputText = ""
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
                            onKeyPress = { char -> state.hermesInputText += char },
                            onBackspace = { if (state.hermesInputText.isNotEmpty()) state.hermesInputText = state.hermesInputText.dropLast(1) },
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
                    HermesActivityIcon(
                        recording = state.hermesRecording,
                        transcribing = state.hermesTranscribing,
                        thinking = state.hermesBusy,
                        speaking = state.hermesSpeaking,
                    )
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

            ClipboardPastePopup(
                visible = showPaste,
                themeColor = AppThemes.Hermes,
                clipboardText = pasteText,
                onPaste = { text ->
                    state.hermesInputText = if (state.hermesInputText.isBlank()) text
                        else state.hermesInputText.trimEnd() + " " + text
                    showPaste = false
                },
                onDismiss = { showPaste = false },
                onClear = if (state.hermesInputText.isNotEmpty()) {
                    { state.hermesInputText = ""; showPaste = false }
                } else null,
            )

            MessageActionPopup(
                visible = copyMenuFor != null,
                themeColor = AppThemes.Hermes,
                onCopy = {
                    copyMenuFor?.let { onCopyMessage(it.text) }
                    copyMenuFor = null
                },
                onSelectText = {
                    selectingMessageId = copyMenuFor?.id
                    copyMenuFor = null
                },
                onDismiss = { copyMenuFor = null },
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

/**
 * Gray "what the agent is doing" block rendered above each assistant bubble.
 * Header `▸ thinking` / `▾ thinking · N tools` is tappable. Body shows the
 * model's reasoning (italic gray) + a timeline of `hermes.tool.progress`
 * events. Hidden entirely if both reasoning and tools are empty.
 *
 * Expansion default tracks [isStreaming] so an in-flight turn auto-opens
 * the card; after streaming ends the user's last toggle sticks per-message
 * via [remember]([messageId]).
 */
@Composable
private fun HermesReasoningCard(
    messageId: String,
    reasoning: String?,
    tools: List<HermesToolEvent>,
    isStreaming: Boolean,
) {
    if (reasoning.isNullOrBlank() && tools.isEmpty()) return
    val type = LocalR1Type.current
    val gray = Color(0xFF888888)
    var expanded by remember(messageId) { mutableStateOf(isStreaming) }
    val header = buildString {
        append(if (expanded) "▾ thinking" else "▸ thinking")
        if (tools.isNotEmpty()) append(" · ${tools.size} tool${if (tools.size == 1) "" else "s"}")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = header,
            style = type.appCard.copy(fontSize = 12.sp),
            color = gray,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + slideInVertically(tween(140)) { -it / 4 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 4 },
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!reasoning.isNullOrBlank()) {
                    Text(
                        text = reasoning.trim(),
                        style = type.appCard.copy(fontSize = 12.sp, fontStyle = FontStyle.Italic),
                        color = gray,
                    )
                }
                tools.forEach { ev ->
                    val prefix = if (ev.emoji.isNotEmpty()) "${ev.emoji} " else ""
                    val suffix = if (ev.status == "completed") " ✓" else "…"
                    Text(
                        text = "$prefix${ev.tool}$suffix",
                        style = type.appCard.copy(fontSize = 12.sp),
                        color = gray,
                    )
                }
            }
        }
    }
}

private val INLINE_THINK_RE = Regex("(?s)<think>(.*?)</think>")

/**
 * One-pass `<think>` extractor for legacy/persisted message text. Returns
 * (textWithoutThink, joinedReasoning|null). Memoized at the call site via
 * `remember(messageId)`.
 */
private fun extractInlineReasoning(text: String): Pair<String, String?> {
    val matches = INLINE_THINK_RE.findAll(text).toList()
    if (matches.isEmpty()) return text to null
    val reasoning = matches.joinToString("\n\n") { it.groupValues[1].trim() }
        .ifBlank { null }
    val stripped = INLINE_THINK_RE.replace(text, "").trim()
    return stripped to reasoning
}

@Composable
private fun HermesActivityIcon(
    recording: Boolean,
    transcribing: Boolean,
    thinking: Boolean,
    speaking: Boolean,
) {
    val (resId, tint) = when {
        recording -> R.drawable.ic_voice to Color(0xFFE53935)
        transcribing -> R.drawable.ic_voice to Color(0xFFAAAAAA)
        thinking -> R.drawable.ic_thinking to AppThemes.Hermes
        speaking -> R.drawable.ic_sound to AppThemes.Hermes
        else -> return
    }
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "hermesActivity")
    val alpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "hermesActivityAlpha",
    )
    Spacer(Modifier.width(6.dp))
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        tint = tint.copy(alpha = alpha),
        modifier = Modifier.size(14.dp),
    )
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
    // Pixel-art status block: hard square (no CircleShape) so it matches the
    // rest of the retro idiom. Green=live, amber=connecting/streaming, red=error.
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HermesBubble(
    msg: HermesMessage,
    fontSize: Int = 14,
    pending: Boolean = false,
    onLongPress: () -> Unit = {},
    selecting: Boolean = false,
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
        // In selecting mode the bubble box stops eating long-press so the inner
        // SelectionContainer can claim the gesture and show Android's native
        // selection handles + Copy/Select-all toolbar (iPhone-style partial copy).
        // Outside selecting mode the box owns long-press → our action popup.
        val boxMod = Modifier
            .padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .let {
                if (selecting) it
                else it.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                )
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
        // Trim leading whitespace: when the SSE splitter strips a leading
        // <think>…</think> block, the stream often leaves a `\n\n` where the
        // tags were. Persisted messages may also carry that residue. Strip
        // at render time only so the underlying text stays unmodified.
        val displayText = msg.text.trimStart().ifEmpty { if (msg.streaming) "…" else "" }
        val segments = remember(displayText) { splitMarkdownSegments(displayText) }
        Box(modifier = boxMod) {
            if (segments.size == 1 && segments[0] is HermesSegment.Text) {
                val txt = (segments[0] as HermesSegment.Text).s
                if (selecting) {
                    SelectionContainer { Text(text = txt, style = chatStyle, color = textColor) }
                } else {
                    Text(text = txt, style = chatStyle, color = textColor)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    segments.forEachIndexed { idx, seg ->
                        // Stable key per segment: Image keys on URL so its
                        // remembered bitmap survives text-segment insertions
                        // around it during SSE streaming; Text keys on position
                        // since its content grows in place.
                        val k = when (seg) {
                            is HermesSegment.Image -> "img:${seg.url}"
                            is HermesSegment.Text -> "txt:$idx"
                        }
                        key(k) {
                            when (seg) {
                                is HermesSegment.Text -> {
                                    if (selecting) {
                                        SelectionContainer { Text(text = seg.s, style = chatStyle, color = textColor) }
                                    } else {
                                        Text(text = seg.s, style = chatStyle, color = textColor)
                                    }
                                }
                                is HermesSegment.Image -> RemoteImage(seg.url, chatStyle.fontSize.value.toInt())
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class HermesSegment {
    data class Text(val s: String) : HermesSegment()
    data class Image(val url: String) : HermesSegment()
}

private val MARKDOWN_IMAGE_RE = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")

/** Split assistant markdown into ordered text + image segments. Partial syntax
 *  during streaming (no closing paren yet) stays as text and resolves once the
 *  full match arrives. */
private fun splitMarkdownSegments(text: String): List<HermesSegment> {
    if (text.isEmpty()) return listOf(HermesSegment.Text(""))
    val matches = MARKDOWN_IMAGE_RE.findAll(text).toList()
    if (matches.isEmpty()) return listOf(HermesSegment.Text(text))
    val out = mutableListOf<HermesSegment>()
    var pos = 0
    for (m in matches) {
        if (m.range.first > pos) {
            val slice = text.substring(pos, m.range.first).trim('\n', ' ')
            if (slice.isNotEmpty()) out += HermesSegment.Text(slice)
        }
        out += HermesSegment.Image(m.groupValues[2])
        pos = m.range.last + 1
    }
    if (pos < text.length) {
        val slice = text.substring(pos).trim('\n', ' ')
        if (slice.isNotEmpty()) out += HermesSegment.Text(slice)
    }
    return out
}

@Composable
private fun RemoteImage(url: String, fallbackFontSize: Int) {
    val ctx = LocalContext.current
    val type = LocalR1Type.current
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(HermesImageLoader.cached(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (bmp != null) return@LaunchedEffect
        val img = HermesImageLoader.load(ctx, url)
        if (img != null) bmp = img else failed = true
    }
    when {
        bmp != null -> Image(
            bitmap = bmp!!,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        failed -> Text(
            text = "(image failed) $url",
            style = type.appCard.copy(fontSize = (fallbackFontSize - 2).coerceAtLeast(10).sp),
            color = Color(0xFFFF6B6B),
        )
        else -> Text(
            text = "loading image…",
            style = type.appCard.copy(fontSize = (fallbackFontSize - 2).coerceAtLeast(10).sp),
            color = Color.Gray,
        )
    }
}

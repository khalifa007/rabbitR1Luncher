package com.r1.launcher.ui

import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
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
    onOpenSettings: () -> Unit = {},
    onSwitchSession: (String) -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onCopyCode: (String) -> Unit = {},
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
        var menuOpen by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                ) {
                    BackPill(label = stringResource(R.string.openclaw_back_home), onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    StatusDot(state.chatStatus)
                    when {
                        state.chatRecording -> {
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.terminal_status_rec), style = type.appCard, color = Color(0xFFFF4500))
                        }
                        state.chatTranscribing -> {
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.terminal_status_stt), style = type.appCard, color = Color(0xFFFFC107))
                        }
                        state.chatBusy -> {
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.common_thinking_dots), style = type.appCard, color = Color.White)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    MenuDot(onClick = { menuOpen = !menuOpen })
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

                val writingText = stringResource(R.string.openclaw_writing)
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
                                    text = writingText,
                                    streaming = true,
                                ),
                                fontSize = state.chatFontSize,
                            )
                        }
                    }
                    // Pending user bubble — live STT partial transcript. Gated on
                    // chatPartialText alone (not chatRecording) so the bubble
                    // persists across the round-trip between button release and
                    // committed_transcript landing — otherwise it'd vanish for
                    // ~200-500ms before the orange bubble appears, which reads as
                    // a flicker. handleCommittedTranscript clears the partial
                    // and adds the orange bubble in the same frame.
                    if (state.chatPartialText.isNotBlank()) {
                        item("partial") {
                            Bubble(
                                ChatMessage(
                                    role = "user",
                                    text = state.chatPartialText,
                                ),
                                fontSize = state.chatFontSize,
                                pending = true,
                            )
                        }
                    }
                    // Live assistant streaming preview — sits beneath the partial
                    // (item index 1) so the user's pending input always reads as
                    // the most recent line.
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
                            Bubble(msg, fontSize = state.chatFontSize, onCopyCode = onCopyCode)
                        }
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
                        val openClawHint = stringResource(R.string.openclaw_input_hint)
                        Text(
                            text = if (inputText.isEmpty()) openClawHint else inputText + if (showKeyboard) "_" else "",
                            style = type.appCard,
                            color = if (inputText.isEmpty()) Color.Gray else Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.common_send),
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

            // Dropdown menu overlay — rendered on top of the Column z-stack
            ChatDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onCamera = { menuOpen = false; onOpenCamera() },
                onSessions = { menuOpen = false; onOpenSessions() },
                onSettings = { menuOpen = false; onOpenSettings() },
            )
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
        val connectingText = stringResource(R.string.openclaw_status_connecting)
        val liveText = stringResource(R.string.openclaw_empty_hint)
        val idleText = stringResource(R.string.openclaw_not_approved)
        Text(
            text = when {
                status.startsWith("error") -> status
                status == "connecting" -> connectingText
                status == "live" -> liveText
                status == "idle" -> idleText
                else -> status
            },
            style = type.appCard.copy(textAlign = TextAlign.Center),
            color = Color(0xFFFF4500),
        )
    }
}

@Composable
private fun MenuDot(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Vertical three-dot icon (⋮) built from 3 small circles
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Color(0xFFFF4500), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ChatDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
) {
    val type = LocalR1Type.current
    if (expanded) {
        // Invisible scrim behind menu — catches taps to dismiss
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
                .padding(top = 38.dp, end = 12.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color(0xFFFF4500).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                MenuRow(label = stringResource(R.string.openclaw_btn_cam), onClick = onCamera)
                MenuRow(label = stringResource(R.string.openclaw_btn_sessions), onClick = onSessions)
                MenuRow(label = stringResource(R.string.openclaw_btn_settings), onClick = onSettings)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 18.sp),
            color = Color(0xFFFF4500),
        )
    }
}

private val CODE_BLOCK_RE = Regex("```([\\s\\S]*?)```")

/** Pull every fenced code block's body out of a markdown string, stripping
 *  an optional bare language tag on the first line so users get just the
 *  code (no stray ```python at the top). Returns empty list for plain prose. */
private fun extractCodeBlocks(text: String): List<String> =
    CODE_BLOCK_RE.findAll(text).map { m ->
        val inner = m.groupValues[1].trim('\n')
        val nl = inner.indexOf('\n')
        if (nl > 0 && inner.substring(0, nl).all {
                it.isLetterOrDigit() || it == '+' || it == '-' || it == '_'
            }) inner.substring(nl + 1).trimEnd('\n')
        else inner
    }.toList()

@Composable
private fun Bubble(
    msg: ChatMessage,
    fontSize: Int = 14,
    onCopyCode: (String) -> Unit = {},
    pending: Boolean = false,
) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    val isUser = msg.role == "user"
    // pending = STT partial transcript awaiting commit. Render as a user-side
    // bubble in muted gray so it's visually distinct from the orange committed
    // bubble that lands when the transcript finalizes.
    val bg = when {
        pending -> Color(0xFF555555)
        isUser -> Color(0xFFFF4500)
        else -> colors.tile
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    msg.imageBase64 != null -> ImageAttachmentPreview(msg.imageBase64)
                    msg.hasImage -> AttachedImageLabel(isUser = isUser)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                val streamingPlaceholder = stringResource(R.string.common_ellipsis)
                if (isUser) {
                    Text(
                        text = msg.text.ifEmpty { if (msg.streaming) streamingPlaceholder else "" },
                        style = chatStyle,
                        color = if (pending) Color.White else Color.Black,
                    )
                } else {
                    // Convert inline `code` → **code** so the library renders it
                    // as bold (Jersey 15) instead of monospace CODE_SPAN.
                    // Fenced code blocks are also stripped to plain text.
                    val rawContent = msg.text.ifEmpty { if (msg.streaming) streamingPlaceholder else "" }
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
                        // Strip blockquote markers: the markdown library's
                        // MarkdownBlockQuote calls a drawLine value-class
                        // signature that doesn't exist in our pinned compose
                        // and hard-crashes the launcher. Reduce to plain text.
                        .replace(Regex("(?m)^[ \t]*>[ \t]?"), "")
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
                                    raw.length >= 4 && raw.startsWith("**") && raw.endsWith("**") -> raw.substring(2, raw.length - 2)
                                    raw.length >= 4 && raw.startsWith("__") && raw.endsWith("__") -> raw.substring(2, raw.length - 2)
                                    raw.length >= 2 && raw.startsWith("*") && raw.endsWith("*") -> raw.substring(1, raw.length - 1)
                                    raw.length >= 2 && raw.startsWith("_") && raw.endsWith("_") -> raw.substring(1, raw.length - 1)
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
                // Copy-code affordance: assistant replies that contain fenced
                // code blocks get a tappable pill that copies the code (all
                // blocks joined by blank lines) to the system clipboard. Pill
                // hidden during streaming so it doesn't flicker on partial
                // ``` openings.
                if (!isUser && !msg.streaming) {
                    val codes = remember(msg.text) { extractCodeBlocks(msg.text) }
                    if (codes.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    onCopyCode(codes.joinToString("\n\n"))
                                }
                                .background(
                                    Color(0xFF1F1F1F),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = if (codes.size == 1) stringResource(R.string.openclaw_copy_code)
                                    else stringResource(R.string.openclaw_copy_code_n, codes.size),
                                color = Color(0xFFFF4500),
                                style = chatStyle.copy(fontSize = (fontSize - 2).coerceAtLeast(9).sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageAttachmentPreview(imageBase64: String) {
    val bitmap = remember(imageBase64) {
        runCatching {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        AttachedImageLabel(isUser = true)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun AttachedImageLabel(isUser: Boolean) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isUser) Color.Black.copy(alpha = 0.16f) else Color(0xFFFF4500).copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(R.string.openclaw_attached_image),
            style = type.appCard.copy(fontSize = 16.sp),
            color = if (isUser) Color.Black else Color(0xFFFF4500),
        )
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

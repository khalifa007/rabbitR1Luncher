package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.claude.ClaudeMessage

/**
 * Claude Code app — chat panel that drives `claude --print [-c]` under the hood.
 *
 * Visual language matches the OpenClaw chat panel: BackPill header + StatusDot
 * + bordered orange input + Jersey-15 chat bubbles (orange filled for user,
 * dark tile for assistant). No CLI-style `>` prompt or monospace clutter.
 */
@Composable
fun ClaudePanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.CLAUDE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val colors = LocalR1Colors.current
        val type = LocalR1Type.current

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // First-entry redirect screen: nudge the user toward the bigger-
            // screen web companion for the OAuth flow (a 480x480 round
            // display + RetroKeyboard is rough for pasting an OAuth URL +
            // 60-char code). Only shown when:
            //  - the user isn't logged in yet (post-login the QR's purpose is gone)
            //  - remote panel is already on (otherwise the QR would be dead;
            //    opening Claude no longer auto-enables it — user opt-in only)
            //  - they haven't dismissed it this session via "open anyway"
            if (!state.claudeAuthed && state.claudeShowWebHint && state.webServerEnabled) {
                ClaudeWebHint(
                    state = state,
                    onBack = onBack,
                    onOpenAnyway = { state.claudeShowWebHint = false },
                )
                return@Box
            }
            Column(modifier = Modifier.fillMaxSize()) {

                // --- header ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                ) {
                    BackPill(label = stringResource(R.string.claude_back_label), onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    when {
                        state.claudeRecording -> Text(
                            text = stringResource(R.string.terminal_status_rec),
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color(0xFFFF4500),
                        )
                        state.claudeTranscribing -> Text(
                            text = stringResource(R.string.terminal_status_stt),
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color(0xFFFFC107),
                        )
                        state.claudeBusy -> Text(
                            text = stringResource(R.string.common_thinking_dots),
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusDot(state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.terminal_short_clear),
                        style = type.appCard.copy(fontSize = 14.sp),
                        color = colors.muted,
                        modifier = Modifier
                            .clickable(onClick = onClear)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }

                // --- chat scrollback ---
                val listState = rememberLazyListState()
                LaunchedEffect(state.claudeMessages.size, state.claudeStreamingText, state.claudeScrollIndex) {
                    if (state.claudeScrollIndex == 0) {
                        runCatching { listState.scrollToItem(0) }
                    }
                }

                val thinkingText = stringResource(R.string.claude_thinking)
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // Live streaming preview at the bottom (item 0 with reverseLayout).
                    if (state.claudeStreamingText.isNotBlank()) {
                        item("streaming") {
                            ClaudeBubble(
                                ClaudeMessage(role = "assistant", text = state.claudeStreamingText),
                                streaming = true,
                            )
                        }
                    }
                    // Typing indicator until the first token arrives.
                    if (state.claudeBusy && state.claudeStreamingText.isBlank()) {
                        item("typing") {
                            ClaudeBubble(
                                ClaudeMessage(role = "assistant", text = thinkingText),
                                streaming = true,
                            )
                        }
                    }
                    if (state.claudeMessages.isEmpty() && state.claudeStreamingText.isBlank()) {
                        item("empty") { ClaudeEmpty() }
                    } else {
                        val reversed = state.claudeMessages.asReversed()
                        // Key by original-list index (claudeMessages is append-only,
                        // so the original index is stable across appends; reversed
                        // index is not — the streaming-bubble item would lose its
                        // identity on every new message otherwise).
                        val originalLast = state.claudeMessages.size - 1
                        itemsIndexed(
                            items = reversed,
                            key = { reversedIdx, _ -> originalLast - reversedIdx },
                        ) { _, msg ->
                            ClaudeBubble(msg)
                        }
                    }
                }

                // --- input row ---
                var inputText by remember { mutableStateOf("") }
                var showKeyboard by remember { mutableStateOf(false) }

                // Keep local input in sync with the host-side claudeInput so
                // voice dictation + paste pill (which mutate state.claudeInput)
                // show up in the row immediately.
                LaunchedEffect(state.claudeInput) {
                    if (state.claudeInput != inputText) inputText = state.claudeInput
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color(0xFFFF4500), RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .clickable { showKeyboard = !showKeyboard }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val claudeHint = stringResource(R.string.claude_input_hint)
                    Text(
                        text = if (inputText.isEmpty()) claudeHint
                            else inputText + if (showKeyboard) "_" else "",
                        style = type.appCard.copy(fontSize = 16.sp),
                        color = if (inputText.isEmpty()) colors.muted else Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_paste),
                        style = type.appCard.copy(fontSize = 14.sp),
                        color = colors.muted,
                        modifier = Modifier
                            .clickable { onPaste() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.common_send),
                        style = type.appCard.copy(fontSize = 16.sp),
                        color = if (inputText.isBlank() || state.claudeBusy)
                            colors.muted else Color(0xFFFF4500),
                        modifier = Modifier
                            .clickable {
                                if (inputText.isNotBlank() && !state.claudeBusy) {
                                    onSend(inputText.trim())
                                    inputText = ""
                                    showKeyboard = false
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                AnimatedVisibility(
                    visible = showKeyboard,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    RetroKeyboard(
                        onKeyPress = { ch ->
                            inputText += ch
                            // Mirror to host-state so wheel-press send + paste
                            // pill see the updated buffer.
                            state.claudeInput = inputText
                        },
                        onBackspace = {
                            if (inputText.isNotEmpty()) {
                                inputText = inputText.dropLast(1)
                                state.claudeInput = inputText
                            }
                        },
                        onDismiss = { showKeyboard = false },
                    )
                }
            }
        }
    }
}

/**
 * First-entry "use the web companion" redirect screen for the Claude tile.
 *
 * Layout (480x480 round screen, bordered orange card centered in the dial):
 * - back pill top-left
 * - "claude code" title
 * - small subtitle: "easier from a browser"
 * - QR of `http://<ip>:8080` (256dp square, white card so the camera can
 *   isolate the modules against the black wallpaper)
 * - URL text under the QR for hand-typing
 * - secondary "open anyway" pill that drops into the on-device chat
 *
 * The IP/port is pulled from [LauncherState.webServerIp]/[webServerPort];
 * `openClaude()` auto-toggles the web server on, so by the time this draws
 * the URL is already serving (or about to within a second).
 */
@Composable
private fun ClaudeWebHint(
    state: LauncherState,
    onBack: () -> Unit,
    onOpenAnyway: () -> Unit,
) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    val ip = state.webServerIp.ifBlank { "—" }
    val port = state.webServerPort
    val urlText = if (ip == "—") "(starting web server…)" else "http://$ip:$port"
    val qrPayload = if (ip == "—") "" else "http://$ip:$port/"
    val qrBitmap = if (qrPayload.isNotEmpty()) rememberQrBitmap(qrPayload, sizePx = 320) else null

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BackPill(label = stringResource(R.string.claude_back_label), onClick = onBack)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "claude code",
            style = type.appCard.copy(fontSize = 22.sp, textAlign = TextAlign.Center),
            color = Color(0xFFFF6A00),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "easier from a browser",
            style = type.appCard.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
            color = colors.muted,
        )

        Spacer(Modifier.height(10.dp))

        // QR card — white background so the orange modules contrast cleanly
        // for any phone camera. Square, centered.
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "web companion QR",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None, // crisp pixel edges
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "…",
                    style = type.appCard.copy(fontSize = 22.sp),
                    color = Color.Black,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = urlText,
            style = type.appCard.copy(fontSize = 14.sp, textAlign = TextAlign.Center),
            color = Color.White,
        )

        Spacer(Modifier.weight(1f))

        // Secondary action — pill, dim, opt-out for users who genuinely want
        // to type on the device (voice dictation works on this side too).
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, colors.muted, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenAnyway)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = "open anyway",
                style = type.appCard.copy(fontSize = 14.sp),
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatusDot(state: LauncherState) {
    val color = when {
        state.claudeRecording -> Color(0xFFE53935)
        state.claudeTranscribing -> Color(0xFFFFC107)
        state.claudeBusy -> Color(0xFFFF6A00)
        else -> Color(0xFF35D26F)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun ClaudeEmpty() {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.claude_empty_hint),
            style = type.appCard.copy(textAlign = TextAlign.Center, fontSize = 16.sp),
            color = Color(0xFFFF4500),
        )
    }
}

@Composable
private fun ClaudeBubble(msg: ClaudeMessage, streaming: Boolean = false) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    val isUser = msg.role == "user"
    val bg = when {
        msg.error -> Color(0xFF4A1414)
        isUser -> Color(0xFFFF4500)
        else -> colors.tile
    }
    val fg = when {
        msg.error -> Color(0xFFFFAAAA)
        isUser -> Color.Black
        else -> Color.White
    }
    val chatStyle = type.appCard.copy(fontSize = 14.sp)
    val streamingPlaceholder = stringResource(R.string.common_ellipsis)
    val text = msg.text.ifEmpty { if (streaming) streamingPlaceholder else "" }
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
                text = text,
                style = chatStyle,
                color = fg,
            )
        }
    }
}

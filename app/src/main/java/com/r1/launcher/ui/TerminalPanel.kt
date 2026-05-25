package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onToggleKb: () -> Unit,
    onPaste: () -> Unit,
    onAppendInput: (String) -> Unit = {},
    getClipboardText: () -> String = { "" },
    onMicStart: () -> Unit = {},
    onMicStop: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TERMINAL,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val output = state.terminalOutput
        val listState = rememberLazyListState()
        // Use the locale-aware display family for UI chrome (back pill, status,
        // pills, empty hint) so Arabic glyphs render in Tajawal-Bold instead of
        // the system Monospace fallback. Actual shell output, prompt $, cwd
        // path, and input text stay Monospace — they're shell content.
        val ui = LocalR1Type.current.appCard.fontFamily

        // Auto-snap to bottom when new lines arrive AND user hasn't scrolled up
        // (terminalScrollIndex==0 means "show latest"). When the user scrolls up
        // we map the offset relative to the bottom so the view stays anchored.
        LaunchedEffect(output.size, state.terminalScrollIndex) {
            if (output.isEmpty()) return@LaunchedEffect
            val target = (output.lastIndex - state.terminalScrollIndex)
                .coerceIn(0, output.lastIndex)
            listState.scrollToItem(target)
        }

        var showPaste by remember { mutableStateOf(false) }
        var pasteText by remember { mutableStateOf("") }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            AppPageHeader(
                backFocused = false,
                onBack = onBack,
                themeColor = AppThemes.Terminal,
                compact = true,
                subtitle = compactCwd(state.terminalCwd),
                trailingContent = {
                    Box(
                        modifier = Modifier
                            .clickable { onToggleKb() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.terminalKbVisible) R.string.terminal_kb_hide else R.string.terminal_kb_show
                            ),
                            color = AppThemes.Terminal,
                            fontSize = 12.sp,
                            fontFamily = ui,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    StatusIndicator(state)
                },
            )

            // --- output scrollback ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
            ) {
                if (output.isEmpty()) {
                    Text(
                        text = stringResource(R.string.terminal_empty_hint),
                        color = Color(0xFF777777),
                        fontSize = 13.sp,
                        fontFamily = ui,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(output) { _, line ->
                            Text(
                                text = line,
                                color = lineColor(line),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // --- input row ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .combinedClickable(
                        onClick = { /* keyboard toggles via 'kbd' pill in the header */ },
                        onLongClick = {
                            pasteText = getClipboardText()
                            showPaste = true
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.terminalBusy) stringResource(R.string.common_thinking_dots)
                        else stringResource(R.string.terminal_prompt),
                    color = AppThemes.Terminal,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = " " + state.terminalInput + "_",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .clickable { onPaste() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.common_paste),
                        color = AppThemes.Terminal.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontFamily = ui,
                    )
                }
                Spacer(Modifier.width(4.dp))
                HoldToTalkPill(
                    recording = state.terminalRecording,
                    themeColor = AppThemes.Terminal,
                    onStart = onMicStart,
                    onStop = onMicStop,
                    size = 28.dp,
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clickable { onSubmit() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.terminal_run),
                        color = if (state.terminalInput.isBlank() || state.terminalBusy)
                            Color(0xFF555555) else AppThemes.Terminal,
                        fontSize = 14.sp,
                        fontFamily = ui,
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.terminal_short_clear),
                        color = AppThemes.Terminal.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontFamily = ui,
                    )
                }
            }

            // --- keyboard (collapsible) ---
            if (state.terminalKbVisible) {
                Box(modifier = Modifier.height(176.dp)) {
                    RetroKeyboard(
                        onKeyPress = onKeyPress,
                        onBackspace = onBackspace,
                        // "hide" key collapses the keyboard so the output area
                        // grows; submit lives on the "run" pill + wheel-press.
                        onDismiss = onToggleKb,
                    )
                }
            }
        }

        ClipboardPastePopup(
            visible = showPaste,
            themeColor = AppThemes.Terminal,
            clipboardText = pasteText,
            onPaste = { text ->
                onAppendInput(text)
                showPaste = false
            },
            onDismiss = { showPaste = false },
        )
        }
    }
}

@Composable
private fun StatusIndicator(state: LauncherState) {
    val ui = LocalR1Type.current.appCard.fontFamily
    when {
        state.terminalRecording -> Text(
            text = stringResource(R.string.terminal_status_rec),
            color = Color(0xFFFF4040),
            fontSize = 13.sp,
            fontFamily = ui,
        )
        state.terminalTranscribing -> Text(
            text = stringResource(R.string.terminal_status_stt),
            color = Color(0xFFFFD600),
            fontSize = 13.sp,
            fontFamily = ui,
        )
        state.terminalBusy -> Text(
            text = stringResource(R.string.common_thinking_dots),
            color = AppThemes.Terminal,
            fontSize = 13.sp,
            fontFamily = ui,
        )
        else -> Text(
            text = stringResource(R.string.terminal_status_idle),
            color = Color(0xFF35D26F),
            fontSize = 13.sp,
            fontFamily = ui,
        )
    }
}

private fun compactCwd(cwd: String): String = when {
    cwd == "/sdcard" -> "~"
    cwd.startsWith("/sdcard/") -> "~" + cwd.removePrefix("/sdcard")
    else -> cwd
}

/** Tint exit-code lines, prompt-echo lines, and socket errors so they pop
 *  visually amid plain output. */
private fun lineColor(line: String): Color = when {
    line.startsWith("[exit ") -> Color(0xFFFF4040)
    line.startsWith("[r1-terminal]") -> Color(0xFFFFD600)
    line.contains(" \$ ") || line.endsWith(" \$") -> Color(0xFFFF4500)
    else -> Color(0xFFCCCCCC)
}

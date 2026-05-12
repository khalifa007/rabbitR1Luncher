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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.R
import com.r1.launcher.Panel
import com.r1.launcher.transcriber.MeetingStatus
import com.r1.launcher.transcriber.MeetingStore
import com.r1.launcher.transcriber.TranscriberDetailAction

/**
 * Meetings → detail. Top bar mirrors the list page (back + title + ⋮); the
 * transcript fills the rest of the screen. Tapping ⋮ slides a bottom sheet up
 * with the available actions for the meeting (play/email/retry/delete) — the
 * exact set is computed by the host based on status, written into
 * `state.transcriberDetailMenuActions`, and rendered here in order.
 *
 * Focus indices (kept in lockstep with [com.r1.launcher.LauncherNav]):
 *   menu CLOSED:
 *     0  back pill (header left)
 *     1  ⋮ menu icon (header right)
 *   menu OPEN:
 *     0..N-1 over [LauncherState.transcriberDetailMenuActions]
 */
@Composable
fun TranscriberDetailPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuItemClick: (TranscriberDetailAction) -> Unit,
    onMenuClose: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSCRIBER_DETAIL,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val divider = Color(0xFF222222)
        val dim = Color(0xFFAAAAAA)

        val uuid = state.currentMeetingUuid
        val meeting = remember(uuid, state.meetings.size, state.detailStatus) {
            uuid?.let { MeetingStore.get(ctx).loadMeeting(it) }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (meeting == null) {
                Text(
                    text = stringResource(R.string.transcriber_meeting_not_found),
                    color = Color(0xFF707070),
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@AnimatedVisibility
            }

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderBackPillD(
                        focused = !state.transcriberDetailMenuOpen && state.transcriberDetailFocus == 0,
                        color = orange,
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = meeting.title,
                        color = orange,
                        fontSize = 16.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    HeaderMenuIcon(
                        focused = !state.transcriberDetailMenuOpen && state.transcriberDetailFocus == 1,
                        color = orange,
                        onClick = onMenuOpen,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(divider))
                Spacer(Modifier.height(8.dp))

                // Meta strip — date · duration · speaker count, plus transient status
                val metaLine = buildMetaLine(meeting)
                Text(
                    text = metaLine,
                    color = dim,
                    fontSize = 11.sp,
                    fontFamily = type.appCard.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.detailStatus.isNotBlank()) {
                    Text(
                        text = state.detailStatus,
                        color = Color(0xFF888888),
                        fontSize = 10.sp,
                        fontFamily = type.appCard.fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.detailPlaying) {
                    Text(
                        text = "▶ playing — tap ⋮ → stop",
                        color = Color(0xFFE65100),
                        fontSize = 10.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Transcript body
                val transcriptText = meeting.transcriptText
                    ?: when (meeting.status) {
                        MeetingStatus.RECORDING, MeetingStatus.QUEUED, MeetingStatus.TRANSCRIBING ->
                            "transcript will appear here when ready."
                        MeetingStatus.FAILED -> meeting.errorMessage ?: "transcription failed."
                        else -> ""
                    }
                val rawLines = remember(transcriptText) { transcriptText.split("\n").filter { it.isNotBlank() } }
                val parsedLines = remember(rawLines) { rawLines.map { parseLine(it) } }
                val speakerColors = remember(parsedLines) { buildSpeakerColorMap(parsedLines) }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(transcriptText) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(end = 4.dp, bottom = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(parsedLines) { line ->
                            TranscriptLine(line, speakerColors, type)
                        }
                    }
                }
            }

            // ⋮ menu overlay — action set + focus live in state, populated by
            // [com.r1.launcher.LauncherHost.transcriberOpenDetailMenu] from the
            // current meeting's status.
            ActionMenuOverlay(
                state = state,
                onMenuItemClick = onMenuItemClick,
                onMenuClose = onMenuClose,
            )
        }
    }
}

// ---------- header ----------

@Composable
private fun HeaderBackPillD(focused: Boolean, color: Color, onClick: () -> Unit) {
    val type = LocalR1Type.current
    val bg = if (focused) color else Color.Transparent
    val fg = if (focused) Color.Black else color
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "< back",
            color = fg,
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}

@Composable
private fun HeaderMenuIcon(focused: Boolean, color: Color, onClick: () -> Unit) {
    val type = LocalR1Type.current
    val bg = if (focused) color else Color.Transparent
    val fg = if (focused) Color.Black else color
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⋮",
            color = fg,
            fontSize = 18.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------- transcript ----------

private data class ParsedLine(
    val timestamp: String?,   // e.g. "00:14"
    val speaker: String?,     // raw speaker label, e.g. "speaker 1" or "Alice"
    val body: String,
)

/** Parse a transcript line of the form `[mm:ss] <speaker>: <body>`. Falls back
 *  to a body-only line if the prefix doesn't match (e.g. status placeholder text). */
private fun parseLine(line: String): ParsedLine {
    val m = Regex("^\\[(\\d{2}:\\d{2})\\]\\s+([^:]+?):\\s*(.*)").find(line)
        ?: return ParsedLine(null, null, line)
    return ParsedLine(
        timestamp = m.groupValues[1],
        speaker = m.groupValues[2].trim(),
        body = m.groupValues[3],
    )
}

/** Visually distinct, R1-aesthetic colors. First-encountered speaker gets
 *  index 0 (orange), next gets index 1 (teal), and so on. After 8 speakers
 *  it wraps. This is per-meeting state computed at render time, not persisted. */
private val speakerPalette = listOf(
    Color(0xFFFF6A00),  // orange (R1 primary)
    Color(0xFF4ECDC4),  // teal
    Color(0xFFFFD93D),  // yellow
    Color(0xFFC77DFF),  // purple
    Color(0xFF58D68D),  // green
    Color(0xFFFF8FAB),  // pink
    Color(0xFF5DADE2),  // blue
    Color(0xFFF39C12),  // amber
)

private fun buildSpeakerColorMap(lines: List<ParsedLine>): Map<String, Color> {
    val map = LinkedHashMap<String, Color>()
    for (line in lines) {
        val s = line.speaker ?: continue
        if (s !in map) map[s] = speakerPalette[map.size % speakerPalette.size]
    }
    return map
}

@Composable
private fun TranscriptLine(line: ParsedLine, speakerColors: Map<String, Color>, type: R1Type) {
    if (line.speaker == null) {
        // No speaker prefix — just dim status text.
        Text(
            text = line.body,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        return
    }
    val accent = speakerColors[line.speaker] ?: Color.White
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.timestamp.orEmpty(),
                color = Color(0xFF666666),
                fontSize = 10.sp,
                fontFamily = type.appCard.fontFamily,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = line.speaker,
                color = accent,
                fontSize = 11.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = line.body,
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = type.appCard.fontFamily,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ---------- meta line ----------

private fun buildMetaLine(meeting: com.r1.launcher.transcriber.Meeting): String {
    val parts = mutableListOf<String>()
    parts += humanDateD(meeting.createdAtMs)
    if (meeting.durationMs > 0) parts += formatDurationD(meeting.durationMs)
    when (meeting.status) {
        MeetingStatus.RECORDING -> parts += "recording…"
        MeetingStatus.QUEUED -> parts += "queued"
        MeetingStatus.TRANSCRIBING -> parts += "transcribing…"
        MeetingStatus.TRANSCRIBED -> {
            val n = meeting.speakerCount.coerceAtLeast(0)
            if (n > 0) parts += "$n speaker${if (n == 1) "" else "s"}"
            meeting.languageCode?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        MeetingStatus.FAILED -> parts += "failed"
    }
    return parts.joinToString(" · ")
}

private fun humanDateD(epochMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val that = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameYear = now.get(java.util.Calendar.YEAR) == that.get(java.util.Calendar.YEAR)
    val sameDay = sameYear &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == that.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = sameYear &&
        now.get(java.util.Calendar.DAY_OF_YEAR) - that.get(java.util.Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> "Today"
        yesterday -> "Yesterday"
        sameYear -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(epochMs))
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(epochMs))
    }
}

private fun formatDurationD(ms: Long): String {
    val total = (ms / 1000L).toInt()
    val mins = total / 60
    val secs = total % 60
    return "%d:%02d".format(mins, secs)
}

// ---------- action menu overlay ----------

@Composable
private fun ActionMenuOverlay(
    state: LauncherState,
    onMenuItemClick: (TranscriberDetailAction) -> Unit,
    onMenuClose: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.transcriberDetailMenuOpen,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
    ) {
        // Scrim — taps outside the sheet dismiss the menu.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onMenuClose,
                ),
        ) {
            // The sheet itself slides up from the bottom with its own animation.
            AnimatedVisibility(
                visible = state.transcriberDetailMenuOpen,
                enter = slideInVertically(tween(200)) { it },
                exit = slideOutVertically(tween(150)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ActionSheet(
                    state = state,
                    onMenuItemClick = onMenuItemClick,
                    onMenuClose = onMenuClose,
                )
            }
        }
    }
}

@Composable
private fun ActionSheet(
    state: LauncherState,
    onMenuItemClick: (TranscriberDetailAction) -> Unit,
    onMenuClose: () -> Unit,
) {
    val type = LocalR1Type.current
    val orange = Color(0xFFFF4500)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, orange, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            // Eat clicks so taps on the sheet body don't dismiss via the scrim.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.transcriber_menu_actions),
                color = Color(0xFF888888),
                fontSize = 11.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            state.transcriberDetailMenuActions.forEachIndexed { idx, action ->
                ActionRow(
                    action = action,
                    isPlaying = state.detailPlaying,
                    focused = state.transcriberDetailMenuFocus == idx,
                    onClick = {
                        if (action == TranscriberDetailAction.CLOSE) onMenuClose()
                        else onMenuItemClick(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    action: TranscriberDetailAction,
    isPlaying: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val (label, accent) = labelAndColorFor(action, isPlaying)
    val bg = if (focused) accent else Color.Transparent
    val fg = if (focused) Color.Black else accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun labelAndColorFor(action: TranscriberDetailAction, isPlaying: Boolean): Pair<String, Color> = when (action) {
    TranscriberDetailAction.PLAY_TOGGLE -> (if (isPlaying) "■ stop playback" else "▶ play audio") to Color(0xFFFF4500)
    TranscriberDetailAction.EMAIL -> "✉ send to email" to Color(0xFF35D26F)
    TranscriberDetailAction.RETRY -> "↻ retry transcription" to Color(0xFFFFA726)
    TranscriberDetailAction.DELETE -> "✕ delete meeting" to Color(0xFFE53935)
    TranscriberDetailAction.CLOSE -> "× close" to Color(0xFF888888)
}

package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
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
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.transcriber.MeetingIndexEntry
import com.r1.launcher.transcriber.MeetingStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Meetings → list.
 *
 *   [< back]                          [+] [⚙]
 *   🎙 meetings
 *   ──────────────────────────────────────
 *   ●  Standup                          ← focus 3
 *      May 10 · 12:34
 *
 * Focus indices:
 *   0       back pill
 *   1       + (start/stop recording)
 *   2       settings gear
 *   3..N+2  one row per saved meeting
 */
@Composable
fun TranscriberListPanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSCRIBER_LIST,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val dim = Color(0xFF707070)

        val listState = rememberLazyListState()
        LaunchedEffect(state.transcriberListFocus, state.meetings.size) {
            val target = when {
                state.transcriberListFocus <= 2 -> 0
                else -> (state.transcriberListFocus - 2).coerceAtMost(state.meetings.size)
            }
            listState.animateScrollToItem(target)
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_meetings,
                        title = stringResource(R.string.transcriber_list_title).lowercase(),
                        backFocused = state.transcriberListFocus == 0,
                        onBack = { onRowClick(0) },
                        plusFocused = state.transcriberListFocus == 1,
                        onPlus = { onRowClick(1) },
                        gearFocused = state.transcriberListFocus == 2,
                        onGear = { onRowClick(2) },
                        themeColor = AppThemes.Meetings,
                    )
                }

                // Live recording status indicator just under the header — the
                // + button itself flips behavior to "stop" while recording, but
                // an explicit indicator helps confirm what's happening.
                if (state.recordingActive || state.transcribeBusy) {
                    item(key = "rec_status") {
                        RecordingStatusBar(
                            recording = state.recordingActive,
                            transcribing = state.transcribeBusy,
                            color = orange,
                        )
                    }
                }

                if (state.meetings.isEmpty()) {
                    item(key = "empty_state") {
                        Text(
                            text = stringResource(R.string.transcriber_no_recordings),
                            color = dim,
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = state.meetings,
                        key = { _, m -> m.uuid },
                    ) { idx, m ->
                        val rowIdx = 3 + idx
                        MeetingRow(
                            meeting = m,
                            focused = state.transcriberListFocus == rowIdx,
                            color = orange,
                            onClick = { onRowClick(rowIdx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatusBar(
    recording: Boolean,
    transcribing: Boolean,
    color: Color,
) {
    val type = LocalR1Type.current
    val pulseAlpha = if (recording) {
        val t = rememberInfiniteTransition(label = "rec_pulse")
        val a by t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        a
    } else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = pulseAlpha), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                recording -> "recording…"
                transcribing -> stringResource(R.string.transcriber_transcribing)
                else -> ""
            },
            color = color,
            fontSize = 11.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MeetingRow(
    meeting: MeetingIndexEntry,
    focused: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val (statusColor, statusGlyph) = statusVisuals(meeting.status)
    val titleColor = if (focused) Color.Black else Color.White
    val subColor = if (focused) Color(0xFF333333) else Color(0xFF888888)
    val rowBg = if (focused) color.copy(alpha = 0.85f) else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (statusGlyph == null) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (focused) Color.Black else statusColor),
                )
            } else {
                Text(
                    text = statusGlyph,
                    color = if (focused) Color.Black else statusColor,
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                    modifier = Modifier.width(10.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    color = titleColor,
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitleFor(meeting),
                    color = subColor,
                    fontSize = 11.sp,
                    fontFamily = type.appCard.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusVisuals(status: MeetingStatus): Pair<Color, String?> = when (status) {
    MeetingStatus.RECORDING -> Color(0xFFFF4500) to null
    MeetingStatus.QUEUED -> Color(0xFFFFA726) to null
    MeetingStatus.TRANSCRIBING -> Color(0xFFFFA726) to null
    MeetingStatus.TRANSCRIBED -> Color(0xFF35D26F) to null
    MeetingStatus.FAILED -> Color(0xFFE53935) to "⚠"
}

private fun subtitleFor(m: MeetingIndexEntry): String {
    val datePart = humanDate(m.createdAtMs)
    val statusOrDuration = when (m.status) {
        MeetingStatus.RECORDING -> "recording…"
        MeetingStatus.QUEUED -> "queued"
        MeetingStatus.TRANSCRIBING -> "transcribing…"
        MeetingStatus.TRANSCRIBED -> formatDuration(m.durationMs)
        MeetingStatus.FAILED -> "failed"
    }
    return "$datePart · $statusOrDuration"
}

private fun humanDate(epochMs: Long): String {
    val now = Calendar.getInstance()
    val that = Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameYear = now.get(Calendar.YEAR) == that.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear &&
        now.get(Calendar.DAY_OF_YEAR) - that.get(Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> "Today"
        yesterday -> "Yesterday"
        sameYear -> SimpleDateFormat("MMM d", Locale.US).format(Date(epochMs))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMs))
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val total = (ms / 1000L).toInt()
    val mins = total / 60
    val secs = total % 60
    return "%d:%02d".format(mins, secs)
}

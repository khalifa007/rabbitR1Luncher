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
import androidx.compose.ui.res.painterResource
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
 * Meetings → list. Layout:
 *
 *   [< back]   meetings        [⚙]      ← header row (focus 0 = back, focus 1 = gear)
 *   ─────────────────────────────────
 *   ┌─ + new recording ──────────┐     ← focus 2 (or "■ stop recording" while live)
 *   └────────────────────────────┘
 *   ●  Standup                          ← focus 3
 *      May 10 · 12:34
 *   ●  Sync with Sara                   ← focus 4
 *      Yesterday · 03:21
 *   ⚠  Q1 review                        ← focus 5
 *      May 8 · failed
 *
 * Focus indices kept in lockstep with [com.r1.launcher.LauncherNav]:
 *   0       back pill (header left)
 *   1       settings gear (header right)
 *   2       + new recording / stop recording
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
        val divider = Color(0xFF222222)
        val dim = Color(0xFF707070)

        // The header (back, title, gear) lives outside the LazyColumn so it
        // never scrolls off. The list below holds rows starting at focus 2.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderBackPill(
                        focused = state.transcriberListFocus == 0,
                        color = orange,
                        onClick = { onRowClick(0) },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.transcriber_list_title),
                        color = orange,
                        fontSize = 22.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    HeaderGearIcon(
                        focused = state.transcriberListFocus == 1,
                        color = orange,
                        onClick = { onRowClick(1) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(divider),
                )
                Spacer(Modifier.height(8.dp))

                // Content list — record row + meetings. focus 2 = record (item 0).
                val listState = rememberLazyListState()
                LaunchedEffect(state.transcriberListFocus, state.meetings.size) {
                    val contentIdx = (state.transcriberListFocus - 2).coerceAtLeast(0)
                    val target = contentIdx.coerceAtMost(state.meetings.size)
                    if (state.transcriberListFocus >= 2) listState.animateScrollToItem(target)
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "rec_row") {
                        RecordRow(
                            label = if (state.recordingActive) "■ stop recording" else "+ new recording",
                            focused = state.transcriberListFocus == 2,
                            recording = state.recordingActive,
                            transcribing = state.transcribeBusy,
                            color = orange,
                            onClick = { onRowClick(2) },
                        )
                    }
                    if (state.meetings.isEmpty()) {
                        item(key = "empty_state") {
                            Text(
                                text = stringResource(R.string.transcriber_no_recordings),
                                color = dim,
                                fontSize = 12.sp,
                                fontFamily = type.appCard.fontFamily,
                                modifier = Modifier.padding(start = 6.dp, top = 12.dp),
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
}

@Composable
private fun HeaderBackPill(focused: Boolean, color: Color, onClick: () -> Unit) {
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
private fun HeaderGearIcon(focused: Boolean, color: Color, onClick: () -> Unit) {
    val bg = if (focused) color else Color.Transparent
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = "settings",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                if (focused) Color.Black else color
            ),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun RecordRow(
    label: String,
    focused: Boolean,
    recording: Boolean,
    transcribing: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = if (focused) color else Color.Transparent
    val fg = if (focused) Color.Black else color
    // Pulse the dot when actively recording so the row reads as "live" at a
    // glance from the list page.
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

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(
                    width = 1.dp,
                    color = if (recording) color.copy(alpha = pulseAlpha) else color,
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 14.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
        if (transcribing && !recording) {
            Text(
                text = stringResource(R.string.transcriber_transcribing),
                color = Color(0xFFE65100),
                fontSize = 10.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
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
            // Status indicator — colored dot for normal states, ⚠ glyph for failed.
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

/** Status → (dot color, optional glyph that replaces the dot). */
private fun statusVisuals(status: MeetingStatus): Pair<Color, String?> = when (status) {
    MeetingStatus.RECORDING -> Color(0xFFFF4500) to null
    MeetingStatus.QUEUED -> Color(0xFFFFA726) to null
    MeetingStatus.TRANSCRIBING -> Color(0xFFFFA726) to null
    MeetingStatus.TRANSCRIBED -> Color(0xFF35D26F) to null
    MeetingStatus.FAILED -> Color(0xFFE53935) to "⚠"
}

/**
 * Returns the per-row metadata line, e.g. "May 10 · 12:34" for a transcribed
 * meeting. For RECORDING/QUEUED/TRANSCRIBING/FAILED we replace the duration
 * with a status word so the line stays informative even when there's no
 * playback length yet.
 */
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

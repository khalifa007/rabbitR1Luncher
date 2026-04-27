package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun OpenClawTalkPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onToggleRecord: () -> Unit,
    onSpeakerChange: (Boolean) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_TALK,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val latestUser = state.chatMessages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val latestAssistant = state.chatStreamingText.ifBlank {
            state.chatMessages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        }
        val mode = when {
            state.chatRecording -> "listening"
            state.chatTranscribing -> "hearing"
            state.chatStreamingText.isNotBlank() -> if (state.chatTtsEnabled) "speaking" else "replying"
            state.chatBusy -> "thinking"
            state.chatStatus == "live" -> "ready"
            else -> state.chatStatus
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(label = "chat", onClick = onBack)
                Spacer(Modifier.weight(1f))
                TalkStatusDot(mode = mode, status = state.chatStatus)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = mode,
                    style = type.appCard.copy(fontSize = 22.sp),
                    color = if (state.chatStatus.startsWith("error")) Color(0xFFE53935) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                SpeakerPill(
                    enabled = state.chatTtsEnabled,
                    onClick = { onSpeakerChange(!state.chatTtsEnabled) },
                )
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                TalkRing(
                    level = state.chatInputLevel,
                    active = state.chatRecording,
                    busy = state.chatBusy || state.chatTranscribing || state.chatStreamingText.isNotBlank(),
                    modifier = Modifier.size(190.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            state.chatRecording -> "listen"
                            state.chatBusy || state.chatTranscribing -> "think"
                            else -> "claw"
                        },
                        style = type.clock.copy(fontSize = 40.sp, textAlign = TextAlign.Center),
                        color = if (state.chatRecording) Color(0xFFFF4500) else Color.White,
                    )
                    Text(
                        text = when {
                            state.chatRecording -> "${state.chatInputLevel.coerceIn(0, 100)}%"
                            state.chatTranscribing -> "stt"
                            state.chatBusy -> "..."
                            state.chatStatus == "live" -> "openclaw"
                            else -> state.chatStatus.take(18)
                        },
                        style = type.appCard.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                        color = Color(0xFFFF6A00),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TalkSnippet(
                label = "you",
                text = latestUser,
                accent = Color(0xFFFF4500),
            )
            Spacer(Modifier.height(8.dp))
            TalkSnippet(
                label = "claw",
                text = latestAssistant,
                accent = Color(0xFF35D26F),
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (state.chatRecording) Color(0xFFFF4500) else Color(0xFF121214))
                    .border(2.dp, Color(0xFFFF4500), RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleRecord),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.chatRecording) "release to send" else "hold to talk",
                    style = type.appCard.copy(fontSize = 24.sp, textAlign = TextAlign.Center),
                    color = if (state.chatRecording) Color.Black else Color(0xFFFF4500),
                )
            }
        }
    }
}

@Composable
private fun TalkRing(
    level: Int,
    active: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "talkPulse")
    val idlePulse by pulse.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idlePulse",
    )
    val target = when {
        active -> (level / 100f).coerceIn(0.08f, 1f)
        busy -> 0.72f
        else -> idlePulse
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "talkLevel",
    )
    val accent = if (active) Color(0xFFFF4500) else Color(0xFFFF6A00)
    Canvas(modifier = modifier) {
        val stroke = 10.dp.toPx()
        drawCircle(
            color = Color(0xFF242429),
            radius = size.minDimension / 2f - stroke,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(
            color = accent.copy(alpha = if (active) 0.22f else 0.1f),
            radius = (size.minDimension / 2f - stroke * 2f) * (0.62f + animated * 0.14f),
        )
    }
}

@Composable
private fun TalkStatusDot(mode: String, status: String) {
    val color = when {
        status.startsWith("error") -> Color(0xFFE53935)
        mode == "listening" -> Color(0xFFFF4500)
        mode == "thinking" || mode == "hearing" -> Color(0xFFFFC107)
        status == "live" -> Color(0xFF35D26F)
        else -> Color(0xFF777777)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun SpeakerPill(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFFF4500) else Color(0xFF151518))
            .border(2.dp, Color(0xFFFF4500), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (enabled) "voice" else "off",
            style = LocalR1Type.current.appCard.copy(fontSize = 16.sp),
            color = if (enabled) Color.Black else Color(0xFFFF4500),
        )
    }
}

@Composable
private fun TalkSnippet(label: String, text: String, accent: Color) {
    val type = LocalR1Type.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1F2023))
            .border(2.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 16.sp),
            color = accent,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = text.ifBlank { "..." },
            style = type.appCard.copy(fontSize = 18.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

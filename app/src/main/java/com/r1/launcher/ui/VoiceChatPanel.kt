package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ChatGPT-style fullscreen voice surface. Opened by tapping the mic icon in
 * a chat panel. Drives the hands-free conversation loop: mic listens → VAD
 * commits → agent replies → TTS plays → mic re-opens. State comes from the
 * LauncherState fields the activity already maintains for the regular chat
 * panels — recording / transcribing / busy / speaking flags drive the orb
 * animation and status text.
 *
 * Each conversational state has its OWN visual personality so the user can
 * tell at a glance what the agent is doing without reading text:
 *   listening  → sonar rings ripple outward, core scales with mic level
 *   thinking   → 3 dots orbit the core
 *   speaking   → core breathes hard (aggressive pulse)
 *   transcribing → soft yellow shimmer
 *   connecting → static orb (no animation = nothing happening yet)
 *
 * Two visual identities (OpenClaw orange vs Hermes amber) share this one
 * composable via the [themeColor] / [serviceLabel] args so we don't end up
 * with two near-identical files diverging over time.
 */
@Composable
fun VoiceChatPanel(
    state: LauncherState,
    visible: Boolean,
    themeColor: Color,
    serviceLabel: String,
    recording: Boolean,
    transcribing: Boolean,
    busy: Boolean,
    speaking: Boolean,
    partialText: String,
    micLevel: Int,
    onEnd: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + scaleIn(tween(ANIM_OPEN_MS), initialScale = 0.92f),
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + scaleOut(tween(ANIM_CLOSE_MS), targetScale = 0.92f),
    ) {
        val type = LocalR1Type.current
        // AI-side states outrank mic-side states. In conversation mode the
        // mic is open continuously (recording=true even while the AI is
        // processing your last turn), so without this priority order the
        // status always reads "listening" and the user can't tell whether
        // the AI is actually doing anything. Order: speaking → thinking →
        // transcribing → listening → connecting so the most informative
        // signal wins.
        val voiceState = when {
            speaking -> VoiceState.SPEAKING
            busy -> VoiceState.THINKING
            transcribing -> VoiceState.TRANSCRIBING
            recording -> VoiceState.LISTENING
            else -> VoiceState.CONNECTING
        }
        val statusText = when (voiceState) {
            VoiceState.LISTENING -> "listening"
            VoiceState.TRANSCRIBING -> "transcribing"
            VoiceState.THINKING -> "thinking"
            VoiceState.SPEAKING -> "speaking"
            VoiceState.CONNECTING -> "connecting"
        }
        val statusColor = when (voiceState) {
            VoiceState.LISTENING -> Color(0xFF35D26F)
            VoiceState.TRANSCRIBING -> Color(0xFFFFC107)
            VoiceState.THINKING -> Color(0xFFFFC107)
            VoiceState.SPEAKING -> themeColor
            VoiceState.CONNECTING -> Color(0xFF888888)
        }
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header — back pill + service label + live status chip with pulsing dot.
            // Chip gives a glanceable reading even if the user can't see the orb
            // (e.g. hand covering the screen).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clickable(onClick = onEnd)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "< back",
                        style = type.appCard.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        color = themeColor,
                    )
                }
                Spacer(Modifier.weight(1f))
                StateChip(statusText = statusText, statusColor = statusColor)
            }

            // Service label centered just under the header — confirms which AI
            // the user is talking to without taking space from the orb.
            Spacer(Modifier.height(4.dp))
            Text(
                text = serviceLabel,
                style = type.appCard.copy(fontSize = 11.sp),
                color = Color(0xFF666666),
            )

            // Center: animated orb + big status label + partial transcript.
            // Weighted so the orb stays vertically centered between header
            // and footer regardless of partial-transcript height.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedVoiceOrb(
                    voiceState = voiceState,
                    statusColor = statusColor,
                    micLevel = micLevel,
                )
                Spacer(Modifier.height(14.dp))
                StateLabel(
                    text = statusText,
                    color = statusColor,
                    showDots = voiceState != VoiceState.CONNECTING,
                )
                if (partialText.isNotBlank() && (recording || transcribing)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "“$partialText”",
                        style = type.appCard.copy(fontSize = 12.sp),
                        color = Color(0xFFAAAAAA),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }

            EndCallButton(onClick = onEnd)
            Spacer(Modifier.height(14.dp))
        }
    }
}

private enum class VoiceState { CONNECTING, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

/**
 * Header status chip — small rounded rect with a pulsing color-coded dot
 * and the state name. Mirrors what the orb says but stays visible in the
 * corner of the eye while the user looks at the orb.
 */
@Composable
private fun StateChip(statusText: String, statusColor: Color) {
    val type = LocalR1Type.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusColor)
                .pulse(active = true, peakScale = 1.4f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusText,
            style = type.appCard.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = statusColor,
        )
    }
}

/**
 * Big bold status label below the orb, with sequential animated dots for
 * active states. Dots give a subtle "still working" signal so the user
 * doesn't think the loop has hung if the AI takes a long time to think.
 *
 * Dots disabled for CONNECTING because that state truly is idle — animation
 * would imply something is happening when nothing is.
 */
@Composable
private fun StateLabel(text: String, color: Color, showDots: Boolean) {
    val type = LocalR1Type.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            style = type.appCard.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            color = color,
            textAlign = TextAlign.Center,
        )
        if (showDots) {
            AnimatedDots(color = color)
        }
    }
}

@Composable
private fun AnimatedDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val type = LocalR1Type.current
    Row(modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 180),
                ),
                label = "dotAlpha$i",
            )
            Text(
                text = ".",
                style = type.appCard.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = color.copy(alpha = alpha),
            )
        }
    }
}

/**
 * The orb itself. Always renders a 3-layer base (faint outer halo + mid ring
 * + solid core). On top of that base, each state adds its own animation
 * layer so the four states are immediately distinguishable visually.
 *
 * Mic-level scaling only applies during LISTENING — using it elsewhere would
 * cause spurious twitches from echo in the speaker → mic loop.
 */
@Composable
private fun AnimatedVoiceOrb(
    voiceState: VoiceState,
    statusColor: Color,
    micLevel: Int,
) {
    val baseSize = 130.dp
    val containerSize = 230.dp

    Box(
        modifier = Modifier.size(containerSize),
        contentAlignment = Alignment.Center,
    ) {
        // State-specific outer animation layer (behind the core).
        when (voiceState) {
            VoiceState.LISTENING -> SonarRings(color = statusColor, baseSize = baseSize)
            VoiceState.SPEAKING -> SpeakingPulse(color = statusColor, baseSize = baseSize)
            VoiceState.THINKING -> OrbitingDots(color = statusColor, baseSize = baseSize)
            VoiceState.TRANSCRIBING -> ShimmerHalo(color = statusColor, baseSize = baseSize)
            VoiceState.CONNECTING -> StaticHalo(color = statusColor, baseSize = baseSize)
        }

        // Mic-level reactive scale, smoothed so quantised samples don't jitter.
        val levelScale by animateFloatAsState(
            targetValue = if (voiceState == VoiceState.LISTENING)
                1f + (micLevel.coerceIn(0, 100) / 100f) * 0.30f
            else 1f,
            animationSpec = tween(120),
            label = "levelScale",
        )

        // Mid ring — gentle always-on breathing, modulated by mic level during
        // listening so the user sees their own voice registering on the orb.
        val transition = rememberInfiniteTransition(label = "orbMid")
        val midPulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "midPulse",
        )
        Box(
            modifier = Modifier
                .size(baseSize * 0.78f)
                .graphicsLayer {
                    scaleX = midPulse * levelScale
                    scaleY = midPulse * levelScale
                }
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.35f)),
        )

        // Solid core — the brightest, most-saturated thing on screen. Anchors
        // the eye so the user always knows where to look.
        Box(
            modifier = Modifier
                .size(baseSize * 0.48f)
                .graphicsLayer {
                    scaleX = levelScale
                    scaleY = levelScale
                }
                .clip(CircleShape)
                .background(statusColor),
        )
    }
}

/**
 * Listening visualization: 3 expanding sonar rings, staggered by 1/3 of the
 * cycle each so there's always one mid-flight. The "I'm picking up your
 * voice" signal.
 */
@Composable
private fun SonarRings(color: Color, baseSize: Dp) {
    val transition = rememberInfiniteTransition(label = "sonar")
    val cycleMs = 2000
    repeat(3) { i ->
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset((cycleMs / 3) * i),
            ),
            label = "sonar$i",
        )
        Box(
            modifier = Modifier
                .size(baseSize)
                .graphicsLayer {
                    val s = 0.7f + progress * 0.8f
                    scaleX = s
                    scaleY = s
                    alpha = (1f - progress) * 0.55f
                }
                .clip(CircleShape)
                .background(color.copy(alpha = 0.20f)),
        )
    }
}

/**
 * Speaking visualization: outer ring breathes hard and fast — different
 * cadence from listening so the eye reads it as "AI talking" not "mic open".
 */
@Composable
private fun SpeakingPulse(color: Color, baseSize: Dp) {
    val transition = rememberInfiniteTransition(label = "speak")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakPulse",
    )
    val alphaPulse by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakAlpha",
    )
    Box(
        modifier = Modifier
            .size(baseSize * 1.05f)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
                alpha = alphaPulse
            }
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Thinking visualization: 3 dots orbit the core at 120° apart, full rotation
 * every 1.8s. Reads like a loading spinner but keeps the orb shape consistent.
 */
@Composable
private fun OrbitingDots(color: Color, baseSize: Dp) {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbitAngle",
    )
    val density = LocalDensity.current
    val radiusPx = with(density) { (baseSize * 0.55f).toPx() }
    val dotRadiusPx = with(density) { 5.dp.toPx() }
    Canvas(modifier = Modifier.size(baseSize * 1.4f)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(3) { i ->
            val a = (angle + i * 120f) * (PI / 180.0)
            val x = center.x + radiusPx * cos(a).toFloat()
            val y = center.y + radiusPx * sin(a).toFloat()
            drawCircle(color = color, radius = dotRadiusPx, center = Offset(x, y))
        }
    }
}

/**
 * Transcribing visualization: outer halo shimmers between two alphas. Quieter
 * than listening / speaking because transcribing is a brief in-between state
 * — we don't want to fight the listening animation it just transitioned from.
 */
@Composable
private fun ShimmerHalo(color: Color, baseSize: Dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    Box(
        modifier = Modifier
            .size(baseSize)
            .graphicsLayer { alpha = alphaAnim }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.40f)),
    )
}

/**
 * Connecting visualization: static halo, no motion. Deliberately dead — the
 * absence of animation tells the user nothing is happening yet (vs the
 * other states which all have ongoing motion).
 */
@Composable
private fun StaticHalo(color: Color, baseSize: Dp) {
    Box(
        modifier = Modifier
            .size(baseSize)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.25f)),
    )
}

@Composable
private fun EndCallButton(onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xFFE53935))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_voice),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "end",
                style = type.appCard.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

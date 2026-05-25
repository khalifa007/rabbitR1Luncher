package com.r1.launcher.ui

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.r1.launcher.R

/** Timings from activity_main.xml animations (ANIM_OPEN / FOCUS). */
internal const val ANIM_OPEN_MS = 220
internal const val ANIM_CLOSE_MS = 170
internal const val ANIM_FOCUS_MS = 140

internal const val FOCUS_SCALE = 1.04f
internal const val UNFOCUS_ALPHA = 0.55f

internal val EnterEasing = LinearOutSlowInEasing
internal val ExitEasing = FastOutLinearInEasing

/**
 * Applies the focus animation (scale + alpha) used by apps/store rows.
 * Kept as a Modifier so it composes cleanly with other modifiers.
 *
 * Scale uses a low-stiffness spring for a subtle bounce on snap-to-focus;
 * alpha stays on a tween so unfocused fade is monotonic (no over/undershoot
 * past 0..1, which would clip).
 */
@Composable
fun Modifier.focusAnim(focused: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) FOCUS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "focusScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else UNFOCUS_ALPHA,
        animationSpec = tween(ANIM_FOCUS_MS, easing = FastOutSlowInEasing),
        label = "focusAlpha",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/**
 * Subtle infinite "alive" pulse — 1.00 ↔ 1.02 scale, 1500ms reverse.
 * Used on small accent chips (LTE pill, recording indicator) to avoid
 * the dead-static feel without competing with focus animations.
 *
 * Disabled when [active] is false (returns identity modifier — important
 * because rememberInfiniteTransition keeps animating even off-screen).
 */
@Composable
fun Modifier.pulse(active: Boolean = true, peakScale: Float = 1.06f): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = peakScale,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Reusable sheet/list row — transparent when idle, tinted accent-orange when focused.
 * Matches drawable/tile_bg.xml (state_selected → #33FF6A00, corner 8dp).
 */
@Composable
fun TileRow(
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = LocalR1Colors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .focusAnim(focused)
            .background(
                color = if (focused) colors.tileFocus else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        content = content,
    )
}

/**
 * Three-dot overflow icon used to open in-panel drawer menus. Lives in Common
 * so both OpenClaw and Hermes chat headers can share it. Color tracks the
 * caller's theme; [focused] inverts colors to give visual feedback while the
 * menu is expanded.
 */
@Composable
fun MenuDot(
    themeColor: Color,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (focused) themeColor else Color.Transparent
    val dotColor = if (focused) Color.Black else themeColor
    // Pixel-art overflow: hard-cornered focus tile + 3 stacked square dots.
    Box(
        modifier = Modifier
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(dotColor),
                )
            }
        }
    }
}

/**
 * Press-and-hold mic pill for chat / terminal input rows. Press = onStart,
 * release / cancel / pointer-leave = onStop — same contract as the side-
 * button push-to-talk so the host handlers are reused as-is.
 *
 * awaitRelease's finally branch fires for both clean release and gesture
 * cancellation, so onStop is guaranteed to run; without it a swipe-off mid
 * record would leak the mic open until the next start.
 */
@Composable
fun HoldToTalkPill(
    recording: Boolean,
    themeColor: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val bg = if (recording) themeColor else Color.Transparent
    val tint = if (recording) Color.Black else themeColor
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .pulse(active = recording, peakScale = 1.10f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onStart()
                        try {
                            awaitRelease()
                        } finally {
                            onStop()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_voice),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Tap-to-open voice-page button used in chat input rows. Replaces the older
 * press-and-hold idiom — a single tap launches the fullscreen voice panel
 * (VoiceChatPanel), which then handles the whole hands-free conversation
 * loop on its own. Same visual footprint as HoldToTalkPill so the input
 * row geometry doesn't shift.
 */
@Composable
fun VoiceLaunchPill(
    themeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_voice),
            contentDescription = null,
            colorFilter = ColorFilter.tint(themeColor),
            modifier = Modifier.size(size * 0.65f),
        )
    }
}

/**
 * Compact send-arrow button used in chat input rows. Themed via [tint]; goes
 * dim-gray when [enabled] is false so empty inputs read as un-tappable.
 */
@Composable
fun SendIconButton(
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val effective = if (enabled) tint else Color(0xFF555555)
    Box(
        modifier = modifier
            .size(size)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_send),
            contentDescription = null,
            colorFilter = ColorFilter.tint(effective),
            modifier = Modifier.size(size * 0.70f),
        )
    }
}

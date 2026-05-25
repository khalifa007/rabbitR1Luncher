package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import kotlinx.coroutines.delay

/**
 * Home: spacer(22dp) to clear the topbar, flex clock/date block, dock row, 9sp hint.
 * Paddings from the old activity_main.xml: horizontal 14dp, top 6dp, bottom 14dp.
 *
 * [onOpenNotifications] is invoked when the user taps the unread badge above the
 * clock. Wired through to `state.openNotifications()` in LauncherRoot so the
 * badge is the primary touch path to the panel.
 */
@Composable
fun HomeScreen(
    state: LauncherState,
    onOpenNotifications: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    AnimatedVisibility(
        visible = state.panel == Panel.HOME,
        enter = fadeIn(tween(ANIM_OPEN_MS, easing = EnterEasing)) +
            slideInVertically(tween(ANIM_OPEN_MS, easing = EnterEasing)) { -it / 3 } +
            scaleIn(tween(ANIM_OPEN_MS, easing = EnterEasing), initialScale = 0.96f),
        exit = fadeOut(tween(ANIM_CLOSE_MS, easing = ExitEasing)) +
            slideOutVertically(tween(ANIM_CLOSE_MS, easing = ExitEasing)) { -it / 3 } +
            scaleOut(tween(ANIM_CLOSE_MS, easing = ExitEasing), targetScale = 1.04f),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    // Swipe-up from bottom 40% opens the apps grid. Gated to the
                    // lower band so the gesture can't be mistaken for a tap on
                    // the notification badge sitting under the clock. Triggers
                    // on ~80dp upward travel; consumed only after we know the
                    // drag started in the armed zone.
                    var totalDy = 0f
                    var armed = false
                    val triggerPx = 80.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            armed = offset.y > size.height * 0.6f
                            totalDy = 0f
                        },
                        onVerticalDrag = { change, dy ->
                            if (armed) {
                                totalDy += dy
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (armed && totalDy < -triggerPx) onOpenApps()
                            armed = false
                            totalDy = 0f
                        },
                        onDragCancel = {
                            armed = false
                            totalDy = 0f
                        },
                    )
                }
                .padding(horizontal = 14.dp)
                .padding(top = 6.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(22.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Date + clock are time strings — read LTR even in Arabic
                // (digits are LTR-strong, Arabic AM/PM markers are RTL-strong;
                // forcing LTR keeps "ص 2:34" rendering as "2:34 ص" instead of
                // bidi-flipping the components mid-string).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(state.dateText.lowercase(), style = type.date, color = Color(0xFFFF6B00)) // Bright Orange
                    Spacer(Modifier.height(6.dp))
                    Text(state.clockText.lowercase(), style = type.clock, color = colors.labelBright)
                }
                // Unread badge directly under the clock — orange outlined chip
                // that hides when count == 0. Tap opens the notifications panel.
                Spacer(Modifier.height(8.dp))
                NotificationBadge(
                    count = state.notificationsUnread,
                    onClick = onOpenNotifications,
                )
                if (state.wifiShareEnabled) {
                    val n = state.wifiShareConnectedClients.size
                    val label = when (n) {
                        0 -> stringResource(R.string.home_hotspot_zero)
                        1 -> stringResource(R.string.home_hotspot_one)
                        else -> stringResource(R.string.home_hotspot_many, n)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
            }

            SwipeUpHint()
        }
    }
}

/** Bottom-center chevron hint that signals the swipe-up-to-apps gesture. Two
 *  short orange strokes forming `︿`, breathing alpha so it doesn't read as a
 *  static UI chrome element. Sits inside the home Column's bottom padding. */
@Composable
private fun SwipeUpHint() {
    val transition = rememberInfiniteTransition(label = "swipeHint")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "swipeHintAlpha",
    )
    Canvas(
        modifier = Modifier
            .size(width = 22.dp, height = 6.dp),
    ) {
        val w = size.width
        val h = size.height
        val color = Color(0xFFFF6B00).copy(alpha = alpha)
        // Two strokes meeting at top-center → `︿`
        drawLine(
            color = color,
            start = Offset(0f, h),
            end = Offset(w / 2f, 0f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w / 2f, 0f),
            end = Offset(w, h),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun NotificationBadge(count: Int, onClick: () -> Unit) {
    val type = LocalR1Type.current
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + scaleIn(tween(ANIM_OPEN_MS), initialScale = 0.8f),
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + scaleOut(tween(ANIM_CLOSE_MS), targetScale = 0.8f),
    ) {
        // Outlined chip — orange 1px border, transparent fill, sharp 3dp
        // corners. Matches the 2px-edge tile aesthetic used elsewhere; the
        // bell icon makes the meaning unambiguous when count "blends in"
        // with the surrounding orange date text.
        val label = if (count == 1) "1 notification" else "$count notifications"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, Color(0xFFFF6B00), RoundedCornerShape(3.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_notifications),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFFFF6B00)),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = Color(0xFFFF6B00),
                fontSize = 13.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Top-of-screen toast for a single freshly-landed notification. Renders on
 *  every panel except NOTIFICATIONS itself (no point banner-ing the user about
 *  a notif while they're staring at the list). Rendered at the top of the
 *  LauncherRoot z-stack so it overlays whatever panel is active. */
@Composable
fun NotificationBanner(state: LauncherState, onClick: () -> Unit) {
    val type = LocalR1Type.current
    val banner = state.notificationBanner
    val visible = banner != null && state.panel != Panel.NOTIFICATIONS
    // Drop the banner immediately if the user opens NOTIFICATIONS — the list
    // already shows the same entry, so the banner is redundant.
    LaunchedEffect(state.panel) {
        if (state.panel == Panel.NOTIFICATIONS && state.notificationBanner != null) {
            state.notificationBanner = null
        }
    }
    // Auto-dismiss after 4s. Keyed by the banner's id so back-to-back
    // notifications restart the timer cleanly.
    LaunchedEffect(banner?.id) {
        if (banner != null) {
            delay(4000L)
            // Only clear if no newer banner has replaced it.
            if (state.notificationBanner?.id == banner.id) {
                state.notificationBanner = null
            }
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it },
    ) {
        val b = banner ?: return@AnimatedVisibility
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 26.dp, start = 14.dp, end = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .border(1.dp, Color(0xFFFF6B00).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column {
                    if (b.title.isNotBlank()) {
                        Text(
                            text = b.title,
                            color = Color(0xFFFF6B00),
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (b.body.isNotBlank()) {
                        Text(
                            text = b.body,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = type.appCard.fontFamily,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

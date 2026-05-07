package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.ToastKind
import kotlinx.coroutines.delay

// Kind colors lifted from AppsPanel.APP_PALETTE so toasts feel native to the
// apps grid aesthetic. INFO = cyan, SUCCESS = lime, FAIL = red.
private val TOAST_INFO_BG    = Color(0xFF00E5FF)
private val TOAST_SUCCESS_BG = Color(0xFF00FF38)
private val TOAST_FAIL_BG    = Color(0xFFFF003C)

// Drawer shape — flat top edge (flush with screen), rounded bottom only.
// The card "hangs" from the top edge like an Android notification drawer.
private val TOAST_SHAPE = RoundedCornerShape(
    topStart = 0.dp, topEnd = 0.dp,
    bottomStart = 18.dp, bottomEnd = 18.dp,
)

private const val TOAST_EXIT_MS = 220

/**
 * Top-edge notification drawer rendering [LauncherState.toast]. Slides down
 * from above, anchored flush to the top edge, with rounded bottom corners
 * only. Kind drives the background color (INFO = cyan, SUCCESS = lime,
 * FAIL = red). Default visible duration 3s; per-call override via
 * `showToast(... durationMs = ...)`.
 *
 * Self-dismissing: a [LaunchedEffect] keyed on the toast id schedules a
 * `delay` until [com.r1.launcher.ToastEntry.expiresAtMs] and clears the slot
 * if it's still the same toast. New `showToast` calls preempt cleanly because
 * the LaunchedEffect re-keys on the new id.
 */
@Composable
fun ToastOverlay(state: LauncherState) {
    val type = LocalR1Type.current
    val current = state.toast
    LaunchedEffect(current?.id) {
        val t = current ?: return@LaunchedEffect
        val remaining = t.expiresAtMs - System.currentTimeMillis()
        if (remaining > 0) delay(remaining)
        if (state.toast?.id == t.id) state.toast = null
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(tween(ANIM_OPEN_MS, easing = EnterEasing)) +
                slideInVertically(tween(280, easing = EnterEasing)) { -it },
            exit = fadeOut(tween(TOAST_EXIT_MS, easing = ExitEasing)) +
                slideOutVertically(tween(TOAST_EXIT_MS, easing = ExitEasing)) { -it },
        ) {
            val bg = when (current?.kind) {
                ToastKind.SUCCESS -> TOAST_SUCCESS_BG
                ToastKind.FAIL -> TOAST_FAIL_BG
                ToastKind.INFO, null -> TOAST_INFO_BG
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TOAST_SHAPE)
                    .background(bg)
                    .border(2.dp, Color.Black, TOAST_SHAPE)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = current?.text ?: "",
                    style = type.appCard.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Medium,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

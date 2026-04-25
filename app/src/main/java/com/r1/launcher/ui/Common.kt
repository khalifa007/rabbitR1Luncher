package com.r1.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.r1.launcher.R

/** Timings from activity_main.xml animations (ANIM_OPEN / FOCUS). */
internal const val ANIM_OPEN_MS = 220
internal const val ANIM_CLOSE_MS = 170
internal const val ANIM_FOCUS_MS = 140

internal const val FOCUS_SCALE = 1.04f
internal const val UNFOCUS_ALPHA = 0.55f

/**
 * Applies the focus animation (scale + alpha) used by apps/store rows.
 * Kept as a Modifier so it composes cleanly with other modifiers.
 */
@Composable
fun Modifier.focusAnim(focused: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) FOCUS_SCALE else 1f,
        animationSpec = tween(ANIM_FOCUS_MS, easing = FastOutSlowInEasing),
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
 * Back pill — rounded-999dp translucent chip, used as the header "←Home" on
 * Apps and Store panels. Mirrors drawable/back_pill_bg.xml.
 */
@Composable
fun BackPill(
    label: String = "Home",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            text = "< $label",
            style = type.appCard,
            color = Color(0xFFFF4500)
        )
    }
}

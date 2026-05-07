package com.r1.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.R

/**
 * Topbar: carrier | (empty middle) | status icons + battery.
 *
 * The clock sits on the home screen, not here, so the bar is a 2-zone grid
 * rather than 3-column. Kept the same measurements as the old XML:
 *  - 6dp top padding, 12dp horizontal
 *  - icons 14x10dp (signal/wifi) or 12dp (bt/update)
 *  - battery pill 18x9dp, 1dp stroke, 3dp radius, 1dp inner padding
 *  - operator name left-aligned, INVISIBLE (not GONE) when no SIM — in the
 *    old layout that was to keep the clock centered; here it's habit but also
 *    stops layout jitter when SIM state flips.
 */
@Composable
fun Topbar(state: LauncherState, modifier: Modifier = Modifier) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(top = 6.dp, start = 12.dp, end = 12.dp),
    ) {
        // Left: carrier name + network type pill (4G / LTE / 5G ...)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = if (state.simPresent) state.simOperator.lowercase() else "",
                style = type.appCard.copy(fontSize = 16.sp),
                color = Color(0xFFFF6B00),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.simPresent && state.cellularOn && state.networkType.isNotEmpty()) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(
                    text = state.networkType,
                    style = type.appCard.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = Color.Black,
                    modifier = Modifier
                        .pulse(active = true, peakScale = 1.05f)
                        .background(Color(0xFFFF6B00), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        // Right: status icons
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (state.simPresent && state.cellularOn) {
                Image(
                    painter = painterResource(R.drawable.ic_signal_bars),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFFFF6B00)),
                    modifier = Modifier.size(width = 14.dp, height = 10.dp),
                )
                IconSpacer()
            }
            if (state.wifiEnabled && state.wifiOn) {
                Image(
                    painter = painterResource(R.drawable.ic_wifi_arc),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFFFF6B00)),
                    modifier = Modifier.size(width = 14.dp, height = 10.dp),
                )
                IconSpacer()
            }
            if (state.btOn) {
                Image(
                    painter = painterResource(R.drawable.ic_bluetooth),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFFFF6B00)),
                    modifier = Modifier.size(12.dp),
                )
                IconSpacer()
            }
            if (state.updateIconState > 0) {
                UpdateIcon(rotating = state.updateIconState == 2, halfAlpha = state.updateIconState == 1)
                IconSpacer()
            }
            BatteryPill(pct = state.batteryPct, charging = state.batteryCharging)
        }
    }
}

@Composable
private fun IconSpacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
}

@Composable
private fun UpdateIcon(rotating: Boolean, halfAlpha: Boolean) {
    val rot = if (rotating) {
        val transition = rememberInfiniteTransition(label = "updateSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "updateAngle",
        )
        angle
    } else 0f

    Image(
        painter = painterResource(R.drawable.ic_update),
        contentDescription = null,
        alpha = if (halfAlpha) 0.5f else 1f,
        modifier = Modifier
            .size(12.dp)
            .graphicsLayer { rotationZ = rot },
    )
}

@Composable
private fun BatteryPill(pct: Float, charging: Boolean) {
    val scale = pct.coerceIn(0.08f, 1f)
    val tint = if (charging) Color(0xFF35D26F) else Color(0xFFFF6B00)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 26.dp, height = 12.dp)
            .border(1.dp, tint, RoundedCornerShape(3.dp))
            .padding(1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Text(
            text = "${(pct * 100).toInt()}",
            style = LocalR1Type.current.appCard.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
            color = if (pct > 0.5f) Color.Black else Color.White,
        )
    }
}

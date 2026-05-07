package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Home: spacer(22dp) to clear the topbar, flex clock/date block, dock row, 9sp hint.
 * Paddings from the old activity_main.xml: horizontal 14dp, top 6dp, bottom 14dp.
 */
@Composable
fun HomeScreen(
    state: LauncherState,
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

        }
    }
}

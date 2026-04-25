package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    onDockClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    AnimatedVisibility(
        visible = state.panel == Panel.HOME,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { -it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { -it },
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
                Text(state.dateText.lowercase(), style = type.date, color = Color(0xFFFF6B00)) // Bright Orange
                Spacer(Modifier.height(6.dp))
                Text(state.clockText.lowercase(), style = type.clock, color = colors.labelBright)
            }

        // Dock removed for cleaner clock screen
    }
}
}

@Composable
private fun Dock(state: LauncherState, onClick: (Int) -> Unit) {
    val colors = LocalR1Colors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(colors.dockGlass, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        DockButton(icon = R.drawable.ic_folder, iconSize = 22, focused = state.homeFocus == 0) { onClick(0) }
        Spacer(Modifier.width(8.dp))
        DockButton(icon = R.drawable.ic_store, iconSize = 22, focused = state.homeFocus == 1) { onClick(1) }
        Spacer(Modifier.width(8.dp))
        DockButton(icon = R.drawable.ic_dots_9, iconSize = 18, focused = state.homeFocus == 2) { onClick(2) }
    }
}

@Composable
private fun DockButton(
    icon: Int,
    iconSize: Int,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalR1Colors.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .background(colors.dockBtn, shape)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

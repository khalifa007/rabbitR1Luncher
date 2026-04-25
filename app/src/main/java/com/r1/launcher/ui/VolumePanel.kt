package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun VolumePanel(
    state: LauncherState,
    onScrimClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.VOLUME,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        val max = state.volumeMax.coerceAtLeast(1)
        LevelCard(
            title = "volume",
            hint = "wheel ↑↓   press OK",
            fraction = state.volumeLevel.toFloat() / max.toFloat(),
            percent = (state.volumeLevel * 100f / max.toFloat()).toInt(),
            onScrimClick = onScrimClick,
        )
    }
}

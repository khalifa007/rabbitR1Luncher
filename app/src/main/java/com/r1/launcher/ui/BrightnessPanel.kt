package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

private const val BRIGHTNESS_SEGMENTS = 15

@Composable
fun BrightnessPanel(
    state: LauncherState,
    onScrimClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.BRIGHTNESS,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        SegmentedLevelCard(
            title = stringResource(R.string.panel_brightness_title),
            hint = stringResource(R.string.panel_volume_hint),
            level = (state.brightnessLevel * BRIGHTNESS_SEGMENTS / 255).coerceIn(0, BRIGHTNESS_SEGMENTS),
            max = BRIGHTNESS_SEGMENTS,
            onScrimClick = onScrimClick,
        )
    }
}

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
        SegmentedLevelCard(
            title = stringResource(R.string.panel_volume_title),
            hint = stringResource(R.string.panel_volume_hint),
            level = state.volumeLevel,
            max = state.volumeMax,
            onScrimClick = onScrimClick,
        )
    }
}

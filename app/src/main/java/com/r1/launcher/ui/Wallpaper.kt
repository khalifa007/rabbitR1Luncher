package com.r1.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Matches drawable/wallpaper.xml — radial gradient, center top, fades to pure bg.
 * We compute the center off-screen-top so the bright pole sits above the visible
 * circle on the 480x480 R1, which matches how the original XML rendered.
 */
@Composable
fun Modifier.wallpaper(): Modifier {
    val colors = LocalR1Colors.current
    return this.background(
        Brush.radialGradient(
            colors = listOf(colors.bgTop, colors.bgMid, colors.bg),
            center = Offset(240f, -40f),
            radius = 560f,
        )
    )
}

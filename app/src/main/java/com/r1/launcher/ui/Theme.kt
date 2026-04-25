package com.r1.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.r1.launcher.R

/**
 * R1-specific color palette. Values mirror res/values/colors.xml so XML drawables
 * (wallpaper, dock_bg, back_pill, etc) that we reuse via painterResource keep their
 * current look in-composition.
 */
data class R1Colors(
    val bg: Color = Color(0xFF0A0A0C),
    val bgTop: Color = Color(0xFF2A2A2E),
    val bgMid: Color = Color(0xFF131315),
    val fg: Color = Color(0xFFF5F5F5),
    val muted: Color = Color(0xFF9A9A9A),
    val accent: Color = Color(0xFFFF6A00),
    val tile: Color = Color(0xFF1C1C1E),
    val dockGlass: Color = Color(0x14FFFFFF),
    val dockBtn: Color = Color(0x1AFFFFFF),
    val labelDim: Color = Color(0xC7FFFFFF),
    val labelBright: Color = Color(0xFFFFFFFF),
    val scrim: Color = Color(0xCC000000),
    val tileFocus: Color = Color(0x33FF6A00),
    val sheet: Color = Color(0xFF141416),
)

val LocalR1Colors = staticCompositionLocalOf { R1Colors() }

/**
 * Typography tokens. Sizes match activity_main.xml so the visual rhythm is preserved.
 */
data class R1Type(
    val clock: TextStyle = TextStyle(
        fontSize = 64.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.sp,
    ),
    val date: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.5.sp,
    ),
    val panelTitle: TextStyle = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.13).sp,
    ),
    val body: TextStyle = TextStyle(fontSize = 14.sp),
    val small: TextStyle = TextStyle(fontSize = 12.sp),
    val tiny: TextStyle = TextStyle(fontSize = 10.sp),
    val hint: TextStyle = TextStyle(fontSize = 9.sp, letterSpacing = 0.45.sp),
    val mono: TextStyle = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
    val appLabel: TextStyle = TextStyle(fontSize = 10.sp),
    val statusChip: TextStyle = TextStyle(fontSize = 10.sp),
    val appCard: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.5.sp
    ),
)

val LocalR1Type = staticCompositionLocalOf { R1Type() }

@Composable
fun R1Theme(content: @Composable () -> Unit) {
    // Material3 scheme is essentially unused (we style everything custom), but we still
    // need the CompositionLocals to be populated so components like Icon don't crash.
    val scheme = darkColorScheme(
        background = R1Colors().bg,
        surface = R1Colors().bg,
        primary = R1Colors().accent,
        onBackground = R1Colors().fg,
        onSurface = R1Colors().fg,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalR1Colors provides R1Colors(),
            LocalR1Type provides R1Type(),
        ) { content() }
    }
}

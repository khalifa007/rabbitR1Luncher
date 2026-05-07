package com.r1.launcher.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
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
 *
 * Two variants exist: [latinType] uses Jersey 15 (the device's signature retro
 * display font) and [arabicType] swaps in Noto Sans Arabic because Jersey 15
 * has no Arabic glyphs. R1Theme picks one based on the active locale.
 */
data class R1Type(
    val clock: TextStyle,
    val date: TextStyle,
    val panelTitle: TextStyle,
    val body: TextStyle,
    val small: TextStyle,
    val tiny: TextStyle,
    val hint: TextStyle,
    val mono: TextStyle,
    val appLabel: TextStyle,
    val statusChip: TextStyle,
    val appCard: TextStyle,
)

private val latinType = R1Type(
    clock = TextStyle(
        fontSize = 64.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.sp,
    ),
    date = TextStyle(
        fontSize = 24.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.5.sp,
    ),
    panelTitle = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.13).sp,
    ),
    body = TextStyle(fontSize = 14.sp),
    small = TextStyle(fontSize = 12.sp),
    tiny = TextStyle(fontSize = 10.sp),
    hint = TextStyle(fontSize = 9.sp, letterSpacing = 0.45.sp),
    mono = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
    appLabel = TextStyle(fontSize = 10.sp),
    statusChip = TextStyle(fontSize = 10.sp),
    appCard = TextStyle(
        fontSize = 24.sp,
        fontFamily = FontFamily(Font(R.font.jersey_15)),
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Build a real per-glyph-fallback Typeface that uses Jersey 15 as the primary
 * font and chains Tajawal-Bold as a custom fallback. Compose's
 * FontFamily(Font(a), Font(b)) treats `a` and `b` as weight/style variants of
 * the *same* family — both ours are weight 400 normal, so the resolver picks
 * Jersey 15 and stops, then Android falls back to the *system* font chain
 * (Noto Sans Arabic) for missing glyphs. That's why Tajawal never appeared
 * before. Typeface.CustomFallbackBuilder (API 29+) is the only way to get
 * Compose to chain a custom font as fallback. R1 is API 34, so this is fine.
 */
private fun buildArabicTypeface(ctx: android.content.Context): Typeface {
    val jersey = ResourcesCompat.getFont(ctx, R.font.jersey_15)
        ?: Typeface.DEFAULT
    val tajawal = ResourcesCompat.getFont(ctx, R.font.tajawal_bold)
        ?: return jersey
    return Typeface.CustomFallbackBuilder(
        android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(
                ctx.resources, R.font.jersey_15,
            ).build()
        ).build()
    ).addCustomFallback(
        android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(
                ctx.resources, R.font.tajawal_bold,
            ).build()
        ).build()
    ).build()
}

private fun buildArabicType(ctx: android.content.Context): R1Type {
    val typeface = buildArabicTypeface(ctx)
    val mixed = FontFamily(typeface)
    val jerseyOnly = FontFamily(Font(R.font.jersey_15))
    return R1Type(
        // clock + date format strings come back from SimpleDateFormat with
        // Arabic month names ("ديسمبر") in ar locale, so they too need the
        // mixed family — the Latin-digit clock is forced via -u-nu-latn.
        clock = TextStyle(fontSize = 64.sp, fontFamily = mixed, letterSpacing = 0.sp),
        date = TextStyle(fontSize = 24.sp, fontFamily = mixed, letterSpacing = 0.5.sp),
        panelTitle = TextStyle(
            fontSize = 13.sp,
            fontFamily = mixed,
            fontWeight = FontWeight.Medium,
        ),
        body = TextStyle(fontSize = 14.sp, fontFamily = mixed),
        small = TextStyle(fontSize = 12.sp, fontFamily = mixed),
        tiny = TextStyle(fontSize = 10.sp, fontFamily = mixed),
        hint = TextStyle(fontSize = 9.sp, fontFamily = mixed, letterSpacing = 0.45.sp),
        mono = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
        appLabel = TextStyle(fontSize = 11.sp, fontFamily = mixed),
        statusChip = TextStyle(fontSize = 10.sp, fontFamily = mixed),
        appCard = TextStyle(
            fontSize = 18.sp,
            fontFamily = mixed,
            letterSpacing = 0.sp,
        ),
    )
}

val LocalR1Type = staticCompositionLocalOf { latinType }

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
    val ctx = LocalContext.current
    val type = if (java.util.Locale.getDefault().language == "ar") buildArabicType(ctx) else latinType
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalR1Colors provides R1Colors(),
            LocalR1Type provides type,
        ) { content() }
    }
}

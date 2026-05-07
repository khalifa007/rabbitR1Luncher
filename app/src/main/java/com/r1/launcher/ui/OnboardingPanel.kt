package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.locale.Language
import com.r1.launcher.locale.LocalePrefs

private val ACCENT = Color(0xFFFF6B00)
private val HIGHLIGHT_BG = Color(0xFFFF4500)
private val BODY_GRAY = Color(0xFFCCCCCC)

@Composable
fun OnboardingPanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.ONBOARDING,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when (state.onboardingStep) {
                0 -> LanguageStep(state = state, onRowClick = onRowClick)
                1 -> WelcomeStep(onRowClick = onRowClick)
                2 -> NetworkStep(state = state, onRowClick = onRowClick)
                3 -> UpdateStep(state = state, onRowClick = onRowClick)
                else -> DoneStep(onRowClick = onRowClick)
            }
        }
    }
}

@Composable
private fun LanguageStep(state: LauncherState, onRowClick: (Int) -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_language_title),
        body = stringResource(R.string.onboarding_language_body),
        showBack = false,
        onBackClick = {},
    ) {
        Spacer(Modifier.height(8.dp))
        // Each language renders in its own script so a fresh-boot user can pick
        // without reading anything else on the device.
        LocalePrefs.SUPPORTED.forEachIndexed { i, lang ->
            LanguageOption(
                lang = lang,
                focused = state.onboardingFocus == i,
                onClick = { onRowClick(i) },
            )
        }
    }
}

@Composable
private fun LanguageOption(lang: Language, focused: Boolean, onClick: () -> Unit) {
    val bg = if (focused) HIGHLIGHT_BG else Color.Transparent
    val fg = if (focused) Color.Black else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = lang.displayName,
            color = fg,
            fontSize = 24.sp,
            // Force the language's own script — the global typography is still
            // Latin until the locale switches, so render Arabic in Arabic font here.
            fontFamily = if (lang.isRtl) {
                androidx.compose.ui.text.font.FontFamily(
                    androidx.compose.ui.text.font.Font(R.font.tajawal_bold),
                )
            } else {
                LocalR1Type.current.appCard.fontFamily
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WelcomeStep(onRowClick: (Int) -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
        showBack = false,
        onBackClick = {},
    ) {
        Spacer(Modifier.weight(1f))
        ContinuePill(
            label = stringResource(R.string.onboarding_continue),
            focused = true, // welcome has only one action, always focused
            onClick = { onRowClick(0) },
        )
    }
}

@Composable
private fun NetworkStep(state: LauncherState, onRowClick: (Int) -> Unit) {
    val cellularSubtitle = when {
        !state.simPresent -> stringResource(R.string.onboarding_network_no_sim)
        state.simOperator.isBlank() && state.networkType.isBlank() -> stringResource(R.string.onboarding_network_ready)
        state.networkType.isBlank() -> state.simOperator.lowercase()
        state.simOperator.isBlank() -> state.networkType
        else -> "${state.simOperator.lowercase()} · ${state.networkType}"
    }
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_network_title),
        body = stringResource(R.string.onboarding_network_body),
        showBack = false,
        onBackClick = {},
    ) {
        Spacer(Modifier.height(8.dp))
        OptionRow(
            label = stringResource(R.string.onboarding_network_wifi),
            focused = state.onboardingFocus == 0,
            onClick = { onRowClick(0) },
        )
        OptionRow(
            label = stringResource(R.string.onboarding_network_cellular),
            subtitle = cellularSubtitle,
            focused = state.onboardingFocus == 1,
            dimmed = !state.simPresent,
            onClick = { onRowClick(1) },
        )
        OptionRow(
            label = stringResource(R.string.onboarding_network_skip),
            focused = state.onboardingFocus == 2,
            onClick = { onRowClick(2) },
        )
    }
}

@Composable
private fun UpdateStep(state: LauncherState, onRowClick: (Int) -> Unit) {
    val statusLine = when (state.updateIconState) {
        1 -> stringResource(R.string.onboarding_updates_checking)
        2 -> stringResource(R.string.onboarding_updates_downloading)
        else -> ""
    }
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_updates_title),
        body = stringResource(R.string.onboarding_updates_body),
        showBack = false,
        onBackClick = {},
    ) {
        Spacer(Modifier.height(8.dp))
        OptionRow(
            label = stringResource(R.string.onboarding_updates_check),
            focused = state.onboardingFocus == 0,
            onClick = { onRowClick(0) },
        )
        OptionRow(
            label = stringResource(R.string.onboarding_updates_skip),
            focused = state.onboardingFocus == 1,
            onClick = { onRowClick(1) },
        )
        Spacer(Modifier.weight(1f))
        if (statusLine.isNotEmpty()) {
            Text(
                text = statusLine,
                color = ACCENT,
                fontSize = 14.sp,
                fontFamily = LocalR1Type.current.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        ContinuePill(
            label = stringResource(R.string.onboarding_continue),
            focused = state.onboardingFocus == 2,
            onClick = { onRowClick(2) },
        )
    }
}

@Composable
private fun DoneStep(onRowClick: (Int) -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_done_title),
        body = stringResource(R.string.onboarding_done_body),
        showBack = false,
        onBackClick = {},
    ) {
        Spacer(Modifier.weight(1f))
        ContinuePill(
            label = stringResource(R.string.onboarding_finish),
            focused = true,
            onClick = { onRowClick(0) },
        )
    }
}

@Composable
private fun OnboardingScaffold(
    title: String,
    body: String,
    showBack: Boolean,
    onBackClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val type = LocalR1Type.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
    ) {
        if (showBack) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBackClick() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.back),
                    color = ACCENT,
                    fontSize = 24.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ACCENT),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = ACCENT,
                fontSize = 28.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = body,
            color = BODY_GRAY,
            fontSize = 16.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun OptionRow(
    label: String,
    focused: Boolean,
    dimmed: Boolean = false,
    subtitle: String = "",
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = if (focused) HIGHLIGHT_BG else Color.Transparent
    val fg = when {
        focused -> Color.Black
        dimmed -> Color(0xFF666666)
        else -> Color.White
    }
    val subFg = when {
        focused -> Color(0xFF1A1A1A)
        dimmed -> Color(0xFF666666)
        else -> ACCENT
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                text = label,
                color = fg,
                fontSize = 22.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = subFg,
                    fontSize = 13.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ContinuePill(
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) HIGHLIGHT_BG else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 24.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

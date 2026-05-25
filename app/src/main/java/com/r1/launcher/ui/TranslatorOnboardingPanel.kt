package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.translator.Language
import com.r1.launcher.translator.Languages

/**
 * First-run wizard for the Translator app. Three focused steps:
 *
 *   0  "i speak"      → source language, with 🌐 auto-detect as the first pick
 *   1  "translate to" → target language
 *   2  "add a key"    → set from phone (panel handoff), paste, or skip
 *
 * Wheel-navigable (focus tracked in [LauncherState.translatorOnboardingFocus],
 * dispatched from LauncherNav) and touch-friendly (rows are tappable). The key
 * step's phone-handoff card auto-advances the moment a key lands — a
 * [LaunchedEffect] watches the per-provider has-key mirrors and calls [onFinish].
 */
@Composable
fun TranslatorOnboardingPanel(
    state: LauncherState,
    onPickSource: (String) -> Unit,
    onPickTarget: (String) -> Unit,
    onEnablePhoneKey: () -> Unit,
    onPasteKey: () -> Unit,
    onSkipKey: () -> Unit,
    onFinish: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSLATOR_ONBOARDING,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val accent = AppThemes.Translator
        val step = state.translatorOnboardingStep

        // Auto-advance: when the key step is waiting and any provider key has
        // landed (typically pasted from the phone panel), finish the wizard.
        val anyKey = state.translatorGeminiHasKey ||
            state.translatorOpenAIHasKey ||
            state.translatorClaudeHasKey
        LaunchedEffect(state.translatorOnboardingWaitingForKey, anyKey) {
            if (state.translatorOnboardingWaitingForKey && anyKey) onFinish()
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                StepDots(step = step, accent = accent)
                Spacer(Modifier.height(8.dp))

                when (step) {
                    0 -> LanguageStep(
                        title = "i speak",
                        focus = state.translatorOnboardingFocus,
                        includeAuto = true,
                        currentCode = state.translatorSource,
                        accent = accent,
                        onPick = onPickSource,
                    )
                    1 -> LanguageStep(
                        title = "translate to",
                        focus = state.translatorOnboardingFocus,
                        includeAuto = false,
                        currentCode = state.translatorTarget,
                        accent = accent,
                        onPick = onPickTarget,
                    )
                    else -> KeyStep(
                        state = state,
                        focus = state.translatorOnboardingFocus,
                        accent = accent,
                        onEnablePhoneKey = onEnablePhoneKey,
                        onPasteKey = onPasteKey,
                        onSkipKey = onSkipKey,
                    )
                }
            }
        }
    }
}

/** 1·2·3 progress dots — filled up to and including the current step. */
@Composable
private fun StepDots(step: Int, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == step) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (i <= step) accent else Color(0xFF333333)),
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, sub: String, accent: Color) {
    val type = LocalR1Type.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = accent,
            fontSize = 30.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = type.appCard.fontFamily,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LanguageStep(
    title: String,
    focus: Int,
    includeAuto: Boolean,
    currentCode: String,
    accent: Color,
    onPick: (String) -> Unit,
) {
    StepTitle(title = title, sub = "wheel to pick · press to choose", accent = accent)
    Spacer(Modifier.height(6.dp))

    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        listState.animateScrollToItem(focus.coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (includeAuto) {
            item("auto") {
                OnboardOption(
                    primary = "🌐 auto-detect",
                    secondary = "let the app figure out the language",
                    focused = focus == 0,
                    accent = accent,
                    onClick = { onPick(Languages.AUTO) },
                )
            }
        }
        itemsIndexed(items = Languages.ALL, key = { _, l -> l.code }) { i, lang ->
            // When auto is present it occupies focus 0, so langs start at focus 1.
            val rowFocus = if (includeAuto) i + 1 else i
            LangRow(
                lang = lang,
                focused = focus == rowFocus,
                selected = lang.code == currentCode,
                accent = accent,
                onClick = { onPick(lang.code) },
            )
        }
    }
}

@Composable
private fun KeyStep(
    state: LauncherState,
    focus: Int,
    accent: Color,
    onEnablePhoneKey: () -> Unit,
    onPasteKey: () -> Unit,
    onSkipKey: () -> Unit,
) {
    val type = LocalR1Type.current
    StepTitle(title = "add a key", sub = "free · takes about a minute", accent = accent)
    Spacer(Modifier.height(8.dp))

    if (state.translatorOnboardingWaitingForKey) {
        // Phone-handoff card: show where to paste + poll-driven auto-advance.
        val ip = state.webServerIp.ifBlank { "—" }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "on your phone, open",
                color = Color(0xFFAAAAAA),
                fontSize = 13.sp,
                fontFamily = type.appCard.fontFamily,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, accent, RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.08f))
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.webServerIp.isBlank()) "connect wifi first"
                        else "http://$ip:${state.webServerPort}",
                    color = accent,
                    fontSize = 20.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "passcode  ${state.panelPasscode}",
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = type.appCard.fontFamily,
            )
            Text(
                text = "credentials → translator → paste your key",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = type.appCard.fontFamily,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.pulse(active = true, peakScale = 1.04f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "waiting for key…",
                    color = accent,
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "skip for now",
                color = Color(0xFF777777),
                fontSize = 13.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSkipKey)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    } else {
        // Three options, wheel-focusable.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OnboardOption(
                primary = "set from phone",
                secondary = "paste it from the remote panel — easiest",
                focused = focus == 0,
                accent = accent,
                onClick = onEnablePhoneKey,
            )
            OnboardOption(
                primary = "paste from clipboard",
                secondary = "if the key is already copied on the r1",
                focused = focus == 1,
                accent = accent,
                onClick = onPasteKey,
            )
            OnboardOption(
                primary = "skip for now",
                secondary = "add it later in settings",
                focused = focus == 2,
                accent = accent,
                onClick = onSkipKey,
            )
        }
    }
}

@Composable
private fun LangRow(
    lang: Language,
    focused: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = if (focused) accent else if (selected) accent.copy(alpha = 0.12f) else Color.Transparent
    val primaryColor = if (focused) Color.Black else Color.White
    val secondaryColor = if (focused) Color(0xFF222222) else Color(0xFF888888)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "[${lang.code.uppercase()}]",
            color = if (focused) Color.Black else accent,
            fontSize = 13.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lang.english,
                color = primaryColor,
                fontSize = 17.sp,
                fontFamily = type.appCard.fontFamily,
            )
            if (lang.endonym != lang.english) {
                Text(
                    text = lang.endonym,
                    color = secondaryColor,
                    fontSize = 12.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
        }
        if (selected) {
            Text(
                text = "•",
                color = if (focused) Color.Black else accent,
                fontSize = 22.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun OnboardOption(
    primary: String,
    secondary: String,
    focused: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = if (focused) accent else Color(0xFF161616)
    val primaryColor = if (focused) Color.Black else accent
    val secondaryColor = if (focused) Color(0xFF222222) else Color(0xFF888888)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = primary,
            color = primaryColor,
            fontSize = 19.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = secondary,
            color = secondaryColor,
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}

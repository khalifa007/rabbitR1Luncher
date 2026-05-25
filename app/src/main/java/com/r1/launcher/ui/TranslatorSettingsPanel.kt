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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.translator.Language
import com.r1.launcher.translator.Languages
import com.r1.launcher.translator.ProviderId

/**
 * Translator settings — provider picker, per-provider keys, language defaults,
 * toggles. Modelled on [SettingsCredentialsPanel] for the shared keyboard
 * overlay; modelled on [HermesConfigPanel] for the focusable-row layout.
 *
 * Row layout (must match [com.r1.launcher.LauncherActivity.translatorSettingsRowActivate]
 * and the wheelDown clamp in [com.r1.launcher.LauncherNav]):
 *
 *   0  < back              (lives inside the page header)
 *   1  provider            (cycle gemini → openai → claude)
 *   2  gemini key
 *   3  openai key
 *   4  claude key
 *   5  source lang         (long-press style not available — opens picker overlay)
 *   6  target lang
 *   7  auto-detect source  (toggle)
 *   8  auto-speak target   (toggle)
 *   9  clear history
 */
@Composable
fun TranslatorSettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveKey: (provider: String, value: String) -> Unit,
    onPasteKey: (provider: String) -> Unit,
    onClearKey: (provider: String) -> Unit,
    onPickSource: (String) -> Unit,
    onPickTarget: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSLATOR_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)
        val warn = Color(0xFFE53935)
        val accent = AppThemes.Translator

        var pickerTarget by remember { mutableStateOf<String?>(null) }

        val rows = buildList<TranslatorRow> {
            add(TranslatorRow.Header)
            add(TranslatorRow.Provider)
            add(TranslatorRow.GeminiKey)
            add(TranslatorRow.OpenAIKey)
            add(TranslatorRow.ClaudeKey)
            add(TranslatorRow.SourceLang)
            add(TranslatorRow.TargetLang)
            add(TranslatorRow.AutoDetect)
            add(TranslatorRow.AutoSpeak)
            add(TranslatorRow.HideInput)
            add(TranslatorRow.ClearHistory)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.translatorSettingsFocus) {
            listState.animateScrollToItem(state.translatorSettingsFocus.coerceIn(0, rows.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items = rows, key = { idx, _ -> idx }) { idx, row ->
                    when (row) {
                        TranslatorRow.Header -> AppPageHeader(
                            titleIconRes = R.drawable.ic_language,
                            title = "translate",
                            backFocused = state.translatorSettingsFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = accent,
                        )
                        TranslatorRow.Provider -> SettingsRow(
                            label = "provider",
                            focused = state.translatorSettingsFocus == 1,
                            subtitle = state.translatorProvider.label + "   (tap to cycle)",
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_about,
                            onClick = { onRowClick(1) },
                        )
                        TranslatorRow.GeminiKey -> KeyRow(
                            label = "gemini key",
                            hint = "AIza… (free tier)",
                            focused = state.translatorSettingsFocus == 2,
                            hasKey = state.translatorGeminiHasKey,
                            tail = state.translatorGeminiKeyTail,
                            ok = ok, warn = warn, dim = dim,
                            onClick = { onRowClick(2) },
                        )
                        TranslatorRow.OpenAIKey -> KeyRow(
                            label = "openai key",
                            hint = "sk-…",
                            focused = state.translatorSettingsFocus == 3,
                            hasKey = state.translatorOpenAIHasKey,
                            tail = state.translatorOpenAIKeyTail,
                            ok = ok, warn = warn, dim = dim,
                            onClick = { onRowClick(3) },
                        )
                        TranslatorRow.ClaudeKey -> KeyRow(
                            label = "claude key",
                            hint = "sk-ant-…",
                            focused = state.translatorSettingsFocus == 4,
                            hasKey = state.translatorClaudeHasKey,
                            tail = state.translatorClaudeKeyTail,
                            ok = ok, warn = warn, dim = dim,
                            onClick = { onRowClick(4) },
                        )
                        TranslatorRow.SourceLang -> SettingsRow(
                            label = "default source",
                            focused = state.translatorSettingsFocus == 5,
                            subtitle = Languages.get(state.translatorSource).english,
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_language,
                            onClick = { pickerTarget = "source" },
                        )
                        TranslatorRow.TargetLang -> SettingsRow(
                            label = "default target",
                            focused = state.translatorSettingsFocus == 6,
                            subtitle = Languages.get(state.translatorTarget).english,
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_language,
                            onClick = { pickerTarget = "target" },
                        )
                        TranslatorRow.AutoDetect -> SettingsRow(
                            label = "auto-detect source",
                            focused = state.translatorSettingsFocus == 7,
                            toggleChecked = state.translatorAutoDetect,
                            subtitle = "let scribe pick the spoken language",
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_voice,
                            onClick = { onRowClick(7) },
                        )
                        TranslatorRow.AutoSpeak -> SettingsRow(
                            label = "auto-speak target",
                            focused = state.translatorSettingsFocus == 8,
                            toggleChecked = state.translatorAutoSpeak,
                            subtitle = "play tts after each translation",
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_sound,
                            onClick = { onRowClick(8) },
                        )
                        TranslatorRow.HideInput -> SettingsRow(
                            label = "hide text input",
                            focused = state.translatorSettingsFocus == 9,
                            toggleChecked = state.translatorHideInput,
                            subtitle = "voice-only — talk with the side button",
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_voice,
                            onClick = { onRowClick(9) },
                        )
                        TranslatorRow.ClearHistory -> SettingsRow(
                            label = "clear history",
                            focused = state.translatorSettingsFocus == 10,
                            subtitle = "delete all saved translations",
                            subtitleColor = dim,
                            leadingIcon = R.drawable.ic_factory_reset,
                            labelColor = warn,
                            onClick = { onRowClick(10) },
                        )
                    }
                }
            }

            // Shared keyboard overlay for the three provider key rows.
            val field = state.translatorEditField
            AnimatedVisibility(
                visible = field.isNotBlank(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val input = state.translatorEditInput
                val saveEnabled = input.trim().isNotBlank()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = keyLabelFor(field),
                            style = type.appCard.copy(fontSize = 16.sp),
                            color = accent,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = keyHintFor(field),
                            style = type.appCard.copy(fontSize = 11.sp),
                            color = Color.DarkGray,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (input.isEmpty()) "type or paste"
                                else mask(input) + "_",
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = if (input.isEmpty()) Color(0xFF707070) else Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TrPill("save", if (saveEnabled) ok else Color.DarkGray, saveEnabled, Modifier.weight(1f)) {
                            onSaveKey(field, input.trim())
                        }
                        TrPill("paste", accent, true, Modifier.weight(1f)) { onPasteKey(field) }
                        TrPill("clear", warn, true, Modifier.weight(1f)) { onClearKey(field) }
                        TrPill("close", Color.White, true, Modifier.weight(1f)) {
                            state.translatorEditField = ""
                            state.translatorEditInput = ""
                        }
                    }

                    RetroKeyboard(
                        onKeyPress = { ch -> state.translatorEditInput += ch },
                        onBackspace = {
                            if (state.translatorEditInput.isNotEmpty()) {
                                state.translatorEditInput = state.translatorEditInput.dropLast(1)
                            }
                        },
                        onDismiss = {
                            state.translatorEditField = ""
                            state.translatorEditInput = ""
                        },
                    )
                }
            }

            // Language picker overlay — shared by source + target rows.
            AnimatedVisibility(
                visible = pickerTarget != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .clickable { pickerTarget = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF111111))
                            .border(1.dp, accent, RoundedCornerShape(12.dp))
                            .clickable(enabled = false, onClick = {})
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = if (pickerTarget == "source") "default source" else "default target",
                            color = accent,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        val currentCode = if (pickerTarget == "source") state.translatorSource else state.translatorTarget
                        LazyColumn(modifier = Modifier.height(280.dp)) {
                            items(items = Languages.ALL, key = { it.code }) { lang ->
                                LangPickRow(lang = lang, selected = lang.code == currentCode) {
                                    if (pickerTarget == "source") onPickSource(lang.code)
                                    else onPickTarget(lang.code)
                                    pickerTarget = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class TranslatorRow {
    object Header : TranslatorRow()
    object Provider : TranslatorRow()
    object GeminiKey : TranslatorRow()
    object OpenAIKey : TranslatorRow()
    object ClaudeKey : TranslatorRow()
    object SourceLang : TranslatorRow()
    object TargetLang : TranslatorRow()
    object AutoDetect : TranslatorRow()
    object AutoSpeak : TranslatorRow()
    object HideInput : TranslatorRow()
    object ClearHistory : TranslatorRow()
}

@Composable
private fun KeyRow(
    label: String,
    hint: String,
    focused: Boolean,
    hasKey: Boolean,
    tail: String,
    ok: Color,
    warn: Color,
    dim: Color,
    onClick: () -> Unit,
) {
    val subtitle = if (hasKey) tail.ifBlank { "set" } else "not set · $hint"
    val subtitleColor = if (hasKey) ok else dim
    SettingsRow(
        label = label,
        focused = focused,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        leadingIcon = R.drawable.ic_about,
        onClick = onClick,
    )
}

@Composable
private fun LangPickRow(lang: Language, selected: Boolean, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppThemes.Translator.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "[${lang.code.uppercase()}]",
            color = AppThemes.Translator,
            fontSize = 13.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = lang.english,
            color = Color.White,
            fontSize = 15.sp,
            fontFamily = type.appCard.fontFamily,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "•",
                color = AppThemes.Translator,
                fontSize = 22.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TrPill(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val borderColor = if (enabled) color else Color(0xFF333333)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 14.sp),
            color = borderColor,
        )
    }
}

private fun keyLabelFor(field: String): String = when (field) {
    "gemini" -> "gemini key"
    "openai" -> "openai key"
    "claude" -> "claude (anthropic) key"
    else -> field
}

private fun keyHintFor(field: String): String = when (field) {
    "gemini" -> "AIza… (39 chars)"
    "openai" -> "sk-…"
    "claude" -> "sk-ant-…"
    else -> ""
}

private fun mask(s: String): String {
    if (s.length <= 8) return "*".repeat(s.length)
    return s.take(3) + "…" + s.takeLast(4)
}

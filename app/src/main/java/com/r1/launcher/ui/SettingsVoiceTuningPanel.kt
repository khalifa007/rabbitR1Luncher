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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.voice.VoicePrefs

/**
 * Settings → Voice → Tuning subpanel. Surfaces ElevenLabs `voice_settings`
 * + the model picker as wheel-navigable rows. Each slider row has inline
 * −/+ pills (the round 480×480 screen + wheel doesn't lend itself to drag
 * sliders); the wheel itself moves focus between rows, activate triggers
 * the action of the focused row (cycle / toggle / test / reset).
 *
 * Row layout — kept in lockstep with [com.r1.launcher.LauncherActivity.voiceTuningRowActivate]:
 *   0  < back
 *   1  model: <name>             (cycle on activate)
 *   2  stability     [-] bar [+] (sliders on activate are no-op; tap pills)
 *   3  similarity    [-] bar [+]
 *   4  style         [-] bar [+]
 *   5  speed         [-] bar [+]
 *   6  speaker boost: on/off     (toggle on activate)
 *   7  test voice
 *   8  reset to defaults
 */
@Composable
fun SettingsVoiceTuningPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSetStability: (Float) -> Unit,
    onSetSimilarity: (Float) -> Unit,
    onSetStyle: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_VOICE_TUNING,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val modelLabel = VoicePrefs.MODELS.firstOrNull { it.second == state.voiceModel }
            ?.first ?: "flash"
        val rows = listOf(
            TuningRowSpec.Action(0, "__header__"),
            TuningRowSpec.Action(1, stringResource(R.string.voice_tuning_row_model, modelLabel)),
            TuningRowSpec.Slider(
                idx = 2,
                label = stringResource(R.string.voice_tuning_row_stability),
                value = state.voiceStability,
                min = 0f, max = 1f, step = 0.05f,
                onChange = onSetStability,
            ),
            TuningRowSpec.Slider(
                idx = 3,
                label = stringResource(R.string.voice_tuning_row_similarity),
                value = state.voiceSimilarity,
                min = 0f, max = 1f, step = 0.05f,
                onChange = onSetSimilarity,
            ),
            TuningRowSpec.Slider(
                idx = 4,
                label = stringResource(R.string.voice_tuning_row_style),
                value = state.voiceStyle,
                min = 0f, max = 1f, step = 0.05f,
                onChange = onSetStyle,
            ),
            TuningRowSpec.Slider(
                idx = 5,
                label = stringResource(R.string.voice_tuning_row_speed),
                value = state.voiceSpeed,
                min = VoicePrefs.MIN_SPEED, max = VoicePrefs.MAX_SPEED, step = 0.05f,
                onChange = onSetSpeed,
                valueFormatter = { String.format("%.2fx", it) },
            ),
            TuningRowSpec.Toggle(
                idx = 6,
                label = stringResource(R.string.voice_tuning_row_speaker_boost),
                checked = state.voiceSpeakerBoost,
            ),
            TuningRowSpec.Action(
                idx = 7,
                label = if (state.voiceTestBusy)
                    stringResource(R.string.voice_tuning_row_test_busy)
                else
                    stringResource(R.string.voice_tuning_row_test),
            ),
            TuningRowSpec.Action(8, stringResource(R.string.voice_tuning_row_reset)),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.voiceTuningFocus) {
            listState.animateScrollToItem(
                state.voiceTuningFocus.coerceIn(0, rows.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = rows, key = { it.idx }) { spec ->
                    val focused = spec.idx == state.voiceTuningFocus
                    when {
                        spec.idx == 0 -> AppPageHeader(
                            titleIconRes = R.drawable.ic_voice,
                            title = "voice tuning",
                            backFocused = state.voiceTuningFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                        spec is TuningRowSpec.Action -> SettingsRow(
                            label = spec.label,
                            focused = focused,
                            onClick = { onRowClick(spec.idx) },
                        )
                        spec is TuningRowSpec.Toggle -> SettingsRow(
                            label = spec.label,
                            focused = focused,
                            toggleChecked = spec.checked,
                            onClick = { onRowClick(spec.idx) },
                        )
                        spec is TuningRowSpec.Slider -> SliderRow(
                            label = spec.label,
                            value = spec.value,
                            min = spec.min,
                            max = spec.max,
                            step = spec.step,
                            focused = focused,
                            onChange = spec.onChange,
                            valueFormatter = spec.valueFormatter,
                        )
                    }
                }
            }
        }
    }
}

private sealed class TuningRowSpec {
    abstract val idx: Int

    data class Action(override val idx: Int, val label: String) : TuningRowSpec()
    data class Toggle(
        override val idx: Int,
        val label: String,
        val checked: Boolean,
    ) : TuningRowSpec()
    data class Slider(
        override val idx: Int,
        val label: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val onChange: (Float) -> Unit,
        val valueFormatter: (Float) -> String = { String.format("%.2f", it) },
    ) : TuningRowSpec()
}

/** Two-line slider row: label + numeric on top, [-] bar [+] underneath.
 *  Wheel-driven nav drives `focused` (orange fill); the −/+ pills are
 *  always tappable (consistent with FontSizeRow in OpenClawSettingsPanel). */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    focused: Boolean,
    onChange: (Float) -> Unit,
    valueFormatter: (Float) -> String,
) {
    val accent = Color(0xFFFF4500)
    val accentDim = Color(0xFF7A2300)
    val bg = if (focused) accent else Color.Transparent
    val labelColor = if (focused) Color.Black else Color.White
    val numericColor = if (focused) Color(0xFF1A1A1A) else Color(0xFFAAAAAA)
    val pillStroke = if (focused) Color.Black else accent
    val pillFill = if (focused) Color.Black else Color.Transparent
    val pillText = if (focused) accent else accent
    val type = LocalR1Type.current

    val canDecrease = value > min + 1e-4f
    val canIncrease = value < max - 1e-4f

    val ratio = ((value - min) / (max - min)).coerceIn(0f, 1f)
    // Bar: 12 cells, lit proportional to ratio. Lit = orange (or black on focus
    // for inversion-of-color-language); unlit = dim. Glyphs would be ideal but
    // Jersey 15 doesn't ship the Unicode block characters reliably across
    // Android versions, so we draw cells as small Boxes.
    val cellsLit = (ratio * BAR_CELLS).toInt().coerceIn(0, BAR_CELLS)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 22.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueFormatter(value),
                color = numericColor,
                fontSize = 16.sp,
                fontFamily = type.appCard.fontFamily,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SliderPill(
                glyph = "−",
                strokeColor = pillStroke,
                fillColor = pillFill,
                textColor = pillText,
                enabled = canDecrease,
                onClick = { if (canDecrease) onChange((value - step).coerceAtLeast(min)) },
            )
            Spacer(Modifier.width(8.dp))
            BarTrack(
                cellsLit = cellsLit,
                focused = focused,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SliderPill(
                glyph = "+",
                strokeColor = pillStroke,
                fillColor = pillFill,
                textColor = pillText,
                enabled = canIncrease,
                onClick = { if (canIncrease) onChange((value + step).coerceAtMost(max)) },
            )
        }
    }
}

private const val BAR_CELLS = 12

@Composable
private fun BarTrack(
    cellsLit: Int,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val litColor = if (focused) Color.Black else Color(0xFFFF4500)
    val unlitColor = if (focused) Color(0x33000000) else Color(0xFF2A2A2A)
    Row(
        modifier = modifier
            .height(14.dp)
            .border(1.dp, litColor.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(BAR_CELLS) { idx ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(if (idx < cellsLit) litColor else unlitColor),
            )
        }
    }
}

@Composable
private fun SliderPill(
    glyph: String,
    strokeColor: Color,
    fillColor: Color,
    textColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val effectiveStroke = if (enabled) strokeColor else Color(0xFF333333)
    val effectiveText = if (enabled) textColor else Color(0xFF555555)
    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, effectiveStroke, RoundedCornerShape(6.dp))
            .background(fillColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = effectiveText,
            fontSize = 18.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.voice.VoicePrefs

/**
 * Settings → Voice subpanel. Single source of truth for the ElevenLabs key,
 * voice picker, custom voice id, and the auto-speak toggle. Used by:
 *   - OpenClaw chat / Terminal / Claude STT (ElevenLabs Realtime, key only)
 *   - OpenClaw assistant TTS readback (ElevenLabs Flash v2.5, key + voice id +
 *     model + voice_settings)
 *   - Meetings (ElevenLabs Scribe v2 batch — same key, same credit pool)
 *
 * Row layout — kept in lockstep with [com.r1.launcher.LauncherActivity.voiceSettingsRowActivate]:
 *   0  < back
 *   1  voice: on/off    (toggles auto-speak of assistant replies)
 *   2  elevenlabs key   (status pill; on click opens RetroKeyboard overlay)
 *   3  subscription     (live credit balance from /v1/user/subscription; tap to refresh)
 *   4  voice: <name>    (cycles through 4 hardcoded ElevenLabs voices)
 *   5  custom voice id  (status pill; on click opens RetroKeyboard overlay)
 *   6  test voice       (synthesize a short sample with current settings)
 *   7  tuning           (opens SETTINGS_VOICE_TUNING)
 *   8  clear key
 */
@Composable
fun SettingsVoicePanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSaveKey: (String) -> Unit,
    onPasteKey: () -> Unit,
    onClear: () -> Unit,
    onSaveCustomVoiceId: (String) -> Unit,
    onPasteCustomVoiceId: () -> Unit,
    onClearCustomVoiceId: () -> Unit,
    onRowClick: (Int) -> Unit = {},
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_VOICE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        var input by remember { mutableStateOf("") }
        // Which keyboard overlay is open: null = none; KEY = elevenlabs key;
        // CUSTOM_VOICE = custom voice id. Keeping a single overlay surface
        // avoids stacking two keyboards on a 480x480 screen.
        var kbTarget by remember { mutableStateOf<KeyboardTarget?>(null) }

        // Reset keyboard state when panel opens.
        LaunchedEffect(state.panel) {
            if (state.panel == Panel.SETTINGS_VOICE) {
                input = ""
                kbTarget = null
            }
        }

        val voiceLabel = VoicePrefs.VOICES.firstOrNull { it.second == state.voiceId }
            ?.first ?: "rachel"
        val keyStatus = if (state.hasVoiceKey) {
            stringResource(R.string.voice_key_set_short) + state.voiceKeyTail
        } else {
            stringResource(R.string.voice_key_not_set)
        }
        val customStatus = if (state.voiceCustomId.isNotBlank()) {
            stringResource(R.string.voice_custom_id_set_short) +
                state.voiceCustomId.takeLast(6)
        } else {
            stringResource(R.string.voice_custom_id_not_set)
        }
        val toggleLabel = stringResource(
            if (state.voiceEnabled) R.string.voice_row_toggle_on else R.string.voice_row_toggle_off
        )
        val testLabel = if (state.voiceTestBusy)
            stringResource(R.string.voice_tuning_row_test_busy)
        else
            stringResource(R.string.voice_row_test)

        // Subscription row is now pure navigation — opens its own page that
        // does the actual fetch + full info display. No live data here.
        val subStatus = "view balance & plan"
        val subOk = false
        val items = listOf(
            SettingsItem.Standard("__header__"),
            SettingsItem.Toggle(toggleLabel, state.voiceEnabled),
            SettingsItem.Standard(stringResource(R.string.voice_row_key)),  // subtitle injected below
            SettingsItem.Standard("subscription"),                          // subtitle injected below
            SettingsItem.Standard("voice: $voiceLabel"),
            SettingsItem.Standard(stringResource(R.string.voice_row_custom_id)),
            SettingsItem.Standard(testLabel),
            SettingsItem.Standard(stringResource(R.string.voice_row_tuning)),
            SettingsItem.Standard(stringResource(R.string.voice_row_clear_key)),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.voiceFocus) {
            listState.animateScrollToItem(state.voiceFocus.coerceIn(0, items.lastIndex))
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
                itemsIndexed(
                    items = items,
                    key = { idx, item -> if (idx == 0) "header" else item.label },
                ) { idx, item ->
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_voice,
                            title = "voice",
                            backFocused = state.voiceFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        val (subtitle, subtitleOk) = when (idx) {
                            2 -> keyStatus to state.hasVoiceKey
                            4 -> subStatus to subOk
                            6 -> customStatus to state.voiceCustomId.isNotBlank()
                            else -> "" to false
                        }
                        val subtitleColor = if (subtitleOk)
                            Color(0xFF35D26F) else Color(0xFFFF4500)
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.voiceFocus,
                            toggleChecked = (item as? SettingsItem.Toggle)?.checked,
                            subtitle = subtitle,
                            subtitleColor = subtitleColor,
                            onClick = {
                                when (idx) {
                                    2 -> { input = ""; kbTarget = KeyboardTarget.KEY }
                                    6 -> { input = ""; kbTarget = KeyboardTarget.CUSTOM_VOICE }
                                    else -> onRowClick(idx)
                                }
                            },
                        )
                    }
                }
            }

            // Single keyboard overlay shared between "elevenlabs key" and
            // "custom voice id" inputs. The label, hint, validation, and the
            // pill actions are switched on `kbTarget`.
            AnimatedVisibility(
                visible = kbTarget != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val target = kbTarget ?: KeyboardTarget.KEY
                val accent = Color(0xFFFF4500)
                val ok = Color(0xFF35D26F)
                val warn = Color(0xFFE53935)
                val saveEnabled = input.trim().isNotBlank()
                val isKey = target == KeyboardTarget.KEY
                val clearEnabled = if (isKey) {
                    state.hasVoiceKey || input.isNotEmpty()
                } else {
                    state.voiceCustomId.isNotBlank() || input.isNotEmpty()
                }
                val title = stringResource(
                    if (isKey) R.string.voice_row_key else R.string.voice_row_custom_id
                )
                val hint = stringResource(
                    if (isKey) R.string.voice_kb_hint else R.string.voice_custom_id_kb_hint
                )
                val statusText = if (isKey) keyStatus else customStatus
                val statusOk = if (isKey) state.hasVoiceKey else state.voiceCustomId.isNotBlank()

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
                            text = title,
                            style = type.appCard.copy(fontSize = 16.sp),
                            color = accent,
                        )
                        Spacer(Modifier.weight(1f))
                        StatusDot(color = if (statusOk) ok else Color.DarkGray)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = type.appCard.copy(fontSize = 12.sp),
                            color = if (statusOk) ok else Color.DarkGray,
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
                        val displayText = when {
                            input.isNotEmpty() -> {
                                // Mask the API key (sensitive) but show the
                                // custom voice id in clear — voice ids aren't
                                // secrets and users need to spot typos.
                                if (isKey) mask(input) + "_" else input + "_"
                            }
                            else -> hint
                        }
                        Text(
                            text = displayText,
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = if (input.isEmpty()) Color(0xFF707070) else Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        VoicePill(stringResource(R.string.common_save), if (saveEnabled) ok else Color.DarkGray, saveEnabled, Modifier.weight(1f)) {
                            val v = input.trim()
                            if (isKey) onSaveKey(v) else onSaveCustomVoiceId(v)
                            input = ""
                            kbTarget = null
                        }
                        VoicePill(stringResource(R.string.common_paste), accent, true, Modifier.weight(1f)) {
                            if (isKey) onPasteKey() else onPasteCustomVoiceId()
                            kbTarget = null
                        }
                        VoicePill(stringResource(R.string.common_clear), if (clearEnabled) warn else Color.DarkGray, clearEnabled, Modifier.weight(1f)) {
                            if (isKey) onClear() else onClearCustomVoiceId()
                            input = ""
                        }
                        VoicePill(stringResource(R.string.common_close), Color.White, true, Modifier.weight(1f)) {
                            kbTarget = null
                        }
                    }

                    RetroKeyboard(
                        onKeyPress = { ch -> input += ch },
                        onBackspace = { if (input.isNotEmpty()) input = input.dropLast(1) },
                        onDismiss = { kbTarget = null },
                    )
                }
            }
        }
    }
}

private enum class KeyboardTarget { KEY, CUSTOM_VOICE }

private fun mask(s: String): String {
    if (s.length <= 8) return s
    return s.take(3) + "…" + s.takeLast(4)
}


@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .height(8.dp)
            .width(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun VoicePill(
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

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.transcriber.TranscriberPrefs

/**
 * Meetings → settings. SMTP creds + default email recipient. Mirrors the
 * row-then-keyboard-overlay pattern from [SettingsVoicePanel].
 *
 * Row layout (kept in lockstep with [com.r1.launcher.LauncherActivity.transcriberSettingsRowActivate]):
 *   0  < back
 *   1  smtp host          (default smtp.gmail.com)
 *   2  smtp port          (default 587)
 *   3  smtp user          (e.g. you@gmail.com)
 *   4  smtp password      (gmail "app password" — never the real account pw)
 *   5  default recipient  (comma-sep ok)
 *   6  clear smtp
 */
@Composable
fun TranscriberSettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveField: (String, String) -> Unit,
    onPasteField: (String) -> Unit,
    onCloseKeyboard: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSCRIBER_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        val items = listOf(
            "< back" to "",
            "smtp host" to state.smtpHostDisplay.ifBlank { TranscriberPrefs.DEFAULT_HOST },
            "smtp port" to (if (state.smtpPortDisplay > 0) state.smtpPortDisplay.toString() else TranscriberPrefs.DEFAULT_PORT.toString()),
            "smtp user" to (state.smtpUserDisplay.ifBlank { "(not set)" }),
            "smtp password" to (if (state.hasSmtp) "••••••••" else "(not set)"),
            "default recipient" to (state.defaultRecipientDisplay.ifBlank { "(not set)" }),
            "clear smtp" to "",
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.transcriberSettingsFocus) {
            listState.animateScrollToItem(state.transcriberSettingsFocus.coerceIn(0, items.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.transcriber_settings_title),
                        color = orange,
                        fontSize = 22.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                itemsIndexed(items) { idx, (label, sub) ->
                    val subColor = when (idx) {
                        3, 4 -> if (state.hasSmtp) ok else dim
                        5 -> if (state.defaultRecipientDisplay.isNotBlank()) ok else dim
                        else -> dim
                    }
                    SettingsRow(
                        label = label,
                        focused = state.transcriberSettingsFocus == idx,
                        subtitle = sub,
                        subtitleColor = subColor,
                        onClick = { onRowClick(idx) },
                    )
                }
            }

            // Keyboard overlay: shown when a field-edit is in progress.
            AnimatedVisibility(
                visible = state.transcriberSettingsEditField.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val field = state.transcriberSettingsEditField
                val warn = Color(0xFFE53935)
                val isPassword = field == "password"
                val displayInput = state.transcriberSettingsEditInput
                val displayMasked = if (isPassword && displayInput.isNotEmpty())
                    "•".repeat(displayInput.length) else displayInput

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = field,
                        color = orange,
                        fontSize = 16.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, orange.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (displayInput.isEmpty()) "(empty)" else "$displayMasked _",
                            color = if (displayInput.isEmpty()) Color(0xFF707070) else Color.White,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Pill("save", ok, true, Modifier.weight(1f)) {
                            onSaveField(field, state.transcriberSettingsEditInput)
                        }
                        Pill("paste", orange, true, Modifier.weight(1f)) { onPasteField(field) }
                        Pill("clear", warn, displayInput.isNotEmpty(), Modifier.weight(1f)) {
                            onSaveField(field, "")
                        }
                        Pill("close", Color.White, true, Modifier.weight(1f)) { onCloseKeyboard() }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch -> state.transcriberSettingsEditInput += ch },
                        onBackspace = {
                            if (state.transcriberSettingsEditInput.isNotEmpty())
                                state.transcriberSettingsEditInput = state.transcriberSettingsEditInput.dropLast(1)
                        },
                        onDismiss = { onCloseKeyboard() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(
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
            color = borderColor,
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}

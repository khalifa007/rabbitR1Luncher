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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Hermes Agent config — server URL, bearer key, voice/UX toggles, test-connection.
 * Row index ↔ LauncherActivity.hermesConfigRowActivate kept in lockstep:
 *   0  < back
 *   1  server url
 *   2  api key
 *   3  scan config from qr  (opens HERMES_QR; payload from scripts/hermes-setup.sh)
 *   4  speak replies     (toggle; mirrors global VoicePrefs.enabled)
 *   5  hide text input   (toggle; persists to HermesPrefs.hideChat)
 *   6  test connection
 *
 * URL and key rows pop a [RetroKeyboard] overlay; everything else fires
 * immediately via [onRowClick]. The model is intentionally NOT exposed here —
 * what model gets used is decided server-side by config.yaml, and a client-side
 * mismatch only causes mystery "model_not_supported" errors.
 */
@Composable
fun HermesConfigPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveServerUrl: (String) -> Unit,
    onPasteServerUrl: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onPasteApiKey: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CONFIG,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        // Which row is currently in keyboard-edit mode ("url" | "key" | "").
        // Local state — the activity doesn't need to know which field the user
        // is typing into until a save lands.
        var editField by remember { mutableStateOf("") }

        val urlSubtitle = state.hermesServerUrl.ifBlank { "(not set)" }
        val keySubtitle = if (state.hermesApiKeyTail.isNotBlank()) state.hermesApiKeyTail else "(none)"

        // (label, subtitle-or-null, toggleChecked-or-null)
        val items = listOf(
            Triple("__header__", null, null),
            Triple("server url", urlSubtitle, null),
            Triple("api key", keySubtitle, null),
            Triple("scan config from qr", null, null),
            Triple("speak replies", null, state.voiceEnabled),
            Triple("hide text input", null, state.hermesHideChat),
            Triple("test connection", statusLine(state.hermesStatus), null),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConfigFocus) {
            listState.animateScrollToItem(state.hermesConfigFocus.coerceIn(0, items.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { idx, triple ->
                    val (label, sub, toggle) = triple
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_hermes,
                            title = "hermes",
                            backFocused = state.hermesConfigFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Hermes,
                        )
                    } else {
                        val subColor = when (idx) {
                            1 -> if (state.hermesServerUrl.isNotBlank()) ok else dim
                            2 -> if (state.hermesApiKeyTail.isNotBlank()) ok else dim
                            6 -> when {
                                state.hermesStatus == "live" -> ok
                                state.hermesStatus.startsWith("error") -> Color(0xFFE53935)
                                else -> dim
                            }
                            else -> dim
                        }
                        SettingsRow(
                            label = label,
                            focused = state.hermesConfigFocus == idx,
                            subtitle = sub.orEmpty(),
                            subtitleColor = subColor,
                            toggleChecked = toggle,
                            onClick = {
                                when (idx) {
                                    1 -> {
                                        state.hermesServerUrlInput = state.hermesServerUrl
                                        editField = "url"
                                    }
                                    2 -> {
                                        state.hermesApiKeyInput = ""
                                        editField = "key"
                                    }
                                    else -> onRowClick(idx)
                                }
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = editField.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val accent = AppThemes.Hermes
                val warn = Color(0xFFE53935)
                val isKey = editField == "key"
                val displayInput = if (isKey) state.hermesApiKeyInput else state.hermesServerUrlInput
                val maskedInput = if (isKey && displayInput.isNotEmpty())
                    "•".repeat(displayInput.length.coerceAtMost(40)) else displayInput

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isKey) "api key" else "server url",
                        color = accent,
                        fontSize = 16.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (displayInput.isEmpty()) "(empty)" else "$maskedInput _",
                            color = if (displayInput.isEmpty()) Color(0xFF707070) else Color.White,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Pill("save", ok, displayInput.isNotBlank(), Modifier.weight(1f)) {
                            if (isKey) onSaveApiKey(state.hermesApiKeyInput)
                            else onSaveServerUrl(state.hermesServerUrlInput)
                            editField = ""
                        }
                        Pill("paste", accent, true, Modifier.weight(1f)) {
                            if (isKey) onPasteApiKey() else onPasteServerUrl()
                        }
                        Pill("clear", warn, displayInput.isNotEmpty(), Modifier.weight(1f)) {
                            if (isKey) {
                                state.hermesApiKeyInput = ""
                                onSaveApiKey("")
                            } else {
                                state.hermesServerUrlInput = ""
                                onSaveServerUrl("")
                            }
                            editField = ""
                        }
                        Pill("close", Color.White, true, Modifier.weight(1f)) {
                            editField = ""
                        }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch ->
                            if (isKey) state.hermesApiKeyInput += ch
                            else state.hermesServerUrlInput += ch
                        },
                        onBackspace = {
                            if (isKey) {
                                if (state.hermesApiKeyInput.isNotEmpty())
                                    state.hermesApiKeyInput = state.hermesApiKeyInput.dropLast(1)
                            } else {
                                if (state.hermesServerUrlInput.isNotEmpty())
                                    state.hermesServerUrlInput = state.hermesServerUrlInput.dropLast(1)
                            }
                        },
                        onDismiss = { editField = "" },
                    )
                }
            }
        }
    }
}

private fun statusLine(status: String): String = when {
    status == "live" -> "ok"
    status == "streaming" -> "streaming…"
    status == "connecting" -> "checking…"
    status.startsWith("error") -> status
    else -> "tap to test"
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
    val textColor = if (enabled) color else Color(0xFF555555)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun OpenClawSettingsPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onClear: () -> Unit,
    onRowClick: (Int) -> Unit = {},
    onFontSizeChange: (Int) -> Unit = {},
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current

        // Local input buffer; persists across recompositions while panel open.
        var input by remember { mutableStateOf("") }
        var showKeyboard by remember { mutableStateOf(false) }

        // When the panel opens, clear input.
        LaunchedEffect(state.panel) {
            if (state.panel == Panel.OPENCLAW_SETTINGS) {
                input = ""
                showKeyboard = false
            }
        }

        val items = listOf(
            SettingsItem.Standard("< back"),
            SettingsItem.Standard("whisper key"),
            SettingsItem.Toggle("hide text input", state.openClawHideChat),
            SettingsItem.Info("font size", "${state.chatFontSize} sp"),
            SettingsItem.Standard("clear history"),
            SettingsItem.Standard("disconnect gate"),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.openClawSettingsFocus) {
            listState.animateScrollToItem(
                state.openClawSettingsFocus.coerceIn(0, items.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Main Settings List
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { idx, item ->
                    when (item) {
                        is SettingsItem.Info -> FontSizeRow(
                            label = item.label,
                            value = item.value,
                            focused = idx == state.openClawSettingsFocus,
                            onDecrease = { onFontSizeChange((state.chatFontSize - 1).coerceAtLeast(8)) },
                            onIncrease = { onFontSizeChange((state.chatFontSize + 1).coerceAtMost(28)) },
                        )
                        else -> SettingsRow(
                            label = item.label,
                            focused = idx == state.openClawSettingsFocus,
                            toggleChecked = (item as? SettingsItem.Toggle)?.checked,
                            onClick = {
                                if (idx == 1) {
                                    showKeyboard = true
                                } else {
                                    onRowClick(idx)
                                }
                            },
                        )
                    }
                }
            }

            // Keyboard overlay when whisper key is selected
            AnimatedVisibility(
                visible = showKeyboard,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(bottom = 12.dp) // extra padding to clear screen edge
                ) {
                    val statusLine = if (state.chatHasOpenaiKey)
                        "status: configured (sk-...${state.chatOpenaiKeyTail})"
                    else
                        "status: not set"
                        
                    Text(
                        text = statusLine,
                        style = type.appCard,
                        color = if (state.chatHasOpenaiKey) Color(0xFF35D26F) else Color.DarkGray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    // Input field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val displayText = when {
                            input.isNotEmpty() -> maskKey(input) + "_"
                            else -> "tap to type sk-... or paste"
                        }
                        Text(
                            text = "> $displayText",
                            style = type.appCard,
                            color = if (input.isEmpty()) Color.DarkGray else Color.White,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Action row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(
                            text = "[save]",
                            style = type.appCard,
                            color = if (input.isNotBlank()) Color(0xFF35D26F) else Color.DarkGray,
                            modifier = Modifier.clickable(enabled = input.isNotBlank()) {
                                onSave(input)
                                showKeyboard = false
                            }
                        )
                        Text(
                            text = "[paste]",
                            style = type.appCard,
                            color = Color(0xFFFFC107),
                            modifier = Modifier.clickable { onPasteFromClipboard() }
                        )
                        Text(
                            text = "[clear]",
                            style = type.appCard,
                            color = Color(0xFFE53935),
                            modifier = Modifier.clickable {
                                onClear()
                                input = ""
                            }
                        )
                        Text(
                            text = "[close]",
                            style = type.appCard,
                            color = Color.White,
                            modifier = Modifier.clickable { showKeyboard = false }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    
                    RetroKeyboard(
                        onKeyPress = { ch -> input += ch },
                        onBackspace = { if (input.isNotEmpty()) input = input.dropLast(1) },
                        onDismiss = { showKeyboard = false },
                    )
                }
            }
        }
    }
}

private fun maskKey(s: String): String {
    if (s.length <= 8) return s
    return s.take(3) + "…" + s.takeLast(4)
}

@Composable
private fun FontSizeRow(
    label: String,
    value: String,
    focused: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val bgColor = if (focused) Color(0xFFFF4500) else Color.Transparent
    val textColor = if (focused) Color.Black else Color.White
    val btnColor = if (focused) Color.Black else Color(0xFFFF4500)
    val type = LocalR1Type.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = textColor,
                fontSize = 24.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                color = if (focused) Color(0xFF1A1A1A) else Color(0xFFAAAAAA),
                fontSize = 14.sp,
                fontFamily = type.appCard.fontFamily,
            )
        }
        Text(
            text = " − ",
            color = btnColor,
            fontSize = 28.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onDecrease() }
                .padding(horizontal = 6.dp),
        )
        Text(
            text = " + ",
            color = btnColor,
            fontSize = 28.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onIncrease() }
                .padding(horizontal = 6.dp),
        )
    }
}

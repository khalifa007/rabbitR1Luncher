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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Settings → Network → "panel passcode" editor. 4-digit numeric input that
 * mirrors the SPA's unlock keypad: 3x4 grid of digits, ⌫ to backspace, save
 * appears once exactly 4 digits are entered.
 *
 * Lifecycle: opened via [LauncherState.openPanelPasscodeEditor], which seeds
 * [LauncherState.panelPasscodeDraft] with the existing passcode so the user
 * sees their current digits before typing. Submit commits the draft to
 * NotifPrefs (via [onSubmit]) and routes back to NETWORK. Back discards.
 */
@Composable
fun PanelPasscodeEditor(
    state: LauncherState,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.PANEL_PASSCODE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val draft = state.panelPasscodeDraft
        val canSubmit = draft.length == 4 && draft.all { it.isDigit() }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Back pill — top-left, matches WifiShareEditPanel.
                Text(
                    text = "< back",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .clickable { onBack() }
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "panel passcode",
                    color = Color(0xFFFF6A00),
                    fontSize = 16.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4-dot indicator — filled for entered digits, empty for the rest.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { i ->
                        val filled = i < draft.length
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .border(2.dp, Color(0xFFFF6A00))
                                .background(if (filled) Color(0xFFFF6A00) else Color.Transparent),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PasscodeKeyRow(listOf("1", "2", "3"), state)
                    PasscodeKeyRow(listOf("4", "5", "6"), state)
                    PasscodeKeyRow(listOf("7", "8", "9"), state)
                    PasscodeKeyRow(listOf("", "0", "⌫"), state)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (canSubmit) "save" else "type 4 digits",
                    color = if (canSubmit) Color.Black else Color.DarkGray,
                    fontSize = 16.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (canSubmit) Color(0xFFFF4500) else Color.Transparent)
                        .clickable(enabled = canSubmit) { onSubmit(draft) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PasscodeKeyRow(keys: List<String>, state: LauncherState) {
    val type = LocalR1Type.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { key ->
            if (key.isEmpty()) {
                // Empty cell — preserves grid symmetry below the 7/8/9 row.
                Box(modifier = Modifier.weight(1f).height(36.dp))
            } else {
                val isBack = key == "⌫"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .border(2.dp, Color(0xFF2A2A2E), RoundedCornerShape(2.dp))
                        .background(Color(0xFF131315))
                        .clickable {
                            if (isBack) {
                                if (state.panelPasscodeDraft.isNotEmpty()) {
                                    state.panelPasscodeDraft =
                                        state.panelPasscodeDraft.dropLast(1)
                                }
                            } else if (state.panelPasscodeDraft.length < 4) {
                                state.panelPasscodeDraft += key
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = key,
                        color = Color(0xFFEEEEEE),
                        fontSize = 20.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

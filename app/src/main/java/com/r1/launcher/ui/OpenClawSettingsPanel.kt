package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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

/**
 * OpenClaw-scoped settings. Voice config (key, on/off, voice picker) lives in
 * the global Settings → Voice subpanel — it's used by chat / terminal / claude
 * and isn't OpenClaw-specific.
 */
@Composable
fun OpenClawSettingsPanel(
    state: LauncherState,
    onBack: () -> Unit,
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
        val items = listOf(
            SettingsItem.Standard(stringResource(R.string.back_short)),
            SettingsItem.Toggle(stringResource(R.string.openclaw_settings_speak), state.voiceEnabled),
            SettingsItem.Toggle(stringResource(R.string.openclaw_settings_hide_input), state.openClawHideChat),
            SettingsItem.Info(stringResource(R.string.openclaw_settings_font), "${state.chatFontSize} sp"),
            SettingsItem.Standard(stringResource(R.string.openclaw_settings_clear)),
            SettingsItem.Standard(stringResource(R.string.openclaw_settings_disconnect)),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.openClawSettingsFocus) {
            listState.animateScrollToItem(
                state.openClawSettingsFocus.coerceIn(0, items.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
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

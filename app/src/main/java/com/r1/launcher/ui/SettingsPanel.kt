package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

private val HIGHLIGHT_BG = Color(0xFFFF4500) // Orange pill background
private val TRACK_OFF = Color(0xFF333333)

private sealed class Item(val label: String) {
    class Standard(label: String) : Item(label)
    class Toggle(label: String, val checked: Boolean) : Item(label)
}

@Composable
fun SettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val items = listOf(
            Item.Standard("< back"),
            Item.Standard("brightness"),
            Item.Standard("volume"),
            Item.Toggle("show key debug", state.showDebugBar),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.settingsFocus) {
            listState.animateScrollToItem(
                state.settingsFocus.coerceIn(0, items.lastIndex)
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
                    SettingsRow(
                        label = item.label,
                        focused = idx == state.settingsFocus,
                        toggleChecked = (item as? Item.Toggle)?.checked,
                        onClick = { onRowClick(idx) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    focused: Boolean,
    toggleChecked: Boolean?,
    onClick: () -> Unit,
) {
    val bgColor = if (focused) HIGHLIGHT_BG else Color.Transparent
    val textColor = if (focused) Color.Black else Color.White
    val type = LocalR1Type.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 24.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
            )
            
            if (toggleChecked != null) {
                Spacer(modifier = Modifier.width(8.dp))
                MinimalSwitch(
                    checked = toggleChecked,
                    focused = focused,
                )
            }
        }
    }
}

@Composable
private fun MinimalSwitch(checked: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(160),
        label = "thumbOffset",
    )
    
    // When the row is focused (orange background), we need a contrasting track color.
    val trackColor = when {
        focused && checked -> Color.Black
        focused && !checked -> Color(0x66000000) // Darker for unchecked focused state
        !focused && checked -> HIGHLIGHT_BG
        else -> TRACK_OFF
    }

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 16.dp)
            .background(trackColor, CircleShape)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .offset(x = thumbOffset)
                .background(Color.White, CircleShape),
        )
    }
}


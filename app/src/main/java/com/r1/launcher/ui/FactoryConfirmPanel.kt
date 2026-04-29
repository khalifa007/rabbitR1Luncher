package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * Two-row destructive confirmation. Default focus is "< back" so a stray
 * activate cancels rather than wipes — see LauncherState.openFactoryConfirm().
 */
@Composable
fun FactoryConfirmPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.FACTORY_CONFIRM,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "factory reset",
                    color = Color(0xFFFF4500),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = type.appCard.fontFamily,
                )
                Text(
                    text = "this wipes all user data and resets the device. cannot be undone.",
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    fontFamily = type.appCard.fontFamily,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ConfirmRow(
                    label = "< back",
                    focused = state.factoryConfirmFocus == 0,
                    danger = false,
                    onClick = { onRowClick(0) },
                )
                ConfirmRow(
                    label = "yes — wipe everything",
                    focused = state.factoryConfirmFocus == 1,
                    danger = true,
                    onClick = { onRowClick(1) },
                )
            }
        }
    }
}

@Composable
private fun ConfirmRow(
    label: String,
    focused: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = when {
        focused && danger -> Color(0xFFCC1F00) // hot red when about to fire
        focused -> Color(0xFFFF4500)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color.Black
        danger -> Color(0xFFFF4500)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 22.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}

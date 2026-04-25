package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

private val HIGHLIGHT_BG = Color(0xFFFF4500) // Orange pill background

/**
 * Full-screen black minimalist sheet matching the Settings menu aesthetic.
 */
@Composable
fun SystemSheet(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onScrimClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SHEET,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onScrimClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SheetRow(
                    focused = state.sheetFocus == 0,
                    label = stringResource(R.string.item_wifi),
                    onClick = { onRowClick(0) },
                )
                SheetRow(
                    focused = state.sheetFocus == 1,
                    label = stringResource(R.string.item_mobile),
                    onClick = { onRowClick(1) },
                )
                SheetRow(
                    focused = state.sheetFocus == 2,
                    label = stringResource(R.string.item_bluetooth),
                    onClick = { onRowClick(2) },
                )
                SheetRow(
                    focused = state.sheetFocus == 3,
                    label = stringResource(R.string.item_update),
                    onClick = { onRowClick(3) },
                )
            }
        }
    }
}

@Composable
private fun SheetRow(focused: Boolean, label: String, onClick: () -> Unit) {
    val bgColor = if (focused) HIGHLIGHT_BG else Color.Transparent
    val textColor = if (focused) Color.Black else Color.White
    val type = LocalR1Type.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = label.lowercase(),
                color = textColor,
                fontSize = 24.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

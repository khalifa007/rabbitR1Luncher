package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

private val DANGER = Color(0xFFCC1F00)

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
        val backFocused = state.factoryConfirmFocus == 0
        val confirmFocused = state.factoryConfirmFocus == 1

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            ) {
                AppPageHeader(
                    titleIconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.factory_confirm_title),
                    backFocused = backFocused,
                    onBack = { onRowClick(0) },
                    themeColor = AppThemes.Settings,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.factory_confirm_warn),
                    color = Color(0xFFCCCCCC),
                    fontSize = 18.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 24.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (confirmFocused) DANGER else Color.Transparent)
                        .clickable { onRowClick(1) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.factory_confirm_action),
                        color = if (confirmFocused) Color.White else Color.White,
                        fontSize = 24.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

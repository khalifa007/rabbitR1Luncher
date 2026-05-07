package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

private val HIGHLIGHT_BG = Color(0xFFFF4500)
private val TRACK_OFF = Color(0xFF333333)

@Composable
fun BrightnessPanel(
    state: LauncherState,
    onScrimClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.BRIGHTNESS,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        LevelCard(
            title = stringResource(R.string.panel_brightness_title),
            hint = stringResource(R.string.panel_volume_hint),
            fraction = state.brightnessLevel.toFloat() / 255f,
            percent = (state.brightnessLevel * 100f / 255f).toInt(),
            onScrimClick = onScrimClick,
        )
    }
}

@Composable
internal fun LevelCard(
    title: String,
    hint: String,
    fraction: Float,
    percent: Int,
    onScrimClick: () -> Unit,
) {
    val type = LocalR1Type.current
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
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = title, 
                fontSize = 28.sp, 
                fontFamily = type.appCard.fontFamily, 
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(Modifier.height(32.dp))
            
            // Track + fill bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(TRACK_OFF, CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(16.dp)
                        .background(HIGHLIGHT_BG, CircleShape),
                )
            }
            Spacer(Modifier.height(32.dp))
            
            Text(
                text = "$percent%", 
                fontSize = 24.sp, 
                fontFamily = type.appCard.fontFamily, 
                fontWeight = FontWeight.Bold,
                color = HIGHLIGHT_BG
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = hint, 
                fontSize = 16.sp, 
                fontFamily = type.appCard.fontFamily, 
                color = Color.Gray
            )
        }
    }
}

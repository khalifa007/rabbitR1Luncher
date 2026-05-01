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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.WifiShareEditTarget

@Composable
fun WifiShareEditPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.WIFI_SHARE_EDIT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val isPass = state.wifiShareEditTarget == WifiShareEditTarget.PASSWORD
        val header = if (isPass) "Hotspot password:" else "Hotspot name:"
        val placeholder = if (isPass) "Enter password..." else "Enter name..."
        val display = state.wifiShareEditInput.ifEmpty { placeholder }
        val canSubmit = if (isPass) state.wifiShareEditInput.length >= 8 else state.wifiShareEditInput.isNotEmpty()

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "< back",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onBack() }.padding(bottom = 16.dp)
                )

                Text(
                    text = header,
                    color = Color.Gray,
                    fontSize = 18.sp,
                    fontFamily = type.appCard.fontFamily,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = display,
                    color = if (state.wifiShareEditInput.isEmpty()) Color.DarkGray else Color.White,
                    fontSize = 24.sp,
                    fontFamily = type.appCard.fontFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "[ SAVE ]",
                    color = if (canSubmit) Color.Black else Color.DarkGray,
                    fontSize = 22.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(if (canSubmit) Color(0xFFFF4500) else Color.Transparent)
                        .clickable(enabled = canSubmit) { if (canSubmit) onSubmit() }
                        .padding(vertical = 8.dp, horizontal = 16.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                RetroKeyboard(
                    onKeyPress = { key ->
                        state.wifiShareEditInput += key
                    },
                    onBackspace = {
                        if (state.wifiShareEditInput.isNotEmpty()) {
                            state.wifiShareEditInput = state.wifiShareEditInput.dropLast(1)
                        }
                    },
                    onDismiss = {
                        if (canSubmit) onSubmit() else onBack()
                    }
                )
            }
        }
    }
}

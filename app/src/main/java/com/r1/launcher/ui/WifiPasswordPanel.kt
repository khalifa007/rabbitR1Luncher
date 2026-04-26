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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun WifiPasswordPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.WIFI_PASSWORD,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current

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
                    text = "Connect to:",
                    color = Color.Gray,
                    fontSize = 18.sp,
                    fontFamily = type.appCard.fontFamily
                )
                
                Text(
                    text = state.wifiSelectedSsid,
                    color = Color(0xFFFF4500),
                    fontSize = 24.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Password display area
                val displayPass = if (state.wifiPasswordInput.isEmpty()) {
                    "Enter password..."
                } else {
                    "*".repeat(state.wifiPasswordInput.length)
                }

                Text(
                    text = displayPass,
                    color = if (state.wifiPasswordInput.isEmpty()) Color.DarkGray else Color.White,
                    fontSize = 24.sp,
                    fontFamily = type.appCard.fontFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )

                // Connect button
                Text(
                    text = "[ CONNECT ]",
                    color = if (state.wifiPasswordInput.isEmpty()) Color.DarkGray else Color.Black,
                    fontSize = 22.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(if (state.wifiPasswordInput.isEmpty()) Color.Transparent else Color(0xFFFF4500))
                        .clickable(enabled = state.wifiPasswordInput.isNotEmpty()) {
                            if (state.wifiPasswordInput.isNotEmpty()) onSubmit()
                        }
                        .padding(vertical = 8.dp, horizontal = 16.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }

            // Keyboard at the bottom
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                RetroKeyboard(
                    onKeyPress = { key ->
                        state.wifiPasswordInput += key
                    },
                    onBackspace = {
                        if (state.wifiPasswordInput.isNotEmpty()) {
                            state.wifiPasswordInput = state.wifiPasswordInput.dropLast(1)
                        }
                    },
                    onDismiss = {
                        // The keyboard "hide" button can also trigger submit if password is not empty
                        if (state.wifiPasswordInput.isNotEmpty()) onSubmit() else onBack()
                    }
                )
            }
        }
    }
}

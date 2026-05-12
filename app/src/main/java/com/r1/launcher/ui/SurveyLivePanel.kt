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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun SurveyLivePanel(state: LauncherState) {
    AnimatedVisibility(
        visible = state.panel == Panel.SURVEY_LIVE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)
        val warn = Color(0xFFE53935)

        val mins = state.surveyCallElapsedMs / 60_000
        val secs = (state.surveyCallElapsedMs / 1000) % 60
        val elapsed = "%02d:%02d".format(mins, secs)

        val statusColor = when (state.surveyCallStatus) {
            "dialing", "ringing", "live" -> ok
            "ended" -> dim
            "failed" -> warn
            else -> dim
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = state.surveyCallContactName.ifBlank { "calling…" },
                    color = orange,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = type.appCard.fontFamily,
                )
                Text(
                    text = elapsed,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                Text(
                    text = state.surveyCallStatus.ifBlank { "idle" },
                    color = statusColor,
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                if (state.surveyCallTotal > 0) {
                    Text(
                        text = "q ${state.surveyCallAnswered} of ${state.surveyCallTotal}",
                        color = dim,
                        fontSize = 12.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                if (state.surveyCallCurrentQuestion.isNotBlank()) {
                    Text(
                        text = state.surveyCallCurrentQuestion,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                Text(
                    text = "tap side to hang up",
                    color = Color(0xFF707070),
                    fontSize = 10.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
        }
    }
}

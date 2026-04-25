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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.audio.AudioTester

@Composable
fun AudioTestPanel(
    state: LauncherState,
    onBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.AUDIO_TEST,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            ) {
                BackPill(label = "home", onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "audio",
                    style = type.appCard,
                    color = Color.White,
                )
            }

            val sources = AudioTester.Source.values()
            val src = sources[state.audioTestSourceIndex.coerceIn(0, sources.lastIndex)]

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "src: ${src.label}",
                    style = type.appCard,
                    color = Color(0xFFFF4500),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(18.dp))

                LevelMeter(
                    rms = state.audioTestLevel,
                    peak = state.audioTestPeak,
                )

                Spacer(Modifier.height(18.dp))

                val statusLine = when {
                    state.audioTestStatus == "recording" ->
                        "recording — peak so far ${state.audioTestLastPeakOverall}%"
                    state.audioTestStatus == "playing" -> "playing back…"
                    state.audioTestStatus == "done" -> {
                        val secs = state.audioTestLastDurationMs / 1000.0
                        "captured ${"%.1f".format(secs)}s · peak ${state.audioTestLastPeakOverall}%"
                    }
                    state.audioTestStatus.startsWith("error") -> state.audioTestStatus
                    else -> if (state.audioTestHasRecording)
                        "press wheel to record again"
                    else
                        "press wheel to record"
                }
                Text(
                    text = statusLine,
                    style = type.appCard,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )

                if (state.audioTestStatus == "idle" || state.audioTestStatus == "done") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "scroll: switch source",
                        style = type.appCard,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(rms: Int, peak: Int) {
    val rmsClamped = rms.coerceIn(0, 100)
    val peakClamped = peak.coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF222222)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(rmsClamped / 100f)
                .background(Color(0xFFFF4500)),
        )
        if (peakClamped > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((peakClamped / 100f).coerceAtMost(1f))
                    .padding(end = 2.dp)
                    .background(Color.Transparent),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
    }
}

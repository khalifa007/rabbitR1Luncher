package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/**
 * Active-recording view. The side button single-tap toggles record (handled in
 * [com.r1.launcher.LauncherActivity.dispatchKeyEvent]) — this composable is
 * mostly visual feedback.
 *
 *   ╭───────────╮
 *   │   • REC   │
 *   │           │
 *   │   05:23   │
 *   │           │
 *   │ ▮▮▮▮▮▯▯▯▯ │   ← 10-bar peak meter
 *   │           │
 *   │ tap side  │
 *   │ to stop   │
 *   ╰───────────╯
 */
@Composable
fun TranscriberRecordingPanel(state: LauncherState, onBack: () -> Unit, onStop: () -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSCRIBER_RECORDING,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val red = Color(0xFFE53935)

        val totalSec = (state.recordingElapsedMs / 1000L).toInt()
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        val timeStr = if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%02d:%02d".format(mm, ss)

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Back affordance in top-left for touch users; side-button-tap is
            // the canonical stop gesture.
            PageBackPill(
                focused = false,
                themeColor = AppThemes.Meetings,
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(red),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.recordingActive) "REC" else "—",
                        color = red,
                        fontSize = 18.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = timeStr,
                    color = Color.White,
                    fontSize = 56.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                Spacer(Modifier.height(20.dp))
                PeakMeter(level = state.recordingPeak, modifier = Modifier.fillMaxWidth().height(20.dp))
                Spacer(Modifier.height(28.dp))

                // Stop pill — touch alternative to the side-button tap.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(red.copy(alpha = 0.16f))
                        .border(1.dp, red, RoundedCornerShape(8.dp))
                        .clickable { onStop() }
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.transcriber_recording_stop),
                        color = red,
                        fontSize = 18.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.transcriber_recording_hint),
                    color = Color(0xFF707070),
                    fontSize = 12.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun PeakMeter(level: Int, modifier: Modifier = Modifier) {
    // 10 segments. MediaRecorder.getMaxAmplitude() returns 0..32767. Scale
    // logarithmically so quiet voices still register a few bars.
    val bars = 10
    val norm = if (level <= 0) 0.0 else (kotlin.math.ln(level.toDouble()) / kotlin.math.ln(32767.0))
    val active = (norm * bars).toInt().coerceIn(0, bars)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until bars) {
            val color = when {
                i >= active -> Color(0xFF1F1F1F)
                i < bars * 0.6 -> Color(0xFF35D26F)
                i < bars * 0.85 -> Color(0xFFFFA726)
                else -> Color(0xFFE53935)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun OpenClawQrPanel(
    state: LauncherState,
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_QR,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val colors = LocalR1Colors.current
        val type = LocalR1Type.current
        var scanner by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    DecoratedBarcodeView(ctx).apply {
                        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        statusView.visibility = android.view.View.GONE
                        viewFinder.setLaserVisibility(false)
                        decodeContinuous(object : BarcodeCallback {
                            private var fired = false
                            override fun barcodeResult(result: BarcodeResult?) {
                                val text = result?.text ?: return
                                if (fired) return
                                fired = true
                                pause()
                                onScanned(text)
                            }
                            override fun possibleResultPoints(points: MutableList<ResultPoint>?) {}
                        })
                        scanner = this
                    }
                },
            )

            DisposableEffect(scanner) {
                scanner?.resume()
                // QR scanner aims at EXTERNAL codes (posters, screens,
                // packages), so pivot the lens fully away from the user
                // (BACK = 180°). On close, return to IDLE (HOME = 90°).
                setMotorOrientation(MOTOR_BACK)
                onDispose {
                    runCatching { scanner?.pause() }
                    setMotorOrientation(MOTOR_HOME)
                }
            }

            // Header overlay
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                BackPill(label = "home", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "scan gate qr",
                    style = type.appCard.copy(fontSize = 18.sp),
                    color = colors.accent,
                )
            }

            // Centered framing reticle
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .border(2.dp, colors.accent, RoundedCornerShape(16.dp)),
            )

            // Bottom error / hint
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 18.dp, start = 24.dp, end = 24.dp),
            ) {
                val err = state.qrError
                    if (err != null) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xCCB30000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(err, style = type.appCard, color = Color.White)
                        }
                    }
                    Text(
                        text = "show your gate setup-code qr",
                        style = type.appCard,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
            }
        }
    }
}

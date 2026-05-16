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

/**
 * QR scanner for the Hermes Agent pairing payload emitted by
 * `scripts/hermes-setup.sh` (`r1-hermes://v1/<base64>`). On a successful scan
 * the launcher decodes the payload, saves URL + key into HermesPrefs, returns
 * to HERMES_CONFIG, and auto-probes `/health`. Errors render as a red toast at
 * the bottom — payload of [LauncherState.hermesQrError].
 *
 * Mirrors [OpenClawQrPanel] structurally; only the panel gate, theme colour,
 * title, and error source differ.
 */
@Composable
fun HermesQrPanel(
    state: LauncherState,
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_QR,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Hermes
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

            // Motor lifetime is decoupled from `scanner` (which transitions
            // null → DecoratedBarcodeView once the AndroidView factory runs).
            // Keying on `scanner` would re-fire the effect on that flip and
            // queue BACK → HOME → BACK on the serialized motor executor —
            // ~1s of visible jitter and the lens occasionally settling at
            // HOME if a coincident carroot write swallows the final BACK.
            // Same fix as OpenClawCameraPanel.kt:99–102.
            //
            // If the stepper has drifted enough that BACK doesn't land
            // fully, use Settings → Device → "reset camera" — driving
            // through FACE on every entry made the swing look like three
            // distinct stops (face → idle → back) and felt worse than
            // the occasional drift.
            DisposableEffect(Unit) {
                setMotorOrientation(MOTOR_BACK)
                onDispose { setMotorOrientation(MOTOR_HOME) }
            }
            DisposableEffect(scanner) {
                scanner?.resume()
                onDispose { runCatching { scanner?.pause() } }
            }

            // Header overlay
            AppPageHeader(
                backFocused = false,
                onBack = onBack,
                themeColor = AppThemes.Hermes,
                compact = true,
                subtitle = "scan hermes config",
            )

            // Centered framing reticle
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .border(2.dp, accent, RoundedCornerShape(16.dp)),
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
                val err = state.hermesQrError
                if (err != null) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCCB30000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(err, style = type.appCard, color = Color.White)
                    }
                }
            }
        }
    }
}

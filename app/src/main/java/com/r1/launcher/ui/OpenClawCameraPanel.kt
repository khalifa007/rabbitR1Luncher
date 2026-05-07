package com.r1.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

@Composable
fun OpenClawCameraPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onCaptured: (ByteArray) -> Unit,
    onRetake: () -> Unit,
    onSend: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_CAMERA,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val context = LocalContext.current
        val type = LocalR1Type.current
        val accent = Color(0xFFFF4500)
        var cameraView by remember { mutableStateOf<R1StillCameraView?>(null) }
        var prompt by remember(state.panel) { mutableStateOf(state.openClawCameraPrompt) }
        var showKeyboard by remember { mutableStateOf(false) }
        var localError by remember { mutableStateOf<String?>(null) }

        // Hold the lens at the user-selected angle for the entire panel
        // session — independent of the camera-view's start/stop cycle.
        // Earlier code drove the motor from R1StillCameraView.start()/stop(),
        // which fired BACK on entry, HOME on capture (when the AndroidView
        // was swapped for the captured Image), and BACK again on retake. The
        // motor visibly cycled through idle ("stuck in idle" / "tries to face
        // front" reports). Decoupling motor lifetime from camera lifetime
        // keeps the lens at the chosen angle throughout review/retake.
        // Wheel up/down nudges state.openClawCameraMotor; we fire those
        // writes directly from the host (LauncherActivity.openClawCameraMotorNudge),
        // so this effect only handles initial pose + return-to-idle on exit.
        DisposableEffect(Unit) {
            setMotorOrientation(state.openClawCameraMotor)
            onDispose { setMotorOrientation(MOTOR_HOME) }
        }

        DisposableEffect(cameraView, state.openClawCameraJpegBase64) {
            if (state.openClawCameraJpegBase64 == null) {
                cameraView?.start()
            }
            onDispose { cameraView?.stop() }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val capturedBytes = remember(state.openClawCameraJpegBase64) {
                state.openClawCameraJpegBase64?.let {
                    runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                }
            }
            val capturedBitmap = remember(capturedBytes) {
                capturedBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }

            if (capturedBitmap != null) {
                Image(
                    bitmap = capturedBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        R1StillCameraView(ctx) { bytes, err ->
                            if (bytes != null) {
                                localError = null
                                onCaptured(bytes)
                            } else {
                                localError = err ?: "camera failed"
                            }
                        }.also { cameraView = it }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f)),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            ) {
                BackPill(label = stringResource(R.string.openclaw_back_chat), onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (capturedBitmap == null) R.string.openclaw_camera_snap_ask
                        else R.string.openclaw_camera_review_ask
                    ),
                    style = type.appCard.copy(fontSize = 20.sp),
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                if (capturedBitmap == null) {
                    val angle = state.openClawCameraMotor
                    val motorTxt = stringResource(
                        when {
                            angle <= 15 -> R.string.openclaw_camera_motor_face
                            angle in 75..105 -> R.string.openclaw_camera_motor_idle
                            angle >= 165 -> R.string.openclaw_camera_motor_back
                            angle < 90 -> R.string.openclaw_camera_motor_to_face
                            else -> R.string.openclaw_camera_motor_to_back
                        },
                        angle,
                    )
                    Text(
                        text = motorTxt,
                        style = type.appCard.copy(fontSize = 14.sp),
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                CameraStatusDot(
                    color = when {
                        state.openClawCameraBusy -> Color(0xFFFFC107)
                        capturedBitmap != null -> Color(0xFF35D26F)
                        else -> accent
                    },
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val error = state.openClawCameraError ?: localError
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = type.appCard.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                        color = Color(0xFFFFC107),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, accent, RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .clickable { showKeyboard = !showKeyboard }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val promptHint = stringResource(R.string.openclaw_camera_prompt_hint)
                    Text(
                        text = prompt.ifBlank { promptHint } + if (showKeyboard) "_" else "",
                        style = type.appCard.copy(fontSize = 18.sp),
                        color = if (prompt.isBlank()) Color.DarkGray else Color.White,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val permNeeded = stringResource(R.string.openclaw_camera_perm_needed)
                    val defaultPrompt = stringResource(R.string.openclaw_camera_default_prompt)
                    if (capturedBitmap == null) {
                        CameraAction(label = stringResource(R.string.openclaw_camera_snap), accent = accent, enabled = !state.openClawCameraBusy) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasPermission) {
                                localError = permNeeded
                            } else {
                                localError = null
                                cameraView?.capture()
                            }
                        }
                    } else {
                        CameraAction(label = stringResource(R.string.openclaw_camera_retake), accent = Color.White, enabled = !state.openClawCameraBusy) {
                            localError = null
                            onRetake()
                        }
                        CameraAction(
                            label = stringResource(
                                if (state.openClawCameraBusy) R.string.openclaw_camera_sending
                                else R.string.openclaw_camera_ask
                            ),
                            accent = accent,
                            enabled = !state.openClawCameraBusy,
                        ) {
                            onSend(prompt.ifBlank { defaultPrompt })
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showKeyboard,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    RetroKeyboard(
                        onKeyPress = { ch -> prompt += ch },
                        onBackspace = { if (prompt.isNotEmpty()) prompt = prompt.dropLast(1) },
                        onDismiss = { showKeyboard = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun CameraAction(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (enabled) accent else Color.DarkGray, RoundedCornerShape(12.dp))
            .background(if (accent == Color.White) Color.Black else accent.copy(alpha = 0.18f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 22.sp),
            color = if (enabled) accent else Color.DarkGray,
        )
    }
}

private class R1StillCameraView(
    context: Context,
    private val onCapture: (ByteArray?, String?) -> Unit,
) : TextureView(context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private var sensorOrientation: Int = 0
    @Volatile private var stopped = true

    private val textureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stop()
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    fun start() {
        stopped = false
        if (thread == null) {
            thread = HandlerThread("r1-still-camera").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        surfaceTextureListener = textureListener
        if (isAvailable) openCamera()
        // Motor pivoting is handled at the panel level — see
        // OpenClawCameraPanel's DisposableEffect(Unit). Doing it here would
        // cycle the lens through HOME on every capture/retake.
    }

    fun stop() {
        stopped = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { imageReader?.close() }
        session = null
        camera = null
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun capture() {
        if (stopped) return onCapture(null, "camera not ready")
        val device = camera ?: return onCapture(null, "camera not ready")
        val activeSession = session ?: return onCapture(null, "preview not ready")
        val reader = imageReader ?: return onCapture(null, "camera buffer not ready")
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
        }.build()
        runCatching {
            activeSession.capture(request, null, handler)
        }.onFailure {
            onCapture(null, it.message ?: "capture failed")
        }
    }

    private fun openCamera() {
        if (stopped) return
        if (camera != null) return
        val selected = selectCamera() ?: return onCapture(null, "no camera found")
        cameraId = selected
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onCapture(null, "camera permission needed")
            return
        }
        runCatching {
            manager.openCamera(selected, object : CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: CameraDevice) {
                    if (stopped) {
                        cameraDevice.close()
                        return
                    }
                    camera = cameraDevice
                    startPreview(cameraDevice)
                }
                override fun onDisconnected(cameraDevice: CameraDevice) {
                    cameraDevice.close()
                    camera = null
                }
                override fun onError(cameraDevice: CameraDevice, error: Int) {
                    cameraDevice.close()
                    camera = null
                    onCapture(null, "camera error $error")
                }
            }, handler)
        }.onFailure {
            onCapture(null, it.message ?: "open camera failed")
        }
    }

    private fun startPreview(device: CameraDevice) {
        if (stopped || camera !== device) return
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(texture)
        val reader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
        imageReader = reader
        reader.setOnImageAvailableListener({ availableReader ->
            val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = image.use {
                val buffer = it.planes[0].buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            onCapture(bytes, null)
        }, handler)
        runCatching {
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (stopped || camera !== device) {
                            runCatching { captureSession.close() }
                            return
                        }
                        session = captureSession
                        runCatching {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            if (!stopped && camera === device) {
                                captureSession.setRepeatingRequest(request, null, handler)
                            }
                        }.onFailure {
                            if (!stopped) onCapture(null, it.message ?: "preview failed")
                            runCatching { captureSession.close() }
                        }
                    }
                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        if (!stopped) onCapture(null, "preview failed")
                    }
                },
                handler,
            )
        }.onFailure {
            onCapture(null, it.message ?: "preview failed")
        }
    }

    private fun selectCamera(): String? {
        val ids = manager.cameraIdList
        val back = ids.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val selected = back ?: ids.firstOrNull()
        if (selected != null) {
            sensorOrientation = manager.getCameraCharacteristics(selected)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }
        return selected
    }
}

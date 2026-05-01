package com.r1.launcher.ui

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

// R1 has a single physical camera mounted on a stepper-motor gimbal
// (kernel driver step_motor_ms35774). The OEM exposes flipping only via a
// Quick Settings tile (com.rabbitescape.stepmotor/.CameraTileService), but
// CarrotOS hides the status bar in kiosk mode, so the QS tile is unreachable.
// We drive /sys/devices/platform/step_motor_ms35774/orientation directly
// through the carroot root shell on TCP 1337.
//
// Empirically observed orientation values (calibrated 2026-04-29):
//   0   -> front camera (lens pointed at the user, selfie position)
//   90  -> idle / rest (boot-default parked angle, lens pointed at neither user nor scene)
//   180 -> back camera (lens pointed away from user, for QR codes / external photos)
// Driver accepts other small ints in between (45, 89, 91, etc.) but clamps to
// 180 max — writes of 270 readback as 180.

const val MOTOR_FACE = 0
const val MOTOR_HOME = 90
const val MOTOR_BACK = 180

// Single-threaded executor so motor writes are FIFO. Earlier code spawned a
// fresh Thread per call; if the panel cycled stop/start (e.g. capture →
// retake), the HOME and BACK writes raced and the lens occasionally settled
// at HOME ("stuck in idle"). Serializing eliminates that race; the most
// recent enqueued value always wins.
private val motorExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "r1-motor").apply { isDaemon = true }
}

fun setMotorOrientation(value: Int) {
    Log.i("R1Motor", "setMotorOrientation($value) queued")
    motorExecutor.execute {
        // One quick retry — carroot is a single-listener nc on :1337 and
        // briefly refuses connections when another caller (toggleWifi,
        // factoryReset, etc.) is in-flight. Without a retry, a coincident
        // toggle silently swallows the motor write and the lens stays put.
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                    s.getOutputStream().apply {
                        write("echo $value > /sys/devices/platform/step_motor_ms35774/orientation\n".toByteArray())
                        flush()
                    }
                    Thread.sleep(300)
                    Log.i("R1Motor", "carroot write done for orientation=$value (attempt=$attempt)")
                    return@execute
                }
            } catch (t: Throwable) {
                Log.w("R1Motor", "setMotorOrientation($value) attempt=$attempt FAILED: ${t.javaClass.simpleName}: ${t.message}")
                runCatching { Thread.sleep(150) }
            }
        }
        Log.e("R1Motor", "setMotorOrientation($value) gave up after $attempt attempts")
    }
}

package com.r1.launcher.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render [content] as a square QR code at [sizePx] pixels and return it as a
 * Compose [ImageBitmap]. Cached on `(content, sizePx)` so panel recompositions
 * don't re-encode every frame.
 *
 * Uses the L (low) error-correction level — at the small size the launcher
 * shows it, higher correction makes the modules so dense that the camera on
 * a phone struggles. URLs are short, so L is plenty.
 */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 360): ImageBitmap? =
    remember(content, sizePx) {
        runCatching { encodeQrToBitmap(content, sizePx) }
            .getOrNull()
            ?.asImageBitmap()
    }

private fun encodeQrToBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    // White background (matches the panel card) + orange foreground modules so
    // the QR ties into the rest of the R1 visual language. Orange-on-white
    // contrast still scans cleanly on any modern camera.
    val fg = 0xFFFF6A00.toInt()
    val bg = 0xFFFFFFFF.toInt()
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) fg else bg)
        }
    }
    return bmp
}

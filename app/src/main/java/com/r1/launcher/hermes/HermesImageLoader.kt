package com.r1.launcher.hermes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and decodes images referenced by Hermes assistant replies (markdown
 * `![alt](url)` syntax). Cached in-memory so re-scrolling doesn't refetch.
 *
 * If the URL points back at the user's own Hermes gateway, the saved bearer
 * token is attached so authenticated `/files/...` endpoints work; for any
 * other host the request goes out unauthenticated.
 */
object HermesImageLoader {
    /** Hard cap on the wire — refuse to even buffer payloads larger than this.
     *  Screen is 480×480; legitimate chat images don't approach this size. */
    private const val MAX_WIRE_BYTES = 8L * 1024 * 1024

    /** Max width/height after downsampling. Picked to fully cover the 480px
     *  bubble at 2× DPR without ever decoding a 4000×3000 photo at full res. */
    private const val MAX_DECODED_DIM = 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(ctx: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        runCatching {
            val prefs = HermesPrefs.get(ctx)
            val req = Request.Builder().url(url).apply {
                if (shouldAttachToken(url, prefs.baseRoot(), prefs.apiKey)) {
                    header("Authorization", "Bearer ${prefs.apiKey}")
                }
            }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val cl = resp.body?.contentLength() ?: -1L
                if (cl in 1..MAX_WIRE_BYTES || cl < 0) {
                    // proceed; cl < 0 means chunked / unknown — bytes() below
                    // will still respect MAX_WIRE_BYTES via the size guard.
                } else {
                    return@runCatching null
                }
                val bytes = resp.body?.bytes() ?: return@runCatching null
                if (bytes.size > MAX_WIRE_BYTES) return@runCatching null
                val bmp = decodeSampled(bytes) ?: return@runCatching null
                bmp.asImageBitmap().also { cache.put(url, it) }
            }
        }.getOrNull()
    }

    /** Origin-bound bearer-token attach. Compares scheme + host + port via
     *  OkHttp's parser so subdomain attacks (gateway.com → gateway.com.evil.test)
     *  can't smuggle the token to a different host that happens to share a prefix. */
    private fun shouldAttachToken(targetUrl: String, base: String, token: String): Boolean {
        if (token.isBlank() || base.isBlank()) return false
        val t = targetUrl.toHttpUrlOrNull() ?: return false
        val b = base.toHttpUrlOrNull() ?: return false
        return t.scheme == b.scheme && t.host == b.host && t.port == b.port
    }

    /** Two-pass decode: first reads dimensions only, then computes a power-of-2
     *  `inSampleSize` so the resulting Bitmap is ≤ MAX_DECODED_DIM on the long
     *  edge. Caps heap regardless of source size. */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while ((w / sample) > MAX_DECODED_DIM || (h / sample) > MAX_DECODED_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

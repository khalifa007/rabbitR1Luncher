package com.r1.launcher.translator

import android.util.LruCache
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Provider-agnostic facade with an in-memory LRU cache.
 *
 * Cache key is `(provider, source, target, text)` — switching providers
 * mid-conversation re-runs the call, which is what users expect ("see how
 * Claude translates this vs Gemini"). Cache hits are instant and free.
 *
 * One in-flight call is tracked so [cancel] can abort the active translation
 * — used when the user starts a new STT turn before the previous translation
 * has returned.
 */
class TranslatorClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cache = LruCache<String, String>(64)

    @Volatile private var inflight: Call? = null

    /** Async one-shot translation.
     *
     * [sourceLangCode] / [targetLangCode] are ISO 639-1 codes. The Languages
     * catalog is hit here to derive the English name passed into the LLM
     * prompt (model accuracy is significantly higher with the English label
     * than with the raw code).
     *
     * [onResult] always fires once — never zero, never twice. Cache hits
     * deliver synchronously on the calling thread; misses deliver on the
     * OkHttp dispatcher thread (the caller is expected to bounce back to UI
     * via `ui.post {}` if needed).
     */
    fun translate(
        provider: TranslatorProvider,
        apiKey: String,
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        onResult: (Result<String>) -> Unit,
    ): Call? {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            onResult(Result.failure(IOException("empty input")))
            return null
        }
        if (sourceLangCode == targetLangCode) {
            // No-op translation — short-circuit so we don't burn an API call
            // when the user has source == target (e.g. swapped accidentally).
            onResult(Result.success(cleanText))
            return null
        }
        val key = "${provider.id.name}|$sourceLangCode|$targetLangCode|$cleanText"
        cache.get(key)?.let {
            onResult(Result.success(it))
            return null
        }
        val src = Languages.get(sourceLangCode).english
        val tgt = Languages.get(targetLangCode).english
        val call = provider.translate(http, apiKey, cleanText, src, tgt) { result ->
            inflight = null
            result.onSuccess { translated ->
                if (translated.isNotBlank()) cache.put(key, translated)
                onResult(Result.success(translated))
            }.onFailure {
                onResult(Result.failure(it))
            }
        }
        inflight = call
        return call
    }

    fun cancel() {
        runCatching { inflight?.cancel() }
        inflight = null
    }

    fun clearCache() {
        cache.evictAll()
    }
}

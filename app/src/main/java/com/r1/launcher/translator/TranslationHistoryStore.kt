package com.r1.launcher.translator

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Append-only JSON log of finished translations, capped at [MAX_ENTRIES].
 *
 * Survives launcher restarts so the user can scroll back through earlier
 * translations (useful for showing the other person what was said). Pending
 * / errored entries are not persisted — only confirmed source→target pairs.
 *
 * File lives at `filesDir/translator-history.json`. Single-threaded — call
 * from a background thread to avoid main-thread IO on save.
 */
object TranslationHistoryStore {
    private const val TAG = "TranslatorHistory"
    private const val FILE_NAME = "translator-history.json"
    const val MAX_ENTRIES = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(ctx: Context): List<TranslationMessage> {
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) emptyList()
            else json.decodeFromString(Blob.serializer(), raw).items
        }.getOrElse {
            Log.e(TAG, "load failed", it)
            emptyList()
        }
    }

    fun save(ctx: Context, items: List<TranslationMessage>) {
        val file = File(ctx.filesDir, FILE_NAME)
        // Drop pending/errored entries before persisting — they're meaningless
        // after a restart and would just be confusing.
        val keep = items
            .filter { !it.pending && it.error == null && it.targetText.isNotBlank() }
            .takeLast(MAX_ENTRIES)
        runCatching {
            file.writeText(json.encodeToString(Blob.serializer(), Blob(keep)))
        }.onFailure { Log.e(TAG, "save failed", it) }
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE_NAME).delete() }
    }

    @Serializable
    private data class Blob(val items: List<TranslationMessage> = emptyList())
}

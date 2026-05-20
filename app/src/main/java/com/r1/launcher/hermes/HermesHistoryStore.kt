package com.r1.launcher.hermes

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-connection chat scrollback persistence. Each connection's history lives
 * at `filesDir/hermes/history-<connectionId>.json`. Hermes is stateless on
 * /v1/chat/completions, so the client owns the full message list.
 *
 * On first read for the migrated connection (`activeId` after the prefs
 * migration completes), the legacy single-file `filesDir/hermes-history.json`
 * is copied across then deleted. Failures are best-effort: the legacy file is
 * preserved and the new file simply starts empty.
 */
object HermesHistoryStore {
    private const val DIR_NAME = "hermes"
    private const val FILE_PREFIX = "history-"
    private const val LEGACY_FILE = "hermes-history.json"
    private const val MAX_ENTRIES = 200
    private const val TAG = "HermesHistoryStore"

    @Serializable
    private data class Root(val messages: List<HermesMessage> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).also { it.mkdirs() }

    private fun file(ctx: Context, connectionId: String): File =
        File(dir(ctx), "$FILE_PREFIX$connectionId.json")

    private fun legacyFile(ctx: Context): File = File(ctx.filesDir, LEGACY_FILE)

    @Synchronized
    fun load(ctx: Context, connectionId: String): List<HermesMessage> {
        val target = file(ctx, connectionId)
        if (!target.exists()) {
            // Try one-time legacy migration: only triggers for the connection
            // that owns the legacy history (the caller is responsible for
            // passing the migrated connection's id here on first call).
            val legacy = legacyFile(ctx)
            if (legacy.exists()) {
                runCatching {
                    legacy.copyTo(target, overwrite = false)
                    legacy.delete()
                    Log.i(TAG, "migrated legacy history → $target")
                }.onFailure { Log.w(TAG, "legacy history migration failed", it) }
            }
        }
        if (!target.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(Root.serializer(), target.readText()).messages
        }.getOrElse {
            Log.w(TAG, "decode failed for ${target.name}", it)
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, connectionId: String, messages: List<HermesMessage>) {
        runCatching {
            val trimmed = if (messages.size > MAX_ENTRIES)
                messages.takeLast(MAX_ENTRIES) else messages
            val raw = json.encodeToString(Root.serializer(), Root(trimmed))
            file(ctx, connectionId).writeText(raw)
        }.onFailure { Log.w(TAG, "save failed for $connectionId", it) }
    }

    @Synchronized
    fun clear(ctx: Context, connectionId: String) {
        runCatching { file(ctx, connectionId).delete() }
    }

    /** Same as [clear] today, but kept as a distinct call so future per-conn
     *  cleanup (auxiliary indexes, etc.) has a single hook. */
    @Synchronized
    fun deleteAll(ctx: Context, connectionId: String) = clear(ctx, connectionId)
}

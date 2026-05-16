package com.r1.launcher.hermes

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the Hermes chat scrollback to `filesDir/hermes-history.json` so it
 * survives launcher restarts. Hermes is stateless on /v1/chat/completions —
 * the client owns the message list, and without this store every cold start
 * shows an empty chat.
 *
 * Capped to avoid runaway file growth; oldest messages drop first.
 */
object HermesHistoryStore {
    private const val FILE_NAME = "hermes-history.json"
    private const val MAX_ENTRIES = 200

    @Serializable
    private data class Root(val messages: List<HermesMessage> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun load(ctx: Context): List<HermesMessage> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = json.decodeFromString(Root.serializer(), f.readText())
            root.messages
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(ctx: Context, messages: List<HermesMessage>) {
        runCatching {
            val trimmed = if (messages.size > MAX_ENTRIES)
                messages.takeLast(MAX_ENTRIES) else messages
            val raw = json.encodeToString(Root.serializer(), Root(trimmed))
            file(ctx).writeText(raw)
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

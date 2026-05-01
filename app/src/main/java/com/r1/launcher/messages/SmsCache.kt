package com.r1.launcher.messages

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local SMS log captured by [SmsReceiver]. Exists because CarrotOS has no
 * default SMS app, so the framework drops every incoming SMS at
 * InboundSmsHandler before it reaches `content://sms`. We listen to the
 * legacy SMS_RECEIVED broadcast (which still fires for any holder of
 * `RECEIVE_SMS`) and persist what we see ourselves.
 *
 * Capped append-only JSON file at filesDir/sms-cache.json. Newest items
 * appear last, in receive order. Reads are O(file-size) but the cap keeps
 * that bounded.
 */
object SmsCache {
    private const val FILE_NAME = "sms-cache.json"
    private const val MAX_ENTRIES = 1000

    @Serializable
    data class Entry(
        val address: String,
        val body: String,
        val timestampMs: Long,
        val read: Boolean = false,
    )

    @Serializable
    private data class CacheRoot(val entries: List<Entry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val mem = mutableListOf<Entry>()

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        if (f.exists()) {
            runCatching {
                val raw = f.readText()
                val root = json.decodeFromString(CacheRoot.serializer(), raw)
                mem.clear()
                mem.addAll(root.entries)
            }
        }
        loaded = true
    }

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        load(ctx)
        return mem.toList()
    }

    @Synchronized
    fun append(ctx: Context, entry: Entry) {
        load(ctx)
        mem.add(entry)
        while (mem.size > MAX_ENTRIES) mem.removeAt(0)
        save(ctx)
    }

    private fun save(ctx: Context) {
        runCatching {
            val raw = json.encodeToString(CacheRoot(mem.toList()))
            file(ctx).writeText(raw)
        }
    }
}

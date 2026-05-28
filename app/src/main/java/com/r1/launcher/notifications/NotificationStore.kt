package com.r1.launcher.notifications

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Append-only JSON log at filesDir/notifications.json. Modeled on SmsCache:
 * eager load on first access, in-memory list of [Notification], FIFO drop
 * once [MAX_ENTRIES] is exceeded so the file never grows unbounded.
 *
 * All mutations persist synchronously. Reads return defensive copies so the
 * caller can iterate freely on the UI thread.
 */
object NotificationStore {
    private const val FILE_NAME = "notifications.json"
    private const val MAX_ENTRIES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val mem = mutableListOf<Notification>()
    private var nextId: Long = 1L

    /** Single-thread writer so disk persistence never blocks the caller (a
     *  burst of ntfy / POST /api/notify messages used to rewrite the whole
     *  file on the main thread). The in-memory list is authoritative for
     *  reads; the executor just mirrors it to disk in order. */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "notif-store-io").apply { isDaemon = true }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        if (f.exists()) {
            runCatching {
                val raw = f.readText()
                val arr = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Notification.serializer()), raw)
                mem.clear()
                mem.addAll(arr)
                nextId = (arr.maxOfOrNull { it.id } ?: 0L) + 1L
            }
        }
        loaded = true
    }

    @Synchronized
    fun all(ctx: Context): List<Notification> {
        load(ctx)
        return mem.toList()
    }

    @Synchronized
    fun unreadCount(ctx: Context): Int {
        load(ctx)
        return mem.count { !it.read }
    }

    @Synchronized
    fun nextId(ctx: Context): Long {
        load(ctx)
        return nextId++
    }

    /** Append a fully-formed notification (caller supplied id via [nextId]). */
    @Synchronized
    fun append(ctx: Context, n: Notification) {
        load(ctx)
        mem.add(n)
        while (mem.size > MAX_ENTRIES) mem.removeAt(0)
        save(ctx)
    }

    @Synchronized
    fun markRead(ctx: Context, id: Long): Boolean {
        load(ctx)
        val idx = mem.indexOfFirst { it.id == id }
        if (idx < 0 || mem[idx].read) return false
        mem[idx] = mem[idx].copy(read = true)
        save(ctx)
        return true
    }

    @Synchronized
    fun markAllRead(ctx: Context): Int {
        load(ctx)
        var changed = 0
        for (i in mem.indices) {
            if (!mem[i].read) {
                mem[i] = mem[i].copy(read = true)
                changed++
            }
        }
        if (changed > 0) save(ctx)
        return changed
    }

    @Synchronized
    fun dismiss(ctx: Context, id: Long): Boolean {
        load(ctx)
        val removed = mem.removeAll { it.id == id }
        if (removed) save(ctx)
        return removed
    }

    @Synchronized
    fun clear(ctx: Context) {
        load(ctx)
        if (mem.isEmpty()) return
        mem.clear()
        save(ctx)
    }

    private fun save(ctx: Context) {
        // Snapshot under the monitor (callers are @Synchronized), then write on
        // the IO thread so a notification burst doesn't rewrite the whole file
        // on the main thread. Atomic temp+rename so a kill mid-write can't
        // truncate the existing log.
        val raw = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Notification.serializer()),
            mem.toList(),
        )
        val f = file(ctx)
        ioExecutor.execute {
            runCatching {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(raw)
                if (!tmp.renameTo(f)) {
                    f.writeText(raw)
                    tmp.delete()
                }
            }
        }
    }
}

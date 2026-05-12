package com.r1.launcher.transcriber

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe persistence for [Meeting] records.
 *
 * Layout under `filesDir/transcriber/`:
 *   index.json           — list of [MeetingIndexEntry] for fast list rendering
 *   <uuid>.json          — full [Meeting] including transcript text/JSON
 *   audio/<uuid>.m4a     — recorded audio (kept in filesDir, NOT cacheDir, so
 *                          Android's storage-pressure cleanup can't wipe an
 *                          unsent meeting)
 *
 * Writes use temp-file + rename for atomicity — a process kill mid-write
 * leaves the previous valid file in place rather than a half-truncated one.
 */
class MeetingStore private constructor(ctx: Context) {

    private val baseDir: File = File(ctx.applicationContext.filesDir, "transcriber").apply { mkdirs() }
    private val audioDir: File = File(baseDir, "audio").apply { mkdirs() }
    private val indexFile: File = File(baseDir, "index.json")
    private val lock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun audioFile(uuid: String): File = File(audioDir, "$uuid.m4a")

    fun loadIndex(): List<MeetingIndexEntry> = synchronized(lock) {
        if (!indexFile.exists()) return emptyList()
        runCatching {
            json.decodeFromString<List<MeetingIndexEntry>>(indexFile.readText())
        }.getOrElse { emptyList() }
    }

    fun loadMeeting(uuid: String): Meeting? = synchronized(lock) {
        val f = File(baseDir, "$uuid.json")
        if (!f.exists()) return null
        runCatching { json.decodeFromString<Meeting>(f.readText()) }.getOrNull()
    }

    /** Atomic upsert: write the per-meeting JSON, then rebuild the index file
     *  from disk so a process kill between the two leaves a consistent state
     *  (the index is derivable from the per-meeting files). */
    fun save(meeting: Meeting): Unit = synchronized(lock) {
        val f = File(baseDir, "${meeting.uuid}.json")
        atomicWrite(f, json.encodeToString(meeting))
        rebuildIndexLocked()
    }

    fun delete(uuid: String): Unit = synchronized(lock) {
        File(baseDir, "$uuid.json").delete()
        audioFile(uuid).delete()
        rebuildIndexLocked()
    }

    /** All known meetings, newest first. Cheap — reads the index only. */
    fun listMeetings(): List<MeetingIndexEntry> =
        loadIndex().sortedByDescending { it.createdAtMs }

    private fun rebuildIndexLocked() {
        val entries = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<Meeting>(f.readText()) }.getOrNull()
            }
            ?.map {
                MeetingIndexEntry(
                    uuid = it.uuid,
                    title = it.title,
                    createdAtMs = it.createdAtMs,
                    durationMs = it.durationMs,
                    status = it.status,
                    speakerCount = it.speakerCount,
                )
            }
            ?: emptyList()
        atomicWrite(indexFile, json.encodeToString(entries))
    }

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystems on some kernels; fall back
            // to copy + delete which is always available.
            target.writeText(contents)
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: MeetingStore? = null
        fun get(ctx: Context): MeetingStore =
            instance ?: synchronized(this) {
                instance ?: MeetingStore(ctx).also { instance = it }
            }
    }
}

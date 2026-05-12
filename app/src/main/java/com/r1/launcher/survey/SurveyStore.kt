package com.r1.launcher.survey

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe persistence for survey-bot artifacts.
 *
 * Layout under `filesDir/surveys/`:
 *   surveys/<id>.json       + surveys/index.json
 *   campaigns/<id>.json     + campaigns/index.json
 *   records/<id>.json       + records/index.json
 *   audio/<campaignId>/<recordId>.wav   (16 kHz mono PCM16, written by SipDialer)
 *
 * Mirrors [com.r1.launcher.transcriber.MeetingStore] — atomic temp-rename writes,
 * index files derivable from per-record JSONs after a partial-write crash.
 */
class SurveyStore private constructor(ctx: Context) {

    private val baseDir: File = File(ctx.applicationContext.filesDir, "surveys").apply { mkdirs() }
    private val surveysDir: File = File(baseDir, "surveys").apply { mkdirs() }
    private val campaignsDir: File = File(baseDir, "campaigns").apply { mkdirs() }
    private val recordsDir: File = File(baseDir, "records").apply { mkdirs() }
    private val audioDir: File = File(baseDir, "audio").apply { mkdirs() }
    private val surveysIndexFile: File = File(surveysDir, "index.json")
    private val campaignsIndexFile: File = File(campaignsDir, "index.json")
    private val recordsIndexFile: File = File(recordsDir, "index.json")
    private val lock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun audioFile(campaignId: String, recordId: String): File {
        val dir = File(audioDir, campaignId).apply { mkdirs() }
        return File(dir, "$recordId.wav")
    }

    // ---- Survey ----

    fun saveSurvey(s: Survey): Unit = synchronized(lock) {
        atomicWrite(File(surveysDir, "${s.id}.json"), json.encodeToString(s))
        rebuildSurveyIndexLocked()
    }

    fun loadSurvey(id: String): Survey? = synchronized(lock) {
        val f = File(surveysDir, "$id.json")
        if (!f.exists()) return null
        runCatching { json.decodeFromString<Survey>(f.readText()) }.getOrNull()
    }

    fun deleteSurvey(id: String): Unit = synchronized(lock) {
        File(surveysDir, "$id.json").delete()
        rebuildSurveyIndexLocked()
    }

    fun listSurveys(): List<SurveyIndexEntry> =
        loadSurveyIndex().sortedByDescending { it.updatedAtMs }

    private fun loadSurveyIndex(): List<SurveyIndexEntry> = synchronized(lock) {
        if (!surveysIndexFile.exists()) return emptyList()
        runCatching {
            json.decodeFromString<List<SurveyIndexEntry>>(surveysIndexFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun rebuildSurveyIndexLocked() {
        val entries = surveysDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<Survey>(f.readText()) }.getOrNull() }
            ?.map {
                SurveyIndexEntry(
                    id = it.id,
                    name = it.name,
                    questionCount = it.questions.size,
                    createdAtMs = it.createdAtMs,
                    updatedAtMs = it.updatedAtMs,
                )
            }
            ?: emptyList()
        atomicWrite(surveysIndexFile, json.encodeToString(entries))
    }

    // ---- Campaign ----

    fun saveCampaign(c: Campaign): Unit = synchronized(lock) {
        atomicWrite(File(campaignsDir, "${c.id}.json"), json.encodeToString(c))
        rebuildCampaignIndexLocked()
    }

    fun loadCampaign(id: String): Campaign? = synchronized(lock) {
        val f = File(campaignsDir, "$id.json")
        if (!f.exists()) return null
        runCatching { json.decodeFromString<Campaign>(f.readText()) }.getOrNull()
    }

    fun deleteCampaign(id: String): Unit = synchronized(lock) {
        File(campaignsDir, "$id.json").delete()
        File(audioDir, id).deleteRecursively()
        rebuildCampaignIndexLocked()
    }

    fun listCampaigns(): List<CampaignIndexEntry> =
        loadCampaignIndex().sortedByDescending { it.createdAtMs }

    private fun loadCampaignIndex(): List<CampaignIndexEntry> = synchronized(lock) {
        if (!campaignsIndexFile.exists()) return emptyList()
        runCatching {
            json.decodeFromString<List<CampaignIndexEntry>>(campaignsIndexFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun rebuildCampaignIndexLocked() {
        val entries = campaignsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<Campaign>(f.readText()) }.getOrNull() }
            ?.map {
                CampaignIndexEntry(
                    id = it.id,
                    surveyId = it.surveyId,
                    contactCount = it.contacts.size,
                    nextContactIdx = it.nextContactIdx,
                    status = it.status,
                    createdAtMs = it.createdAtMs,
                )
            }
            ?: emptyList()
        atomicWrite(campaignsIndexFile, json.encodeToString(entries))
    }

    // ---- Call record ----

    fun saveCallRecord(r: CallRecord): Unit = synchronized(lock) {
        atomicWrite(File(recordsDir, "${r.id}.json"), json.encodeToString(r))
        rebuildRecordIndexLocked()
    }

    fun loadCallRecord(id: String): CallRecord? = synchronized(lock) {
        val f = File(recordsDir, "$id.json")
        if (!f.exists()) return null
        runCatching { json.decodeFromString<CallRecord>(f.readText()) }.getOrNull()
    }

    fun deleteCallRecord(id: String): Unit = synchronized(lock) {
        val rec = loadCallRecord(id)
        File(recordsDir, "$id.json").delete()
        if (rec != null) audioFile(rec.campaignId, rec.id).delete()
        rebuildRecordIndexLocked()
    }

    fun listCallRecords(): List<CallRecordIndexEntry> =
        loadRecordIndex().sortedByDescending { it.createdAtMs }

    fun listCallRecordsForCampaign(campaignId: String): List<CallRecordIndexEntry> =
        loadRecordIndex().filter { it.campaignId == campaignId }.sortedByDescending { it.createdAtMs }

    private fun loadRecordIndex(): List<CallRecordIndexEntry> = synchronized(lock) {
        if (!recordsIndexFile.exists()) return emptyList()
        runCatching {
            json.decodeFromString<List<CallRecordIndexEntry>>(recordsIndexFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun rebuildRecordIndexLocked() {
        val entries = recordsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<CallRecord>(f.readText()) }.getOrNull() }
            ?.map {
                CallRecordIndexEntry(
                    id = it.id,
                    campaignId = it.campaignId,
                    surveyId = it.surveyId,
                    contactName = it.contact.name,
                    contactPhone = it.contact.phone,
                    createdAtMs = it.createdAtMs,
                    durationMs = it.durationMs,
                    status = it.status,
                )
            }
            ?: emptyList()
        atomicWrite(recordsIndexFile, json.encodeToString(entries))
    }

    // ---- atomic write ----

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            target.writeText(contents)
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: SurveyStore? = null
        fun get(ctx: Context): SurveyStore =
            instance ?: synchronized(this) {
                instance ?: SurveyStore(ctx).also { instance = it }
            }
    }
}

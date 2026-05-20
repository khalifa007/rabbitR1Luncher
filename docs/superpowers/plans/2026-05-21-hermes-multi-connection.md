# Hermes multi-connection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multi-connection support to the Hermes Agent app: up to 5 servers, per-connection chat history + session id, default-to-first active, auto-host labels.

**Architecture:** Replace the single-connection `HermesPrefs` API with a list of `HermesConnection` data classes (id/url/key/sessionId) protected by a Mutex; per-connection chat history stored on disk as `filesDir/hermes/history-<id>.json`; `HermesClient` becomes stateless re: connection (caller passes a snapshot per call). UI adds a connection list in `HermesConfigPanel` and a new `HermesConnectionEditPanel`. Existing single connection is migrated in place on first launch.

**Tech Stack:** Kotlin, Jetpack Compose, EncryptedSharedPreferences, kotlinx.serialization, OkHttp, NanoHTTPD (web RPC).

**Spec:** `docs/superpowers/specs/2026-05-21-hermes-multi-connection-design.md`

**Testing model:** No unit tests in this repo. Each task ends with `./gradlew compileDebugKotlin` (or `assembleDebug` when the change spans Compose) for build verification + commit. The final task is a manual on-device smoke matrix.

---

## File map

**New:**
- `app/src/main/java/com/r1/launcher/hermes/HermesConnection.kt` — data class + URL helpers
- `app/src/main/java/com/r1/launcher/ui/HermesConnectionEditPanel.kt` — Compose panel

**Modify:**
- `app/src/main/java/com/r1/launcher/hermes/HermesPrefs.kt` — full rewrite
- `app/src/main/java/com/r1/launcher/hermes/HermesHistoryStore.kt` — per-conn rewrite
- `app/src/main/java/com/r1/launcher/hermes/HermesClient.kt` — signature change
- `app/src/main/java/com/r1/launcher/hermes/HermesImageLoader.kt` — read active conn
- `app/src/main/java/com/r1/launcher/LauncherState.kt` — per-conn history map, new fields, new panel enum
- `app/src/main/java/com/r1/launcher/LauncherActivity.kt` — host method rewires
- `app/src/main/java/com/r1/launcher/LauncherNav.kt` — new panel back-routing + dispatcher
- `app/src/main/java/com/r1/launcher/LauncherRoot.kt` — wire `HermesConnectionEditPanel`
- `app/src/main/java/com/r1/launcher/ui/HermesConfigPanel.kt` — connection list rows
- `app/src/main/java/com/r1/launcher/ui/HermesChatPanel.kt` — read from `hermesActiveHistory()`
- `app/src/main/java/com/r1/launcher/web/WebRpc.kt` — active-conn shims

---

## Task 1: Add `HermesConnection` data class

**Files:**
- Create: `app/src/main/java/com/r1/launcher/hermes/HermesConnection.kt`

- [ ] **Step 1: Create the data class**

```kotlin
package com.r1.launcher.hermes

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One Hermes Agent server connection. Identified by a stable UUID so the
 * user-visible label (derived from URL host) can change without losing
 * chat-history binding.
 *
 * - [url] always includes the `/v1` suffix; the convenience getters strip
 *   and re-append as needed.
 * - [apiKey] is the bearer token sent in `Authorization: Bearer <key>`.
 *   Empty string is valid for LAN-only Hermes instances.
 * - [sessionId] is sent in `X-Hermes-Session-Id`; rotated to start a
 *   fresh server-side conversation thread for this connection only.
 */
@Serializable
data class HermesConnection(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val apiKey: String,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    /** Strip trailing slash + optional `/v1` so sub-endpoints build consistently. */
    fun baseRoot(): String {
        val u = url.trimEnd('/')
        return if (u.endsWith("/v1")) u.removeSuffix("/v1") else u
    }

    fun chatCompletionsUrl(): String = baseRoot() + "/v1/chat/completions"
    fun healthUrl(): String = baseRoot() + "/health"

    /** Display label: host portion of URL, or "(invalid url)" if unparseable. */
    val hostLabel: String
        get() = runCatching {
            val authority = url.substringAfter("://").substringBefore('/')
            authority.substringBefore(':').ifBlank { "(invalid url)" }
        }.getOrDefault("(invalid url)")

    /** Subtitle: port + path so two same-host connections are distinguishable.
     *  E.g. `:8642/v1`, or just `/v1` when default port. */
    val subtitle: String
        get() = runCatching {
            val authority = url.substringAfter("://").substringBefore('/')
            val pathStart = url.indexOf('/', url.indexOf("://") + 3).let { if (it < 0) url.length else it }
            val path = url.substring(pathStart).ifBlank { "/" }
            val port = authority.substringAfter(':', "")
            if (port.isNotEmpty()) ":$port$path" else path
        }.getOrDefault("")

    /** Truncated key tail for display: `…abcd` or `set` / empty. */
    val keyTail: String
        get() = when {
            apiKey.length > 6 -> "…" + apiKey.takeLast(4)
            apiKey.isNotEmpty() -> "set"
            else -> ""
        }
}

/** Normalize a URL for dedup comparison: trim, lowercase scheme + host, strip
 *  trailing slash. Path/port preserved (different paths = different servers). */
fun normalizeHermesUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val schemeIdx = trimmed.indexOf("://")
    if (schemeIdx < 0) return trimmed.lowercase()
    val scheme = trimmed.substring(0, schemeIdx).lowercase()
    val rest = trimmed.substring(schemeIdx + 3)
    val pathIdx = rest.indexOf('/')
    val authority = if (pathIdx < 0) rest else rest.substring(0, pathIdx)
    val path = if (pathIdx < 0) "" else rest.substring(pathIdx)
    return "$scheme://${authority.lowercase()}$path"
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/r1/launcher/hermes/HermesConnection.kt
git commit -m "hermes: add HermesConnection data class + URL helpers"
```

---

## Task 2: Rewrite `HermesPrefs` for multi-connection (with backward-compat shims)

The shims (`serverUrl` / `apiKey` / `sessionId` / `rotateSessionId()` / `baseRoot()` / `chatCompletionsUrl()` / `healthUrl()` that read/write the *active* connection) let the existing callers in `LauncherActivity`, `HermesClient`, and `HermesImageLoader` keep compiling. They'll be removed in Task 11.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/hermes/HermesPrefs.kt` (full rewrite)

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.r1.launcher.hermes

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Multi-connection prefs for the Hermes Agent app.
 *
 * Storage:
 *   - `hermes.secure` (EncryptedSharedPreferences) holds the connections JSON
 *     blob under `hermes.connections`. One mutex serialises read-modify-write
 *     so background-thread `rotateSessionId` cannot race a main-thread
 *     `deleteConnection`.
 *   - `hermes.plain` holds UI prefs (`fontSize`, `hideChat`), `activeId`, and
 *     the one-shot migration flag.
 *
 * Legacy single-connection getters (`serverUrl`, `apiKey`, `sessionId`,
 * `rotateSessionId()`, URL builders) operate on the active connection and are
 * temporary shims kept while callers migrate to the new per-connection API.
 * Task 9 removes them.
 */
class HermesPrefs private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "hermes.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        app.getSharedPreferences("hermes.fallback", Context.MODE_PRIVATE)
    }

    private val plain: SharedPreferences =
        app.getSharedPreferences("hermes.plain", Context.MODE_PRIVATE)

    private val mutex = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        migrateIfNeeded()
    }

    // ---- new multi-connection API ----

    var connections: List<HermesConnection>
        get() = synchronized(mutex) { readConnectionsLocked() }
        private set(value) = synchronized(mutex) { writeConnectionsLocked(value) }

    var activeId: String?
        get() = plain.getString(KEY_ACTIVE_ID, null)?.takeIf { it.isNotBlank() }
        private set(value) = plain.edit {
            if (value.isNullOrBlank()) remove(KEY_ACTIVE_ID) else putString(KEY_ACTIVE_ID, value)
        }

    /** Active connection. Falls back to `connections.firstOrNull()` (logging
     *  a warning) when `activeId` is set but stale — keeps the launcher usable
     *  instead of bricking after a deletion race, but surfaces the drift. */
    val active: HermesConnection?
        get() = synchronized(mutex) {
            val list = readConnectionsLocked()
            val id = activeId
            if (id == null) return@synchronized list.firstOrNull()
            val hit = list.firstOrNull { it.id == id }
            if (hit != null) return@synchronized hit
            Log.w(TAG, "activeId=$id not found in connections; falling back to list head")
            list.firstOrNull()
        }

    fun hasConfig(): Boolean = active != null

    /** Append a new connection. Dedupes against existing by normalized URL —
     *  returns the matching existing entry unchanged on dedup. Returns null
     *  when the soft cap (5) is hit. */
    fun addConnection(url: String, key: String): HermesConnection? = synchronized(mutex) {
        val normalized = normalizeHermesUrl(url)
        if (normalized.isEmpty()) return@synchronized null
        val current = readConnectionsLocked()
        current.firstOrNull { normalizeHermesUrl(it.url) == normalized }?.let { return@synchronized it }
        if (current.size >= MAX_CONNECTIONS) {
            Log.w(TAG, "addConnection refused: cap=$MAX_CONNECTIONS reached")
            return@synchronized null
        }
        val fresh = HermesConnection(url = url.trim(), apiKey = key.trim())
        writeConnectionsLocked(current + fresh)
        fresh
    }

    fun updateConnection(id: String, url: String? = null, key: String? = null): Boolean = synchronized(mutex) {
        val current = readConnectionsLocked()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val existing = current[idx]
        val next = existing.copy(
            url = (url ?: existing.url).trim(),
            apiKey = (key ?: existing.apiKey).trim(),
        )
        writeConnectionsLocked(current.toMutableList().also { it[idx] = next })
        true
    }

    /** Returns the id that became active afterwards (or null if list is now empty). */
    fun deleteConnection(id: String): String? = synchronized(mutex) {
        val current = readConnectionsLocked()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized activeId
        val next = current.toMutableList().also { it.removeAt(idx) }
        writeConnectionsLocked(next)
        if (activeId == id) {
            val newActive = next.firstOrNull()?.id
            activeId = newActive
            return@synchronized newActive
        }
        activeId
    }

    fun setActive(id: String) {
        synchronized(mutex) {
            val current = readConnectionsLocked()
            if (current.none { it.id == id }) {
                Log.w(TAG, "setActive($id) ignored: id not found")
                return
            }
            activeId = id
        }
    }

    fun rotateSessionId(id: String) {
        synchronized(mutex) {
            val current = readConnectionsLocked()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val rotated = current[idx].copy(sessionId = UUID.randomUUID().toString())
            writeConnectionsLocked(current.toMutableList().also { it[idx] = rotated })
        }
    }

    // ---- UI prefs (unchanged) ----

    var fontSize: Int
        get() = plain.getInt(KEY_FONT_SIZE, 14)
        set(value) = plain.edit { putInt(KEY_FONT_SIZE, value) }

    var hideChat: Boolean
        get() = plain.getBoolean(KEY_HIDE_CHAT, false)
        set(value) = plain.edit { putBoolean(KEY_HIDE_CHAT, value) }

    var model: String
        get() = plain.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = plain.edit { putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }) }

    // ---- Backward-compat shims — DEPRECATED, removed in Task 9 ----

    /** Active connection URL (shim). */
    @Deprecated("Use active?.url", ReplaceWith("active?.url.orEmpty()"))
    var serverUrl: String
        get() = active?.url.orEmpty()
        set(value) {
            val a = active
            if (a == null) {
                addConnection(value, "")?.also { setActive(it.id) }
            } else {
                updateConnection(a.id, url = value)
            }
        }

    /** Active connection API key (shim). */
    @Deprecated("Use active?.apiKey", ReplaceWith("active?.apiKey.orEmpty()"))
    var apiKey: String
        get() = active?.apiKey.orEmpty()
        set(value) {
            val a = active ?: return
            updateConnection(a.id, key = value)
        }

    /** Active session id (shim). Generates a fresh one if no active connection. */
    @Deprecated("Use active?.sessionId", ReplaceWith("active?.sessionId.orEmpty()"))
    val sessionId: String
        get() = active?.sessionId.orEmpty()

    /** Rotate the active connection's session id (shim). */
    @Deprecated("Use rotateSessionId(id)", ReplaceWith("active?.id?.let { rotateSessionId(it) }"))
    fun rotateSessionId() {
        active?.id?.let { rotateSessionId(it) }
    }

    @Deprecated("Use active?.baseRoot()", ReplaceWith("active?.baseRoot().orEmpty()"))
    fun baseRoot(): String = active?.baseRoot().orEmpty()

    @Deprecated("Use active?.chatCompletionsUrl()", ReplaceWith("active?.chatCompletionsUrl().orEmpty()"))
    fun chatCompletionsUrl(): String = active?.chatCompletionsUrl().orEmpty()

    @Deprecated("Use active?.healthUrl()", ReplaceWith("active?.healthUrl().orEmpty()"))
    fun healthUrl(): String = active?.healthUrl().orEmpty()

    // ---- internals ----

    private fun readConnectionsLocked(): List<HermesConnection> {
        val raw = secure.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ConnectionsBlob.serializer(), raw).items
        }.getOrElse {
            Log.e(TAG, "connections decode failed; treating as empty", it)
            emptyList()
        }
    }

    private fun writeConnectionsLocked(list: List<HermesConnection>) {
        val raw = json.encodeToString(ConnectionsBlob.serializer(), ConnectionsBlob(list))
        secure.edit { putString(KEY_CONNECTIONS, raw) }
    }

    private fun migrateIfNeeded() {
        if (plain.getBoolean(KEY_MIGRATED, false)) return
        val oldUrl = secure.getString(LEGACY_KEY_URL, null)?.trim().orEmpty()
        if (oldUrl.isBlank()) {
            // Nothing to migrate; mark done so we don't keep re-checking.
            plain.edit { putBoolean(KEY_MIGRATED, true) }
            return
        }
        val oldKey = secure.getString(LEGACY_KEY_KEY, null).orEmpty()
        val oldSession = plain.getString(LEGACY_KEY_SESSION, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val migrated = HermesConnection(
            url = oldUrl,
            apiKey = oldKey,
            sessionId = oldSession,
        )
        runCatching {
            synchronized(mutex) {
                val existing = readConnectionsLocked()
                if (existing.isEmpty()) writeConnectionsLocked(listOf(migrated))
            }
            activeId = migrated.id
            plain.edit { putBoolean(KEY_MIGRATED, true) }
            secure.edit {
                remove(LEGACY_KEY_URL)
                remove(LEGACY_KEY_KEY)
            }
            plain.edit { remove(LEGACY_KEY_SESSION) }
            Log.i(TAG, "migrated legacy connection id=${migrated.id} host=${migrated.hostLabel}")
        }.onFailure {
            Log.e(TAG, "legacy migration failed; will retry next launch", it)
        }
    }

    @Serializable
    private data class ConnectionsBlob(val items: List<HermesConnection> = emptyList())

    companion object {
        const val DEFAULT_MODEL = "hermes-agent"
        const val MAX_CONNECTIONS = 5
        private const val TAG = "HermesPrefs"

        private const val KEY_CONNECTIONS = "hermes.connections"
        private const val KEY_ACTIVE_ID = "hermes.activeId"
        private const val KEY_MIGRATED = "hermes.migrated"
        private const val KEY_FONT_SIZE = "hermes.fontSize"
        private const val KEY_HIDE_CHAT = "hermes.hideChat"
        private const val KEY_MODEL = "hermes.model"

        private const val LEGACY_KEY_URL = "hermes.serverUrl"
        private const val LEGACY_KEY_KEY = "hermes.apiKey"
        private const val LEGACY_KEY_SESSION = "hermes.sessionId"

        @Volatile private var instance: HermesPrefs? = null
        fun get(ctx: Context): HermesPrefs =
            instance ?: synchronized(this) {
                instance ?: HermesPrefs(ctx).also { instance = it }
            }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Some `@Deprecated` warnings expected on the shim usages in `LauncherActivity` / `HermesClient` / `HermesImageLoader`; those are fine.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/r1/launcher/hermes/HermesPrefs.kt
git commit -m "hermes: rewrite prefs for multi-connection with migration + deprecated shims"
```

---

## Task 3: Per-connection `HermesHistoryStore`

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/hermes/HermesHistoryStore.kt` (rewrite)

- [ ] **Step 1: Replace file contents**

```kotlin
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
```

- [ ] **Step 2: Update `LauncherActivity` call sites**

Find: `hydrateHermesStateFromPrefs()` at `LauncherActivity.kt:1003`.

Replace the `if (state.hermesMessages.isEmpty())` block (lines ~1012–1017) with:

```kotlin
        val activeId = hermesPrefs.active?.id
        if (activeId != null && state.hermesMessages.isEmpty()) {
            val persisted = com.r1.launcher.hermes.HermesHistoryStore.load(this, activeId)
            if (persisted.isNotEmpty()) {
                state.hermesMessages.addAll(persisted)
            }
        }
```

Replace `persistHermesHistory()` (lines ~1020–1022) with:

```kotlin
    private fun persistHermesHistory() {
        val activeId = hermesPrefs.active?.id ?: return
        com.r1.launcher.hermes.HermesHistoryStore.save(this, activeId, state.hermesMessages.toList())
    }
```

Find `hermesClearHistory()` at `LauncherActivity.kt:2934`. Replace the line `com.r1.launcher.hermes.HermesHistoryStore.clear(this)` with:

```kotlin
        hermesPrefs.active?.id?.let { com.r1.launcher.hermes.HermesHistoryStore.clear(this, it) }
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/hermes/HermesHistoryStore.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt
git commit -m "hermes: per-connection history storage with legacy migration"
```

---

## Task 4: `HermesClient` takes `HermesConnection` snapshot per call

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/hermes/HermesClient.kt`
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt`
- Modify: `app/src/main/java/com/r1/launcher/hermes/HermesImageLoader.kt`

- [ ] **Step 1: Rewrite `HermesClient`**

```kotlin
package com.r1.launcher.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stateless-per-connection client for the Hermes OpenAI-compatible gateway.
 *
 * Each call takes a [HermesConnection] snapshot so an in-flight stream remains
 * bound to its originating connection even if the user switches active mid-stream.
 * Inflight calls are tracked per connection id so [cancel] / [cancelAll] can
 * tear down exactly the right ones.
 */
class HermesClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val inflight = ConcurrentHashMap<String, Call>()

    fun testConnection(connection: HermesConnection, onResult: (ok: Boolean, msg: String) -> Unit) {
        val url = connection.healthUrl()
        if (url.isBlank()) {
            onResult(false, "no server url"); return
        }
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "connect failed")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) onResult(true, "ok")
                    else onResult(false, "http ${r.code}")
                }
            }
        })
    }

    fun streamChat(
        connection: HermesConnection,
        history: List<HermesMessage>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ): Call {
        val url = connection.chatCompletionsUrl()
        if (url.isBlank()) {
            onError("no server url")
            return failedCall()
        }

        val body = buildJsonObject {
            put("model", JsonPrimitive("hermes-agent"))
            put("stream", JsonPrimitive(true))
            put("messages", buildJsonArray {
                for (m in history) {
                    if (m.role != "user" && m.role != "assistant" && m.role != "system") continue
                    add(buildJsonObject {
                        put("role", JsonPrimitive(m.role))
                        put("content", JsonPrimitive(m.text))
                    })
                }
            })
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "text/event-stream")
            .header("X-Hermes-Session-Id", connection.sessionId)
            .apply { if (connection.apiKey.isNotBlank()) header("Authorization", "Bearer ${connection.apiKey}") }
            .build()

        val call = http.newCall(req)
        inflight[connection.id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight.remove(connection.id, call)
                if (call.isCanceled()) return
                onError(e.message ?: "stream failed")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        onError(parseErrorMessage(errBody, response.code))
                        return
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        onError("empty stream")
                        return
                    }
                    val full = StringBuilder()
                    while (!source.exhausted()) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (line.isEmpty()) continue
                        if (line.startsWith(":")) continue
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue
                        val delta = parseDeltaContent(payload)
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onDelta(delta)
                        }
                    }
                    onDone(full.toString())
                } catch (e: Exception) {
                    if (!call.isCanceled()) onError(e.message ?: "stream parse failed")
                } finally {
                    runCatching { response.close() }
                    inflight.remove(connection.id, call)
                }
            }
        })
        return call
    }

    /** Cancel any in-flight call bound to [connectionId]. Null cancels all. */
    fun cancel(connectionId: String?) {
        if (connectionId == null) {
            cancelAll()
            return
        }
        runCatching { inflight.remove(connectionId)?.cancel() }
    }

    fun cancelAll() {
        val snap = inflight.values.toList()
        inflight.clear()
        snap.forEach { runCatching { it.cancel() } }
    }

    private fun parseDeltaContent(payload: String): String {
        val el: JsonElement = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return ""
        val obj = (el as? JsonObject) ?: return ""
        val choices = (obj["choices"] as? JsonArray) ?: return ""
        val first = (choices.firstOrNull() as? JsonObject) ?: return ""
        val delta = (first["delta"] as? JsonObject) ?: return ""
        return delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        val el = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
        val errObj = (el as? JsonObject)?.get("error") as? JsonObject
        val msg = errObj?.get("message")?.jsonPrimitive?.contentOrNull
        return if (!msg.isNullOrBlank()) "$code $msg" else "http $code"
    }

    private fun failedCall(): Call {
        val dummy = http.newCall(Request.Builder().url("http://127.0.0.1/").build())
        dummy.cancel()
        return dummy
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
```

- [ ] **Step 2: Update `LauncherActivity` construction + call sites**

At `LauncherActivity.kt:117`, change:

```kotlin
    private val hermesClient by lazy { com.r1.launcher.hermes.HermesClient(hermesPrefs) }
```

to:

```kotlin
    private val hermesClient by lazy { com.r1.launcher.hermes.HermesClient() }
```

Inside `hermesSendText` (around `LauncherActivity.kt:2847`), replace the `hermesClient.streamChat(history = history, …)` call with:

```kotlin
        val active = hermesPrefs.active ?: run {
            state.hermesBusy = false
            state.hermesStatus = "error: no connection"
            return
        }
        hermesClient.streamChat(
            connection = active,
            history = history,
            onDelta = { delta -> /* unchanged body */ },
            onDone = { full -> /* unchanged body */ },
            onError = { msg -> /* unchanged body */ },
        )
```

(Keep the lambda bodies identical — only the named-arg `connection = active` is added.)

Inside `hermesTestConnection` (around `LauncherActivity.kt:2945`), replace `hermesClient.testConnection { ok, msg -> … }` with:

```kotlin
        val active = hermesPrefs.active ?: run {
            state.hermesStatus = "error: no connection"
            return
        }
        hermesClient.testConnection(active) { ok, msg ->
            ui.post {
                state.hermesStatus = if (ok) "live" else "error: $msg"
                if (!ok) toastFail("hermes: $msg")
            }
        }
```

Inside `hermesClearHistory` (around `LauncherActivity.kt:2934`), replace `hermesClient.cancel()` with:

```kotlin
        hermesClient.cancel(hermesPrefs.active?.id)
```

- [ ] **Step 3: Update `HermesImageLoader.load`**

Find `HermesImageLoader.kt:48`. Replace the `runCatching { val prefs = HermesPrefs.get(ctx) … }` block with:

```kotlin
        runCatching {
            val active = HermesPrefs.get(ctx).active ?: return@runCatching null
            val req = Request.Builder().url(url).apply {
                if (shouldAttachToken(url, active.baseRoot(), active.apiKey)) {
                    header("Authorization", "Bearer ${active.apiKey}")
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
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/r1/launcher/hermes/HermesClient.kt \
        app/src/main/java/com/r1/launcher/hermes/HermesImageLoader.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt
git commit -m "hermes: client takes HermesConnection snapshot per call"
```

---

## Task 5: Per-connection history in `LauncherState`

Switches `state.hermesMessages` (single list) to `state.hermesHistories` (map keyed by connection id) and routes every existing reader through `hermesActiveHistory()`. Touches the chat panel, the web RPC, and the activity in one commit because the type is shared.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/LauncherState.kt`
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt`
- Modify: `app/src/main/java/com/r1/launcher/ui/HermesChatPanel.kt`
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt`

- [ ] **Step 1: Update `LauncherState`**

In `LauncherState.kt`, locate the Hermes section starting around line 288. Replace:

```kotlin
    val hermesMessages = mutableStateListOf<HermesMessage>()
    val hermesMessagesMax = 500
```

with:

```kotlin
    /** Per-connection chat scrollback. Compose-observable; switching active
     *  connection swaps which list the chat panel renders via
     *  [hermesActiveHistory]. */
    val hermesHistories = mutableStateMapOf<String, SnapshotStateList<HermesMessage>>()
    val hermesMessagesMax = 500

    /** Observable mirror of HermesPrefs.connections. */
    val hermesConnections = mutableStateListOf<HermesConnection>()

    /** Observable mirror of HermesPrefs.activeId (null when no connections). */
    var hermesActiveId by mutableStateOf<String?>(null)

    /** Returns the message list for the currently-active connection, or null
     *  when there is none. Lazily creates an empty observable list on first
     *  access for an active connection — call from the UI thread. */
    fun hermesActiveHistory(): SnapshotStateList<HermesMessage>? {
        val id = hermesActiveId ?: return null
        return hermesHistories.getOrPut(id) { mutableStateListOf() }
    }
```

Also add the imports at the top:

```kotlin
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.r1.launcher.hermes.HermesConnection
```

And add the edit-panel state fields just after `var hermesConfigCameFromChat by mutableStateOf(false)` (line ~324):

```kotlin
    /** Connection-edit sub-panel state: id being edited (null = new-mode). */
    var hermesConnectionEditId by mutableStateOf<String?>(null)
    var hermesConnectionEditFocus by mutableIntStateOf(0)
    /** Buffer for the edit panel's URL row. */
    var hermesConnectionEditUrlInput by mutableStateOf("")
    /** Buffer for the edit panel's API key row. */
    var hermesConnectionEditKeyInput by mutableStateOf("")
    /** Timestamp (SystemClock.uptimeMillis) when the "delete connection" row
     *  was first armed. Second activate within 3000 ms confirms. */
    var hermesConnectionEditDeleteArmedAt by mutableStateOf(0L)
```

Add the new panel enum value: locate the `enum class Panel { … }` line (line 18) and insert `HERMES_CONNECTION_EDIT` after `HERMES_QR`. The full enum becomes:

```kotlin
enum class Panel { HOME, ONBOARDING, APPS, SETTINGS, SETTINGS_DISPLAY, SETTINGS_SOUND, SETTINGS_DEVICE, SETTINGS_ABOUT, SETTINGS_VOICE, SETTINGS_VOICE_TUNING, SETTINGS_VOICE_SUBSCRIPTION, SETTINGS_LANGUAGE, SETTINGS_CREDENTIALS, NETWORK, WIFI_SCAN, WIFI_PASSWORD, WIFI_SHARE, WIFI_SHARE_EDIT, PANEL_PASSCODE, NTFY_CONFIG, BT_SCAN, BRIGHTNESS, VOLUME, UI_VOLUME, FACTORY_CONFIRM, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_CAMERA, OPENCLAW_SETTINGS, OPENCLAW_SESSIONS, MESSAGES, MESSAGES_THREAD, TERMINAL, HERMES_CHAT, HERMES_CONFIG, HERMES_QR, HERMES_CONNECTION_EDIT, TRANSCRIBER_LIST, TRANSCRIBER_RECORDING, TRANSCRIBER_DETAIL, TRANSCRIBER_SETTINGS, NOTIFICATIONS }
```

Add open helpers below `openHermesQr()` (line ~683):

```kotlin
    fun openHermesConnectionEdit(id: String?) {
        hermesConnectionEditId = id
        hermesConnectionEditFocus = 0
        hermesConnectionEditDeleteArmedAt = 0L
        val existing = id?.let { editId -> hermesConnections.firstOrNull { it.id == editId } }
        hermesConnectionEditUrlInput = existing?.url.orEmpty()
        hermesConnectionEditKeyInput = ""
        panel = Panel.HERMES_CONNECTION_EDIT
    }
```

Add `Panel.HERMES_CONNECTION_EDIT -> Panel.HERMES_CONFIG` to the `back()` `when` (line ~752, right after the `HERMES_QR` entry).

- [ ] **Step 2: Update `LauncherActivity` to route all history reads/writes through `hermesActiveHistory()`**

In `hydrateHermesStateFromPrefs()` (line ~1003), replace:

```kotlin
        val activeId = hermesPrefs.active?.id
        if (activeId != null && state.hermesMessages.isEmpty()) {
            val persisted = com.r1.launcher.hermes.HermesHistoryStore.load(this, activeId)
            if (persisted.isNotEmpty()) {
                state.hermesMessages.addAll(persisted)
            }
        }
```

with:

```kotlin
        state.hermesConnections.clear()
        state.hermesConnections.addAll(hermesPrefs.connections)
        state.hermesActiveId = hermesPrefs.active?.id
        val activeId = state.hermesActiveId
        if (activeId != null) {
            val list = state.hermesActiveHistory() ?: return
            if (list.isEmpty()) {
                val persisted = com.r1.launcher.hermes.HermesHistoryStore.load(this, activeId)
                if (persisted.isNotEmpty()) list.addAll(persisted)
            }
        }
```

Replace `persistHermesHistory()` (line ~1020) with:

```kotlin
    private fun persistHermesHistory() {
        val activeId = state.hermesActiveId ?: return
        val list = state.hermesActiveHistory() ?: return
        com.r1.launcher.hermes.HermesHistoryStore.save(this, activeId, list.toList())
    }
```

In `hermesSendText` (line ~2822), replace:

```kotlin
        state.hermesMessages.add(userMsg)
        trimHermesMessages()
```

with:

```kotlin
        val active = hermesPrefs.active ?: run {
            toastFail("hermes: configure server url first")
            return
        }
        val capturedId = active.id
        val history = state.hermesActiveHistory() ?: run {
            toastFail("hermes: no connection")
            return
        }
        history.add(userMsg)
        trimHermesMessages(history)
```

Then everywhere later in the function that reads/writes `state.hermesMessages`, replace with `history`. Specifically:

- `val history = state.hermesMessages.toList()` → already exists; remove the duplicate (we built `history` above; pass `history.toList()` to streamChat).
- `state.hermesMessages.add(com.r1.launcher.hermes.HermesMessage(role = "assistant", text = full))` → `history.add(com.r1.launcher.hermes.HermesMessage(role = "assistant", text = full))`
- `state.hermesMessages.add(com.r1.launcher.hermes.HermesMessage(role = "error", text = msg))` → `history.add(com.r1.launcher.hermes.HermesMessage(role = "error", text = msg))`
- `trimHermesMessages()` → `trimHermesMessages(history)` everywhere
- Inside `onDone` / `onError`, replace `persistHermesHistory()` with `com.r1.launcher.hermes.HermesHistoryStore.save(this, capturedId, history.toList())` (uses the captured id, not the possibly-stale `state.hermesActiveId`).

The `streamChat` call becomes:

```kotlin
        hermesClient.streamChat(
            connection = active,
            history = history.toList(),
            onDelta = { delta -> /* unchanged */ },
            onDone = { full -> /* unchanged, but uses `history` not state.hermesMessages */ },
            onError = { msg -> /* unchanged, but uses `history` not state.hermesMessages */ },
        )
```

Replace `trimHermesMessages` (line ~2911) with:

```kotlin
    private fun trimHermesMessages(target: SnapshotStateList<com.r1.launcher.hermes.HermesMessage>) {
        val over = target.size - state.hermesMessagesMax
        if (over > 0) repeat(over) { target.removeAt(0) }
    }
```

Add the import `import androidx.compose.runtime.snapshots.SnapshotStateList` at the top of `LauncherActivity.kt`.

In `hermesClearHistory()` (line ~2934), replace:

```kotlin
        state.hermesMessages.clear()
        com.r1.launcher.hermes.HermesHistoryStore.clear(this)
```

with:

```kotlin
        val activeId = hermesPrefs.active?.id
        state.hermesActiveHistory()?.clear()
        if (activeId != null) com.r1.launcher.hermes.HermesHistoryStore.clear(this, activeId)
```

And replace `hermesPrefs.rotateSessionId()` (next line) with:

```kotlin
        activeId?.let { hermesPrefs.rotateSessionId(it) }
```

- [ ] **Step 3: Update `HermesChatPanel` reads**

In `ui/HermesChatPanel.kt`, replace every `state.hermesMessages` reference with `(state.hermesActiveHistory() ?: emptyList())`. Most uses (size, isEmpty, asReversed, indexed reads) work on `List<HermesMessage>`. For the `lastSize` `mutableStateOf` (line ~101), capture via `val messages = state.hermesActiveHistory() ?: emptyList()` once near the top of the affected composable scope and reuse.

Concretely, top of the body containing the `LazyColumn`:

```kotlin
                val messages: List<HermesMessage> = state.hermesActiveHistory() ?: emptyList()
                var lastSize by remember { mutableStateOf(messages.size) }
                LaunchedEffect(state.hermesScrollIndex, messages.size) {
                    val sizeGrew = messages.size > lastSize
                    lastSize = messages.size
                    /* unchanged body */
                }
```

Then all `state.hermesMessages.X` → `messages.X` in this scope.

- [ ] **Step 4: Update `WebRpc`**

In `web/WebRpc.kt`:

- Line ~113: `state.hermesMessages.toList().forEach { m ->` → `(state.hermesActiveHistory()?.toList().orEmpty()).forEach { m ->`
- Line ~265: `put("messageCount", state.hermesMessages.size)` → `put("messageCount", state.hermesActiveHistory()?.size ?: 0)`
- Line ~100 + ~341: `state.hermesServerUrl.isBlank()` → `state.hermesActiveId == null` (the URL mirror still tracks active in v1 but the id is the correct truth)
- Line ~263: `put("hasConfig", state.hermesServerUrl.isNotBlank())` → `put("hasConfig", state.hermesActiveId != null)`

- [ ] **Step 5: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/r1/launcher/LauncherState.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt \
        app/src/main/java/com/r1/launcher/ui/HermesChatPanel.kt \
        app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "hermes: route chat history through per-connection map"
```

---

## Task 6: New host methods + `LauncherHost` interface

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt` (interface + impls)

- [ ] **Step 1: Locate `LauncherHost` interface**

Search: `grep -n "interface LauncherHost\|hermesSendText\|hermesSetServerUrl" app/src/main/java/com/r1/launcher/LauncherActivity.kt`

The interface is defined near the top of `LauncherActivity.kt`. Add these methods to it:

```kotlin
    fun hermesSetActiveConnection(id: String)
    fun hermesAddConnection(url: String, key: String): com.r1.launcher.hermes.HermesConnection?
    fun hermesUpdateConnection(id: String, url: String? = null, key: String? = null)
    fun hermesDeleteConnection(id: String)
    fun hermesRotateSession(id: String)
```

- [ ] **Step 2: Implement them in `LauncherActivity`**

Add right after `hermesTestConnection()` (around line ~2958):

```kotlin
    override fun hermesSetActiveConnection(id: String) {
        val current = hermesPrefs.active?.id
        if (current == id) return
        hermesClient.cancel(current)
        cancelHermesSpeech()
        state.hermesStreamingText = ""
        state.hermesPartialText = ""
        state.hermesBusy = false
        state.hermesStatus = "idle"
        hermesPrefs.setActive(id)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesAddConnection(url: String, key: String): com.r1.launcher.hermes.HermesConnection? {
        val added = hermesPrefs.addConnection(url, key)
        if (added == null) {
            toastFail("hermes: max ${com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS} connections")
            return null
        }
        hermesPrefs.setActive(added.id)
        hydrateHermesStateFromPrefs()
        return added
    }

    override fun hermesUpdateConnection(id: String, url: String?, key: String?) {
        val credsChanged = url != null || key != null
        if (credsChanged) hermesClient.cancel(id)
        hermesPrefs.updateConnection(id, url = url, key = key)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesDeleteConnection(id: String) {
        hermesClient.cancel(id)
        com.r1.launcher.hermes.HermesHistoryStore.deleteAll(this, id)
        state.hermesHistories.remove(id)
        hermesPrefs.deleteConnection(id)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesRotateSession(id: String) {
        hermesClient.cancel(id)
        hermesPrefs.rotateSessionId(id)
        state.hermesHistories[id]?.clear()
        com.r1.launcher.hermes.HermesHistoryStore.clear(this, id)
        hydrateHermesStateFromPrefs()
    }
```

- [ ] **Step 3: Re-route `hermesSetServerUrl` and `hermesSetApiKey` (called from the existing config panel) through the new API**

Replace the existing `hermesSetServerUrl` (line ~3018):

```kotlin
    override fun hermesSetServerUrl(value: String) {
        val active = hermesPrefs.active
        if (active == null) {
            hermesAddConnection(value, "")
        } else {
            hermesUpdateConnection(active.id, url = value)
        }
        toast("hermes: url saved")
    }
```

Replace the existing `hermesSetApiKey` (line ~3024):

```kotlin
    override fun hermesSetApiKey(value: String) {
        val active = hermesPrefs.active ?: return
        hermesUpdateConnection(active.id, key = value)
        toast(if (value.isBlank()) "hermes: key cleared" else "hermes: key saved")
    }
```

Replace `hermesScanned` (line ~3005):

```kotlin
    override fun hermesScanned(raw: String) {
        val code = com.r1.launcher.hermes.decodeHermesSetupCode(raw) ?: run {
            state.hermesQrError = "QR not recognised"
            return
        }
        val added = hermesAddConnection(code.url, code.key.orEmpty())
        if (added == null) {
            state.hermesQrError = "max ${com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS} connections — delete one first"
            return
        }
        state.back()
        hermesTestConnection()
        toastSuccess("hermes paired")
    }
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/r1/launcher/LauncherActivity.kt
git commit -m "hermes: add multi-connection host methods + QR routing"
```

---

## Task 7: `HermesConfigPanel` — connection list + activate / add flows

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/ui/HermesConfigPanel.kt`
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt` (`hermesConfigRowActivate` dispatcher)
- Modify: `app/src/main/java/com/r1/launcher/LauncherNav.kt` (focus bounds)

- [ ] **Step 1: Replace the panel body**

Replace `HermesConfigPanel.kt` (full file). Keep the same imports + signature shape; the row layout becomes dynamic.

```kotlin
package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.hermes.HermesPrefs

@Composable
fun HermesConfigPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CONFIG,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)
        val accent = AppThemes.Hermes

        val connections = state.hermesConnections.toList()
        val canAdd = connections.size < HermesPrefs.MAX_CONNECTIONS

        // Row layout:
        //   0                                          back header
        //   1..connections.size                        connection rows
        //   connections.size + 1                       "add new" (hidden if !canAdd)
        //   + 1 (or 0 when hidden)                     "scan from qr"
        //   + 1                                        "speak replies" toggle
        //   + 1                                        "hide text input" toggle
        //   + 1                                        "test active connection"
        val addRowIdx = if (canAdd) connections.size + 1 else -1
        val scanRowIdx = (if (canAdd) connections.size + 2 else connections.size + 1)
        val speakRowIdx = scanRowIdx + 1
        val hideRowIdx = scanRowIdx + 2
        val testRowIdx = scanRowIdx + 3
        val totalRows = testRowIdx + 1

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConfigFocus, totalRows) {
            listState.animateScrollToItem(state.hermesConfigFocus.coerceIn(0, totalRows - 1))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(List(totalRows) { it }) { idx, _ ->
                    when (idx) {
                        0 -> AppPageHeader(
                            titleIconRes = R.drawable.ic_hermes,
                            title = "hermes",
                            backFocused = state.hermesConfigFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = accent,
                        )
                        in 1..connections.size -> {
                            val conn = connections[idx - 1]
                            val isActive = state.hermesActiveId == conn.id
                            ConnectionRow(
                                label = conn.hostLabel,
                                subtitle = conn.subtitle,
                                isActive = isActive,
                                focused = state.hermesConfigFocus == idx,
                                accent = accent,
                                onClick = { onRowClick(idx) },
                            )
                        }
                        addRowIdx -> SettingsRow(
                            label = "+ add new connection",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                        scanRowIdx -> SettingsRow(
                            label = "scan config from qr",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                        speakRowIdx -> SettingsRow(
                            label = "speak replies",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = state.voiceEnabled,
                            onClick = { onRowClick(idx) },
                        )
                        hideRowIdx -> SettingsRow(
                            label = "hide text input",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            toggleChecked = state.hermesHideChat,
                            onClick = { onRowClick(idx) },
                        )
                        testRowIdx -> SettingsRow(
                            label = "test connection",
                            focused = state.hermesConfigFocus == idx,
                            subtitle = statusLine(state.hermesStatus),
                            subtitleColor = when {
                                state.hermesStatus == "live" -> ok
                                state.hermesStatus.startsWith("error") -> Color(0xFFE53935)
                                else -> dim
                            },
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    label: String,
    subtitle: String,
    isActive: Boolean,
    focused: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val dim = Color(0xFFAAAAAA)
    val border = if (focused) accent else Color(0xFF333333)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101010))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = dim,
                fontSize = 12.sp,
                fontFamily = type.appCard.fontFamily,
                modifier = Modifier
                    .padding(top = 24.dp),
            )
        }
    }
}

private fun statusLine(status: String): String = when {
    status == "live" -> "ok"
    status == "streaming" -> "streaming…"
    status == "connecting" -> "checking…"
    status.startsWith("error") -> status
    else -> "tap to test"
}
```

Note: the inline RetroKeyboard editor for URL/key is gone from this panel — those edits move to the connection-edit sub-panel (Task 8).

- [ ] **Step 2: Replace `LauncherRoot.kt` call to `HermesConfigPanel`**

Find the call (likely `HermesConfigPanel(state, onRowClick = …, onSaveServerUrl = …, …)`). Replace its argument list with just `state` and `onRowClick`:

```kotlin
            HermesConfigPanel(
                state = state,
                onRowClick = { idx -> host.hermesConfigRowActivate(idx) },
            )
```

- [ ] **Step 3: Update `hermesConfigRowActivate` in `LauncherActivity`**

Replace the existing function (line ~2960) with:

```kotlin
    override fun hermesConfigRowActivate(idx: Int) {
        val conns = hermesPrefs.connections
        val canAdd = conns.size < com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS
        val addRowIdx = if (canAdd) conns.size + 1 else -1
        val scanRowIdx = if (canAdd) conns.size + 2 else conns.size + 1
        val speakRowIdx = scanRowIdx + 1
        val hideRowIdx = scanRowIdx + 2
        val testRowIdx = scanRowIdx + 3
        when {
            idx == 0 -> { state.back(); backTone() }
            idx in 1..conns.size -> {
                val conn = conns[idx - 1]
                val active = hermesPrefs.active
                if (active?.id == conn.id) {
                    state.openHermesConnectionEdit(conn.id)
                } else {
                    hermesSetActiveConnection(conn.id)
                }
                popTone()
            }
            idx == addRowIdx -> { state.openHermesConnectionEdit(null); popTone() }
            idx == scanRowIdx -> { openHermesQr(); popTone() }
            idx == speakRowIdx -> { voiceToggleEnabled(); popTone() }
            idx == hideRowIdx -> {
                val newHide = !state.hermesHideChat
                state.hermesHideChat = newHide
                hermesPrefs.hideChat = newHide
                popTone()
            }
            idx == testRowIdx -> { hermesTestConnection(); popTone() }
        }
    }
```

- [ ] **Step 4: Update wheel-nav bounds in `LauncherNav.kt`**

Find the `Panel.HERMES_CONFIG` cases in `wheelUp`/`wheelDown`/`activate`. Update the row count used for bounds clamping. The new dynamic count (with `c = connections.size`):

```kotlin
        Panel.HERMES_CONFIG -> {
            val c = hermesConnections.size
            val canAdd = c < com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS
            val totalRows = (if (canAdd) c + 2 else c + 1) + 4
            hermesConfigFocus = (hermesConfigFocus + delta).coerceIn(0, totalRows - 1)
        }
```

(Apply equivalent logic in `wheelUp` and `wheelDown` — the existing code in those functions uses a hard-coded row count; replace it with the `totalRows` computation above.)

For `activate(host)`, keep dispatching to `host.hermesConfigRowActivate(hermesConfigFocus)` unchanged.

- [ ] **Step 5: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/r1/launcher/ui/HermesConfigPanel.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt \
        app/src/main/java/com/r1/launcher/LauncherNav.kt \
        app/src/main/java/com/r1/launcher/LauncherRoot.kt
git commit -m "hermes: connection list UI + activate/add/scan row dispatch"
```

---

## Task 8: `HermesConnectionEditPanel` + wiring

**Files:**
- Create: `app/src/main/java/com/r1/launcher/ui/HermesConnectionEditPanel.kt`
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt` (new dispatcher + paste helpers)
- Modify: `app/src/main/java/com/r1/launcher/LauncherNav.kt` (focus + activate dispatch)
- Modify: `app/src/main/java/com/r1/launcher/LauncherRoot.kt` (add to z-stack)

- [ ] **Step 1: Create the panel**

```kotlin
package com.r1.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

/**
 * Add-or-edit a single Hermes connection.
 *
 * In edit-mode (state.hermesConnectionEditId != null) shows all 5 rows:
 *   0  < back
 *   1  server url
 *   2  api key
 *   3  rotate session
 *   4  delete connection (two-step confirm via state.hermesConnectionEditDeleteArmedAt)
 *
 * In new-mode (id = null) row 0 reads "cancel" and rotate/delete are hidden.
 * Saving any URL field in new-mode triggers host.hermesAddConnection(...).
 */
@Composable
fun HermesConnectionEditPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onPasteUrl: () -> Unit,
    onPasteKey: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CONNECTION_EDIT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Hermes
        val warn = Color(0xFFE53935)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        val isNew = state.hermesConnectionEditId == null
        val existing = state.hermesConnectionEditId?.let { editId ->
            state.hermesConnections.firstOrNull { it.id == editId }
        }

        val rows = buildList {
            add(if (isNew) "cancel" else "< back")
            add("server url")
            add("api key")
            if (!isNew) add("rotate session")
            if (!isNew) add("delete connection")
        }

        var editField by remember { mutableStateOf("") }  // "" | "url" | "key"
        val nowArmed = state.hermesConnectionEditDeleteArmedAt > 0L &&
            SystemClock.uptimeMillis() - state.hermesConnectionEditDeleteArmedAt < DELETE_ARM_MS

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConnectionEditFocus, rows.size) {
            listState.animateScrollToItem(state.hermesConnectionEditFocus.coerceIn(0, rows.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows) { idx, label ->
                    val focused = state.hermesConnectionEditFocus == idx
                    val isDelete = !isNew && idx == 4
                    val subtitle = when (idx) {
                        1 -> if (isNew) state.hermesConnectionEditUrlInput.ifBlank { "(empty)" }
                             else existing?.url.orEmpty().ifBlank { "(empty)" }
                        2 -> if (state.hermesConnectionEditKeyInput.isNotEmpty())
                                 "•".repeat(state.hermesConnectionEditKeyInput.length.coerceAtMost(20))
                             else existing?.keyTail.orEmpty().ifBlank { "(none)" }
                        else -> ""
                    }
                    val labelColor = when {
                        isDelete && nowArmed -> warn
                        isDelete -> Color(0xFFB04040)
                        else -> Color.White
                    }
                    val displayLabel = if (isDelete && nowArmed) "tap again to confirm" else label
                    SettingsRow(
                        label = displayLabel,
                        focused = focused,
                        subtitle = subtitle,
                        subtitleColor = if (idx in 1..2) ok else dim,
                        toggleChecked = null,
                        onClick = {
                            when (idx) {
                                1 -> { editField = "url" }
                                2 -> { editField = "key" }
                                else -> onRowClick(idx)
                            }
                        },
                        labelColor = labelColor,
                    )
                }
            }

            AnimatedVisibility(
                visible = editField.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val isKey = editField == "key"
                val displayInput = if (isKey) state.hermesConnectionEditKeyInput else state.hermesConnectionEditUrlInput
                val maskedInput = if (isKey && displayInput.isNotEmpty())
                    "•".repeat(displayInput.length.coerceAtMost(40)) else displayInput

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isKey) "api key" else "server url",
                        color = accent,
                        fontSize = 16.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (displayInput.isEmpty()) "(empty)" else "$maskedInput _",
                            color = if (displayInput.isEmpty()) Color(0xFF707070) else Color.White,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EditPill("save", ok, displayInput.isNotBlank(), Modifier.weight(1f)) {
                            if (isKey) onSaveKey(state.hermesConnectionEditKeyInput)
                            else onSaveUrl(state.hermesConnectionEditUrlInput)
                            editField = ""
                        }
                        EditPill("paste", accent, true, Modifier.weight(1f)) {
                            if (isKey) onPasteKey() else onPasteUrl()
                        }
                        EditPill("clear", warn, displayInput.isNotEmpty(), Modifier.weight(1f)) {
                            if (isKey) state.hermesConnectionEditKeyInput = ""
                            else state.hermesConnectionEditUrlInput = ""
                        }
                        EditPill("close", Color.White, true, Modifier.weight(1f)) {
                            editField = ""
                        }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch ->
                            if (isKey) state.hermesConnectionEditKeyInput += ch
                            else state.hermesConnectionEditUrlInput += ch
                        },
                        onBackspace = {
                            if (isKey) {
                                if (state.hermesConnectionEditKeyInput.isNotEmpty())
                                    state.hermesConnectionEditKeyInput = state.hermesConnectionEditKeyInput.dropLast(1)
                            } else {
                                if (state.hermesConnectionEditUrlInput.isNotEmpty())
                                    state.hermesConnectionEditUrlInput = state.hermesConnectionEditUrlInput.dropLast(1)
                            }
                        },
                        onDismiss = { editField = "" },
                    )
                }
            }
        }
    }
}

const val DELETE_ARM_MS = 3000L

@Composable
private fun EditPill(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val borderColor = if (enabled) color else Color(0xFF333333)
    val textColor = if (enabled) color else Color(0xFF555555)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}
```

Note: this requires extending `SettingsRow` to accept an optional `labelColor` param. If `SettingsRow` doesn't already support it (check `ui/SettingsRow.kt` or wherever it's defined), add a `labelColor: Color = Color.White` param with the existing default behavior.

- [ ] **Step 2: Extend `SettingsRow` with `labelColor` (if needed)**

Find `SettingsRow` definition: `grep -n "fun SettingsRow" app/src/main/java/com/r1/launcher/ui/`. Add `labelColor: Color = Color.White` to the parameter list and replace the row's `Text(text = label, …)` line with `Text(text = label, color = labelColor, …)`.

- [ ] **Step 3: Wire host method dispatcher in `LauncherActivity`**

Add right after `hermesConfigRowActivate`:

```kotlin
    override fun hermesConnectionEditRowActivate(idx: Int) {
        val editId = state.hermesConnectionEditId
        val isNew = editId == null
        when (idx) {
            0 -> {
                state.hermesConnectionEditDeleteArmedAt = 0L
                state.back()
                backTone()
            }
            1, 2 -> popTone()  // row 1/2 open the inline keyboard in the panel itself
            3 -> if (!isNew && editId != null) {
                hermesRotateSession(editId)
                toast("hermes: session rotated")
                popTone()
            }
            4 -> if (!isNew && editId != null) {
                val now = android.os.SystemClock.uptimeMillis()
                val armed = state.hermesConnectionEditDeleteArmedAt
                if (armed > 0L && now - armed < com.r1.launcher.ui.DELETE_ARM_MS) {
                    hermesDeleteConnection(editId)
                    state.hermesConnectionEditDeleteArmedAt = 0L
                    state.back()
                    toast("hermes: connection deleted")
                } else {
                    state.hermesConnectionEditDeleteArmedAt = now
                }
                popTone()
            }
        }
    }

    override fun hermesConnectionEditSaveUrl(value: String) {
        val editId = state.hermesConnectionEditId
        if (editId == null) {
            val added = hermesAddConnection(value, state.hermesConnectionEditKeyInput)
            if (added != null) {
                state.hermesConnectionEditDeleteArmedAt = 0L
                state.back()
                hermesTestConnection()
            }
        } else {
            hermesUpdateConnection(editId, url = value)
            toast("hermes: url saved")
        }
    }

    override fun hermesConnectionEditSaveKey(value: String) {
        val editId = state.hermesConnectionEditId
        if (editId == null) {
            // Buffer-only — wait for URL save to commit the new connection.
            state.hermesConnectionEditKeyInput = value
            toast("hermes: key buffered (save url to create)")
        } else {
            hermesUpdateConnection(editId, key = value)
            toast(if (value.isBlank()) "hermes: key cleared" else "hermes: key saved")
        }
    }

    override fun hermesConnectionEditPasteUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) { toastFail("clipboard empty"); return }
        state.hermesConnectionEditUrlInput = raw
    }

    override fun hermesConnectionEditPasteKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) { toastFail("clipboard empty"); return }
        state.hermesConnectionEditKeyInput = raw
    }
```

Add to the `LauncherHost` interface:

```kotlin
    fun hermesConnectionEditRowActivate(idx: Int)
    fun hermesConnectionEditSaveUrl(value: String)
    fun hermesConnectionEditSaveKey(value: String)
    fun hermesConnectionEditPasteUrl()
    fun hermesConnectionEditPasteKey()
```

- [ ] **Step 4: Add panel to `LauncherRoot.kt`**

In the z-stack inside the main `Box`, after the `HermesConfigPanel(...)` call, add:

```kotlin
            HermesConnectionEditPanel(
                state = state,
                onRowClick = { idx -> host.hermesConnectionEditRowActivate(idx) },
                onSaveUrl = { value -> host.hermesConnectionEditSaveUrl(value) },
                onSaveKey = { value -> host.hermesConnectionEditSaveKey(value) },
                onPasteUrl = { host.hermesConnectionEditPasteUrl() },
                onPasteKey = { host.hermesConnectionEditPasteKey() },
            )
```

- [ ] **Step 5: Wire wheel-nav for the new panel in `LauncherNav.kt`**

In `wheelUp`, `wheelDown`, and `activate`, add cases:

```kotlin
        Panel.HERMES_CONNECTION_EDIT -> {
            val isNew = hermesConnectionEditId == null
            val maxIdx = if (isNew) 2 else 4
            hermesConnectionEditFocus = (hermesConnectionEditFocus + delta).coerceIn(0, maxIdx)
        }
```

For `activate`:

```kotlin
        Panel.HERMES_CONNECTION_EDIT -> host.hermesConnectionEditRowActivate(hermesConnectionEditFocus)
```

For `backPressed`, the existing `panel = Panel.HERMES_CONFIG` from the `back()` enum table handles return. Confirm the table entry from Task 5 step 1 is in place.

- [ ] **Step 6: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/r1/launcher/ui/HermesConnectionEditPanel.kt \
        app/src/main/java/com/r1/launcher/ui/SettingsRow.kt \
        app/src/main/java/com/r1/launcher/LauncherActivity.kt \
        app/src/main/java/com/r1/launcher/LauncherNav.kt \
        app/src/main/java/com/r1/launcher/LauncherRoot.kt
git commit -m "hermes: connection edit sub-panel + add/rotate/delete flows"
```

(Adjust `git add` paths if `SettingsRow` lives elsewhere.)

---

## Task 9: Remove deprecated shims in `HermesPrefs`

All callers should now go through the new API. This task confirms that and deletes the shims so they can't drift.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/hermes/HermesPrefs.kt`

- [ ] **Step 1: Search for any remaining shim callers**

```bash
grep -n "hermesPrefs\.\(serverUrl\|apiKey\|sessionId\|rotateSessionId()\|baseRoot()\|chatCompletionsUrl()\|healthUrl()\)\b" \
  app/src/main/java/com/r1/launcher/
```

Expected output: empty (or only the shim declarations in `HermesPrefs.kt` itself). If anything else remains, migrate those call sites to the new API before continuing.

- [ ] **Step 2: Delete the shim block from `HermesPrefs.kt`**

Remove everything between the comment `// ---- Backward-compat shims — DEPRECATED, removed in Task 9 ----` and the next section header `// ---- internals ----`.

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If anything fails, the grep in Step 1 missed a caller — migrate it before re-running.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/hermes/HermesPrefs.kt
git commit -m "hermes: remove deprecated single-connection shims"
```

---

## Task 10: Manual on-device smoke matrix

No code changes — this is the integration verification step. Run through every item; record any unexpected behavior as a follow-up.

**Setup:**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
adb logcat -s HermesPrefs HermesClient HermesHistoryStore R1Motor
```

- [ ] **1. Fresh install, no prior config**

  After `adb shell pm clear com.r1.launcher` + reinstall + launch:
  - Open Hermes app → routes to `HERMES_CONFIG` (no `HERMES_CHAT`).
  - Config panel shows: back, "+ add new connection", "scan from qr", "speak replies", "hide text input", "test connection".
  - No connection rows shown.
  - Logcat: `HermesPrefs: ...` no errors.

- [ ] **2. Upgrade from v1.1.x (legacy single connection)**

  Install the previous release (manual: build at the prior tag or download the artifact), pair a connection, send a turn so history is non-empty, then install the multi-conn APK on top:
  - Launch Hermes → `HERMES_CHAT` opens directly (because migration ran + connection exists).
  - Scrollback contains the prior turn (history-file migration succeeded).
  - Logcat shows `HermesPrefs: migrated legacy connection id=… host=…` and `HermesHistoryStore: migrated legacy history → …`.
  - `adb shell ls /data/data/com.r1.launcher/files/hermes/` → contains `history-<uuid>.json`.
  - `adb shell ls /data/data/com.r1.launcher/files/` → no `hermes-history.json` (legacy was deleted post-copy).

- [ ] **3. Cold-start after upgrade**

  Force-stop and relaunch:
  - No second migration log line. The `hermes.migrated` flag prevents re-run.

- [ ] **4. Add via QR**

  Settings → Hermes → "scan from qr". Scan a valid pairing QR:
  - New connection appears in the list, becomes active (orange dot).
  - Returns to `HERMES_CONFIG`. Status row shows "ok" after the auto-test.

- [ ] **5. Add via manual URL/key**

  Tap "+ add new connection" → edit panel opens (cancel/url/key, no rotate/delete).
  - Tap "server url" → keyboard opens. Type a valid URL. "save" creates the connection and returns to config; new row appears and is active.

- [ ] **6. Add duplicate URL**

  Repeat step 5 with the same URL. Expected behavior:
  - No duplicate row appears in the list.
  - Toast `"using existing <host>"` (or similar — verify the actual addConnection-returns-existing path).
  - Active connection unchanged from before.

- [ ] **7. Switch active by tapping inactive row**

  In config panel with ≥ 2 connections, tap a non-active row:
  - Orange dot moves to the tapped row.
  - Status row resets to "tap to test".
  - Opening chat shows the new active connection's history (independent from the previous one's).

- [ ] **8. Tap already-active row**

  Tap the row currently showing the orange dot:
  - Edit sub-panel opens with back/url/key/rotate session/delete.

- [ ] **9. Edit URL on an inactive connection**

  With ≥ 2 connections, select an inactive one in the edit panel via the active-connection switch flow (tap inactive, then tap it again now-active). Edit the URL. Save:
  - URL change persists, visible in config row subtitle.
  - Chat panel for *that* connection updated; other connection's chat unaffected.

- [ ] **10. Rotate session**

  In the active connection's edit panel, tap "rotate session":
  - Toast `"hermes: session rotated"`.
  - Opening chat shows empty history for this conn (in-memory + on-disk cleared).
  - Other connections' history intact.
  - Next turn sends a fresh `X-Hermes-Session-Id` (verify in logcat or with server logs).

- [ ] **11. Delete active connection**

  Edit panel → "delete connection" (tap twice within 3 s):
  - Connection removed; active falls back to the new list head.
  - Chat panel shows the new active connection's history.
  - `adb shell ls /data/data/com.r1.launcher/files/hermes/` no longer contains the deleted conn's history file.

- [ ] **12. Delete last connection**

  Delete connections until none remain:
  - Tapping the Hermes app icon routes to `HERMES_CONFIG`.
  - `HERMES_CONFIG` shows only header + add/scan/voice/hide/test rows.
  - Tapping "test connection" with no connections produces `state.hermesStatus = "error: no connection"`.

- [ ] **13. Hit soft cap at 5**

  Add 5 connections (mix of QR + manual):
  - "+ add new connection" row disappears.
  - Scanning a 6th QR → scanner shows error `"max 5 connections — delete one first"`; no new connection created.
  - `hermesPrefs.addConnection` returns null in logcat.

- [ ] **14. Switch active mid-stream**

  Open chat on conn A, send a long-prompt turn (use something the LLM will take >5 s to answer). While the stream is in flight, navigate back to config and tap conn B:
  - Conn A's stream is cancelled (logcat shows OkHttp cancel).
  - Streaming text in conn A's history wipes; user message remains.
  - Chat panel re-rendered with conn B's history.

- [ ] **15. Histories independent across switches**

  Send a turn on conn A → "A turn 1". Switch to B → send "B turn 1". Switch back to A → scrollback shows "A turn 1" (not "B turn 1").
  - On-disk files match (`history-<A>.json` ≠ `history-<B>.json`).

- [ ] **16. Two-step delete timing**

  In the edit panel, tap "delete connection" once → label changes to "tap again to confirm" in bright red. Wait 4 s → it re-arms (label returns to "delete connection"); a single tap now does NOT delete. Tap twice within 3 s → deletes.

- [ ] **17. Edit URL while stream inflight on same conn**

  Open active connection's chat, fire a long turn. While streaming, navigate to that conn's edit panel, change the URL, save:
  - Inflight call cancelled.
  - New URL takes effect for the next turn.
  - The in-progress reply is gone (user message remains in history).

**Acceptance:** All 17 items pass. If any fail, file as a follow-up with the failing item number + observed vs. expected.

- [ ] **Final commit (test results note)**

Optional — if the smoke matrix surfaces nothing, no commit needed. If anything is patched as a result, commit those fixes referencing the matrix item number in the commit message.

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

    /** Active session id (shim). Returns "" when no active connection. */
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
                if (existing.isEmpty()) {
                    writeConnectionsLocked(listOf(migrated))
                    activeId = migrated.id
                } else {
                    // Retry after a partial-write crash: blob already has the entry.
                    // Use its id so we don't strand `activeId` on a phantom UUID.
                    activeId = existing.first().id
                }
            }
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

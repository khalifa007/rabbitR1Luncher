package com.r1.launcher.openclaw

/**
 * Multi-session helpers ported from openclaw-src
 * (apps/android/.../ui/chat/SessionFilters.kt). Same semantics: friendly label
 * derivation + visible-pill resolution (main pinned first, then recent <24h,
 * then current if not already included).
 */

data class SessionEntry(
    val key: String,
    val updatedAtMs: Long?,
    val displayName: String? = null,
)

private const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L

/**
 * Derive a human-friendly label from a raw session key.
 * Examples:
 *   "telegram:g-agent-main-main" -> "Main"
 *   "agent:main:main" -> "Main"
 *   "discord:g-server-channel" -> "Server Channel"
 *   "my-custom-session" -> "My Custom Session"
 */
fun friendlySessionName(key: String): String {
    val stripped = key.substringAfterLast(":")
    val cleaned = if (stripped.startsWith("g-")) stripped.removePrefix("g-") else stripped
    val words = cleaned.split('-', '_').filter { it.isNotBlank() }.map { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }.distinct()
    val result = words.joinToString(" ")
    return result.ifBlank { key }
}

fun resolveSessionChoices(
    currentSessionKey: String,
    sessions: List<SessionEntry>,
    mainSessionKey: String,
    nowMs: Long = System.currentTimeMillis(),
): List<SessionEntry> {
    val mainKey = mainSessionKey.trim().ifEmpty { "main" }
    val current = currentSessionKey.trim().let { if (it == "main" && mainKey != "main") mainKey else it }
    val aliasKey = if (mainKey == "main") null else "main"
    val cutoff = nowMs - RECENT_WINDOW_MS
    val sorted = sessions.sortedByDescending { it.updatedAtMs ?: 0L }
    val recent = mutableListOf<SessionEntry>()
    val seen = mutableSetOf<String>()
    for (entry in sorted) {
        if (aliasKey != null && entry.key == aliasKey) continue
        if (!seen.add(entry.key)) continue
        if ((entry.updatedAtMs ?: 0L) < cutoff) continue
        recent.add(entry)
    }

    val result = mutableListOf<SessionEntry>()
    val included = mutableSetOf<String>()
    val mainEntry = sorted.firstOrNull { it.key == mainKey }
    if (mainEntry != null) {
        result.add(mainEntry)
        included.add(mainKey)
    } else if (current == mainKey) {
        result.add(SessionEntry(key = mainKey, updatedAtMs = null))
        included.add(mainKey)
    }

    for (entry in recent) {
        if (included.add(entry.key)) {
            result.add(entry)
        }
    }

    if (current.isNotEmpty() && !included.contains(current)) {
        result.add(SessionEntry(key = current, updatedAtMs = null))
    }

    return result
}

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

package com.r1.launcher.openclaw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

data class GatewaySetupCode(
    val url: String,
    val bootstrapToken: String?,
    val token: String?,
    val password: String?,
)

private val json = Json { ignoreUnknownKeys = true }

/** Cap on the `setupCode` self-reference unwrap. A crafted QR nesting
 *  `{"setupCode":"<base64 of {\"setupCode\":...}>"}` would otherwise recurse
 *  per layer; a StackOverflowError can land anywhere (incl. outside the
 *  try/catch) on untrusted input. 4 is far beyond any legitimate payload. */
private const val MAX_SETUP_DEPTH = 4

fun decodeGatewaySetupCode(rawInput: String, depth: Int = 0): GatewaySetupCode? {
    if (depth > MAX_SETUP_DEPTH) return null
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    parseSetupJson(trimmed, depth)?.let { return it }

    val padded = trimmed
        .replace('-', '+')
        .replace('_', '/')
        .let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }

    return try {
        val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        parseSetupJson(decoded, depth)
    } catch (_: Throwable) {
        null
    }
}

private fun parseSetupJson(text: String, depth: Int): GatewaySetupCode? {
    return try {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
        val inner = (obj["setupCode"]?.jsonPrimitive?.contentOrNull)
            ?.let { decodeGatewaySetupCode(it, depth + 1) }
        if (inner != null) return inner
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        GatewaySetupCode(
            url = url.trim(),
            bootstrapToken = obj["bootstrapToken"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            token = obj["token"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            password = obj["password"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
        )
    } catch (_: Throwable) {
        null
    }
}

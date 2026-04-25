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

fun decodeGatewaySetupCode(rawInput: String): GatewaySetupCode? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    parseSetupJson(trimmed)?.let { return it }

    val padded = trimmed
        .replace('-', '+')
        .replace('_', '/')
        .let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }

    return try {
        val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        parseSetupJson(decoded)
    } catch (_: Throwable) {
        null
    }
}

private fun parseSetupJson(text: String): GatewaySetupCode? {
    return try {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
        val inner = (obj["setupCode"]?.jsonPrimitive?.contentOrNull)
            ?.let { decodeGatewaySetupCode(it) }
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

package com.r1.launcher.openclaw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class ChatMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
)

fun extractText(content: JsonArray?): String {
    if (content == null) return ""
    val sb = StringBuilder()
    for (el in content) {
        val obj = el as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "text") {
            val t = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            sb.append(t)
        }
    }
    return sb.toString()
}

/**
 * Extract text from a history message, handling all three server formats:
 *   1. `content` as a JsonArray of `{type:"text", text:"..."}` blocks
 *   2. `content` as a plain string
 *   3. Top-level `text` field (fallback)
 * Matches the official openclaw UI client's `extractRawText` logic.
 */
private fun extractMessageText(obj: JsonObject): String {
    // 1. content as JsonArray
    val contentArr = obj["content"] as? JsonArray
    if (contentArr != null) {
        val text = extractText(contentArr)
        if (text.isNotEmpty()) return text
    }
    // 2. content as plain string (server may strip envelope to bare string)
    val contentStr = runCatching {
        obj["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    if (!contentStr.isNullOrEmpty()) return contentStr
    // 3. top-level text field
    val topText = obj["text"]?.jsonPrimitive?.contentOrNull
    if (!topText.isNullOrEmpty()) return topText
    return ""
}

private val SILENT_REPLY = Regex("^\\s*NO_REPLY\\s*$")

fun parseHistoryMessage(obj: JsonObject): ChatMessage? {
    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
    // Only render user and assistant bubbles; skip tool results, system, etc.
    if (role != "user" && role != "assistant") return null
    // Server already strips envelopes via stripEnvelopeFromMessages,
    // so we just need to extract raw text from whichever format it uses.
    val text = extractMessageText(obj)
    // Drop empty bubbles and silent NO_REPLY placeholders
    if (text.isBlank() || SILENT_REPLY.matches(text)) return null
    val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: System.currentTimeMillis()
    return ChatMessage(role = role, text = text, timestamp = ts)
}


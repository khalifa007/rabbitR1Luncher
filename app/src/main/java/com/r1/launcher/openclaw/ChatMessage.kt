package com.r1.launcher.openclaw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

// Strips openclaw's gateway noise from inbound user messages so the chat bubble
// shows only the human-typed body. The gateway prepends `System: [ts] ...` event
// lines (WhatsApp connect/disconnect, model switches, etc.) and wraps the body
// in a `[channel from ts]` envelope header — both are useful prompt context for
// the LLM but pure clutter in the UI.
private val SYSTEM_LINE = Regex("^\\s*System:\\s*\\[[^\\]]*\\].*$")
private val ENVELOPE_PREFIX = Regex("^\\[[^\\]]+\\]\\s*")

private fun cleanInboundText(raw: String): String {
    val kept = raw.lineSequence()
        .filterNot { SYSTEM_LINE.matches(it) }
        .joinToString("\n")
        .trim()
    return ENVELOPE_PREFIX.replaceFirst(kept, "")
}

fun parseHistoryMessage(obj: JsonObject): ChatMessage? {
    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
    val content = obj["content"] as? JsonArray ?: return null
    val raw = extractText(content)
    val text = if (role == "user") cleanInboundText(raw) else raw
    val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: System.currentTimeMillis()
    return ChatMessage(role = role, text = text, timestamp = ts)
}


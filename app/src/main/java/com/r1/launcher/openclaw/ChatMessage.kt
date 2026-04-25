package com.r1.launcher.openclaw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
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

fun parseHistoryMessage(obj: JsonObject): ChatMessage? {
    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
    val content = obj["content"] as? JsonArray ?: return null
    val text = extractText(content)
    val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: System.currentTimeMillis()
    return ChatMessage(role = role, text = text, timestamp = ts)
}

fun parseStreamMessage(payload: JsonObject): Pair<ChatMessage, String>? {
    val state = payload["state"]?.jsonPrimitive?.contentOrNull ?: return null
    val msg = payload["message"] as? JsonObject ?: return null
    val role = msg["role"]?.jsonPrimitive?.contentOrNull ?: "assistant"
    val text = extractText(msg["content"] as? JsonArray)
    return ChatMessage(role = role, text = text, streaming = state == "delta") to state
}

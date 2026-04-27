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
    val imageBase64: String? = null,
    val imageSource: String? = null,
    val hasImage: Boolean = imageBase64 != null || imageSource != null,
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

private fun extractImageBase64(content: JsonArray?): String? {
    if (content == null) return null
    for (el in content) {
        val obj = el as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (type != "image") continue
        val raw = obj["content"]?.jsonPrimitive?.contentOrNull
            ?: obj["base64"]?.jsonPrimitive?.contentOrNull
            ?: continue
        return raw.substringAfter("base64,", raw).takeIf { it.isNotBlank() }
    }
    return null
}

private val IMAGE_REF = Regex("""(?i)(^|\s)(?:MEDIA:)?(@?/\S+\.(jpg|jpeg|png|webp))\b""")
private val SILENT_REPLY = Regex("^\\s*NO_REPLY\\s*$")

private fun cleanImageRefs(text: String): Triple<String, Boolean, String?> {
    var imageSource: String? = null
    val cleaned = IMAGE_REF.replace(text) {
        val raw = it.groupValues.getOrNull(2)?.trim()
        if (imageSource == null && !raw.isNullOrBlank()) {
            imageSource = raw.removePrefix("@")
        }
        "\nattached image"
    }
        .replace(Regex("(attached image\\s*){2,}", RegexOption.IGNORE_CASE), "attached image")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return Triple(cleaned, imageSource != null, imageSource)
}

private fun imageSourceFromTranscriptFields(obj: JsonObject): String? {
    val direct = obj["MediaPath"]?.jsonPrimitive?.contentOrNull
        ?: obj["mediaPath"]?.jsonPrimitive?.contentOrNull
        ?: obj["mediaUrl"]?.jsonPrimitive?.contentOrNull
    if (!direct.isNullOrBlank() && IMAGE_REF.containsMatchIn(" $direct")) {
        return direct.removePrefix("MEDIA:").removePrefix("@")
    }
    val paths = obj["MediaPaths"] as? JsonArray ?: obj["mediaUrls"] as? JsonArray
    if (paths != null) {
        for (el in paths) {
            val value = el.jsonPrimitive.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank() && IMAGE_REF.containsMatchIn(" $value")) {
                return value.removePrefix("MEDIA:").removePrefix("@")
            }
        }
    }
    return null
}

/**
 * Detect internal/system messages that the openclaw agent injects into the
 * chat history but should never be shown to the end user. These include:
 *   - HEARTBEAT_OK responses and heartbeat check prompts
 *   - System-prompt-style instruction blocks ("Read HEARTBEAT.md …")
 *   - Slash-command echoes (/status, /ping, etc.)
 */
internal fun isInternalMessage(text: String): Boolean {
    val t = text.trim()
    // HEARTBEAT_OK or similar short ack tokens
    if (t.length < 80 && t.uppercase().let {
            "HEARTBEAT" in it || "NO_REPLY" in it
        }) return true
    // System prompt leaks — instruction blocks directed at the AI
    if (t.startsWith("Read HEARTBEAT", ignoreCase = true)) return true
    if (t.startsWith("You are ", ignoreCase = true) && t.length > 200) return true
    // Slash commands echoed into history
    if (t.startsWith("/") && t.length < 40 && ' ' !in t.substring(1).trimEnd()) return true
    return false
}

fun parseHistoryMessage(obj: JsonObject): ChatMessage? {
    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
    // Only render user and assistant bubbles; skip tool results, system, etc.
    if (role != "user" && role != "assistant") return null
    // Server already strips envelopes via stripEnvelopeFromMessages,
    // so we just need to extract raw text from whichever format it uses.
    val imageBase64 = extractImageBase64(obj["content"] as? JsonArray)
    val fieldImageSource = imageSourceFromTranscriptFields(obj)
    val (text, hasImageRef, textImageSource) = cleanImageRefs(extractMessageText(obj))
    val imageSource = textImageSource ?: fieldImageSource
    // Drop empty bubbles, silent NO_REPLY placeholders, and internal messages
    if (text.isBlank() && imageBase64 == null && !hasImageRef) return null
    if (SILENT_REPLY.matches(text) || isInternalMessage(text)) return null
    val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: System.currentTimeMillis()
    return ChatMessage(
        role = role,
        text = text.ifBlank { "attached image" },
        timestamp = ts,
        imageBase64 = imageBase64,
        imageSource = imageSource,
        hasImage = imageBase64 != null || hasImageRef || imageSource != null,
    )
}

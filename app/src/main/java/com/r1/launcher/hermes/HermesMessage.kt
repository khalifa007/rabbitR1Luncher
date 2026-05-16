package com.r1.launcher.hermes

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One message in the Hermes chat scrollback. Roles are the OpenAI-compatible
 * trio (`user`, `assistant`, `system`); the launcher also injects `"error"`
 * locally for failed turns. Hermes doesn't return tool-call deltas via
 * /v1/chat/completions, so this is text-only.
 */
@Serializable
data class HermesMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
)

package com.r1.launcher.hermes

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One message in the Hermes chat scrollback. Roles are the OpenAI-compatible
 * trio (`user`, `assistant`, `system`); the launcher also injects `"error"`
 * locally for failed turns.
 *
 * `reasoning` captures `<think>` / `delta.reasoning_content` content extracted
 * during streaming (excluded from [text] which is the user-visible reply).
 * `toolEvents` is the in-order timeline of `hermes.tool.progress` events that
 * fired while the agent produced this reply.
 */
@Serializable
data class HermesMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val reasoning: String? = null,
    val toolEvents: List<HermesToolEvent> = emptyList(),
)

/**
 * One `hermes.tool.progress` SSE event. The Hermes gateway emits two per tool
 * call: `status="running"` on start, then `status="completed"` on finish. The
 * launcher upserts by [toolCallId] so the timeline shows one entry per tool.
 */
@Serializable
data class HermesToolEvent(
    val tool: String,
    val emoji: String = "",
    val label: String = "",
    val toolCallId: String = "",
    val status: String = "running",
    val startedAt: Long = System.currentTimeMillis(),
)

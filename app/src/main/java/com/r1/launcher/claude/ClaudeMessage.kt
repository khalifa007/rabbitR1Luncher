package com.r1.launcher.claude

/**
 * One turn in the Claude Code app's chat scrollback.
 *
 * @param role "user" or "assistant"
 * @param text Plain text body — claude --print outputs unstyled UTF-8 with no
 *             ANSI escapes, so no stripping needed (unlike PTY interactive mode).
 * @param error True if the assistant turn was a stderr capture or "claude exited
 *              N" surfacing — rendered with a red tint so failures stand out.
 */
data class ClaudeMessage(
    val role: String,
    val text: String,
    val error: Boolean = false,
)

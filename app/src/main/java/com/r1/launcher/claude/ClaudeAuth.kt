package com.r1.launcher.claude

/**
 * Surface types for the Claude Code login flow exposed via [LauncherHost] and
 * the web companion's `claude.auth.*` RPCs.
 *
 * The actual auth runs in a detached `claude auth login --claudeai` process
 * inside the alpine chroot — see scripts/claude-auth-{start,finish}.sh. These
 * data classes are the thin shims the launcher returns to its callers.
 */

/**
 * @property url     The OAuth URL the user must open in a browser. Empty when
 *                   the script did not print a URL (e.g. the chroot is
 *                   missing, or claude is already logged in and the helper
 *                   short-circuited).
 * @property log     Raw stdout from claude-auth-start.sh — useful in the web
 *                   UI's "details" disclosure for debugging.
 * @property error   Non-null when the script's exit code or output indicates
 *                   a hard failure (no chroot, no claude binary, network
 *                   error). When set, [url] will usually be empty.
 */
data class ClaudeAuthStartResult(
    val url: String,
    val log: String,
    val error: String? = null,
)

/**
 * @property ok    True iff `~claude/.credentials.json` exists after the script
 *                 finishes — the only reliable signal the OAuth round-trip
 *                 actually completed.
 * @property log   Raw stdout from claude-auth-finish.sh.
 * @property error User-facing error description when [ok] is false.
 */
data class ClaudeAuthFinishResult(
    val ok: Boolean,
    val log: String,
    val error: String? = null,
)

/**
 * Snapshot of how the device is currently authenticated to Claude. The web
 * companion's login UI uses this to decide whether to render the auth form
 * or jump straight to the chat.
 *
 * @property hasOAuth  /home/claude/.claude/.credentials.json (or /root/.../)
 *                     is present.
 * @property hasApiKey /data/local/tmp/.anthropic_key has non-zero length.
 * @property chrootReady alpine + claude binary exist on the device.
 *                       When false, neither auth path can run.
 */
data class ClaudeAuthStatus(
    val hasOAuth: Boolean,
    val hasApiKey: Boolean,
    val chrootReady: Boolean,
)

/**
 * Result of an end-to-end auth probe — runs `claude --print` as the
 * unprivileged claude user and reports whether the binary actually accepts
 * the saved credentials. Used to disambiguate "creds file present" from
 * "creds actually work", which the on-disk file check cannot do.
 *
 * @property ok    True iff the probe completed without claude returning
 *                 "Not logged in" / "Please run /login".
 * @property log   Raw stdout+stderr from the claude probe command.
 * @property error Short user-facing description when [ok] is false.
 */
data class ClaudeAuthVerifyResult(
    val ok: Boolean,
    val log: String,
    val error: String? = null,
)

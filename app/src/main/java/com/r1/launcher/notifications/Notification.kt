package com.r1.launcher.notifications

import kotlinx.serialization.Serializable

/** Sources we know how to deeplink back to. `WEBHOOK`, `NTFY`, and `LOCAL`
 *  carry an optional [Notification.deeplink] string the activity routes by name. */
enum class NotifSource { OPENCLAW, HERMES, WEBHOOK, NTFY, LOCAL }

/**
 * Single notification entry shown in the NOTIFICATIONS panel and (for unread)
 * counted on the HOME badge. [id] is a monotonically increasing counter used
 * as the stable key for mark-read/dismiss; [timestamp] is wall-clock ms.
 *
 * [deeplink] names a panel the activity opens when the row is activated:
 *   "openclaw_chat" | "hermes_chat" | "messages" | "notifications" | null.
 */
@Serializable
data class Notification(
    val id: Long,
    val source: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val read: Boolean = false,
    val deeplink: String? = null,
) {
    val sourceEnum: NotifSource
        get() = runCatching { NotifSource.valueOf(source.uppercase()) }.getOrDefault(NotifSource.LOCAL)
}

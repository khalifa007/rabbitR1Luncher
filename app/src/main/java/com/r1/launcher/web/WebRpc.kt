package com.r1.launcher.web

import android.content.Context
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.messages.SmsLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON-RPC dispatcher for the embedded web panel. Mirrors the request/response
 * shape used by [com.r1.launcher.openclaw.GatewaySession] so the server-side
 * patterns and the client-side RPC helper share a wire vocabulary.
 *
 * Methods are thin shims over [LauncherHost] / [LauncherState] / [SmsLoader].
 * No business logic lives here — keep this file boring.
 */
class RpcException(val code: String, message: String) : RuntimeException(message)

/**
 * Mask a secret for read-back over the web channel. Keeps the first 3 visible
 * chars of the value (or the `sk_` / `eyJ` literal when the value starts with
 * one) and the last 5, ellipsizing the middle. Short values (<= 8 chars) come
 * back unchanged because tailing them would reveal more than masking.
 */
internal fun secretTail(value: String): String {
    if (value.isEmpty()) return ""
    val v = value.trim()
    val prefix = when {
        v.startsWith("sk_") -> "sk_"
        v.startsWith("eyJ") -> "eyJ"
        v.length >= 3 -> v.take(3)
        else -> ""
    }
    val last5 = v.takeLast(5)
    if (v.length <= prefix.length + last5.length) return v
    return "$prefix…$last5"
}

object WebRpc {

    fun dispatch(
        host: LauncherHost,
        state: LauncherState,
        ctx: Context,
        method: String,
        params: JsonObject?,
    ): JsonElement = when (method) {
        "state.snapshot" -> buildSnapshot(state, ctx)

        "sms.list" -> buildSmsList(ctx)
        "sms.thread" -> buildSmsThread(ctx, params.requireString("address"))

        "text.send" -> handleTextSend(host, state, params)

        "wifi.toggle" -> {
            host.toggleWifi(params.requireBool("on")); JsonNull
        }
        "cellular.toggle" -> {
            host.toggleCellular(params.requireBool("on")); JsonNull
        }
        "bt.toggle" -> {
            host.toggleBluetooth(params.requireBool("on")); JsonNull
        }
        "hotspot.toggle" -> {
            host.toggleWifiShare(params.requireBool("on")); JsonNull
        }
        "brightness.set" -> {
            host.setBrightness(params.requireInt("level").coerceIn(1, 255)); JsonNull
        }
        "volume.set" -> {
            val v = params.requireInt("level").coerceAtLeast(0).coerceAtMost(state.volumeMax)
            host.setVolume(v); JsonNull
        }
        "voice.set_key" -> {
            host.voiceSaveKey(params.requireString("key")); JsonNull
        }
        "voice.set_custom_id" -> {
            // Empty string = clear the override (fall back to catalog voice).
            val v = params.requireString("id").trim()
            if (v.isEmpty()) host.voiceClearCustomVoiceId()
            else host.voiceSaveCustomVoiceId(v)
            JsonNull
        }
        "voice.clear_custom_id" -> {
            host.voiceClearCustomVoiceId(); JsonNull
        }

        "credentials.get" -> buildCredentialsBlock(ctx)

        "credentials.set_voice_key" -> {
            host.voiceSaveKey(params.requireString("key")); JsonNull
        }
        "credentials.set_voice_id" -> {
            host.voiceSetVoiceId(params.requireString("id")); JsonNull
        }
        "credentials.set_voice_custom_id" -> {
            val v = params.requireString("id").trim()
            if (v.isEmpty()) host.voiceClearCustomVoiceId()
            else host.voiceSaveCustomVoiceId(v)
            JsonNull
        }
        "credentials.set_ntfy_topic" -> {
            host.ntfySetTopic(params.requireString("topic").trim()); JsonNull
        }

        "terminal.run" -> {
            requireWebTerminal(state)
            host.terminalRun(params.requireString("cmd")); JsonNull
        }
        "terminal.clear" -> {
            requireWebTerminal(state)
            host.terminalClear(); JsonNull
        }
        "terminal.history" -> {
            requireWebTerminal(state)
            buildJsonObject {
                put("cwd", state.terminalCwd)
                put("busy", state.terminalBusy)
                put("lines", buildJsonArray {
                    state.terminalOutput.toList().forEach { add(JsonPrimitive(it)) }
                })
            }
        }

        "hermes.send" -> {
            if (state.hermesActiveId == null) {
                throw RpcException("hermes_unconfigured", "hermes server url not set")
            }
            host.hermesSendText(params.requireString("text")); JsonNull
        }
        "hermes.clear" -> {
            host.hermesClearHistory(); JsonNull
        }
        "hermes.history" -> buildJsonObject {
            put("busy", state.hermesBusy)
            put("status", state.hermesStatus)
            put("streaming", state.hermesStreamingText)
            put("messages", buildJsonArray {
                (state.hermesActiveHistory()?.toList().orEmpty()).forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("text", m.text)
                    })
                }
            })
        }

        // --- meetings (transcriber) ---
        "transcriber.list" -> buildJsonObject {
            put("recording", state.recordingActive)
            put("transcribing", state.transcribeBusy)
            put("hasSmtp", state.hasSmtp)
            put("defaultRecipient", state.defaultRecipientDisplay)
            put("meetings", buildJsonArray {
                com.r1.launcher.transcriber.MeetingStore.get(ctx).listMeetings().forEach { m ->
                    add(buildJsonObject {
                        put("uuid", m.uuid)
                        put("title", m.title)
                        put("createdAtMs", m.createdAtMs)
                        put("durationMs", m.durationMs)
                        put("status", m.status.name.lowercase())
                        put("speakerCount", m.speakerCount)
                    })
                }
            })
        }
        "transcriber.delete" -> {
            host.transcriberDelete(params.requireString("uuid")); JsonNull
        }
        "transcriber.email" -> {
            host.transcriberShareEmail(
                params.requireString("uuid"),
                params.requireString("recipient"),
            ); JsonNull
        }
        "transcriber.start" -> {
            host.transcriberStartRecording(); JsonNull
        }
        "transcriber.stop" -> {
            host.transcriberStopRecording(); JsonNull
        }
        "transcriber.retry" -> {
            host.transcriberRetryTranscribe(params.requireString("uuid")); JsonNull
        }

        "notifications.list" -> buildJsonObject {
            put("unread", state.notificationsUnread)
            put("items", buildJsonArray {
                // Newest-first to match the on-device panel.
                state.notifications.asReversed().forEach { n ->
                    add(buildJsonObject {
                        put("id", n.id)
                        put("source", n.source)
                        put("title", n.title)
                        put("body", n.body)
                        put("timestamp", n.timestamp)
                        put("read", n.read)
                        n.deeplink?.let { put("deeplink", it) }
                    })
                }
            })
        }
        "notifications.markAllRead" -> {
            host.notificationsMarkAllRead(); JsonNull
        }
        "notifications.activate" -> {
            host.notificationActivate(params.requireLong("id")); JsonNull
        }
        "notifications.clear" -> {
            host.notificationsClear(); JsonNull
        }
        // notifications.token / notifications.rotateToken intentionally removed.
        // The web RPC channel is unauthenticated (any LAN client can connect),
        // so returning the webhook bearer here would defeat the token's purpose
        // — anyone reachable could grab it and then post fake notifications
        // from the public internet via ntfy.sh or the /api/notify endpoint.
        // The bearer is only visible on-device in Settings → Credentials.
        "notifications.test" -> {
            // Convenience for the web companion: simulate an incoming
            // notification so users can verify sound + badge wiring without
            // having to fire a real webhook from a separate machine.
            host.notify(
                source = params?.get("source")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "webhook",
                title = params?.get("title")?.jsonPrimitive?.contentOrNull ?: "test",
                body = params?.get("body")?.jsonPrimitive?.contentOrNull ?: "hello from web companion",
                deeplink = params?.get("deeplink")?.jsonPrimitive?.contentOrNull,
            )
            JsonNull
        }

        else -> throw RpcException("unknown_method", "unknown method: $method")
    }

    /** Gate the web-terminal methods behind the explicit opt-in toggle so that
     *  the launcher's root shell isn't silently exposed to anyone on the LAN. */
    private fun requireWebTerminal(state: LauncherState) {
        if (!state.webTerminalEnabled) {
            throw RpcException(
                "disabled",
                "Enable in Settings → Network → remote terminal",
            )
        }
    }

    fun buildSnapshot(state: LauncherState, ctx: Context? = null): JsonObject = buildJsonObject {
        // Locale lets the web companion render English/Arabic + flip RTL in
        // sync with the device. Default "en" when ctx isn't available — this
        // only happens in callers that didn't thread ctx through; fix at
        // call site rather than degrading silently elsewhere.
        put("locale", ctx?.let { com.r1.launcher.locale.LocalePrefs.get(it).language } ?: "en")
        put("system", buildJsonObject {
            put("battery", state.batteryPct)
            put("charging", state.batteryCharging)
            put("ip", state.webServerIp)
            put("port", state.webServerPort)
            put("clockText", state.clockText)
            put("dateText", state.dateText)
        })
        put("network", buildJsonObject {
            put("wifi", state.wifiEnabled)
            put("cellular", state.cellularOn)
            put("bt", state.btOn)
            put("hotspot", state.wifiShareEnabled)
            put("hotspotClients", state.wifiShareConnectedClients.size)
            put("signal", state.signalLevel)
            put("operator", state.simOperator)
            put("networkType", state.networkType)
            put("simPresent", state.simPresent)
        })
        put("brightness", state.brightnessLevel)
        put("volume", state.volumeLevel)
        put("volumeMax", state.volumeMax)
        put("openclaw", buildJsonObject {
            put("status", state.chatStatus)
            put("hasVoiceKey", state.hasVoiceKey)
            put("voiceKeyTail", state.voiceKeyTail)
            put("voiceEnabled", state.voiceEnabled)
            put("voiceId", state.voiceId)
            // Empty when no override is set; UI shows "using catalog voice".
            put("voiceCustomId", state.voiceCustomId)
        })
        put("terminal", buildJsonObject {
            put("enabled", state.webTerminalEnabled)
            put("cwd", state.terminalCwd)
            put("busy", state.terminalBusy)
        })
        put("hermes", buildJsonObject {
            put("status", state.hermesStatus)
            put("hasConfig", state.hermesActiveId != null)
            put("model", state.hermesModel)
            put("messageCount", state.hermesActiveHistory()?.size ?: 0)
            put("busy", state.hermesBusy)
        })
        put("notifications", buildJsonObject {
            put("unread", state.notificationsUnread)
            put("total", state.notifications.size)
            put("soundEnabled", state.notificationSoundEnabled)
        })
        ctx?.let {
            put("credentials", buildCredentialsBlock(it))
        }
    }

    private fun buildSmsList(ctx: Context): JsonArray {
        val convs = runCatching { SmsLoader.loadConversations(ctx) }.getOrDefault(emptyList())
        return buildJsonArray {
            convs.forEach { c ->
                add(buildJsonObject {
                    put("address", c.address)
                    put("name", c.displayName)
                    put("latestBody", c.latestBody)
                    put("latestTimestampMs", c.latestTimestampMs)
                    put("unreadCount", c.unreadCount)
                    put("totalCount", c.totalCount)
                })
            }
        }
    }

    private fun buildSmsThread(ctx: Context, address: String): JsonArray {
        val items = runCatching { SmsLoader.loadMessagesFor(ctx, address) }.getOrDefault(emptyList())
        return buildJsonArray {
            items.forEach { m ->
                add(buildJsonObject {
                    put("body", m.body)
                    put("timestampMs", m.timestampMs)
                    put("incoming", m.incoming)
                    put("read", m.read)
                })
            }
        }
    }

    /** Build the credentials block for `credentials.get` and for inclusion in
     *  the 1 Hz `state.snapshot`. Secrets are tailed; URLs / voice ids / topic
     *  are returned in full. See spec for the threat model rationale. */
    internal fun buildCredentialsBlock(ctx: Context): JsonObject {
        val voicePrefs = com.r1.launcher.voice.VoicePrefs.get(ctx)
        val hermesPrefs = com.r1.launcher.hermes.HermesPrefs.get(ctx)
        val ntfyPrefs = com.r1.launcher.notifications.NtfyPrefs.get(ctx)

        return buildJsonObject {
            put("elevenlabs", buildJsonObject {
                val key = voicePrefs.elevenlabsKey.orEmpty()
                put("hasApiKey", key.isNotBlank())
                put("apiKeyTail", secretTail(key))
                put("voiceId", voicePrefs.voiceId)
                put("voiceCustomId", voicePrefs.customVoiceId.orEmpty())
            })
            put("hermes", buildJsonObject {
                put("maxConnections", com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS)
                put("activeId", hermesPrefs.active?.id ?: "")
                put("connections", buildJsonArray {
                    hermesPrefs.connections.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("url", c.url)
                            put("hostLabel", c.hostLabel)
                            put("hasBearer", c.apiKey.isNotBlank())
                            put("bearerTail", secretTail(c.apiKey))
                        })
                    }
                })
            })
            put("ntfy", buildJsonObject {
                put("topic", ntfyPrefs.topic)
            })
        }
    }

    /**
     * `text.send` is a multi-target relay: paste arbitrary text from a phone
     * keyboard into one of the launcher's input sinks.
     *
     * targets:
     *   - "voice_key"       → save as ElevenLabs API key (sk_* or 32-char hex)
     *   - "voice_custom_id" → save as ElevenLabs voice id (clones / pro voices);
     *                         empty string clears the override
     *   - "openclaw_chat"   → send as a chat message (only when a session is live)
     */
    private fun handleTextSend(
        host: LauncherHost,
        state: LauncherState,
        params: JsonObject?,
    ): JsonElement {
        val target = params.requireString("target")
        val text = params.requireString("text")
        return when (target) {
            "voice_key" -> {
                host.voiceSaveKey(text.trim())
                JsonNull
            }
            "voice_custom_id" -> {
                val v = text.trim()
                if (v.isEmpty()) host.voiceClearCustomVoiceId()
                else host.voiceSaveCustomVoiceId(v)
                JsonNull
            }
            "openclaw_chat" -> {
                if (!state.chatStatus.startsWith("live")) {
                    throw RpcException("openclaw_offline", "openclaw not connected")
                }
                host.openClawSendText(text)
                JsonNull
            }
            "hermes_chat" -> {
                if (state.hermesActiveId == null) {
                    throw RpcException("hermes_unconfigured", "hermes server url not set")
                }
                host.hermesSendText(text)
                JsonNull
            }
            else -> throw RpcException("unknown_target", "unknown target: $target")
        }
    }
}

private fun JsonObject?.requireString(key: String): String =
    this?.get(key)?.jsonPrimitive?.contentOrNull
        ?: throw RpcException("missing_param", "missing param: $key")

private fun JsonObject?.requireInt(key: String): Int =
    this?.get(key)?.jsonPrimitive?.int
        ?: throw RpcException("missing_param", "missing param: $key")

private fun JsonObject?.requireBool(key: String): Boolean =
    this?.get(key)?.jsonPrimitive?.boolean
        ?: throw RpcException("missing_param", "missing param: $key")

private fun JsonObject?.requireLong(key: String): Long =
    this?.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: throw RpcException("missing_param", "missing param: $key")
